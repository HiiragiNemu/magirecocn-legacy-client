package io.kamihama.magianative;

import android.app.Activity;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;

/**
 * 客户端版本检查：每次启动、<b>热更检查之前</b>跑。云端版本高于本端内置版本时，
 * 弹强制更新框（调起浏览器下载新包），并<b>不再</b>接力热更检查。
 *
 * <h3>版本号在哪儿</h3>
 *
 * 本端版本硬编码在 native 侧（magia-native/src/MagiaLegacy.cpp 的
 * {@code CLIENT_VERSION}），经 JNI 的 {@link #nativeClientVersion()} 取回。
 * 刻意不读也不改 APK 的 versionName / versionCode——那是上游包的身份，
 * 动了会影响覆盖安装；客户端更新通道的版本号是我们自己的一套。
 *
 * <p>云端版本与下载地址记在 {@code config.json} 的 {@code client} 段
 * （{@code version} / {@code apk_url}）。配置类请求一律直连主线，与
 * {@link CNHotUpdateCheck#fetchVersion} 同理。
 *
 * <h3>失败放行</h3>
 *
 * 拉不到 config.json、解析不了、读不到 native 版本——任何一种异常都<b>放行</b>
 * （日志照记），绝不让玩家因为一次网络抖动进不了游戏。强制更新拦的是
 * 「明确知道云端更新了」这一种情况，其余一律当作没有更新。
 *
 * <h3>调用方</h3>
 *
 * {@link CNDownloaderFix#triggerInstaller} 在确认安装完成标记存在后调用本类，
 * 原先那里直接调 {@code CNHotUpdateCheck.start()}；是否需要更新由本类判断，
 * 不需要时才接力 {@code CNHotUpdateCheck.start()}。
 */
public final class CNVersionCheck {

    private static final String TAG = "CNVersion";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS    = 15000;

    /** 等可用 Activity 的上限：60 × 500ms = 30 秒（热更检查同量级）。 */
    private static final int  ACTIVITY_WAIT_TRIES   = 60;
    private static final long ACTIVITY_WAIT_STEP_MS = 500L;

    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private CNVersionCheck() {}

    /**
     * 本端客户端版本，由 libMagiaLegacy 经 RegisterNatives 提供。
     * 读不到（旧库没这个函数）时抛 {@link UnsatisfiedLinkError}——调用方必须
     * 兜住并按「不强制更新」处理。
     */
    public static native String nativeClientVersion();

    /**
     * 启动版本检查。不抛异常、不阻塞调用方：内部另起守护线程；
     * 重复调用只有第一次生效。
     */
    public static void startBeforeHotUpdate() {
        try {
            if (!STARTED.compareAndSet(false, true)) {
                CNLog.i(TAG, "版本检查已经在跑，忽略重复调用");
                return;
            }
            Thread t = new Thread("cnv-version-check") {
                @Override public void run() {
                    try { runInner(); }
                    catch (Throwable th) {
                        CNLog.e(TAG, "版本检查异常终止（放行，接力热更）: " + th, th);
                        CNHotUpdateCheck.start();
                    }
                }
            };
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            try { android.util.Log.e(TAG, "版本检查线程起不来，直接接力热更", t); }
            catch (Throwable ignore) {}
            CNHotUpdateCheck.start();
        }
    }

    // ==================================================================
    // 主流程
    // ==================================================================

    private static void runInner() {
        CNLog.i(TAG, "客户端版本检查开始");

        // 先等界面。这不只是为了弹窗——libMagiaLegacy 是在 Cocos2dxActivity
        // 启动时才链式加载的（onLoadNativeLibraries），本线程由
        // MyApplication.onCreate 拉起，那时 native 库还没就位，native 方法一
        // 调就是 UnsatisfiedLinkError。等到带 decorView 的 Activity 出现，
        // 库必然已加载（第一版就踩了这个时序坑：版本读取永远失败，fail-open
        // 把检查整个吞了，表现为「云端版本更高也不弹窗」）。
        Activity act = awaitUsableActivity();
        if (act != null) {
            showOverlay(act);
            CNCNDownloadUI.updateSimple("检查客户端版本", "正在检查客户端版本…", 0);
        } else {
            CNLog.w(TAG, "等不到可用的 Activity，本次版本检查将无浮层运行");
        }

        String local = awaitClientVersion();
        if (local == null) {
            // 读不到本端版本就没法比较——放行，别误伤。
            CNLog.w(TAG, "拿不到本端版本，跳过版本检查");
            CNHotUpdateCheck.start();
            return;
        }

        JSONObject client;
        try {
            client = fetchClientSection(CNMirrors.MIRRORS_URL);
        } catch (Throwable t) {
            CNLog.w(TAG, "config.json 拉取/解析失败，按不强制更新放行: " + t);
            CNHotUpdateCheck.start();
            return;
        }
        if (client == null) {
            // 线上 config.json 还没有 client 段：旧服务端配置，按不强制更新放行。
            CNLog.i(TAG, "config.json 无 client 段，跳过版本检查");
            CNHotUpdateCheck.start();
            return;
        }

        String cloud   = client.optString("version", "");
        String apkUrl  = client.optString("apk_url", "");
        String note    = client.optString("note", "");
        CNLog.i(TAG, "版本比对：本端 " + local + " / 云端 " + cloud);
        if (cloud.isEmpty() || compareVersion(local, cloud) >= 0) {
            CNLog.i(TAG, "本端已是最新，接力热更检查");
            CNHotUpdateCheck.start();
            return;
        }

        // 云端明确更高：强制更新。弹窗模态挂在浮层上，不接력热更——玩家要么去
        // 更新，要么退出游戏；下次启动还会再查再拦。
        CNLog.w(TAG, "云端版本更高（" + local + " → " + cloud + "），弹强制更新框");
        if (act != null) {
            CNCNDownloadUI.updateSimple("客户端更新", "发现新版本 v" + cloud + "，需要更新客户端", 0);
            CNCNDownloadUI.showVersionUpdateDialog(act, local, cloud, apkUrl, note);
        } else {
            // 没有界面可挂时至少别静默吞掉：日志里已经有完整信息。
            CNLog.e(TAG, "无浮层可弹强制更新框（云端 " + cloud + "，地址 " + apkUrl + "）");
        }
    }

