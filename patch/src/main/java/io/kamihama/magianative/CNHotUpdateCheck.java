package io.kamihama.magianative;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
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
 * 没读到台词/脚本，原地替换即可生效。会重启进程的是别处的事——首次安装完成、
 * 序章播完、手动重下基础包、改写调试开关等场景（见 {@code CNRestart}）。
 */
public final class CNHotUpdateCheck {

    private static final String TAG = "MagiaCNHotUpdate";

    private static final String FILES_DIR  = CNPaths.filesDir() + "/";
    private static final String FINAL_FLAG = FILES_DIR + "madomagi/magica/cn_base_done.flag";

    /** 版本号存放的 SharedPreferences 文件名，与原实现一致，不能改。 */
    private static final String PREFS_NAME = "MagiaCN";

    /** 等 Activity 可用的上限：150 × 100ms = 15 秒。 */
    private static final int  ACTIVITY_WAIT_TRIES = 150;
    private static final long ACTIVITY_WAIT_STEP_MS = 100L;

    /**
     * 没有更新时，把结论留在屏幕上的基础时长（无交互）。
     *
     * <p>原先 4 秒，太短（2026-08-13 反馈）：玩家要先看清屏幕上写了什么、再决定
     * 要不要点 LOG 或「停留本页」，然后手还得移过去。4 秒基本只够看清第一行字，
     * 按钮还没够着页面就收了，于是「想看日志永远来不及」。
     *
     * <p>代价是每次启动都多停几秒，所以没有放得更长——真要久留有「停留本页」，
     * 那条不受这里限制。
     */
    private static final long IDLE_LINGER_MS = 9000L;

    /**
     * 玩家在浮层上每交互一次（任意按下），停留就从该时刻起再顺延这么长。
     * 有弹窗/日志面板开着时则一直等（见 awaitPlayerWindow），直到总上限。
     *
     * <p>原先 3 秒：手指刚离开屏幕三秒页面就没了，正在看的东西被抽走。既然玩家
     * 已经明确在操作，就该给足反应时间——他真想走，点「进入游戏」即可。
     */
    private static final long INTERACT_LINGER_MS = 12000L;

    /**
     * 玩家窗口的总上限。弹窗开着也会在这之后强制收浮层进游戏——
     * 玩家开着弹窗走开了，不能让他永远停在启动画面。
     */
    private static final long PLAYER_WINDOW_MAX_MS = 120000L;

    /**
     * 收浮层前等 config.json 到位的上限。署名区内容来自 config 的 ui_credits，
     * 秒退路径下热更收工常常比 config 到位还早，不给这个窗口玩家就永远只能
     * 看到「署名加载中…」。只等「还在加载」这一种状态，成功/失败立即放行——
     * 绝不能让它长成第二条启动关键路径。
     */
    private static final long CONFIG_SETTLE_MS = 3000L;

    /** 看门狗周期：与安装器路径一致地把浮层按回视图树。 */
    private static final long WATCHDOG_PERIOD_MS = 1000L;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS    = 20000;
    // 版本 json 只有几十字节，超时可以比资源文件（15s/30s）紧得多。
    //
    // ⚠ 这里有过一次方向相反的调整，别再来回改：早先把读超时放到 8s 以上，理由是
    // 版本 json 在 CDN 边缘常常是**冷的**（热门的是 cn_js_update.zip，没人单独请求
    // 版本 json），一次回源就可能超过 8 秒，太紧会把健康线路误判掉。
    //
    // 现在改紧到 3.5s，靠的是下面那道**总闸**换来的：单条线路判死得快 → 早点换下
    // 一条；就算所有线路都没在总闸内答上来，也只是 fail-open 进游戏、本次不热更，
    // 不会卡在白屏上。代价是冷边缘的线路更容易被跳过（热更少做一次，下次启动补上）。
    //
    // 所以这两个值只有连着总闸一起看才成立。要放宽单条超时，必须同时抬总闸，
    // 否则等于什么都没改——总闸先到期，单条那点余量根本用不上。
    private static final int VER_CONNECT_TIMEOUT_MS = 2000;
    private static final int VER_READ_TIMEOUT_MS    = 3500;
    /**
     * 两份版本查询**合计**最多占用启动关键路径 6 秒，超过即取消未完成项、
     * fail-open 进入游戏。
     *
     * <p>两个包是并行查的（固定 2 线程池），所以这 6 秒是墙钟时间，不是两份相加。
     */
    private static final long VERSION_QUERY_DEADLINE_MS = 6000L;
    /** 玩家选「继续等待」后再给的一段时间。到点仍没结果就再问一次，不无限等。 */
    private static final long VERSION_QUERY_EXTEND_MS = 15000L;

