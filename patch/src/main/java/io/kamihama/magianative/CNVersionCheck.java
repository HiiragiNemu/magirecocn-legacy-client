package io.kamihama.magianative;

import android.app.Activity;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 客户端版本检查，在热更检查与首次资源安装之前运行。
 * 云端更高时提示更新，玩家可继续用当前客户端；检查失败也继续原启动流程。
 * 更新提示仅影响 APK 更新选择，不改动资源校验、下载或安装标记。
 * 本端版本由 nativeClientVersion() 读取；云端取各完整来源中的最高版本。
 */
public final class CNVersionCheck {

    private static final String TAG = "CNVersion";

    /** 最近一次多源检查结果，供主界面版本面板显示；初始值保持可读。 */
    public static volatile String lastLocalVersion = "—";
    public static volatile String lastBestVersion = "—";
    public static volatile String lastPersonalVersion = "—";
    public static volatile String lastEdgeOneVersion = "—";

    // config 是可选控制面：失败必须快速放行。**原先是 15s + 15s**，落在启动关键
    // 路径上就是最多白等半分钟，而这一步失败本来就只意味着「本次不弹更新提示」。
    // 所以收到下面这两个值——不是「随手调紧」，是这一步的失败代价本来就近乎为零。
    private static final int CONNECT_TIMEOUT_MS = 1800;
    private static final int READ_TIMEOUT_MS = 2200;

    /** 等可用 Activity 的上限：60 × 500ms = 30 秒（热更检查同量级）。 */
    private static final int  ACTIVITY_WAIT_TRIES   = 60;
    private static final long ACTIVITY_WAIT_STEP_MS = 500L;

    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 不更新 APK 时的接力动作；每条放行路径调一次且仅一次。 */
    private static volatile Runnable afterPass;
    private static final java.util.concurrent.atomic.AtomicBoolean PROCEEDED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private CNVersionCheck() {}

    private static final java.util.concurrent.atomic.AtomicBoolean MANUAL_RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    static boolean manualCheckInProgress() { return MANUAL_RUNNING.get(); }

    /** A repeatable user request, separate from the once-only startup check. */
    public static void checkManually(Activity act) {
        if (act == null || act.isFinishing() || !MANUAL_RUNNING.compareAndSet(false, true)) return;
        CNCNDownloadUI.setManualApkCheckBusy(act, true);
        try {
            Thread t = new Thread(new ManualCheck(act), "cnv-manual-version-check");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            MANUAL_RUNNING.set(false);
            CNCNDownloadUI.setManualApkCheckBusy(act, false);
            CNCNDownloadUI.showManualApkResult(act, null, null, "检查启动失败，请重试");
        }
    }

    /** -1: unavailable; 0: no newer valid package; 1: offer a newer package. */
    static int manualResult(String local, JSONObject client) {
        if (local == null || !local.matches("[0-9]+(\\.[0-9]+){1,3}") || !validClient(client)) return -1;
        return compareVersion(local, client.optString("version")) < 0 ? 1 : 0;
    }

    private static final class ManualCheck implements Runnable {
        private final Activity act;
        ManualCheck(Activity act) { this.act = act; }
        @Override public void run() {
            String local = null;
            JSONObject update = null;
            String message = "检查失败，请稍后重试";
            try {
                local = awaitClientVersion();
                lastLocalVersion = local == null ? "—" : local;
                JSONObject client = local == null ? null : fetchBestClientSection();
                int result = manualResult(local, client);
                if (result > 0) update = client;
                else if (result == 0) message = "当前已是最新可用版本（v" + local + "）";
                else message = local == null ? "读取当前版本失败，请重试" : "未取得有效更新信息，请重试";
            } catch (Throwable t) {
                CNLog.w(TAG, "手动检查 APK 更新失败: " + t);
            }
            try { act.runOnUiThread(new ManualResult(act, local, update, message)); }
            catch (Throwable t) { MANUAL_RUNNING.set(false); }
        }
    }

