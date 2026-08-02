package io.kamihama.magianative;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;

import org.json.JSONObject;

/**
 * 热更新检查流程：启动时比对台词包 / 前端脚本包的版本，必要时下载并应用。
 *
 * <h3>为什么重写</h3>
 *
 * 原实现是 {@code RestClient.checkAndApplyHotUpdate()}（原包自带的 smali），
 * 由 {@code libcn_hook.so} 在 {@code JNI_OnLoad} 末尾起线程调用。切到
 * {@code libMagiaLegacy.so} 后照原样补回了那个 JNI 触发，真机实测的结果是
 * <b>浮层自始至终没有出现</b>，于是整条链路是否跑过、跑到哪一步，都无从判断。
 *
 * <p>原实现里能解释这一点的有三处：
 * <ul>
 *   <li>它只等 {@code getCurrentActivity() != null}。而这个方法读的是
 *       {@code ActivityThread.mActivities}，Activity 记录在 {@code onCreate}
 *       <b>之前</b>就已登记——拿到的 Activity 可能连 decorView 都还没有，
 *       此时 {@code show()} 建不出浮层。</li>
 *   <li>建失败之后没有任何重试；也没有看门狗，引擎切场景换掉 decorView
 *       内容时浮层会脱离视图树，而安装器路径是有看门狗保着的。</li>
 *   <li>版本相同（绝大多数启动）时它立刻 {@code hide()}，即使浮层建成了
 *       也只是一闪而过。</li>
 * </ul>
 *
 * <p>本类把这三点逐条修掉，并且<b>无论有没有更新都把结论显示出来</b>——
 * 「已是最新」也要看得见，否则没法区分「查过了没更新」和「压根没跑」。
 *
 * <h3>与原实现保持一致的约定</h3>
 * <ul>
 *   <li>版本号记在 SharedPreferences {@code MagiaCN} 的
 *       {@code scenario_version} / {@code js_version} 两个 int 键上；</li>
 *   <li>两份 version json <b>直连主线</b>（与 {@code mirrors.json} 同理，
 *       配置类请求不换线）；</li>
 *   <li>分发文件本身走支线：交给 {@link CNHotUpdate#download} —— 与首次安装
 *       同一套选线 + 分片 + 失败换线；</li>
 *   <li>解压到 {@code <files>/}，解压完删临时包。</li>
 * </ul>
 *
 * <h3>与原实现不同的地方</h3>
 *
 * 应用成功后<b>会重启进程</b>。原实现不重启，赌的是「热更跑完时引擎还没读到
 * 这些文件」——而本类为了让浮层可靠出现，会一直等到 Activity 真正可用，
 * 那时引擎已经在起了，再原地改脚本就有读到半新半旧的风险。整包安装完成后
 * 本来就要重启（见 {@code CNDownloaderFix} 末尾），这里沿用同一处理。
 */
public final class CNHotUpdateCheck {

    private static final String TAG = "MagiaCNHotUpdate";

    private static final String FILES_DIR  = "/data/data/io.kamihama.totentanz/files/";
    private static final String FINAL_FLAG = FILES_DIR + "madomagi/magica/cn_base_done.flag";

    /** 版本号存放的 SharedPreferences 文件名，与原实现一致，不能改。 */
    private static final String PREFS_NAME = "MagiaCN";

    /** 等 Activity 可用的上限：150 × 100ms = 15 秒。 */
    private static final int  ACTIVITY_WAIT_TRIES = 150;
    private static final long ACTIVITY_WAIT_STEP_MS = 100L;

    /** 没有更新时，把「已是最新」这个结论留在屏幕上的时间。 */
    private static final long IDLE_LINGER_MS = 1800L;

    /** 看门狗周期：与安装器路径一致地把浮层按回视图树。 */
    private static final long WATCHDOG_PERIOD_MS = 1000L;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS    = 20000;

    /** 只跑一次。 */
    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private CNHotUpdateCheck() {}

