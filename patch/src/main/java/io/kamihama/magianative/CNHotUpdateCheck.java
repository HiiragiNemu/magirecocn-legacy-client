package io.kamihama.magianative;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
 *   <li>两份 version json <b>直连主线</b>（与 {@code config.json} 同理，
 *       配置类请求不换线）；</li>
 *   <li>分发文件本身走支线：交给 {@link CNHotUpdate#download} —— 与首次安装
 *       同一套选线 + 分片 + 失败换线；</li>
 *   <li>解压到 {@code <files>/}，解压完删临时包——但<b>解压方式改了</b>：
 *       不再直接往活动树上覆盖，而是走 {@link CNHotUpdateTx} 的
 *       「暂存 → 备份 → 换入 → 出错回滚」，避免中途失败留下新旧混杂的前端。</li>
 * </ul>
 *
 * <h3>不重启</h3>
 *
 * 应用成功后<b>不</b>重启进程，与原实现一致：热更是启动早期跑的，引擎此时还
 * 没读到台词/脚本，原地替换即可生效。整个客户端里只有一处会重启——首次安装
 * 跑完那一次（见 {@code CNDownloaderFix} 末尾），因为那次引擎是在「没有资源」
 * 的状态下起来的，非重启不可。
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
    // 版本 json 只有几十字节，给长超时只会让坏线路拖慢整个检查；
    // 连不上/5 秒读不完就该换下一条线路
    private static final int VER_CONNECT_TIMEOUT_MS = 5000;
    private static final int VER_READ_TIMEOUT_MS    = 8000;

    /** 只跑一次。 */
    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 检查是否正在跑（含下载与解压）。教程胶囊据此决定要不要立刻重启。 */
    private static volatile boolean running = false;
    /** 非 null 表示「本次检查跑完后按这个文案重启」。由教程胶囊设置。 */
    private static volatile String pendingRestartMsg = null;

    /** 检查是否正在进行。跑到一半重启会打断下载或解压。 */
    static boolean isRunning() { return running; }

    /**
     * 请求「等本次检查跑完再重启」。教程胶囊在检查进行中被点时走这条路，
     * 而不是当场重启。检查已经收工的话返回 false，调用方自己重启。
     */
    static boolean requestRestartWhenDone(String toastText) {
        if (!running) return false;
        pendingRestartMsg = toastText;
        return true;
    }

    private CNHotUpdateCheck() {}

    /** 一个热更包的全部参数。 */
    private static final class Pkg {
        final String label;        // 日志与 UI 上的名字
        final String versionUrl;   // 版本 json（直连主线）
        final String versionKey;   // SharedPreferences 键
        final String zipUrl;       // 分发地址（会被换成支线）
        final String tmpName;      // 落地的临时文件名
        final String txTag;        // 事务工作区名（见 CNHotUpdateTx）
        final int    slot;         // 浮层进度槽位
        Pkg(String label, String versionUrl, String versionKey,
            String zipUrl, String tmpName, String txTag, int slot) {
            this.label = label;
            this.versionUrl = versionUrl;
            this.versionKey = versionKey;
            this.zipUrl = zipUrl;
            this.tmpName = tmpName;
            this.txTag = txTag;
            this.slot = slot;
        }
    }

    // 槽位取自 CNCNDownloadUI.FILE_NAMES 的下标。两个热更包已被排到列表最前，
    // 所以是 0 和 1——原实现里写的 14 / 11 是排序前的下标，照抄会画错行。
    private static final Pkg[] PACKAGES = {
        new Pkg("台词包",
                "https://r2.assets.example.test/version_scenario.json", "scenario_version",
                "https://r2.assets.example.test/cn_scenario_update.zip",
                "cn_scenario_update.zip", "scenario", 0),
        new Pkg("前端脚本",
                "https://r2.assets.example.test/version_js.json", "js_version",
                "https://r2.assets.example.test/cn_js_update.zip",
                "cn_js_update_hot.zip", "js", 1),
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

        // 先收拾上一轮可能留下的半截事务，再谈这一轮。放在最前面是因为：
        // 半更新的树会让引擎读到新旧混杂的前端，而恢复本身只是几次目录 stat，
        // 没有残留时代价可以忽略。
        CNHotUpdateTx.recover(new File(FILES_DIR));

        // 预热线路表：别等第一次取版本时才现场拉 config.json
        new Thread(new Runnable() {
            @Override public void run() {
                CNMirrors.refresh(false);
                if (!CNMirrors.isLoaded()) CNMirrors.refresh(true);
            }
        }, "cnv-mirrors-prewarm").start();

        Activity act = awaitUsableActivity();
        if (act == null) {
            // 没有界面也要把检查跑完：更新照样能应用，只是玩家看不到进度。
            CNLog.w(TAG, "等不到可用的 Activity，本次热更检查将无浮层运行");
        } else {
            showOverlay(act);
        }

        java.util.concurrent.ScheduledExecutorService watchdog = startWatchdog(act);
        boolean applied = false;
        // 任何包处理失败都记下——末尾的「已是最新」不能谎报
        boolean anyFailure = false;
        running = true;
        try {
            CNCNDownloadUI.updateSimple("检查热更新", "正在查询台词与前端脚本的版本…", 0);
            // 版本号并行查：串行时首条线路的慢/挂会在两个包上各吃一轮超时
            final java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.Future<VerMeta> fScenario =
                    pool.submit(new java.util.concurrent.Callable<VerMeta>() {
                        @Override public VerMeta call() { return fetchMetaSafe(PACKAGES[0]); }});
            java.util.concurrent.Future<VerMeta> fJs =
                    pool.submit(new java.util.concurrent.Callable<VerMeta>() {
                        @Override public VerMeta call() { return fetchMetaSafe(PACKAGES[1]); }});
            final VerMeta[] metas = new VerMeta[2];
            try {
                metas[0] = fScenario.get();
                metas[1] = fJs.get();
            } catch (Throwable t) {
                CNLog.w(TAG, "并行版本查询异常: " + t);
            }
            pool.shutdown();

            // 判定哪些包要更新
            final boolean[] needs  = new boolean[PACKAGES.length];
            final int[]    locals  = new int[PACKAGES.length];
            final File[]   tmpFiles = new File[PACKAGES.length];
            boolean anyNeed = false;
            for (int i = 0; i < PACKAGES.length; i++) {
                Pkg pkg = PACKAGES[i];
                VerMeta meta = metas[i];
                if (meta == null) continue;  // 查询失败已在 fetchMetaSafe 里提示
                int local = readLocalVersion(pkg.versionKey);
                locals[i] = local;
                CNLog.i(TAG, "[" + pkg.label + "] server=" + meta.version + " local=" + local);
                if (meta.version <= local) {
                    CNCNDownloadUI.updateSimple("检查热更新",
                            pkg.label + "：已是最新（v" + local + "）", 0);
                    continue;
                }
                File tmp = new File(FILES_DIR, pkg.tmpName);
                // 上一次跑到一半留下的残骸会让 download() 直接判定「目标已存在」而跳过
                if (tmp.exists() && !tmp.delete()) {
                    CNLog.w(TAG, "[" + pkg.label + "] 删不掉旧的临时包 " + tmp + "，放弃本项");
                    continue;
                }
                tmpFiles[i] = tmp;
                needs[i] = true;
                anyNeed = true;
            }

            // 需要更新的包并行下载（js 小包不再被 scenario 大包拖住）
            final java.util.Map<Integer, java.util.concurrent.Future<Boolean>> dls =
                    new java.util.LinkedHashMap<Integer, java.util.concurrent.Future<Boolean>>();
            int needCount = 0;
            for (int i = 0; i < PACKAGES.length; i++) if (needs[i]) needCount++;
            if (anyNeed) {
                final java.util.concurrent.ExecutorService dlPool =
                        java.util.concurrent.Executors.newFixedThreadPool(2);
                // 并行下载时文案统一，不再按包互相覆盖——各包进度走槽位
                CNCNDownloadUI.updateSimple("下载热更新",
                        "正在下载更新包（共 " + needCount + " 个）…", 0);
                for (int i = 0; i < PACKAGES.length; i++) {
                    if (!needs[i]) continue;
                    final Pkg pkg = PACKAGES[i];
                    final File tmp = tmpFiles[i];
                    final VerMeta meta = metas[i];
                    final int idx = i;
                    dls.put(idx, dlPool.submit(new java.util.concurrent.Callable<Boolean>() {
                        @Override public Boolean call() {
                            return CNHotUpdate.download(pkg.zipUrl, tmp.getAbsolutePath(),
                                                        pkg.tmpName, pkg.slot);
                        }}));
                }
                dlPool.shutdown();
            }

            // 收下载结果 → md5/size 校验 → 顺序解压（磁盘友好）
            int processedCount = 0;
            for (java.util.Map.Entry<Integer, java.util.concurrent.Future<Boolean>> e
                    : dls.entrySet()) {
                int i = e.getKey();
                Pkg pkg = PACKAGES[i];
                File tmp = tmpFiles[i];
                VerMeta meta = metas[i];
                int local = locals[i];
                boolean ok;
                try {
                    ok = e.getValue().get();
                } catch (Throwable t) {
                    ok = false;
                }
                processedCount++;
                if (!ok) {
                    anyFailure = true;
                    CNLog.e(TAG, "[" + pkg.label + "] 下载失败，本项不更新（版本号保持 " + local + "）");
                    CNCNDownloadUI.updateSimple("下载热更新",
                            pkg.label + "：下载失败，已跳过（" + processedCount + "/" + needCount + "）", 0);
                    continue;
                }
                String bad = verifyZip(tmp, meta);
                if (bad != null) {
                    anyFailure = true;
                    CNLog.e(TAG, "[" + pkg.label + "] 校验失败（" + bad + "），丢弃本项");
                    CNCNDownloadUI.updateSimple("下载热更新",
                            pkg.label + "：校验失败，已跳过（" + processedCount + "/" + needCount + "）", 0);
                    deleteQuietly(tmp);
                    continue;
                }
                CNCNDownloadUI.updateSimple("应用热更新",
                        "正在处理更新包（" + processedCount + "/" + needCount + "）…", 0);
                try {
                    // 事务化应用：先解压到暂存区，再整体换入；中途失败整体回滚，
                    // 绝不把「一半新一半旧」的树留给引擎（见 CNHotUpdateTx）
                    CNHotUpdateTx.apply(tmp, new File(FILES_DIR), pkg.txTag);
                } catch (Throwable t) {
                    // 应用失败时**不能**写新版本号，否则下次启动会以为已经更新过。
                    anyFailure = true;
                    CNLog.e(TAG, "[" + pkg.label + "] 应用失败（已回滚），版本号保持 " + local, t);
                    CNCNDownloadUI.updateSimple("应用热更新",
                            pkg.label + "：应用失败已回滚，已跳过（" + processedCount + "/" + needCount + "）", 0);
                    deleteQuietly(tmp);
                    continue;
                }
                deleteQuietly(tmp);
                saveLocalVersion(pkg.versionKey, meta.version);
                CNLog.i(TAG, "[" + pkg.label + "] 更新完成，版本号记为 " + meta.version);
                applied = true;
            }
        } finally {
            stopWatchdog(watchdog);
        }

        // 无论有没有更新都把结论留在屏幕上——否则和「压根没跑」看起来一模一样。
        if (applied) {
            CNLog.i(TAG, "热更检查完毕：已应用更新");
            CNCNDownloadUI.updateSimple("更新完成", "热更新已应用，即将进入游戏", 0);
        } else if (anyFailure) {
            // 有包处理失败时不能谎报「已是最新」——版本号没更新，下次还会重试
            CNLog.w(TAG, "热更检查完毕：部分更新包处理失败，已跳过");
            CNCNDownloadUI.updateSimple("更新未完成",
                    "部分更新包处理失败已跳过，将保持旧版本进入游戏", 0);
        } else {
            CNLog.i(TAG, "热更检查完毕：无需更新");
            CNCNDownloadUI.updateSimple("已是最新", "台词与前端脚本均为最新版本，即将进入游戏", 0);
        }
        sleep(IDLE_LINGER_MS);
        // running 要在浮层收掉之前清掉：之后再点胶囊（浮层还在的最后一刻）
        // 应当走「自己重启」那条路，而不是挂在一个马上就结束的检查上。
        running = false;
        CNCNDownloadUI.hide();

        // 检查本身不重启——热更是启动早期跑的，引擎此时还没读到台词/脚本，
        // 原地替换即可生效，原实现也是这么做的。唯一的例外是玩家在检查进行中
        // 点了教程胶囊：那次重启不能打断下载/解压，于是接力到这里来做。
        String msg = pendingRestartMsg;
        pendingRestartMsg = null;
        if (msg != null) {
            CNLog.i(TAG, "检查已收工，执行教程胶囊请求的重启");
            CNDownloaderFix.noticeAndRestart(msg);
        }
    }

    /**
     * 处理单个热更包：比对版本，必要时下载 + 解压 + 记录新版本号。
     * 已被 runInner 内联的「并行取版本 → 并行下载 → 校验 → 顺序解压」流程取代。
     */

    /** 供并行预取版本号用：失败返回 null 并提示，调用方按「跳过本包」处理。 */
    private static VerMeta fetchMetaSafe(Pkg pkg) {
        try {
            return fetchMeta(pkg.versionUrl);
        } catch (Throwable t) {
            CNLog.w(TAG, "[" + pkg.label + "] 版本查询失败，跳过：" + t);
            CNCNDownloadUI.updateSimple("检查热更新",
                    pkg.label + "：版本查询失败，跳过本项", 0);
            return null;
        }
    }

    /** 下载完工校验：size 对得上、md5 对得上才放行；返回 null 表示通过。 */
    private static String verifyZip(File f, VerMeta meta) {
        if (meta == null || f == null || !f.isFile()) return "文件缺失";
        if (meta.size > 0 && f.length() != meta.size) {
            return "大小不符 " + f.length() + " != " + meta.size;
        }
        if (meta.md5 != null && meta.md5.length() > 0) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                InputStream in = new BufferedInputStream(new FileInputStream(f), 65536);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
                in.close();
                StringBuilder sb = new StringBuilder(32);
                for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
                if (!meta.md5.equalsIgnoreCase(sb.toString())) {
                    return "md5 不符";
                }
            } catch (Throwable t) {
                return "md5 计算失败: " + t;
            }
        }
        return null;
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

    /**
     * 取版本 json。<b>走换线</b>：与资源文件同一套线路（维护者 2026-08-03 定的
     * 新规——「配置直连主线」的铁律对这两份 version json 不再适用；仍直连主线
     * 的只有线路表本身 config.json，它定义了线路，没得选）。从规范地址取出
     * 文件名后逐条线路试，失败记冷却；全部失败才抛出（调用方按「跳过本次
     * 热更」处理，不会卡住启动）。
     */
    /** 版本 json 的三元组：version 用来比对，size/md5 用于下载后的完工校验。 */
    private static final class VerMeta {
        final int    version;
        final long   size;
        final String md5;
        VerMeta(int v, long s, String m) { version = v; size = s; md5 = m; }
    }

    /**
     * 取版本 json（含 size/md5）。<b>走换线</b>：与资源文件同一套线路。
     * 从规范地址取出文件名后逐条线路试，失败记冷却；全部失败才抛出
     * （调用方按「跳过本次热更」处理，不会卡住启动）。
     */
    private static VerMeta fetchMeta(String url) throws Exception {
        String base = CNMirrors.DEFAULT_BASE;
        String name = url.startsWith(base) ? url.substring(base.length()) : url;
        // 线路表可能还没拉过（热更检查不一定跟在安装器后面跑）
        if (!CNMirrors.isLoaded()) {
            CNMirrors.refresh(false);
            if (!CNMirrors.isLoaded()) CNMirrors.refresh(true);
        }
        Exception last = null;
        for (CNMirrors.Mirror m : CNMirrors.healthy()) {
            try {
                return fetchMetaDirect(m.urlFor(name));
            } catch (Exception t) {
                CNLog.w(TAG, "版本 json 线路失败 mirror=" + m.name + ": " + t);
                CNMirrors.reportFailure(m, "version json");
                last = t;
            }
        }
        throw last != null ? last : new java.io.IOException("无可用线路");
    }

    /** 从单条线路直取版本 json 并解析 version/size/md5。 */
    private static VerMeta fetchMetaDirect(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        try {
            c.setConnectTimeout(VER_CONNECT_TIMEOUT_MS);
            c.setReadTimeout(VER_READ_TIMEOUT_MS);
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
            JSONObject o = new JSONObject(bos.toString("UTF-8"));
            return new VerMeta(o.getInt("version"),
                             o.optLong("size", -1L),
                             o.optString("md5", ""));
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