    private static final class ManualResult implements Runnable {
        private final Activity act;
        private final String local, message;
        private final JSONObject update;
        ManualResult(Activity act, String local, JSONObject update, String message) {
            this.act = act; this.local = local; this.update = update; this.message = message;
        }
        @Override public void run() {
            MANUAL_RUNNING.set(false);
            CNCNDownloadUI.setManualApkCheckBusy(act, false);
            CNCNDownloadUI.showManualApkResult(act, local, update, message);
        }
    }

    /**
     * 本端客户端版本，由 libMagiaLegacy 经 RegisterNatives 提供。
     * 读不到（旧库没这个函数）时抛 {@link UnsatisfiedLinkError}——调用方必须
     * 兜住并按「不强制更新」处理。
     */
    public static native String nativeClientVersion();

    /**
     * 启动版本检查。不抛异常、不阻塞调用方：内部另起守护线程；
     * 重复调用只有第一次生效。
     *
     * @param cont 不更新 APK 时要接力的动作（热更检查，或首次安装的安装器）。
     *             选择继续、检查失败等放行路径都会执行且只执行一次。
     */
    public static void start(Runnable cont) {
        // F-031：先原子取得唯一启动权，再把 continuation 绑定给赢家。旧实现
        // 先 afterPass = cont 再 CAS——后到的重复调用会覆盖首个调用者的
        // continuation，proceed() 执行的是最后一次写入的动作而非赢得门的动作。
        try {
            if (!STARTED.compareAndSet(false, true)) {
                CNLog.i(TAG, "版本检查已经在跑，忽略重复调用");
                return;
            }
            afterPass = cont;
            if (CNDebugFlags.isOn(CNDebugFlags.SKIP_VERSION_CHECK)) {
                CNLog.i(TAG, "调试开关 skipVersionCheck 生效，直接接力");
                proceed();
                return;
            }
            Thread t = new Thread("cnv-version-check") {
                @Override public void run() {
                    try { runInner(); }
                    catch (Throwable th) {
                        CNLog.e(TAG, "版本检查异常终止（放行，接力）: " + th, th);
                        proceed();
                    }
                }
            };
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            try { android.util.Log.e(TAG, "版本检查线程起不来，直接接力", t); }
            catch (Throwable ignore) {}
            proceed();
        }
    }

    /** 执行接力动作，全局最多一次；动作自身抛异常不外溢。 */
    private static void proceed() {
        if (!PROCEEDED.compareAndSet(false, true)) return;
        Runnable r = afterPass;
        if (r == null) return;
        try { r.run(); }
        catch (Throwable t) { CNLog.e(TAG, "接力动作执行异常: " + t, t); }
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
        boolean overlayReady = false;
        if (act != null) {
            overlayReady = showOverlay(act);
            if (overlayReady) {
                CNCNDownloadUI.updateSimple("检查客户端版本", "正在检查客户端版本…", 0);
            } else {
                CNLog.w(TAG, "Activity 存活但浮层未能挂载；本次继续原启动流程");
            }
        } else {
            CNLog.w(TAG, "等不到可用的 Activity，本次版本检查将无浮层运行");
        }

        String local = awaitClientVersion();
        lastLocalVersion = local == null ? "—" : local;
        if (local == null) {
            // 读不到本端版本就没法比较——放行，别误伤。
            CNLog.w(TAG, "拿不到本端版本，跳过版本检查");
            proceed();
            return;
        }

        JSONObject client;
        try {
            client = fetchBestClientSection();
        } catch (Throwable t) {
            CNLog.w(TAG, "config.json 拉取/解析失败，按不强制更新放行: " + t);
            proceed();
            return;
        }
        if (client == null) {
            // 线上 config.json 还没有 client 段：旧服务端配置，按不强制更新放行。
            CNLog.i(TAG, "config.json 无 client 段，跳过版本检查");
            proceed();
            return;
        }

        String cloud   = client.optString("version", "");
        String apkUrl  = client.optString("apk_url", "");
        String note    = client.optString("note", "");
        CNLog.i(TAG, "版本比对：本端 " + local + " / 云端 " + cloud);
        if (cloud.isEmpty() || compareVersion(local, cloud) >= 0) {
            CNLog.i(TAG, "本端已是最新，接力后续流程");
            proceed();
            return;
        }

        // 更新地址不符合既有准入规则时跳过提示，继续原来的资源检查。
        String urlErr = CNSafeLink.reject(apkUrl);
        if (urlErr != null) {
            CNLog.e(TAG, "云端 client.apk_url 不合法（" + urlErr + "），本次不强制更新，放行进游戏");
            proceed();
            return;
        }

        // 是否安装较新 APK 由玩家决定；继续入口接回同一条一次性 continuation。
        CNLog.i(TAG, "云端版本更高（" + local + " → " + cloud + "），提示可选更新");
        if (act != null && overlayReady) {
            CNCNDownloadUI.updateSimple("客户端更新", "发现新版本 v" + cloud + "，也可继续使用当前版本", 0);
            CNCNDownloadUI.showVersionUpdateDialog(act, local, cloud, apkUrl, note, client);
        } else {
            CNLog.w(TAG, "更新提示缺少可见浮层，继续原启动流程");
            proceed();
        }
    }