    // ==================================================================
    // 版本号
    // ==================================================================

    /** 本端版本；读不到返回 null（放行信号）。 */
    private static String awaitClientVersion() {
        // libMagiaLegacy 在引擎 Activity 启动时才加载，第一次调用多半撞上
        // UnsatisfiedLinkError——重试吸收掉这段启动窗口；真没有（比如库没
        // 带这个函数）才放行。
        for (int i = 0; i < 40; i++) {   // 40 × 500ms = 20 秒上限
            try {
                String v = nativeClientVersion();
                if (v != null && !v.isEmpty()) {
                    if (i > 0) CNLog.i(TAG, "读到本端版本 " + v + "（第 " + (i + 1) + " 次）");
                    return v;
                }
            } catch (UnsatisfiedLinkError e) {
                if (i == 0) CNLog.i(TAG, "native 库尚未加载，等待就位…");
            } catch (Throwable t) {
                CNLog.w(TAG, "读 native 版本出错: " + t);
                return null;
            }
            sleep(500L);
        }
        CNLog.w(TAG, "等满 20 秒仍读不到 native 客户端版本");
        return null;
    }

    /**
     * 点分版本号比较：local 小于 cloud 返回负值，相等返回 0，大于返回正值。
     * 逐段比较；两段都是纯数字按数值，否则按字符串；缺段按 "0" 计
     * （"1.0" 与 "1.0.0" 相等）。
     */
    static int compareVersion(String local, String cloud) {
        String[] a = local.split("\\.");
        String[] b = cloud.split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            String x = i < a.length ? a[i] : "0";
            String y = i < b.length ? b[i] : "0";
            int c = compareSegment(x, y);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int compareSegment(String x, String y) {
        boolean nx = x.matches("\\d+"), ny = y.matches("\\d+");
        if (nx && ny) {
            // 段不可能长到溢出 long；真溢出就退成字符串比较
            try {
                long d = Long.parseLong(x) - Long.parseLong(y);
                return d < 0 ? -1 : (d > 0 ? 1 : 0);
            } catch (NumberFormatException ignore) {}
        }
        return x.compareTo(y);
    }

    // ==================================================================
    // 网络
    // ==================================================================

    /**
     * 直连主线拉 config.json 并取出 client 段。配置类请求一律不换线。
     * 没有 client 段时返回 null；网络/解析异常向上抛（调用方按放行处理）。
     */
    private static JSONObject fetchClientSection(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new java.io.IOException("HTTP " + code);
            InputStream in = new BufferedInputStream(c.getInputStream(), 8192);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            // config.json 只有几 KB；设个上限免得对面返回一坨东西把内存吃了
            while ((n = in.read(buf)) >= 0 && bos.size() < 262144) bos.write(buf, 0, n);
            in.close();
            return new JSONObject(bos.toString("UTF-8")).optJSONObject("client");
        } finally {
            try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    // ==================================================================
    // 浮层
    // ==================================================================

    /**
     * 等一个<b>真正能挂浮层</b>的 Activity：decorView 存在才谈得上往上加 View
     * （peek 而不是 get——后者会强制创建 decorView，在别人的 Activity 上不合适）。
     */
    private static Activity awaitUsableActivity() {
        Activity last = null;
        for (int i = 0; i < ACTIVITY_WAIT_TRIES; i++) {
            Activity act = null;
            try { act = RestClient.getCurrentActivity(); } catch (Throwable ignore) {}
            if (act != null) {
                last = act;
                try {
                    if (act.getWindow() != null && act.getWindow().peekDecorView() != null) {
                        return act;
                    }
                } catch (Throwable ignore) {}
            }
            sleep(ACTIVITY_WAIT_STEP_MS);
        }
        return last;
    }

    /** 建浮层，建不成就重试几轮（与热更检查同一手法）。 */
    private static void showOverlay(Activity act) {
        for (int i = 0; i < 3; i++) {
            try {
                CNCNDownloadUI.show(act);
                CNCNDownloadUI.ensureVisible(act);
            } catch (Throwable t) {
                CNLog.w(TAG, "show() 第 " + (i + 1) + " 次失败：" + t);
            }
            if (CNCNDownloadUI.isShowing) return;
            sleep(400L);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