    /**
     * 版本查询超预算时问玩家：继续等，还是本次跳过。
     *
     * <p>取代原来那句「超时即 fail-open 进入游戏」。这件事众口难调——网好的觉得被
     * 慢线路拖着，网差的觉得刚开始就被放弃，而且两种都是<b>静默</b>发生的：玩家
     * 只看到「进游戏了但台词没更新」，根本不知道刚才做过一次取舍。所以摆到台面上。
     *
     * <p>浮层不在、或调用线程是 UI 线程时，{@code askSlowNetwork} 会返回
     * {@link CNCNDownloadUI#SLOW_SKIP}，也就是退回原来的行为——问不了就别卡着。
     */
    private static int askVersionSlow(android.app.Activity act, long waitedMs) {
        try {
            return CNCNDownloadUI.askSlowNetwork(act, "热更新",
                    "正在查询台词与前端脚本的版本",
                    "继续等待", "跳过",
                    "再给它一些时间。网络慢但可用时选这个。",
                    "本次不检查热更新，直接进入游戏；下次启动会再试。",
                    waitedMs);
        } catch (Throwable t) {
            CNLog.e(TAG, "[慢网询问] 版本查询询问出错，按跳过处理", t);
            return CNCNDownloadUI.SLOW_SKIP;
        }
    }

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
                "https://assets.example.test/version_scenario.json", "scenario_version",
                "https://assets.example.test/cn_scenario_update.zip",
                "cn_scenario_update.zip", "scenario", 0),
        new Pkg("前端脚本",
                "https://assets.example.test/version_js.json", "js_version",
                "https://assets.example.test/cn_js_update.zip",
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
            if (CNDebugFlags.isOn(CNDebugFlags.SKIP_HOT_UPDATE)) {
                CNLog.i(TAG, "调试开关 skipHotUpdate 生效，跳过热更检查直接进游戏");
                return;
            }
            Thread t = new Thread("cnv-hotupdate") {
                @Override public void run() {
                    try {
                        runInner();
                    } catch (Throwable th) {
                        running = false;
                        CNLog.e(TAG, "热更检查异常终止（fail-open 进入游戏）: " + th, th);
                        try { CNCNDownloadUI.hide(); } catch (Throwable ignore) {}
                        String msg = pendingRestartMsg;
                        pendingRestartMsg = null;
                        if (msg != null) {
                            try { CNDownloaderFix.noticeAndRestart(msg); }
                            catch (Throwable ignore) {}
                        }
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

        // 线路表只做后台优化。内置默认线路从进程启动起就可用；
        // api.example.test 故障绝不能进入启动关键路径。
        CNMirrors.ensureLoadedAsync();

        // 注：WebView 拦截层代理的安装点在 CNDownloaderFix.triggerInstaller()，
        // 不在这里。原先挂在本方法里，结果「首次安装」那一支走不到——它跑完
        // runInstaller() 就 return 了，整个会话拦截层都没装上。移到分支之前
        // 才能两条路都覆盖。install() 内部有 CAS，重复调用无副作用。

        // final flag 已存在时，15 个基础资源的 marker 才是 UI 的事实源。
        // 先恢复真实完成状态；稍后只有确认“需要热更”的 0/1 号槽位才切回等待。
        CNDownloaderFix.syncInstalledUiState();

        Activity act = awaitUsableActivity();
        if (act == null) {
            // 没有界面也要把检查跑完：更新照样能应用，只是玩家看不到进度。
            CNLog.w(TAG, "等不到可用的 Activity，本次热更检查将无浮层运行");
        } else {
            showOverlay(act);
        }

        java.util.concurrent.ScheduledExecutorService watchdog = startWatchdog(act);
        boolean applied = false;
        // 下载失败询问框整轮只弹一次，见下方 !ok 分支
        boolean askedHotFallback = false;
        // 任何包处理失败都记下——末尾的「已是最新」不能谎报
        boolean anyFailure = false;
        running = true;
        try {
            CNCNDownloadUI.updateSimple("检查热更新", "正在查询台词与前端脚本的版本…", 0);
            // 版本号并行查：串行时首条线路的慢/挂会在两个包上各吃一轮超时
            final java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.Future<CNHotUpdateValidate.VerMeta> fScenario =
                    pool.submit(new java.util.concurrent.Callable<CNHotUpdateValidate.VerMeta>() {
                        @Override public CNHotUpdateValidate.VerMeta call() { return fetchMetaSafe(PACKAGES[0]); }});
            java.util.concurrent.Future<CNHotUpdateValidate.VerMeta> fJs =
                    pool.submit(new java.util.concurrent.Callable<CNHotUpdateValidate.VerMeta>() {
                        @Override public CNHotUpdateValidate.VerMeta call() { return fetchMetaSafe(PACKAGES[1]); }});
            final CNHotUpdateValidate.VerMeta[] metas = new CNHotUpdateValidate.VerMeta[2];
            // 预算用完不再替玩家决定，而是问他（见 askVersionSlow 的说明）。
            final long startedMs = android.os.SystemClock.uptimeMillis();
            long budgetMs = VERSION_QUERY_DEADLINE_MS;
            try {
                while (true) {
                    long deadlineNs = System.nanoTime()
                            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budgetMs);
                    try {
                        if (metas[0] == null) {
                            metas[0] = fScenario.get(deadlineNs - System.nanoTime(),
                                    java.util.concurrent.TimeUnit.NANOSECONDS);
                        }
                        if (metas[1] == null) {
                            metas[1] = fJs.get(deadlineNs - System.nanoTime(),
                                    java.util.concurrent.TimeUnit.NANOSECONDS);
                        }
                        break;                       // 两份都拿到了
                    } catch (java.util.concurrent.TimeoutException te) {
                        long waited = android.os.SystemClock.uptimeMillis() - startedMs;
                        if (askVersionSlow(act, waited) != CNCNDownloadUI.SLOW_WAIT) {
                            anyFailure = true;
                            CNLog.w(TAG, "版本查询等待 " + waited
                                    + "ms 后按「跳过」处理，未完成项本次不更新");
                            fScenario.cancel(true);
                            fJs.cancel(true);
                            break;
                        }
                        budgetMs = VERSION_QUERY_EXTEND_MS;   // 玩家说再等，就再给一段
                        CNCNDownloadUI.updateSimple("检查热更新",
                                "继续等待版本查询…（已等 " + (waited / 1000) + " 秒）", 0);
                        CNLog.i(TAG, "玩家选择继续等待版本查询，追加 "
                                + VERSION_QUERY_EXTEND_MS + "ms");
                    }
                }
            } catch (Throwable t) {
                anyFailure = true;
                CNLog.w(TAG, "并行版本查询异常: " + t);
                fScenario.cancel(true);
                fJs.cancel(true);
            } finally {
                pool.shutdownNow();
            }

            // 判定哪些包要更新
            final boolean[] needs  = new boolean[PACKAGES.length];
            final int[]    locals  = new int[PACKAGES.length];
            final File[]   tmpFiles = new File[PACKAGES.length];
            boolean anyNeed = false;
            for (int i = 0; i < PACKAGES.length; i++) {
                Pkg pkg = PACKAGES[i];
                CNHotUpdateValidate.VerMeta meta = metas[i];
                if (meta == null) {
                    anyFailure = true;
                    // 版本查不到 ≠ 已是最新。原先这里直接 continue，槽位就沿用
                    // syncInstalledUiState 恢复出来的「✓ 完成」——把「不知道」
                    // 显示成了「已确认最新」，而这恰恰是最该让玩家看见的情况：
                    // 热更没生效时，界面反而最像一切正常。
                    CNCNDownloadUI.markFileUnchecked(pkg.slot, "版本查询失败 · 未检查");
                    continue;
                }
                int local = readLocalVersion(pkg.versionKey);
                locals[i] = local;
                CNLog.i(TAG, "[" + pkg.label + "] server=" + meta.version + " local=" + local);
                if (meta.version <= local) {
                    // 这一支是**本轮真的查过**的，标完成名副其实。
                    CNCNDownloadUI.updateSimple("检查热更新",
                            pkg.label + "：已是最新（v" + local + "）", 0);
                    CNCNDownloadUI.markFileDone(pkg.slot);
                    continue;
                }
                // 需要更新的槽位回到等待/0%。（其余 13 个基础包本轮不检查，
                // 已由 syncInstalledUiState 标成「未检查」，不再冒充完成。）
                CNCNDownloadUI.markFilePending(pkg.slot);
                File tmp = new File(FILES_DIR, pkg.tmpName);
                // 上一次跑到一半留下的残骸会让 download() 直接判定「目标已存在」而跳过
                if (tmp.exists() && !tmp.delete()) {
                    anyFailure = true;
                    markHotFailed(pkg.slot);
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
                    final CNHotUpdateValidate.VerMeta meta = metas[i];
                    final int idx = i;
                    dls.put(idx, dlPool.submit(new java.util.concurrent.Callable<Boolean>() {
                        @Override public Boolean call() {
                            return CNHotUpdate.download(pkg.zipUrl, tmp.getAbsolutePath(),
                                                        pkg.tmpName, pkg.slot, meta);
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
                CNHotUpdateValidate.VerMeta meta = metas[i];
                int local = locals[i];
                boolean ok;
                try {
                    ok = e.getValue().get();
                } catch (Throwable t) {
                    ok = false;
                }
                processedCount++;
                if (!ok) {
                    // 热更是「进游戏前的最后一关」，卡在这里的玩家根本进不去，
                    // 所以和基础包一样把取舍摆出来，而不是默默跳过。
                    //
                    // 整轮只问一次：两个包都失败时问两遍毫无意义——玩家第二次
                    // 面对同一个框，没有任何新信息可给。
                    //
                    // 不给「改用离线包」：离线导入只覆盖 13 个基础包，scenario/js
                    // 走版本 JSON 通道，给了就是个死路按钮。
                    if (!askedHotFallback) {
                        askedHotFallback = true;
                        int choice = CNDownloaderFix.awaitDownloadFallbackChoice(
                                pkg.label, CNAria2.isAvailable(), false);
                        if (choice != CNCNDownloadUI.ARIA2_OFFLINE) {
                            CNLog.w(TAG, "[" + pkg.label + "] 玩家选择重试，模式="
                                    + CNDownloadMode.describe());
                            CNCNDownloadUI.updateSimple("下载热更新",
                                    pkg.label + "：正在按新设置重试…", 0);
                            // redownloadPackage 自带「取版本 → 下载 → 校验 → 事务
                            // 应用 → 记版本号」整条链，成功即本项已完成，不能再
                            // 落到下面的应用流程里去（tmp 已被它删掉）。
                            if (redownloadPackage(pkg.slot)) {
                                applied = true;
                                deleteQuietly(tmp);
                                continue;
                            }
                        }
                    }
                    anyFailure = true;
                    CNLog.e(TAG, "[" + pkg.label + "] 下载失败，本项不更新（版本号保持 " + local + "）");
                    CNCNDownloadUI.updateSimple("下载热更新",
                            pkg.label + "：下载失败，已跳过（" + processedCount + "/" + needCount + "）", 0);
                    continue;
                }
                String bad = CNHotUpdateValidate.verifyZip(tmp, meta);
                if (bad != null) {
                    anyFailure = true;
                    markHotFailed(pkg.slot);
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
                    synchronized (CNDownloaderFix.extractCommitLock()) {
                        CNHotUpdateTx.apply(tmp, new File(FILES_DIR), pkg.txTag);
                    }
                } catch (Throwable t) {
                    // 应用失败时**不能**写新版本号，否则下次启动会以为已经更新过。
                    anyFailure = true;
                    markHotFailed(pkg.slot);
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

        // 无论有没有更新都把结论留在屏幕上。失败优先于“有一个成功”：
        // JS 成功而 scenario 失败时只能叫“部分更新完成”，绝不能谎报“更新完成”。
        String summaryPhase = summaryPhaseForTest(applied, anyFailure);
        if (anyFailure) {
            if (applied) {
                CNLog.w(TAG, "热更检查完毕：部分成功、部分失败");
                CNCNDownloadUI.updateSimple(summaryPhase,
                        "成功项已应用；失败项可点红色“重试”或紫色“重下”", 0);
            } else {
                CNLog.w(TAG, "热更检查完毕：更新失败");
                CNCNDownloadUI.updateSimple(summaryPhase,
                        "更新包未能应用；失败项可直接重试，当前旧版本保持可用", 0);
            }
        } else if (applied) {
            CNLog.i(TAG, "热更检查完毕：已应用全部需要的更新");
            CNCNDownloadUI.updateSimple(summaryPhase, "热更新已校验并事务应用", 100);
        } else {
            CNLog.i(TAG, "热更检查完毕：无需更新");
            CNCNDownloadUI.updateSimple(summaryPhase,
                    "检查已完成。可查看日志或管理资源；需要停留请使用“停留本页”。", 0);
        }
        awaitPlayerWindow();
        awaitConfigSettled();
        // 配置到位的短等待期间玩家仍可能点‘停留本页’；收浮层前再做一次
        // 无上限的显式停留闸。只有玩家自己点‘进入游戏’才释放。
        awaitExplicitStayRelease();
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



    private static void markHotFailed(int slot) {
        if (CNCNDownloadUI.fileStatus != null
                && slot >= 0 && slot < CNCNDownloadUI.fileStatus.length) {
            CNCNDownloadUI.fileStatus[slot] = CNCNDownloadUI.ST_ERROR;
        }
        CNCNDownloadUI.setDownloadSpeed(slot, 0.0f);
        CNCNDownloadUI.throttledUpdate();
    }

    /**
     * 手动强制重下 scenario/js 的当前服务端版本。它不依赖其它 14 个 marker，
     * 不读取基础包 manifest；下载通过 version JSON 的 size/MD5 后才事务应用。
     */
    static boolean redownloadPackage(int slot) {
        Pkg pkg = null;
        for (int i = 0; i < PACKAGES.length; i++) {
            if (PACKAGES[i].slot == slot) { pkg = PACKAGES[i]; break; }
        }
        if (pkg == null) {
            CNLog.e(TAG, "手动热更新槽位无效: " + slot);
            return false;
        }
        File tmp = new File(FILES_DIR, pkg.tmpName + ".manual.zip");
        try {
            CNMirrors.ensureLoadedAsync();
            CNCNDownloadUI.markFilePending(slot);
            CNCNDownloadUI.updateSimple("重新下载热更新",
                    pkg.label + "：正在取得当前版本身份…", 0);
            CNHotUpdateValidate.VerMeta meta = fetchMeta(pkg.versionUrl);
            if (meta == null || meta.size <= 0) throw new java.io.IOException("版本 JSON 缺少有效 size");
            CNCNDownloadUI.setFileSize(slot, (float) (meta.size / 1000000.0d));
            CNHotUpdate.cleanupDownloadArtifacts(tmp);
            boolean ok = CNHotUpdate.download(pkg.zipUrl, tmp.getAbsolutePath(),
                    pkg.tmpName, pkg.slot, meta);
            if (!ok) throw new java.io.IOException("所有镜像均未取得匹配 version JSON 的 ZIP");
            String bad = CNHotUpdateValidate.verifyZip(tmp, meta);
            if (bad != null) throw new java.io.IOException("完工校验失败: " + bad);
            CNCNDownloadUI.updateSimple("应用热更新", pkg.label + "：事务提交中…", 0);
            synchronized (CNDownloaderFix.extractCommitLock()) {
                CNHotUpdateTx.apply(tmp, new File(FILES_DIR), pkg.txTag);
            }
            saveLocalVersion(pkg.versionKey, meta.version);
            CNDownloaderFix.commitManualMarker(slot, meta.size, "hot-v" + meta.version);
            deleteQuietly(tmp);
            CNCNDownloadUI.markFileDone(slot);
            CNLog.i(TAG, "手动热更新完成 slot=" + slot + " version=" + meta.version);
            return true;
        } catch (Throwable t) {
            CNHotUpdate.cleanupDownloadArtifacts(tmp);
            if (CNCNDownloadUI.fileStatus != null
                    && slot >= 0 && slot < CNCNDownloadUI.fileStatus.length) {
                CNCNDownloadUI.fileStatus[slot] = CNCNDownloadUI.ST_ERROR;
            }
            CNCNDownloadUI.setDownloadSpeed(slot, 0.0f);
            CNCNDownloadUI.throttledUpdate();
            CNLog.e(TAG, "手动热更新失败 slot=" + slot, t);
            return false;
        }
    }

    /** 供回归测试与真实汇总共用，避免“一个成功 + 一个失败”显示成全成功。 */
    public static String summaryPhaseForTest(boolean applied, boolean failed) {
        if (failed) return applied ? "部分更新完成" : "更新未完成";
        return applied ? "更新完成" : "已是最新";
    }

    /** 供并行预取版本号用：失败返回 null 并提示，调用方按「跳过本包」处理。 */
    private static CNHotUpdateValidate.VerMeta fetchMetaSafe(Pkg pkg) {
        try {
            // 注入点放在真正发请求之前：拖慢的是「查询这件事」，
            // 不是某一条线路——这样总闸与询问框的行为才和真实慢网一致。
            CNDebugFlags.injectSlow(CNDebugFlags.SLOW_VERSION_QUERY,
                                    "版本查询 " + pkg.label);
            if (CNDebugFlags.isOn(CNDebugFlags.FAIL_VERSION_QUERY)) {
                throw new java.io.IOException("[DEBUG] failVersionQuery 注入的失败");
            }
            return fetchMeta(pkg.versionUrl);
        } catch (Throwable t) {
            CNLog.w(TAG, "[" + pkg.label + "] 版本查询失败，跳过：" + t);
            CNCNDownloadUI.updateSimple("检查热更新",
                    pkg.label + "：版本查询失败，跳过本项", 0);
            return null;
        }
    }

    /**
     * 供<b>首次安装器</b>用：按槽位取热更包的权威身份（version json 的
     * version/size/md5）。
     *
     * <p>首次安装器把这两包排在下载队列最前，走的却是基础包那条路；基础包靠
     * {@code manifest.json} 的块指纹认证内容，而热更包的清单会滞后于流水线单独
     * 重发的 ZIP（{@code docs/DOWNLOAD_TRANSACTIONAL_CHUNKS.md}「动态热更新与
     * 静态分块的边界」）。安装器改成不读清单之后，得从这里拿回真正的身份。
     *
     * <p>取不到返回 null，由调用方决定是否放行——不在这里抛，热更那一轮还会
     * 按版本号再核对一次。
     */
    static CNHotUpdateValidate.VerMeta metaForSlot(int slot) {
        for (int i = 0; i < PACKAGES.length; i++) {
            if (PACKAGES[i].slot != slot) continue;
            try {
                return fetchMeta(PACKAGES[i].versionUrl);
            } catch (Throwable t) {
                CNLog.w(TAG, "取热更包版本身份失败 slot=" + slot
                        + " (" + PACKAGES[i].label + "): " + t);
                return null;
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
    private static CNHotUpdateValidate.VerMeta fetchMeta(String url) throws Exception {
        // 规范前缀，不是兜底线路——换兜底线路时这里必须岿然不动，
        // 否则剥不出文件名，拼出来的地址每条线路都会 404。
        String base = CNMirrors.CANONICAL_BASE;
        String name = url.startsWith(base) ? url.substring(base.length()) : url;
        // 内置 fallback 一直存在；远程 config 只在后台刷新，绝不在
        // 版本查询关键路径同步等 api.example.test。
        if (!CNMirrors.isLoaded()) CNMirrors.ensureLoadedAsync();
        Exception last = null;
        for (CNMirrors.Mirror m : CNMirrors.healthy()) {
            try {
                String metaUrl = m.urlFor(name);
                String sep = metaUrl.indexOf('?') >= 0 ? "&" : "?";
                metaUrl = metaUrl + sep + "cnv_version="
                        + System.currentTimeMillis() + "-" + Math.abs(name.hashCode());
                return fetchMetaDirect(metaUrl);
            } catch (Exception t) {
                // 只换下一条线路，**不调 reportFailure**。
                //
                // 这里失败不代表这条线路不适合传大文件。版本 json 是个几十字节的
                // 冷对象，一次回源慢就可能超时；而 reportFailure 在
                // switch_after_failures=1 的线上配置下会让它立刻进 60 秒冷却，
                // 冷却中的线路被 healthy() 整个排除——于是后续所有资源包都不再用它。
                //
                // 实际撞到过：竞速刚用 cn_js_update.zip 的前 256KB 实测吞吐把
                // （已下线线路） 评为最快、排到最前，紧接着版本 json 这一步就把它拉黑了。
                // 两个机制测的根本不是一回事：小文件冷启动的延迟与大文件的吞吐
                // 没有因果关系。线路适不适合传大文件，交给竞速与下载过程中的
                // stall / 限速判定去管，那才是对口的度量。
                CNLog.w(TAG, "版本 json 线路失败（只换线，不计入冷却） mirror="
                        + m.name + ": " + t);
                last = t;
            }
        }
        throw last != null ? last : new java.io.IOException("无可用线路");
    }

    /** 从单条线路直取版本 json 并解析 version/size/md5。 */
    private static CNHotUpdateValidate.VerMeta fetchMetaDirect(String url) throws Exception {
        // 尊重 Android 系统代理。未配置系统代理时 openConnection() 本身就是直连；
        // 显式 Proxy.NO_PROXY 会绕开 MuMu/Clash/mitm 链，正是本次真机长超时的来源。
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(VER_CONNECT_TIMEOUT_MS);
            c.setReadTimeout(VER_READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
            c.setRequestProperty("Pragma", "no-cache");
            c.setRequestProperty("Accept-Encoding", "identity");
            CNUserAgent.apply(c);
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
            return new CNHotUpdateValidate.VerMeta(o.getInt("version"),
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

    /**
     * 「玩家窗口」：检查结论出来后，收浮层之前留给玩家的时间。
     *
     * <p>三种结局共用一个窗口（已是最新 / 更新完成 / 更新未完成都一样要给
     * 玩家读结论的时间）。规则：
     * <ul>
     *   <li>无交互：停 {@link #IDLE_LINGER_MS} 就走；</li>
     *   <li>有交互：从最后一次按下起顺延 {@link #INTERACT_LINGER_MS}，
     *       给点教程胶囊（播序章）、BGM 胶囊、翻署名这些手动入口留时间；</li>
     *   <li>弹窗/日志面板开着：一直等，玩家正在操作，不能从他手底下抽走；</li>
     *   <li>总上限 {@link #PLAYER_WINDOW_MAX_MS}：弹窗忘了关也最终放行，
     *       不把玩家永远拦在启动画面。</li>
     * </ul>
     */
    private static void awaitPlayerWindow() {
        long start = android.os.SystemClock.uptimeMillis();
        try {
            while (true) {
                if (CNDownloadUiAssist.consumeLeaveRequest()) {
                    CNLog.i(TAG, "玩家点击“进入游戏”，结束资源页停留");
                    break;
                }
                // 玩家明确停留、或正在操作任一模态框时不设强制上限，绝不能
                // 从手底下抽走页面。引擎闸门也要一直保留到真正 hide。
                if (CNDownloadUiAssist.shouldStayOnPage()
                        || CNCNDownloadUI.isModalOpen()
                        || CNDownloadUiAssist.isModalOpen()) {
                    Thread.sleep(100);
                    continue;
                }
                long now = android.os.SystemClock.uptimeMillis();
                long lastTouch = CNCNDownloadUI.lastInteractionMs();
                boolean interacted = lastTouch > start;
                // 只认窗口开始后的交互；更早的触摸属于检查过程本身，不该顺延
                long anchor = interacted ? lastTouch : start;
                long linger = interacted ? INTERACT_LINGER_MS : IDLE_LINGER_MS;
                if (now - anchor >= linger) break;
                if (now - start >= PLAYER_WINDOW_MAX_MS) {
                    CNLog.w(TAG, "无人停留且无模态操作，玩家窗口到达总上限");
                    break;
                }
                Thread.sleep(100);
            }
        } catch (Throwable ignore) {}
    }

    /** 显式停留没有自动超时；按钮切回‘进入游戏’后才继续完成启动。 */
    private static void awaitExplicitStayRelease() {
        try {
            while (CNDownloadUiAssist.shouldStayOnPage()) Thread.sleep(100L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 收浮层前给云端配置一个短短的到位窗口（{@link #CONFIG_SETTLE_MS}）。
     *
     * <p>只等「还在加载」（configState==0）这一种状态：加载成功/失败都立刻
     * 放行，超时也放行。调试开关 skipMirrorConfig 下 config 永远不会到位，
     * 直接不等。
     */
    private static void awaitConfigSettled() {
        try {
            if (CNMirrors.configState != 0) return;
            if (CNDebugFlags.isOn(CNDebugFlags.SKIP_MIRROR_CONFIG)) return;
            long deadline = android.os.SystemClock.uptimeMillis() + CONFIG_SETTLE_MS;
            while (CNMirrors.configState == 0
                    && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(100);
            }
            CNLog.i(TAG, "收浮层前 config 状态=" + CNMirrors.configState
                    + (CNMirrors.configState == 0 ? "（等到超时仍未加载）"
                                                  : "（1=成功 2=最终失败）"));
        } catch (Throwable ignore) {}
    }
}