    /** 一个热更包的全部参数。 */
    private static final class Pkg {
        final String label;        // 日志与 UI 上的名字
        final String versionUrl;   // 版本 json（直连主线）
        final String versionKey;   // SharedPreferences 键
        final String zipUrl;       // 分发地址（会被换成支线）
        final String tmpName;      // 落地的临时文件名
        final int    slot;         // 浮层进度槽位
        Pkg(String label, String versionUrl, String versionKey,
            String zipUrl, String tmpName, int slot) {
            this.label = label;
            this.versionUrl = versionUrl;
            this.versionKey = versionKey;
            this.zipUrl = zipUrl;
            this.tmpName = tmpName;
            this.slot = slot;
        }
    }

    // 槽位取自 CNCNDownloadUI.FILE_NAMES 的下标。两个热更包已被排到列表最前，
    // 所以是 0 和 1——原实现里写的 14 / 11 是排序前的下标，照抄会画错行。
    private static final Pkg[] PACKAGES = {
        new Pkg("台词包",
                "https://assets.example.test/version_scenario.json", "scenario_version",
                "https://assets.example.test/cn_scenario_update.zip",
                "cn_scenario_update.zip", 0),
        new Pkg("前端脚本",
                "https://assets.example.test/version_js.json", "js_version",
                "https://assets.example.test/cn_js_update.zip",
                "cn_js_update_hot.zip", 1),
    };

    // ==================================================================
    // 入口
    // ==================================================================

    /**
     * 启动热更新检查。由 {@link CNDownloaderFix#triggerInstaller()} 在确认
     * 安装完成标记已存在之后调用。
     *
     * <p>不抛异常，也不阻塞调用方：内部另起守护线程。重复调用只有第一次生效。
     */
    public static void start() {
        try {
            if (!STARTED.compareAndSet(false, true)) {
                CNLog.i(TAG, "热更检查已经在跑，忽略重复调用");
                return;
            }
            Thread t = new Thread("cnv-hotupdate") {
                @Override public void run() {
                    try {
                        runInner();
                    } catch (Throwable th) {
                        CNLog.e(TAG, "热更检查异常终止: " + th, th);
                        try { CNCNDownloadUI.hide(); } catch (Throwable ignore) {}
                    }
                }
            };
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            try { android.util.Log.e(TAG, "热更检查线程起不来", t); } catch (Throwable ignore) {}
        }
    }

    // ==================================================================
    // 主流程
    // ==================================================================

    private static void runInner() {
        if (!new File(FINAL_FLAG).isFile()) {
            CNLog.i(TAG, "安装完成标记不存在，跳过热更检查（首次安装会把两个热更包一并下完）");
            return;
        }
        CNLog.i(TAG, "热更检查开始");

        Activity act = awaitUsableActivity();
        if (act == null) {
            // 没有界面也要把检查跑完：更新照样能应用，只是玩家看不到进度。
            CNLog.w(TAG, "等不到可用的 Activity，本次热更检查将无浮层运行");
        } else {
            showOverlay(act);
        }

        java.util.concurrent.ScheduledExecutorService watchdog = startWatchdog(act);
        boolean applied = false;
        try {
            CNCNDownloadUI.updateSimple("检查热更新", "正在查询台词与前端脚本的版本…", 0);
            for (int i = 0; i < PACKAGES.length; i++) {
                if (applyIfNewer(PACKAGES[i])) applied = true;
            }
        } finally {
            stopWatchdog(watchdog);
        }

        if (applied) {
            CNLog.i(TAG, "热更已应用，重启进程让引擎读到新文件");
            CNCNDownloadUI.updateSimple("更新完成", "已应用热更新，正在重启以生效…", 0);
            sleep(1200L);
            CNCNDownloadUI.hide();
            relaunch(act);
            return;
        }

        // 没有更新也要把结论留在屏幕上——否则和「压根没跑」看起来一模一样。
        CNCNDownloadUI.updateSimple("已是最新", "台词与前端脚本均为最新版本，即将进入游戏", 0);
        CNLog.i(TAG, "热更检查完毕：无需更新");
        sleep(IDLE_LINGER_MS);
        CNCNDownloadUI.hide();

        // 浮层收掉之后才问新手教程：这里正是「即将正式进入游戏」的时刻，
        // 而且两者不会抢同一片屏幕。只在不重启的这条路上问——要重启的话，
        // 重启后的那次启动自然会走到这里。
        CNTutorialPrompt.maybeAsk();
    }

