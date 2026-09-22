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

    /** 最新多源热更版本，供主界面显示；-1 表示尚未收到该项元数据。 */
    public static volatile int latestJsVersion = -1;
    public static volatile int latestScenarioVersion = -1;
    public static volatile int edgeJsVersion = -1;
    public static volatile int edgeScenarioVersion = -1;

    private static final String FILES_DIR  = CNPaths.filesDir() + "/";
    // 安装完成的判据统一在 CNDownloaderFix.isBaseInstallationComplete()（F-074），
    // 这里不再自己拼一份 cn_base_done.flag 的路径——两份路径常量迟早会分叉。

    /** 版本号存放的 SharedPreferences 文件名，与原实现一致，不能改。 */
    private static final String PREFS_NAME = "MagiaCN";

    /** 等 Activity 可用的上限：150 × 100ms = 15 秒。 */
    private static final int  ACTIVITY_WAIT_TRIES = 150;
    private static final long ACTIVITY_WAIT_STEP_MS = 100L;

    /**
     * 没有更新时，把结论留在屏幕上的基础时长（无交互）。
     *
     * <p>两头夹的值：4 秒太短（2026-08-13 反馈：玩家看不清、够不着 LOG/停留入口）；
     * 涨到 9 秒后又太长（2026-08-17 反馈：热更检查完成后干等九秒，像卡死了）。
     * 取中间 6 秒——比 4 秒多 50% 反应时间，比 9 秒短三分之一，既来得及看清
     * 并决定要不要点 LOG/停留，又不至于让启动画面看起来停住不动。
     *
     * <p>真要久留就点状态行「停在本页」，那条不受这里限制。
     */
    private static final long IDLE_LINGER_MS = 6000L;

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
     * 版本 json 的硬上限（F-080）。真实响应只有几十字节，64 KiB 已经是三个数量级
     * 的余量；超过它<b>一定</b>是对面出了问题，所以判据是「明确失败」而不是
     * 「截断了继续用」——半份 JSON 拿去解析，最好的结果也只是把「响应超限」伪装
     * 成一个语法错误。
     */
    private static final int MAX_VERSION_JSON_BYTES = 65536;
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
    /** F-036：running 与 pendingRestartMsg 的同一同步边界。 */
    private static final Object RESTART_GATE = new Object();
    // F-036：running 与 pendingRestartMsg 的同一同步边界。旧实现「先查 running
    // 再写消息」与收尾线程「running=false 后读消息」竞态——请求线程可读到 true、
    // 收尾线程读到旧 null 并结束、请求线程随后写入消息，再也没有消费者。

    /** 检查是否正在进行。跑到一半重启会打断下载或解压。 */
    static boolean isRunning() {
        synchronized (RESTART_GATE) { return running; }
    }

    /**
     * 请求「等本次检查跑完再重启」。返回 true 时收尾线程**必定**能观察到该消息
     * （同锁互斥）；检查已收工则返回 false，由调用方自己重启。
     */
    static boolean requestRestartWhenDone(String toastText) {
        synchronized (RESTART_GATE) {
            if (!running) return false;
            if (pendingRestartMsg == null) pendingRestartMsg = toastText;
            return true;
        }
    }

    private CNHotUpdateCheck() {}

    /**
     * 一个热更包的全部参数。
     *
     * <p>构造器收的是<b>文件名</b>，完整地址由 {@link CNMirrors#CANONICAL_BASE}
     * 当场拼出来。这不只是为了让仓库里不留真实域名——更要紧的是，「PACKAGES 表
     * 里的地址与规范前缀对不上」这个<b>静默</b>失效模式（前缀剥不掉 → 拼出
     * {@code https://<镜像>/https://…} → 每条线路都失败 → 玩家看到「已是最新」）
     * 在结构上不再可能发生。原先两边各写一份，改一边就会踩中。
     */
    private static final class Pkg {
        final String label;        // 日志与 UI 上的名字
        final String versionUrl;   // 版本 json（直连主线）
        final String versionKey;   // SharedPreferences 键
        final String zipUrl;       // 分发地址（会被换成支线）
        final String tmpName;      // 落地的临时文件名
        final String txTag;        // 事务工作区名（见 CNHotUpdateTx）
        final int    slot;         // 浮层进度槽位
        /**
         * 同一热更槽位「最终版本复核 → 内容提交 → 版本状态发布」的线性化锁（F-088）。
         *
         * <p>PACKAGES 是静态表，每个槽位只有一个 Pkg 实例，所以这把锁天然是
         * 「每槽位一把」。<b>下载阶段绝不持有它</b>——并行下载是这条链的性能前提，
         * 锁只覆盖最后那段不可交错的提交窗口。
         */
        final Object lifecycleLock = new Object();
        Pkg(String label, String versionFile, String versionKey,
            String zipFile, String tmpName, String txTag, int slot) {
            this.label = label;
            this.versionUrl = CNMirrors.CANONICAL_BASE + versionFile;
            this.versionKey = versionKey;
            this.zipUrl = CNMirrors.CANONICAL_BASE + zipFile;
            this.tmpName = tmpName;
            this.txTag = txTag;
            this.slot = slot;
        }
    }

    // 槽位取自 CNCNDownloadUI.FILE_NAMES 的下标。两个热更包已被排到列表最前，
    // 所以是 0 和 1——原实现里写的 14 / 11 是排序前的下标，照抄会画错行。
    private static final Pkg[] PACKAGES = {
        new Pkg("台词包",
                "version_scenario.json", "scenario_version",
                "cn_scenario_update.zip",
                "cn_scenario_update.zip", "scenario",
                CNDownloaderFix.HOT_SLOT_SCENARIO),
        new Pkg("前端脚本",
                "version_js.json", "js_version",
                "cn_js_update.zip",
                "cn_js_update_hot.zip", "js",
                CNDownloaderFix.HOT_SLOT_JS),
        new Pkg("累计补充包", "version_js_delta.json", "js_delta_version",
                "cn_js_delta.zip", "cn_js_delta_hot.zip", "js_delta",
                CNDownloaderFix.HOT_SLOT_DELTA),
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
                        String msg;
                        synchronized (RESTART_GATE) {
                            running = false;
                            msg = pendingRestartMsg;
                            pendingRestartMsg = null;
                        }
                        CNLog.e(TAG, "热更检查异常终止（fail-open 进入游戏）: " + th, th);
                        try { CNCNDownloadUI.hide(); } catch (Throwable ignore) {}
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
        // F-074：判据与安装器共用一份——两边一旦分叉，就会出现「安装器认为没装完
        // 所以要重下、热更认为装完了所以往上盖」这种互相拆台的状态。
        if (!CNDownloaderFix.isBaseInstallationComplete()) {
            CNLog.i(TAG, "基础资源未装齐，跳过热更检查（首次安装会把两个热更包一并下完）");
            return;
        }
        CNLog.i(TAG, "热更检查开始");

        // 先收拾上一轮可能留下的半截事务，再谈这一轮。放在最前面是因为：
        // 半更新的树会让引擎读到新旧混杂的前端，而恢复本身只是几次目录 stat，
        // 没有残留时代价可以忽略。
        CNHotUpdateTx.recover(new File(FILES_DIR));

        // 线路表只做后台优化。内置默认线路从进程启动起就可用；
        // 线路表服务故障绝不能进入启动关键路径。
        CNMirrors.ensureLoadedAsync();

        // 注：WebView 拦截层代理的安装点在 CNDownloaderFix.triggerInstaller()，
        // 不在这里。原先挂在本方法里，结果「首次安装」那一支走不到——它跑完
        // runInstaller() 就 return 了，整个会话拦截层都没装上。移到分支之前
        // 才能两条路都覆盖。install() 内部有 CAS，重复调用无副作用。

        // final flag 已存在时，15 个槽位的 marker 才是 UI 的事实源。
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
        synchronized (RESTART_GATE) { running = true; }
        try {
            CNCNDownloadUI.updateSimple("检查热更新", "正在查询台词与前端脚本的版本…", 0);
            // 版本号并行查：串行时首条线路的慢/挂会在两个包上各吃一轮超时
            final java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(PACKAGES.length);
            java.util.concurrent.Future<CNHotUpdateValidate.VerMeta> fScenario =
                    pool.submit(new java.util.concurrent.Callable<CNHotUpdateValidate.VerMeta>() {
                        @Override public CNHotUpdateValidate.VerMeta call() { return fetchMetaSafe(PACKAGES[0]); }});
            java.util.concurrent.Future<CNHotUpdateValidate.VerMeta> fJs =
                    pool.submit(new java.util.concurrent.Callable<CNHotUpdateValidate.VerMeta>() {
                        @Override public CNHotUpdateValidate.VerMeta call() { return fetchMetaSafe(PACKAGES[1]); }});
            java.util.concurrent.Future<CNHotUpdateValidate.VerMeta> fDelta =
                    pool.submit(new java.util.concurrent.Callable<CNHotUpdateValidate.VerMeta>() {
                        @Override public CNHotUpdateValidate.VerMeta call() { return fetchMetaSafe(PACKAGES[2]); }});
            final CNHotUpdateValidate.VerMeta[] metas = new CNHotUpdateValidate.VerMeta[PACKAGES.length];
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
                        if (metas[2] == null) metas[2] = fDelta.get(deadlineNs - System.nanoTime(),
                                java.util.concurrent.TimeUnit.NANOSECONDS);
                        break;                       // 三份都拿到了
                    } catch (java.util.concurrent.TimeoutException te) {
                        long waited = android.os.SystemClock.uptimeMillis() - startedMs;
                        if (askVersionSlow(act, waited) != CNCNDownloadUI.SLOW_WAIT) {
                            anyFailure = true;
                            CNLog.w(TAG, "版本查询等待 " + waited
                                    + "ms 后按「跳过」处理，未完成项本次不更新");
                            fScenario.cancel(true);
                            fJs.cancel(true);
                            fDelta.cancel(true);
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
                            fDelta.cancel(true);
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
                boolean deltaNeedsRepair = pkg.slot == CNDownloaderFix.HOT_SLOT_DELTA
                        && (needs[0] || needs[1] || !CNJsDelta.installedMatches(
                                new File(FILES_DIR), readLocalVersion("js_version"), local));
                if (meta.version <= local && !deltaNeedsRepair) {
                    // 这一支是**本轮真的查过**的，标完成名副其实。
                    CNCNDownloadUI.updateSimple("检查热更新",
                            pkg.label + "：已是最新（v" + local + "）", 0);
                    CNCNDownloadUI.markFileDone(pkg.slot);
                    continue;
                }
                // F-B-07：三元组不齐的包不能进下载队列。CNHotUpdateValidate
                // .verifyZip 对「size 与 md5 都缺」已 fail-closed，但等到下完
                // 再拒会白烧一次下载与四条线路；而且只有 size 没有 md5 时身份
                // 强度也配不上「写可执行 JS 进本地优先目录」这条通道。在这里
                // 就按「校验无法进行」处理：记失败、不写版本号、不下载——
                // 与 redownloadPackage 的拒收判据对齐（那边原先只查 size，
                // 一并补上 md5）。失败方向偏安全：下次启动还会再查再试。
                if (meta.size <= 0 || meta.md5 == null || meta.md5.length() == 0) {
                    anyFailure = true;
                    markHotFailed(pkg.slot);
                    CNLog.e(TAG, "[" + pkg.label + "] version JSON 缺少 size/md5（size="
                            + meta.size + "），完整性校验无法进行，本轮跳过且不更新");
                    CNCNDownloadUI.updateSimple("检查热更新",
                            pkg.label + "：版本信息缺少校验字段，已跳过", 0);
                    continue;
                }
                // 需要更新的槽位回到等待/0%。（其余 13 个基础包本轮不检查，
                // 已由 syncInstalledUiState 标成「未检查」，不再冒充完成。）
                CNCNDownloadUI.markFilePending(pkg.slot);
                File tmp = new File(FILES_DIR, pkg.tmpName);
                // F-069：这里原先无条件 tmp.delete()。那条注释（「残骸会让
                // download() 直接判定目标已存在而跳过」）在身份校验落地之前是对的，
                // 现在不成立了：download() 拿到本轮 VerMeta 后会对已存在的目标做
                // size + 整包 MD5 + ZIP 结构校验，不符才 cleanupDownloadArtifacts()
                // 清掉重下——「陈旧的包被当成新的」这条路已经堵死。
                //
                // 而预删把 F-067 刻意保留的东西也一起删了：内容事务已经成功、只有
                // 版本号没落盘时，那份**已经通过 size/MD5/ZIP 校验**的包被留着，
                // 就是为了下次启动能不联网直接修复版本状态。日志说「保留更新包供
                // 下次修复」，下次启动第一件事却是删掉它——玩家白下一整个热更包，
                // 而运维看日志还以为复用生效了。
                //
                // 现在一律把 tmp 与本轮 meta 交给 download() 自己判：合格就复用，
                // 不合格由它按同一套身份合同清理，.part/.cpart/meta 也归它管。
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
                        java.util.concurrent.Executors.newFixedThreadPool(PACKAGES.length);
                // 并行下载时文案统一，不再按包互相覆盖——各包进度走槽位
                CNCNDownloadUI.updateSimple("下载热更新",
                        "正在下载更新包（共 " + needCount + " 个）…", 0);
                for (int i = 0; i < PACKAGES.length; i++) {
                    if (!needs[i]) continue;
                    final Pkg pkg = PACKAGES[i];
                    final File tmp = tmpFiles[i];
                    final CNHotUpdateValidate.VerMeta meta = metas[i];
                    final int idx = i;
                    java.util.concurrent.FutureTask<Boolean> task = new java.util.concurrent.FutureTask<Boolean>(
                            new java.util.concurrent.Callable<Boolean>() {
                        @Override public Boolean call() {
                            return CNHotUpdate.download(pkg.zipUrl, tmp.getAbsolutePath(),
                                                        pkg.tmpName, pkg.slot, meta);
                        }});
                    dls.put(idx, task);
                    // Delta stays lazy until earlier downloads AND commits have completed.
                    if (pkg.slot != CNDownloaderFix.HOT_SLOT_DELTA) dlPool.execute(task);
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
                    if (pkg.slot == CNDownloaderFix.HOT_SLOT_DELTA)
                        ((java.util.concurrent.FutureTask<Boolean>) e.getValue()).run();
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
                        if (choice != CNCNDownloadUI.ARIA2_OFFLINE && choice != CNCNDownloadUI.DL_CLOSE) {
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
                int commitResult;
                try {
                    // 事务化应用：先解压到暂存区，再整体换入；中途失败整体回滚，
                    // 绝不把「一半新一半旧」的树留给引擎（见 CNHotUpdateTx）。
                    // F-088：版本复核与提交收进每槽位窗口，见 commitVerifiedPackage。
                    commitResult = commitVerifiedPackage(pkg, tmp, meta, "startup");
                } catch (Throwable t) {
                    // 应用失败时**不能**写新版本号，否则下次启动会以为已经更新过。
                    anyFailure = true;
                    markHotFailed(pkg.slot);
                    if (CNDiskSpace.isOutOfSpace(t)) {
                        // F-B-08 解压段：解压中途写满磁盘（CNArchiveInstallTx 的
                        // 空间预检抛 NotEnoughSpace，或写盘 ENOSPC）不是「包坏了」。
                        // 事务已整体回滚、活动树未受污染，把「去清空间」如实告诉
                        // 玩家，而不是一句看不出原因的「应用失败」。
                        CNLog.e(TAG, "[" + pkg.label + "] 应用失败：存储空间不足（已回滚），版本号保持 " + local, t);
                        CNCNDownloadUI.updateSimple("存储空间不足",
                                CNDiskSpace.shortfall(pkg.label + " 解压", 0L,
                                        CNDiskSpace.usableBytes(new File(FILES_DIR)))
                                        + "。请清理后点「重试」。", 0);
                    } else {
                        CNLog.e(TAG, "[" + pkg.label + "] 应用失败（已回滚），版本号保持 " + local, t);
                        CNCNDownloadUI.updateSimple("应用热更新",
                                pkg.label + "：应用失败已回滚，已跳过（" + processedCount + "/" + needCount + "）", 0);
                    }
                    deleteQuietly(tmp);
                    continue;
                }
                if (commitResult == HOT_COMMIT_STALE) {
                    // F-088：玩家在本链下载期间手动更新到了更新的版本。活动树没被
                    // 碰过，这个槽位已经是更新的内容——按完成显示，不是失败。
                    deleteQuietly(tmp);
                    CNCNDownloadUI.markFileDone(pkg.slot);
                    CNLog.i(TAG, "[" + pkg.label + "] 已有更新版本，丢弃本次陈旧候选");
                    continue;
                }
                // F-067：内容事务成功不等于版本状态已持久化。先确认同步 commit
                // 成功，再删唯一的已验证下载包、再把本项标为完整成功。
                if (commitResult == HOT_COMMIT_STATE_FAILED) {
                    applied = true;     // 活动树已经是新内容，不能谎称完全没应用
                    anyFailure = true;  // 但控制状态分裂，整体只能叫部分失败
                    markHotFailed(pkg.slot);
                    CNLog.e(TAG, "[" + pkg.label + "] 内容已应用，但版本号未能落盘；"
                            + "保留已验证下载包，下一次启动重试状态修复");
                    CNCNDownloadUI.updateSimple("热更新状态未保存",
                            pkg.label + "：内容已应用，但版本记录失败；已保留更新包", 0);
                    continue;
                }
                deleteQuietly(tmp);
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
                    "检查已完成。可查看日志或管理资源；点带倒计时那行文字可停在本页。", 0);
        }
        awaitPlayerWindow();
        awaitConfigSettled();
        // 配置到位的短等待期间玩家仍可能点状态行停留；收浮层前再做一次
        // 无上限的显式停留闸。只有玩家自己点‘进入游戏’才释放。
        awaitExplicitStayRelease();
        // running 要在浮层收掉之前清掉：之后再点胶囊（浮层还在的最后一刻）
        // 应当走「自己重启」那条路，而不是挂在一个马上就结束的检查上。
        // F-036：running=false 与消息读取同锁原子，确保请求线程要么在锁内写入
        // 消息（本收尾必读到）、要么看到 false 自己重启，不留「没人消费」的窗口。
        String msg;
        synchronized (RESTART_GATE) {
            running = false;
            msg = pendingRestartMsg;
            pendingRestartMsg = null;
        }
        CNCNDownloadUI.hide();

        // 检查本身不重启——热更是启动早期跑的，引擎此时还没读到台词/脚本，
        // 原地替换即可生效，原实现也是这么做的。唯一的例外是玩家在检查进行中
        // 点了教程胶囊：那次重启不能打断下载/解压，于是接力到这里来做。
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
            // F-B-07：md5 也纳入强制。verifyZip 的 fail-closed 只拦「两者
            // 皆缺」；只有 size 没有 md5 仍会放行，而 size 相同、内容不同的
            // 重打包在线上真实发生过——这条通道写的是可执行 JS，身份强度不该
            // 停在 size 上。下完再拒等于白下一遍，所以在这里就拒。
            if (meta == null || meta.size <= 0 || meta.md5 == null || meta.md5.length() == 0)
                throw new java.io.IOException("版本 JSON 缺少有效 size/md5");
            CNCNDownloadUI.setFileSize(slot, (float) (meta.size / 1000000.0d));
            CNHotUpdate.cleanupDownloadArtifacts(tmp);
            boolean ok = CNHotUpdate.download(pkg.zipUrl, tmp.getAbsolutePath(),
                    pkg.tmpName, pkg.slot, meta);
            if (!ok) throw new java.io.IOException("所有镜像均未取得匹配 version JSON 的 ZIP");
            String bad = CNHotUpdateValidate.verifyZip(tmp, meta);
            if (bad != null) throw new java.io.IOException("完工校验失败: " + bad);
            CNCNDownloadUI.updateSimple("应用热更新", pkg.label + "：事务提交中…", 0);
            // F-088：与启动 continuation 走同一个提交助手，两条链才可能互相看见。
            int commitResult = commitVerifiedPackage(pkg, tmp, meta, "manual");
            if (commitResult == HOT_COMMIT_STALE) {
                // 手动链自己拿到的版本比当前值还旧（例如启动 continuation 抢先提交了
                // 更新的版本）。活动树没动，但玩家点的这次「重下」没有产生新内容，
                // 不能标绿——如实报失败，让玩家看得见。
                deleteQuietly(tmp);
                markHotFailed(slot);
                CNLog.w(TAG, "手动热更新候选比当前版本旧，已丢弃 slot=" + slot
                        + " candidate=" + meta.version);
                return false;
            }
            if (commitResult == HOT_COMMIT_STATE_FAILED) {
                // F-067：内容已经事务提交，不能回滚成「什么都没发生」；但也绝不能
                // 写成功 marker、标绿或删除 tmp。保留包和错误状态供下一次修复。
                if (CNCNDownloadUI.fileStatus != null
                        && slot >= 0 && slot < CNCNDownloadUI.fileStatus.length) {
                    CNCNDownloadUI.fileStatus[slot] = CNCNDownloadUI.ST_ERROR;
                }
                CNCNDownloadUI.setDownloadSpeed(slot, 0.0f);
                CNCNDownloadUI.throttledUpdate();
                CNLog.e(TAG, "手动热更新内容已应用，但版本号未落盘 slot=" + slot
                        + "；保留已验证下载包，不报告成功");
                return false;
            }
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

    /** @param act 仅作「调用方认为当前已有宿主」的语义判断（null 则不起看门狗）；
     *              看门狗每拍自行现取当前 Activity（Y-01），不再使用本参数。 */
    private static java.util.concurrent.ScheduledExecutorService startWatchdog(final Activity act) {
        if (act == null) return null;
        try {
            java.util.concurrent.ScheduledExecutorService ex =
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            ex.scheduleWithFixedDelay(new Runnable() {
                @Override public void run() {
                    // Y-01：每拍现取当前 Activity，而不是死守启动时捕获进
                    // final 参数的那一只。Activity 重建（深色模式/字体缩放/
                    // 分屏，configChanges 未覆盖 uiMode/density）后旧 act 已
                    // 销毁，对它 ensureVisible 只会往死树上补挂，永远挂不回
                    // 用户真正看到的界面。与 awaitUsableActivity 内（:735）、
                    // SpeedWatchdog（CNDownloaderFix）的既有写法对齐。
                    Activity cur = null;
                    try { cur = RestClient.getCurrentActivity(); } catch (Throwable ignore) {}
                    if (cur == null) return;   // 本拍没有可用宿主，下一拍再来
                    try { CNCNDownloadUI.ensureVisible(cur); } catch (Throwable ignore) {}
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

    /** Query every independent publisher; select the highest complete identity per package. */
    private static CNHotUpdateValidate.VerMeta fetchMeta(String url) throws Exception {
        String base=CNMirrors.CANONICAL_BASE;
        final String name=url.startsWith(base)?url.substring(base.length()):url;
        if (!CNMirrors.isLoaded()) CNMirrors.ensureLoadedAsync();
        final java.util.LinkedHashMap<String,String> bases=new java.util.LinkedHashMap<String,String>();
        for (CNMirrors.Mirror m:CNUpdateSources.mirrors(null)) bases.put(m.urlFor(name),m.base);
        java.util.List<String> urls=new java.util.ArrayList<String>(bases.keySet());
        java.util.List<CNUpdateSources.Reply<CNHotUpdateValidate.VerMeta>> replies=CNUpdateSources.collect(urls,
            new CNUpdateSources.Loader<CNHotUpdateValidate.VerMeta>() {
                public CNHotUpdateValidate.VerMeta load(String source) throws Exception {
                    String request=source+(source.indexOf('?')>=0?"&":"?")+"cnv_version="+System.nanoTime();
                    CNHotUpdateValidate.VerMeta v=fetchMetaDirect(request);
                    return new CNHotUpdateValidate.VerMeta(v.version,v.size,v.md5,bases.get(source));
                }
            },CNUpdateSources.QUERY_BUDGET_MS);
        java.util.List<CNHotUpdateValidate.VerMeta> values=new java.util.ArrayList<CNHotUpdateValidate.VerMeta>();
        for (CNUpdateSources.Reply<CNHotUpdateValidate.VerMeta> reply:replies) {
            if (reply.error!=null) CNLog.w(TAG,"热更新源失败（不计入传输冷却） source="+reply.url+" error="+reply.error);
            else if (CNUpdateSources.validHot(reply.value)) {
                values.add(reply.value);
                boolean edge = isEdgeOneSource(reply.url);
                if ("version_js.json".equals(name)) {
                    if (edge) edgeJsVersion = Math.max(edgeJsVersion, reply.value.version);
                    else latestJsVersion = Math.max(latestJsVersion, reply.value.version);
                } else if (name.contains("scenario")) {
                    if (edge) edgeScenarioVersion = Math.max(edgeScenarioVersion, reply.value.version);
                    else latestScenarioVersion = Math.max(latestScenarioVersion, reply.value.version);
                }
            } else CNLog.w(TAG,"热更新源元数据不完整 source="+reply.url);
        }
        CNHotUpdateValidate.VerMeta best=CNUpdateSources.highestHot(values);
        if (best==null) throw new java.io.IOException("所有来源均未返回完整有效的版本身份");
        CNLog.i(TAG,"最高热更版本 file="+name+" version="+best.version+" source="+best.sourceBase
                   +" completed="+replies.size()+" total="+urls.size());
        return best;
    }

    /** 从单条线路直取版本 json 并解析 version/size/md5。 */
    private static boolean isEdgeOneSource(String url) {
        String s = url == null ? "" : url.toLowerCase(java.util.Locale.US);
        return s.contains("edgeone") || s.contains("esa") || s.contains("edge-one");
    }

    private static CNHotUpdateValidate.VerMeta fetchMetaDirect(String url) throws Exception {
        // 尊重 Android 系统代理。未配置系统代理时 openConnection() 本身就是直连；
        // 显式 Proxy.NO_PROXY 会绕开 MuMu/Clash/mitm 链，正是本次真机长超时的来源。
        HttpURLConnection c = CNHttp.open(new URL(url), false,
                VER_CONNECT_TIMEOUT_MS, VER_READ_TIMEOUT_MS);
        try {
            c.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
            c.setRequestProperty("Pragma", "no-cache");
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new java.io.IOException("HTTP " + code);
            // F-080：上限要在**写进缓冲之前**判，而且超限必须报错。
            // 原写法是 `while ((n = in.read(buf)) >= 0 && bos.size() < 65536)`，
            // 两处都不对：
            //   · 判 size 时这一块已经读进来了，下一轮才退出——缓冲区实际能越过
            //     上限一整块（8 KiB）；
            //   · 退出后既不确认 EOF、也不报「响应过大」，而是把**截断前缀**直接
            //     交给 JSONObject。多数截断 JSON 会解析失败，但只要前缀本身正好
            //     构成一个完整对象（后面跟着大段空白或第二段内容），客户端就会
            //     接受一份并不完整的响应；而即使解析失败，日志也把「响应超限」
            //     伪装成普通 JSON 语法错误，排查时根本看不出真正的原因。
            // getContentLength()（int）而不是 getContentLengthLong()：后者是
            // API 24 才有的，minSdk 21 上会 NoSuchMethodError。上限只有 64 KiB，
            // int 绰绰有余；长度未知或超 int 时它返回 -1，正好落进「不预拒、
            // 交给下面的逐块闸」。
            int declared = c.getContentLength();
            if (declared > MAX_VERSION_JSON_BYTES) {
                throw new java.io.IOException("版本 json 声明长度超限: " + declared);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = new BufferedInputStream(c.getInputStream(), 8192);
            try {
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buf)) >= 0) {
                    if (n > MAX_VERSION_JSON_BYTES - total) {
                        throw new java.io.IOException("版本 json 超过 "
                                + MAX_VERSION_JSON_BYTES + " 字节上限");
                    }
                    bos.write(buf, 0, n);
                    total += n;
                }
            } finally {
                CNIo.closeQuietly(in);
            }
            JSONObject o = new JSONObject(bos.toString("UTF-8"));
            return new CNHotUpdateValidate.VerMeta(o.getInt("version"),
                             o.optLong("size", -1L),
                             o.optString("md5", ""));
        } finally {
            try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    private static SharedPreferences prefs() {
        Context ctx = CNRestClientActivity.appContext();
        return ctx == null ? null : ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── F-088：同槽位热更提交必须单调 ──────────────────────────────
    //
    // 故障链（外部审计确认）：启动检查取到 v2 并下载完，continuation 还没拿到资源
    // 提交锁；玩家这时对同一槽位点了「重下」，手动链取到 v3、先提交并写下
    // localVersion=3。旧的启动 continuation 后到，而它只靠全局 extractCommitLock()
    // 串行化——那把锁只保证两笔事务不同时改活动树，**不保证同一槽位版本单调**。
    // 于是 v2 覆盖回去，localVersion 被写回 2，玩家的手动更新被静默降级。
    //
    // 修法是把「最终复核 + 内容提交 + 版本发布」收进一个每槽位的窗口，并在窗口内
    // 重读当前版本；候选版本更旧就只丢弃候选，绝不触碰活动树。
    //
    // 锁序固定为 lifecycleLock → extractCommitLock，全仓库没有反向获取的地方；
    // 反过来拿会死锁（tools/HotUpdateMonotonicTest.java 的第 3 组把它证出来了），
    // 加新调用点时注意。
    private static final int HOT_COMMIT_STALE        = 0;
    private static final int HOT_COMMIT_APPLIED      = 1;
    private static final int HOT_COMMIT_STATE_FAILED = -1;

    /**
     * 把一个已校验的热更包提交进活动树，并发布版本号。
     *
     * <p>这是本文件里<b>唯一</b>调用 {@link CNHotUpdateTx#apply} 的地方。收敛成一处
     * 是这条修复的结构保证：只要还有第二个直接 apply 的调用点，单调性就又能被绕过。
     *
     * @return {@link #HOT_COMMIT_APPLIED} 已提交并发布；
     *         {@link #HOT_COMMIT_STALE} 候选比当前版本旧，已丢弃、活动树未动；
     *         {@link #HOT_COMMIT_STATE_FAILED} 内容已应用但版本号没落盘（F-067 部分失败）
     * @throws Exception 内容事务本身失败，由调用方按原有回滚/空间不足语义处理
     */
    private static int commitVerifiedPackage(Pkg pkg, File tmp,
            CNHotUpdateValidate.VerMeta meta, String source) throws Exception {
        synchronized (pkg.lifecycleLock) {
            int current = readLocalVersion(pkg.versionKey);
            if (meta.version < current) {
                CNLog.w(TAG, "[" + pkg.label + "] 拒绝陈旧热更候选 source=" + source
                        + " candidate=" + meta.version + " current=" + current);
                return HOT_COMMIT_STALE;
            }
            synchronized (CNDownloaderFix.extractCommitLock()) {
                // 等全局提交锁期间别的链条仍可能推进版本，进了真正的修改窗口
                // 之后必须再读一次——只在窗口外读等于没读。
                current = readLocalVersion(pkg.versionKey);
                if (meta.version < current) {
                    CNLog.w(TAG, "[" + pkg.label + "] 等待提交锁期间候选过期 source=" + source
                            + " candidate=" + meta.version + " current=" + current);
                    return HOT_COMMIT_STALE;
                }
                if (pkg.slot == CNDownloaderFix.HOT_SLOT_DELTA) {
                    CNJsDelta.apply(tmp, new File(FILES_DIR), readLocalVersion("js_version"), meta.version);
                } else {
                    CNHotUpdateTx.apply(tmp, new File(FILES_DIR), pkg.txTag);
                }
                // F-067：内容事务成功不等于版本状态已持久化，必须同步 commit
                // 并把失败如实上报，不能伪报完整成功。
                if (!saveLocalVersion(pkg.versionKey, meta.version)) {
                    return HOT_COMMIT_STATE_FAILED;
                }
                if (pkg.slot != CNDownloaderFix.HOT_SLOT_DELTA) reapplyDeltaAfterBase();
                return HOT_COMMIT_APPLIED;
            }
        }
    }

    /** Called after a successful first-install or manual base ZIP extraction, before its marker. */
    static void afterInstallerPackage(int slot, CNHotUpdateValidate.VerMeta meta) throws java.io.IOException {
        synchronized (CNDownloaderFix.extractCommitLock()) {
            if (slot == CNDownloaderFix.HOT_SLOT_JS || slot == CNDownloaderFix.HOT_SLOT_SCENARIO) {
                String key = slot == CNDownloaderFix.HOT_SLOT_JS ? "js_version" : "scenario_version";
                if (!CNUpdateSources.validHot(meta) || !saveLocalVersion(key, meta.version))
                    throw new java.io.IOException("安装完成，但热更版本记录未保存");
            }
            reapplyDeltaAfterBase();
        }
    }

    private static void reapplyDeltaAfterBase() {
        try { CNJsDelta.reapplyCached(new File(FILES_DIR), readLocalVersion("js_version")); }
        catch (Exception e) {
            // Preserve the successful base install; next check repairs a missing/incompatible layer.
            saveLocalVersion("js_delta_version", 0);
            CNLog.w(TAG, "补充层等待重新应用", e);
        }
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

    /** 同步持久化版本号；只有 commit 明确成功才返回 true（F-067）。 */
    private static boolean saveLocalVersion(String key, int value) {
        try {
            SharedPreferences p = prefs();
            if (p == null) {
                CNLog.e(TAG, "拿不到 Context，版本号 " + key + "=" + value + " 没能落盘");
                return false;
            }
            boolean ok = p.edit().putInt(key, value).commit();
            if (!ok) {
                CNLog.e(TAG, "SharedPreferences.commit 返回 false，版本号 "
                        + key + "=" + value + " 未确认落盘");
            }
            return ok;   // commit 而非 apply：紧接着可能就重启了
        } catch (Throwable t) {
            CNLog.e(TAG, "写本地版本号失败（" + key + "）", t);
            return false;
        }
    }

    // ==================================================================
    // 杂项
    // ==================================================================

    /**
     * 取 Application Context。与原包同一手法（反射 {@code ActivityThread}），
     * 因为补丁类没有别的途径拿到 Context——它们不由框架实例化。
     */

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
     *   <li>弹窗/日志面板开着：等待玩家操作，但受总上限约束（X-C8：
     *       modal split-brain 或真忘关时不能永远拦住启动）；</li>
     *   <li>总上限 {@link #PLAYER_WINDOW_MAX_MS}：弹窗忘了关也最终放行，
     *       不把玩家永远拦在启动画面。唯「显式停留」（玩家点过状态行的
     *       「停在本页」）不受此限。</li>
     * </ul>
     */
    private static void awaitPlayerWindow() {
        long start = android.os.SystemClock.uptimeMillis();
        try {
            while (true) {
                if (CNDownloadUiAssist.consumeLeaveRequest()) {
                    CNLog.i(TAG, "玩家点击“进入游戏”，结束资源页停留");
                    CNCNDownloadUI.setAutoEnterCountdown(0);
                    break;
                }
                // 玩家明确停留不设强制上限（与 awaitExplicitStayRelease 同
                // 语义），绝不能从手底下抽走页面。
                if (CNDownloadUiAssist.shouldStayOnPage()) {
                    CNCNDownloadUI.setAutoEnterCountdown(0);   // 无限期，不数秒
                    Thread.sleep(100);
                    continue;
                }
                // X-C8：模态分支必须受 PLAYER_WINDOW_MAX_MS 总上限约束——
                // 本方法 javadoc 承诺「弹窗忘了关也最终放行」，但原实现把
                // maxMs 检查排在 modal continue 之后，永远到不了：一旦 modal
                // split-brain（toggleTheme 重建树后字段残留，isModalOpen
                // 永 true）或玩家真忘关，启动就永久卡在资源页。isModalOpen
                // 已按「挂在当前树上」收紧（X-C8），这里再兜一道上限。
                if (CNCNDownloadUI.isModalOpen()
                        || CNDownloadUiAssist.isModalOpen()) {
                    CNCNDownloadUI.setAutoEnterCountdown(0);   // 不数秒
                    if (android.os.SystemClock.uptimeMillis() - start
                            >= PLAYER_WINDOW_MAX_MS) {
                        CNLog.w(TAG, "模态框持续超过 " + (PLAYER_WINDOW_MAX_MS / 1000)
                                + " 秒未关，到达玩家窗口总上限，放行");
                        break;
                    }
                    Thread.sleep(100);
                    continue;
                }
                long now = android.os.SystemClock.uptimeMillis();
                long lastTouch = CNCNDownloadUI.lastInteractionMs();
                boolean interacted = lastTouch > start;
                // 只认窗口开始后的交互；更早的触摸属于检查过程本身，不该顺延
                long anchor = interacted ? lastTouch : start;
                long linger = interacted ? INTERACT_LINGER_MS : IDLE_LINGER_MS;
                // 倒计时截止时刻 = 无交互/有交互各自的停留终点，封顶总上限；
                // 浮层据此渲染「N 秒后进入游戏」。
                CNCNDownloadUI.setAutoEnterCountdown(
                        Math.min(anchor + linger, start + PLAYER_WINDOW_MAX_MS));
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
            // R1-06：显式停留期间看门狗已停，Activity 重建会把浮层留在死树
            // 上且无人补挂——「进入游戏」按钮随之死亡，shouldStayOnPage 永真，
            // 本循环永久自旋、热更线程泄漏，玩家再也进不了游戏。浮层连续
            // 3 秒不在活宿主上即认定停留语义不可达，fail-open 放行（方向：
            // 宁可提前进游戏，也不把玩家永远拦在启动画面）。
            int deadBeats = 0;
            while (CNDownloadUiAssist.shouldStayOnPage()) {
                if (CNCNDownloadUI.overlayRecoverable()) {
                    deadBeats = 0;
                } else if (++deadBeats >= 30) {   // 30 × 100ms = 3s
                    CNLog.w(TAG, "显式停留期间浮层不可恢复（Activity 重建死树?），放行");
                    break;
                }
                Thread.sleep(100L);
            }
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