    /** UI 关闭提示后的首次安装可能涉及 I/O，仍在后台接力，不阻塞主线程。 */
    static void continueWithCurrentVersion() {
        try {
            Thread t = new Thread(new ContinueCurrentVersion(), "cnv-version-continue");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            CNLog.e(TAG, "继续启动线程创建失败，直接接力", t);
            proceed();
        }
    }

    private static final class ContinueCurrentVersion implements Runnable {
        @Override public void run() { proceed(); }
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
            // F-045：纯数字段按数值比较，任意长度不回退字典序。超 long 段此前
            // 捕获异常后直接字典序——"9…9" 会被误判大于 "1…0…0"。剥前导零后
            // 长度即数值大小；等长时字典序即数值序。
            String ax = x.replaceFirst("^0+", "");
            String ay = y.replaceFirst("^0+", "");
            if (ax.length() != ay.length()) return ax.length() < ay.length() ? -1 : 1;
            int c = ax.compareTo(ay);
            return c < 0 ? -1 : (c > 0 ? 1 : 0);
        }
        return x.compareTo(y);
    }

    // ==================================================================
    // 网络
    // ==================================================================

    /** Query built-in independent publishers even when the primary config responds with an old version. */
    static JSONObject fetchBestClientSection() throws Exception {
        java.util.List<String> urls=CNUpdateSources.clientUrls();
        java.util.List<CNUpdateSources.Reply<JSONObject>> replies=CNUpdateSources.collect(urls,
            new CNUpdateSources.Loader<JSONObject>() {
                public JSONObject load(String url) throws Exception {
                    JSONObject result = fetchClientSection(url);
                    return validClient(result) ? result : null;
                }
            }, CNUpdateSources.QUERY_BUDGET_MS);
        java.util.List<JSONObject> candidates=new java.util.ArrayList<JSONObject>();
        java.util.List<CNUpdateSources.ClientIdentity> identities=new java.util.ArrayList<CNUpdateSources.ClientIdentity>();
        for (CNUpdateSources.Reply<JSONObject> reply:replies) {
            if (reply.error!=null) { CNLog.w(TAG,"客户端更新源失败 source="+reply.url+" error="+reply.error); continue; }
            JSONObject candidate=reply.value;
            if (!validClient(candidate)) { CNLog.w(TAG,"客户端更新源元数据不完整 source="+reply.url); continue; }
            String version=candidate.getString("version");
            if (isEdgeOneSource(reply.url)) lastEdgeOneVersion = higherVersion(lastEdgeOneVersion, version);
            else lastPersonalVersion = higherVersion(lastPersonalVersion, version);
            CNLog.i(TAG,"客户端更新源 source="+reply.url+" version="+version);
            candidates.add(candidate);
            identities.add(new CNUpdateSources.ClientIdentity(version,candidate.getLong("size"),candidate.getString("sha256")));
        }
        CNLog.i(TAG,"客户端多源检查 completed="+replies.size()+" total="+urls.size());
        int selected=CNUpdateSources.highestClientIndex(identities);
        JSONObject best=selected<0?null:candidates.get(selected);
        lastBestVersion = best == null ? "—" : best.optString("version", "—");
        if (best!=null) {
            // 安装器换线只使用同版本、同大小、同哈希的来源，绝不在下载时退回旧包。
            org.json.JSONArray same=new org.json.JSONArray();
            same.put(best.getString("apk_url"));
            for(JSONObject c:candidates) {
                if(c==best)continue;
                if(compareVersion(c.getString("version"),best.getString("version"))==0
                   && c.getLong("size")==best.getLong("size")
                   && c.getString("sha256").equalsIgnoreCase(best.getString("sha256")))same.put(c.getString("apk_url"));
            }
            best.put("_apk_urls",same);
            CNLog.i(TAG,"客户端最高有效版本="+best.getString("version")+" source="+best.optString("_source"));
        }
        return best;
    }

    private static boolean isEdgeOneSource(String url) {
        String s = url == null ? "" : url.toLowerCase(java.util.Locale.US);
        return s.contains("edgeone") || s.contains("esa") || s.contains("edge-one");
    }

    private static String higherVersion(String a, String b) {
        if (a == null || a.equals("—") || a.isEmpty()) return b;
        return compareVersion(a, b) >= 0 ? a : b;
    }

    static boolean validClient(JSONObject c) {
        return c!=null && c.optString("version","").matches("[0-9]+(\\.[0-9]+){1,3}")
            && c.optLong("size",-1)>0 && c.optString("sha256","").matches("(?i)[0-9a-f]{64}")
            && CNSafeLink.reject(c.optString("apk_url",""))==null;
    }

    /** Accept either a config client section or the existing APK Release sidecar. */
    private static JSONObject fetchClientSection(String sourceUrl) throws Exception {
        String url=sourceUrl+(sourceUrl.indexOf('?')>=0?"&":"?")+"cnv_probe="+System.nanoTime();
        HttpURLConnection c=CNHttp.open(new URL(url),false,CONNECT_TIMEOUT_MS,READ_TIMEOUT_MS);
        try {
            c.setRequestProperty("Cache-Control","no-cache, no-store, max-age=0");
            int code=c.getResponseCode();
            if (code/100!=2) throw new java.io.IOException("HTTP "+code);
            if (c.getContentLength()>262144) throw new java.io.IOException("config response too large");
            InputStream in=new BufferedInputStream(c.getInputStream(),8192);
            ByteArrayOutputStream bos=new ByteArrayOutputStream();
            try {
                byte[] buf=new byte[8192];int n;
                while ((n=in.read(buf))>=0) {
                    if (n>262144-bos.size()) throw new java.io.IOException("config response too large");
                    bos.write(buf,0,n);
                }
            } finally { CNIo.closeQuietly(in); }
            String body=bos.toString("UTF-8");
            CNMirrors.requireJsonBody(body,c.getContentType());
            JSONObject root=new JSONObject(body);
            JSONObject client=root.optJSONObject("client");
            if (sourceUrl.endsWith(CNUpdateSources.CLIENT_META)) {
                if (!CNUpdateSources.CLIENT_APK.equals(root.optString("apk"))) throw new java.io.IOException("unexpected APK filename");
                client=new JSONObject(root.toString());
                client.put("apk_url",sourceUrl.substring(0,sourceUrl.length()-CNUpdateSources.CLIENT_META.length())+CNUpdateSources.CLIENT_APK);
            }
            if (client!=null) client.put("_source",sourceUrl);
            return client;
        } finally { c.disconnect(); }
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
        // F-064：`last` 从未通过 decorView 判据，不能冒充 usable——返回它会让调用方
        // 误以为可以等待更新选择，最终 UI 不存在且 continuation 被吞掉。超时返回 null。
        return null;
    }

    /** 建浮层，建不成就重试几轮；只有真实根视图存在才报告成功（F-064）。 */
    private static boolean showOverlay(Activity act) {
        for (int i = 0; i < 3; i++) {
            try {
                CNCNDownloadUI.show(act);
                CNCNDownloadUI.ensureVisible(act);
            } catch (Throwable t) {
                CNLog.w(TAG, "show() 第 " + (i + 1) + " 次失败：" + t);
            }
            if (CNCNDownloadUI.isShowing && CNCNDownloadUI.overlayView != null) return true;
            sleep(400L);
        }
        return false;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