    /**
     * 处理单个热更包：比对版本，必要时下载 + 解压 + 记录新版本号。
     *
     * @return true 表示确实应用了更新
     */
    private static boolean applyIfNewer(Pkg pkg) {
        int remote;
        try {
            remote = fetchVersion(pkg.versionUrl);
        } catch (Throwable t) {
            CNLog.w(TAG, "[" + pkg.label + "] 版本查询失败，跳过：" + t);
            CNCNDownloadUI.updateSimple("检查热更新",
                    pkg.label + "：版本查询失败，跳过本项", 0);
            return false;
        }
        int local = readLocalVersion(pkg.versionKey);
        CNLog.i(TAG, "[" + pkg.label + "] server=" + remote + " local=" + local);

        if (remote <= 0) {
            CNLog.w(TAG, "[" + pkg.label + "] 服务端版本号无效（" + remote + "），跳过");
            return false;
        }
        if (remote <= local) {
            CNCNDownloadUI.updateSimple("检查热更新",
                    pkg.label + "：已是最新（v" + local + "）", 0);
            return false;
        }

        CNLog.i(TAG, "[" + pkg.label + "] 需要更新 " + local + " → " + remote);
        CNCNDownloadUI.updateSimple("下载热更新",
                pkg.label + "：v" + local + " → v" + remote + "，正在下载…", 0);

        File tmp = new File(FILES_DIR, pkg.tmpName);
        // 上一次跑到一半留下的残骸会让 download() 直接判定「目标已存在」而跳过，
        // 于是拿旧的半截包去解压。先删掉。
        if (tmp.exists() && !tmp.delete()) {
            CNLog.w(TAG, "[" + pkg.label + "] 删不掉旧的临时包 " + tmp + "，放弃本项");
            return false;
        }

        if (!CNHotUpdate.download(pkg.zipUrl, tmp.getAbsolutePath(), pkg.tmpName, pkg.slot)) {
            CNLog.e(TAG, "[" + pkg.label + "] 下载失败，本项不更新（版本号保持 " + local + "）");
            CNCNDownloadUI.updateSimple("下载热更新", pkg.label + "：下载失败，已跳过", 0);
            return false;
        }

        CNCNDownloadUI.updateSimple("应用热更新", pkg.label + "：正在解压…", 0);
        try {
            CNDownloaderFix.extractChecked(tmp, new File(FILES_DIR));
        } catch (Throwable t) {
            // 解压失败时**不能**写新版本号，否则下次启动会以为已经更新过。
            CNLog.e(TAG, "[" + pkg.label + "] 解压失败，版本号保持 " + local, t);
            CNCNDownloadUI.updateSimple("应用热更新", pkg.label + "：解压失败，已跳过", 0);
            deleteQuietly(tmp);
            return false;
        }
        deleteQuietly(tmp);
        saveLocalVersion(pkg.versionKey, remote);
        CNLog.i(TAG, "[" + pkg.label + "] 更新完成，版本号记为 " + remote);
        CNCNDownloadUI.updateSimple("应用热更新", pkg.label + "：已更新到 v" + remote, 0);
        return true;
    }

    // ==================================================================
    // 浮层
    // ==================================================================

    /**
     * 等一个<b>真正能挂浮层</b>的 Activity。
     *
     * <p>只判断 {@code getCurrentActivity() != null} 是不够的：那个方法读的是
     * {@code ActivityThread.mActivities}，记录在 {@code onCreate} 之前就登记了，
     * 拿到的 Activity 可能还没有窗口。这里额外要求
     * {@code getWindow().peekDecorView() != null} —— decorView 存在才谈得上
     * 往上加 View。（用 peek 而不是 get：后者会强制创建 decorView，
     * 在别人的 Activity 上这么干不合适。）
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
                        if (i > 0) CNLog.i(TAG, "等到可用 Activity，耗时约 " + (i * 100) + "ms");
                        return act;
                    }
                } catch (Throwable ignore) {}
            }
            sleep(ACTIVITY_WAIT_STEP_MS);
        }
        if (last != null) {
            CNLog.w(TAG, "等满 " + (ACTIVITY_WAIT_TRIES * ACTIVITY_WAIT_STEP_MS / 1000)
                    + " 秒仍没等到 decorView，退而使用当前 Activity 试一把");
        }
        return last;
    }

    /** 建浮层，建不成就重试几轮——一次失败就放弃正是原实现看不见浮层的原因之一。 */
    private static void showOverlay(Activity act) {
        for (int i = 0; i < 3; i++) {
            try {
                CNCNDownloadUI.show(act);
                CNCNDownloadUI.ensureVisible(act);
            } catch (Throwable t) {
                CNLog.w(TAG, "show() 第 " + (i + 1) + " 次失败：" + t);
            }
            if (CNCNDownloadUI.isShowing) {
                CNLog.i(TAG, "浮层已显示");
                return;
            }
            sleep(400L);
        }
        CNLog.e(TAG, "浮层始终建不起来，热更将无界面运行");
    }

    private static java.util.concurrent.ScheduledExecutorService startWatchdog(final Activity act) {
        if (act == null) return null;
        try {
            java.util.concurrent.ScheduledExecutorService ex =
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            ex.scheduleWithFixedDelay(new Runnable() {
                @Override public void run() {
                    try { CNCNDownloadUI.ensureVisible(act); } catch (Throwable ignore) {}
                }
            }, WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS,
               java.util.concurrent.TimeUnit.MILLISECONDS);
            return ex;
        } catch (Throwable t) {
            CNLog.w(TAG, "看门狗起不来：" + t);
            return null;
        }
    }

    private static void stopWatchdog(java.util.concurrent.ScheduledExecutorService ex) {
        if (ex == null) return;
        try { ex.shutdownNow(); } catch (Throwable ignore) {}
    }

    // ==================================================================
    // 版本号
    // ==================================================================

    /** 直连主线取版本 json。配置类请求一律不换线，与 {@code mirrors.json} 同理。 */
    private static int fetchVersion(String url) throws Exception {
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
            // 版本 json 只有几十字节；设个上限免得对面返回一坨东西把内存吃了
            while ((n = in.read(buf)) >= 0 && bos.size() < 65536) bos.write(buf, 0, n);
            in.close();
            return new JSONObject(bos.toString("UTF-8")).getInt("version");
        } finally {
            try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    private static SharedPreferences prefs() {
        Context ctx = appContext();
        return ctx == null ? null : ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static int readLocalVersion(String key) {
        try {
            SharedPreferences p = prefs();
            return p == null ? 0 : p.getInt(key, 0);
        } catch (Throwable t) {
            CNLog.w(TAG, "读本地版本号失败（" + key + "）：" + t);
            return 0;
        }
    }

    private static void saveLocalVersion(String key, int value) {
        try {
            SharedPreferences p = prefs();
            if (p == null) {
                CNLog.e(TAG, "拿不到 Context，版本号 " + key + "=" + value + " 没能落盘");
                return;
            }
            p.edit().putInt(key, value).commit();   // commit 而非 apply：紧接着可能就重启了
        } catch (Throwable t) {
            CNLog.e(TAG, "写本地版本号失败（" + key + "）", t);
        }
    }

    // ==================================================================
    // 杂项
    // ==================================================================

    /**
     * 取 Application Context。与原包同一手法（反射 {@code ActivityThread}），
     * 因为补丁类没有别的途径拿到 Context——它们不由框架实例化。
     */
    private static Context appContext() {
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Object thread = cls.getMethod("currentActivityThread").invoke(null);
            return (Context) cls.getMethod("getApplication").invoke(thread);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 重启进程。不走 {@code RestClient.restartApp()}：那个实现会先调一次
     * 原包的 {@code checkAndApplyHotUpdate()}，等于把刚被本类取代的旧流程
     * 再跑一遍。
     */
    private static void relaunch(Activity act) {
        try {
            Context ctx = act != null ? act : appContext();
            if (ctx != null) {
                Intent it = ctx.getPackageManager()
                        .getLaunchIntentForPackage(ctx.getPackageName());
                if (it != null) {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    if (act != null) act.finish();
                    sleep(300L);
                    ctx.startActivity(it);
                    sleep(800L);
                }
            }
        } catch (Throwable t) {
            CNLog.e(TAG, "重启失败，直接结束进程", t);
        }
        try { CNLog.flushNow(); } catch (Throwable ignore) {}
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private static void deleteQuietly(File f) {
        try { if (f != null && f.exists() && !f.delete()) {
            CNLog.w(TAG, "删不掉临时文件 " + f);
        } } catch (Throwable ignore) {}
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
