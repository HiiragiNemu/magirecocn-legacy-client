package io.kamihama.magianative;

import android.app.Activity;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 资源安装器。
 *
 * <p>对外入口只有两个，均由 {@code RestClient} 调用：{@link #runInstaller()} 与
 * {@link #getEndpoint(int)}。安装流程、完成标记（marker）、解压校验、重试与
 * 断点续传的语义与改版前保持一致。
 *
 * <p>本次改版新增两件事：
 * <ul>
 *   <li><b>多线程分片下载</b>——单个文件按 {@code Range} 切片并行下载
 *       （见 {@link CNChunkedDownload}）。服务端不支持 Range 时自动退回改版前的
 *       单线程续传实现。</li>
 *   <li><b>自动换线</b>——线路列表从 {@link CNMirrors#MIRRORS_URL} 拉取；
 *       下载失败、停滞或过慢都会让该线路进入冷却，下一次重试自动换到下一条线路。
 *       拉不到线路列表时回退到内置的默认线路，行为与改版前一致。</li>
 * </ul>
 *
 * <p>注意：完成标记里记录的始终是<b>规范 URL</b>（{@link #RESOURCE_BASE_URL} +
 * 文件名），与实际使用的线路无关。这样换线既不会让已有安装失效，也不会让
 * {@link #allMarkersValid()} 因为线路不同而误判。
 */
public final class CNDownloaderFix {

    private static final String BOOTSTRAP_URL = "https://totentanz-9b.magi-reco.com/magica/api/snaa";

    /** SNAA 引导地址：代理配置下发后改经 /stream 走香港代理（尽量全代理）。 */
    private static String snaaUrl() {
        String base = CNMirrors.proxyBase();
        if (base != null && !base.isEmpty()) {
            return base + "totentanz-9b.magi-reco.com/magica/api/snaa";
        }
        return BOOTSTRAP_URL;
    }
    private static final int    CONNECT_TIMEOUT_MS = 15000;
    private static final String FILE_ROOT = CNPaths.filesDir();
    private static final String FINAL_FLAG = FILE_ROOT + "/madomagi/magica/cn_base_done.flag";
    private static final String INSTALL_ROOT = FILE_ROOT + "/";
    private static final int    MAX_ATTEMPTS = 4;
    /** {@link #tryAria2Download} 的返回：装好了。 */
    private static final int A2_INSTALLED = 1;
    /** {@link #tryAria2Download} 的返回：玩家选继续主引擎（或询问兜底），走主引擎重试。 */
    private static final int A2_MAIN      = 0;
    /** {@link #tryAria2Download} 的返回：玩家选改用离线包，跳过主引擎重试。 */
    private static final int A2_OFFLINE   = -1;
    /**
     * {@link #tryAria2Download} 的返回：磁盘满。与 A2_OFFLINE 分开是因为
     * 调用方对 OFFLINE 的语义是「玩家不要这个包了」，会清掉半截产物——
     * 而磁盘满时那 1.4GB 的完整/半截包恰恰是**最不能删**的东西：删完
     * 玩家腾出来的空间又得拿去重下一遍。下层已 reportNoSpace，这里只
     * 需要原样保留、静默退出，等他腾完空间点重试续传。
     */
    private static final int A2_NOSPACE   = -2;
    /**
     * aria2 单文件最多尝试次数。与主引擎的 {@link #MAX_ATTEMPTS} 对齐，好让
     * {@code CNMirrors.pick(attempt)} 有机会把线路表轮一遍——线路只有轮得完，
     * 「换线」才叫换线。
     */
    private static final int A2_MAX_ATTEMPTS = 4;
    private static final int    MAX_DOWNLOADS = 4;
    private static final int    MIN_SNAA_VERSION = 128;
    private static final String NO_RESTART_FLAG = FILE_ROOT + "/madomagi/magica/.cn_installer/r128-downloader-v1/no_restart";
    private static final int    READ_TIMEOUT_MS = 30000;
    // 低速看门狗：read timeout 管的是「完全没字节」，管不了「每秒几十 KB 的滴速」。
    // 窗口速度持续低于 MIN_OK_BPS 超过 SLOW_FAIL_NS 就抛异常走换线。
    private static final long   MIN_OK_BPS  = 100L * 1024L;                      // 100 KB/s
    private static final long   SLOW_FAIL_NS = TimeUnit.SECONDS.toNanos(15L);
    /**
     * 规范资源地址：仅用于生成完成标记里的 {@code url} 字段，<b>不代表实际下载线路</b>。
     *
     * <h3>⚠ 这个串不许改，即使它不解析</h3>
     *
     * 它<b>从来不会被请求</b>——全部用途只有两处：{@link #writeMarker} 把它写进
     * 标记文件，{@link #isMarkerValid} 拿它做<b>逐字符串比对</b>
     * （{@code text.contains("url=" + url + "\n")}）。所以它是个**身份标识**，
     * 不是下载源，域名能不能解析与它的职责无关。
     *
     * <p>而它已经写进**每一台已安装设备**的 15 个标记文件里了。一旦改动，
     * {@code allMarkersValid()} 会对全部 15 个包返回 false，安装器判定「没装过」
     * ——**每个老玩家重新下载几个 GB**。
     *
     * <p>所以：看到这个域名解析不了，那是正常的，<b>不要「顺手修好」它</b>。
     * 真要迁移，得先给标记文件加 schema=2 与迁移逻辑（认旧 url 也算有效），
     * 而不是直接改这个常量。{@code tools/check-base-urls.py} 会把它钉住。
     */
    private static final String RESOURCE_BASE_URL = CNEndpoints.ASSETS_BASE;
    private static final String STATE_ROOT = FILE_ROOT + "/madomagi/magica/.cn_installer/r128-downloader-v1";
    private static final String TAG = "MagiaCNDownloader";

    private static final long   STALE_SPEED_NS = TimeUnit.SECONDS.toNanos(2);
    private static final Object EXTRACT_LOCK   = new Object();

    /**
     * 下载顺序。
     *
     * <p><b>热更两包排在最后</b>：{@code cn_scenario_update.zip}（台词）与
     * {@code cn_js_update.zip}（前端脚本）里可能带着针对其它包的<b>覆盖修正</b>
     * ——同一个文件，别的包给一份、热更包给一份改好的。装反了，那个包会把改好的
     * 那份盖回去，而这种坏法完全没有报错：文件都在、标记都全，只是内容退回了
     * 修正之前。
     *
     * <p>所以前置集合就是热更两包的<b>补集</b>：其余 13 个包，一个不落。不按前缀
     * 或用途挑——「这个包应该不会跟热更撞文件吧」这种判断一旦下错，错法是静默的，
     * 而收益只是让汉化早到一会儿。
     *
     * <p><b>但这张表本身给不了「装在之后」的保证</b>：15 个包是一次性全部提交给
     * 一个 4 线程池的（见 {@link #runInstallerInner}），表序只决定谁先开工，几十 MB
     * 的热更包必然在几 GB 的内容包还没下完时就装完了。真正的保证在
     * {@link #awaitPrereqInstalled}——那道闸只挡解压提交，下载照旧并行。
     *
     * <p>排在最后还有一层作用：线程池按提交序取任务，热更包因此最晚开工，等在
     * 闸前的时间最短，不会白占着池里的线程让其余包只剩两条腿下载。
     *
     * <p>顺序只是下载次序，不是身份：所有逻辑都按**文件名**索引，完成标记也是
     * {@code <文件名>.done}，所以调整顺序不会让既有安装失效、也不会触发重下。
     * 与 {@link CNCNDownloadUI#FILE_NAMES} / {@link CNCNDownloadUI#FILE_URLS}
     * <b>必须逐项对齐</b>——三张表是按下标并行的。
     */
    private static final String[] FILE_NAMES = {
        "cn_base_00_db.zip", "cn_base_01_json.zip", "cn_base_02.zip",
        "cn_base_03.zip", "cn_base_04.zip", "cn_base_05.zip",
        "cn_base_06.zip", "cn_magica_resource.zip", "cn_scenario_img.zip",
        "cn_voice_01.zip", "cn_voice_02_done.zip",
        "movie.zip", "movie2.zip",
        "cn_scenario_update.zip", "cn_js_update.zip"
    };

    private static final int ARCHIVE_COUNT = 15;

    /**
     * 热更那一轮真正检查的两个槽位，与 {@code CNHotUpdateCheck.PACKAGES} 的 slot 对应。
     *
     * <p><b>按文件名查出来，不写死数字。</b>原先是 0 / 1，跟表序绑死；一调顺序
     * 这两个常量就悄悄指向别的包，而它们被用来决定「走热更通道还是基础包通道」
     * 与「装完要不要重启」——指错了不会报错，只会走错分支。
     */
    static final int HOT_SLOT_SCENARIO = slotOf("cn_scenario_update.zip");
    static final int HOT_SLOT_JS       = slotOf("cn_js_update.zip");

    /**
     * 前置包槽位：<b>热更两包的补集</b>——其余 13 个包，一个不落。
     *
     * <p>刻意不按前缀或用途挑。「这个包应该不会跟热更撞文件吧」这种判断下错了
     * 是静默的（文件都在、标记都全，只是内容退回了修正之前），而挑对了的收益
     * 不过是让汉化早到一会儿。取补集就没有可挑错的地方，新增包也自动落进来。
     */
    private static final int[] PREREQ_SLOTS = prereqSlots();

    private static int slotOf(String name) {
        for (int i = 0; i < FILE_NAMES.length; i++) {
            if (FILE_NAMES[i].equals(name)) return i;
        }
        throw new IllegalStateException("FILE_NAMES 里没有 " + name);
    }

    private static int[] prereqSlots() {
        int n = 0;
        for (int i = 0; i < FILE_NAMES.length; i++) {
            if (!isHotSlot(i)) n++;
        }
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < FILE_NAMES.length; i++) {
            if (!isHotSlot(i)) out[k++] = i;
        }
        return out;
    }

    /** 这个下标是不是热更两包之一。五处判断都读这里，别再各写一份下标比较。 */
    static boolean isHotSlot(int index) {
        return index == HOT_SLOT_SCENARIO || index == HOT_SLOT_JS;
    }

    /** 补集的另一半，写成函数只是为了让 countDown 那处读起来是一句话。 */
    private static boolean isPrereqSlot(int index) {
        return !isHotSlot(index);
    }

    /**
     * 「前置包没装完，热更两包不许装」的闸门，每轮安装重建一次。
     *
     * <p>计数在每个前置包<b>收尾时</b>减一，<b>成败都减</b>。只在成功时减的话，
     * 某个前置包失败就会把热更两包永远挂在闸前，主循环等 future 等不回来——
     * 玩家连「重试」都点不到，界面停在那里不动。放行之后再核对 marker：前置包
     * 真失败了，热更包这一轮也不装，返回失败，由重试进入下一轮。
     */
    private static volatile CountDownLatch prereqGate = new CountDownLatch(0);

    /**
     * 挡在解压提交之前：热更包等前置包收尾。返回 false 表示这一轮不该装。
     *
     * <p>不会死锁：热更两包排在表尾，线程池按提交序取任务，所以轮到热更包时
     * 其余 13 个包早已全部开工——最多只剩 {@code MAX_DOWNLOADS - 1} 个还在跑，
     * 它们在别的线程上，减到零只是时间问题。
     */
    private static boolean awaitPrereqInstalled(int index, String name) {
        if (!isHotSlot(index)) return true;
        CountDownLatch gate = prereqGate;
        if (gate.getCount() > 0) {
            CNLog.i(TAG, "hold-for-prereq file=" + name
                    + " remaining=" + gate.getCount());
            CNCNDownloadUI.updateSimple("正在安装资源",
                    name + "：等其余资源装完再装（它可能覆盖那些包里的文件）", 100);
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        for (int i = 0; i < PREREQ_SLOTS.length; i++) {
            String pre = FILE_NAMES[PREREQ_SLOTS[i]];
            if (!isMarkerValid(markerFor(pre), pre, RESOURCE_BASE_URL + pre)) {
                CNLog.w(TAG, "defer-hot file=" + name + " reason=prereq-incomplete pending=" + pre);
                return false;
            }
        }
        return true;
    }

    /** 防止 native hook 与 Java 侧同时触发安装器。 */
    private static final AtomicBoolean installerStarted = new AtomicBoolean(false);

    /**
     * 浮层看门狗（每秒确保浮层挂在视图树上 + 归零停滞速度）。存成静态引用，
     * 使「意外错误」路径也能把它停掉——否则异常从 runInstallerInner 冒出来时
     * 这个局部 executor（默认非守护线程）会泄漏到进程结束，持续在窗口上调用
     * ensureVisible，并阻止进程正常退出。
     */
    private static volatile ScheduledExecutorService speedWatchdog;

    /**
     * 安装器是否正在跑。教程胶囊用它决定「改完设置要不要立刻重启」——
     * 安装到一半重启会把下载打断（虽然 .cnvprog 能续传，但没必要），
     * 而且安装收尾本来就会重启一次，等它就好。
     */
    static boolean isInstalling() {
        return installerStarted.get();
    }

    /** 玩家点「重试」时用来唤醒安装器主循环。 */
    private static final Object RETRY_LOCK = new Object();
    private static volatile boolean retryRequested = false;

    private static final AtomicLongArray    LAST_PROGRESS_NS = new AtomicLongArray(ARCHIVE_COUNT);
    private static final AtomicIntegerArray ACTIVE           = new AtomicIntegerArray(ARCHIVE_COUNT);
    /** 手动重下只绕过所选 marker；旧 marker 一直保留到新包成功提交。 */
    private static final AtomicIntegerArray FORCE_REDOWNLOAD =
            new AtomicIntegerArray(ARCHIVE_COUNT);
    /** 同一 ZIP 的自动安装与手动重下串行，不同 ZIP 仍可并行。 */
    private static final Object[] ARCHIVE_LOCKS = createArchiveLocks();

    private CNDownloaderFix() {
    }

    /** 由 CNCNDownloadUI 在 Cocos GL 线程调用，释放被下载浮层闸住的主页/BGM。 */
    public static native void nativeReleaseDeferredTop();
    public static native void nativeTutorialRestartFailed();

    /** 独立重启跳板进程只负责把主进程重新拉起，绝不能再启动安装/热更线程。 */
    private static boolean isRestartProcess() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                String n = android.app.Application.getProcessName();
                if (n != null && n.endsWith(":cnrestart")) return true;
            }
        } catch (Throwable ignore) {}
        try {
            FileInputStream in = new FileInputStream("/proc/self/cmdline");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) > 0 && bos.size() < 256) bos.write(b);
            in.close();
            String n = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            return n.endsWith(":cnrestart");
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * Java 侧的安装器入口，由 {@code MyApplication.onCreate()} 调用，
     * 作为 native hook 触发之外的第二道保险。
     *
     * <p><b>为什么需要它。</b>把 libcn_hook.so 反汇编出来看，native 侧真正会转调
     * {@code RestClient.startCNDownload} 的只有 {@code triggerCNDownload()}，
     * 而它只有两个调用点：
     * <ul>
     *   <li>{@code DownloadAssetJsonState::checkParseJson}（正常路径）</li>
     *   <li>{@code MainScene::onError}（出错路径）</li>
     * </ul>
     * 两者都先查 {@code cn_base_done.flag}，再用一个进程级 atomic 保证只触发一次。
     * 换句话说，安装器起不起得来，取决于引擎能不能走到「下载资源清单」那一步；
     * 引擎在那之前卡住或走了别的分支，安装器就永远不会被调用，玩家看到的就是
     * 引擎自带的下载场景。本方法把这个依赖去掉。
     *
     * <p>（注意 {@code DownloadSceneLayer} 的 ctor / init / onEnter 三个 hook
     * <b>不</b>调用任何 Java 代码，它们只打日志并维护一张 layer→info 的映射。
     * 之前注释里写的「hook 拦下 DownloadSceneLayer::init 后转调
     * startCNDownload」是错的。）
     *
     * <p>本方法先无条件启动客户端版本检查（{@link CNVersionCheck}），再放行到
     * 分支动作：final flag 存在时接力热更检查，不存在时启动安装器
     * （{@link #runInstaller()}，内置哨兵保证只执行一次——所以即使 native 侧
     * 随后也触发了，也不会重复跑）。版本检查放在分支<b>之前</b>是因为最需要
     * 强更的恰恰是装不上资源的玩家：只在安装完成后才查，他们永远收不到提示。
     */
    public static void triggerInstaller() {
        if (isRestartProcess()) {
            try { android.util.Log.i(TAG, "restart trampoline process: skip installer/hot-update"); }
            catch (Throwable ignore) {}
            return;
        }
        // 这个方法同样由外部（Application.onCreate）直接调用，出了事不能把
        // 宿主进程的启动流程带崩，所以整体不抛。
        try {
            Thread t = new Thread("cnv-installer-trigger") {
                @Override public void run() {
                    try {
                        CNLog.initEarly();
                        // 紧跟 initEarly：调试开关的首次读取要落在这条后台线程上，
                        // 而不是碰运气落到 UI 线程（见 CNDebugFlags.preload）。
                        CNDebugFlags.preload();
                        // 紧跟 preload：单线程模式的三层来源里有一层就是调试开关，
                        // 得等它读完才问得出结果。四处并发里连接闸门与并行文件池
                        // 是长期对象，必须在这里显式下发一次，否则玩家上次选的
                        // 单线程要等到第一次切换才生效。
                        CNDownloadMode.applyNow();
                        // 调试悬浮窗：总闸烧在包里（native 的 DEBUG_OVERLAY_ENABLED），
                        // 关着就什么都不做。它是排查工具，绝不能反过来影响启动，
                        // 所以整条路径静默降级（见 CNDebugBridge.mount）。
                        mountDebugOverlay();

                        // WebView 拦截层代理：放在分支**之前**，两条路都覆盖得到。
                        //
                        // 原先它挂在 CNHotUpdateCheck.runInner 里，而那条路只有
                        // 「flag 已存在」这一支走得到——首次安装那一支跑完
                        // runInstaller() 就 return 了，整个会话拦截层都没装上。
                        // 装完是否重启还取决于 NO_RESTART_FLAG，不重启就一路裸奔
                        // 进游戏。
                        //
                        // 放这里是安全的：install() 内部有 CAS 保证只生效一次，
                        // 本身只是起一个守护线程等 WebView，不依赖任何前置状态；
                        // 真正走不走代理由 config.json 的 proxy.web_mode 决定，
                        // 而配置由 CNMirrors.refresh 下发——两条路都会调它。
                        // ⚠ 默认不装（2026-08-18）：WebProxy 拦截层改成调试开关
                        // 显式开启才装载（USE_WEB_PROXY），平时网页直连。
                        if (CNDebugFlags.isOn(CNDebugFlags.USE_WEB_PROXY)) {
                            CNWebProxy.install();
                        }

                        File finalFlag = new File(FINAL_FLAG);
                        // 无论资源装没装完，都先查客户端版本：最需要强更的恰恰是
                        // 装不上资源的玩家（下载器本身有 bug 的那批）——只在安装
                        // 完成后才查的话，他们永远收不到「去下修复包」的提示。
                        // 版本检查每条放行路径都会且只会执行一次接力动作。
                        boolean installed = finalFlag.isFile();
                        if (installed) {
                            CNLog.i(TAG, "triggerInstaller: flag 已存在，无需安装，版本检查后接力热更");
                            // 热更新页仍展示 15 个槽位，因此先按 marker 还原真实安装状态：
                            // 已装好的 13 个基础包必须是 100% / 完成，而不是 0% / 等待中。
                            syncInstalledUiState();
                            // 资源已就位的正常启动：先查客户端版本，再（需要时）
                            // 接力热更检查。旧版由 libcn_hook 在 JNI_OnLoad 末尾经
                            // JNI 叫起 RestClient.checkAndApplyHotUpdate；那条路真机上
                            // 浮层建不出来（详见 CNHotUpdateCheck 的类注释），
                            // 现在改由 Java 侧自己跑，时机与等待条件都可控。
                            // 玩家选过「序章」的话无需 Java 侧动作：标记由
                            // native 在引擎首个「进主页」命令上消费（MagiaLegacy
                            // 的 pushSceneTop 闸门），比前端导航可靠得多。
                        }
                        CNVersionCheck.start(new AfterVersionCheck(installed));
                    } catch (Throwable t) {
                        CNLog.e(TAG, "triggerInstaller 异常: " + t, t);
                    }
                }
            };
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            try { android.util.Log.e(TAG, "triggerInstaller 启动失败", t); }
            catch (Throwable ignore) {}
        }
    }

    /** 等 Activity 的上限。等不到就放弃，别让守护线程一直空转。 */
    private static final long DEBUG_OVERLAY_WAIT_MS = 60_000L;

    /** 调试提示条只挂一次；它与悬浮窗总闸无关，见 CNDebugHud。 */
    private static volatile boolean hudMounted;

    /**
     * 起一条守护线程等 Activity 出现，然后在 UI 线程上挂调试悬浮窗。
     *
     * <p>要等，是因为本方法跑在 {@code Application.onCreate} 拉起的后台线程上，
     * 那时引擎的 Activity 往往还没建出来；不能在这里同步等，那会拖住整条启动链。
     *
     * <p>🔴 <b>总闸不能在这里问</b>。它烧在 libMagiaLegacy.so 里，而那个库是在
     * {@code Cocos2dxActivity} 里链式加载的（引擎库之后）——比本方法所在的
     * {@code Application.onCreate} 线程<b>晚</b>。在这里问必然抛
     * UnsatisfiedLinkError，早先据此 return 的写法让悬浮窗在真机上从不出现，
     * 连权限提示都到不了（2026-08-13）。
     *
     * <p>所以把总闸判断挪进下面的循环：等到 Activity 出现时，库必然已经加载
     * （加载它的正是那个 Activity），那时问才问得到真话。代价是正式发布包上会
     * 多起一条守护线程，但它拿到 Activity、发现总闸是关的就立刻退出，不建任何
     * 窗口、不留任何常驻物——「收回调试权限」仍然是彻底的。
     */
    private static void mountDebugOverlay() {
        try {
            Thread t = new Thread(new MountDebugOverlay(), "cnv-debug-overlay-mount");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            CNLog.w(TAG, "调试悬浮窗挂载线程起不来（忽略）: " + t);
        }
    }

    /** 见 {@link #mountDebugOverlay()}。static 嵌套类，理由同 AfterVersionCheck。 */
    private static final class MountDebugOverlay implements Runnable {
        @Override public void run() {
            // 整条链每一步都留痕。上一次真机排查（2026-08-13）里这条链一行日志
            // 都没打，于是「没打进包」「总闸关」「等不到 Activity」「没权限」四种
            // 可能在日志上完全无法区分，只能靠读代码猜——这种事不该有第二次。
            long started = System.currentTimeMillis();
            long deadline = started + DEBUG_OVERLAY_WAIT_MS;
            // 进门就留一行。没有它，「这条线程压根没起来」与「起来了但一直
            // 等不到」在日志上是同一个样子——都是一片空白。
            CNLog.i(TAG, "调试悬浮窗：开始等 Activity 与总闸（最多 "
                    + (DEBUG_OVERLAY_WAIT_MS / 1000L) + "秒）");
            while (System.currentTimeMillis() < deadline) {
                try {
                    // 要等的是**两件事同时成立**：Activity 出现了，且总闸给出了
                    // 确定答案。
                    //
                    // 上一版只等 Activity，理由是「加载 libMagiaLegacy 的就是这个
                    // Activity，所以等到它库就好了」——错的，差一毫秒：
                    // getCurrentActivity() 在 onCreate 里比 System.loadLibrary
                    // 更早被设上。真机日志里是「等到 Activity（1ms），总闸=关」，
                    // 而 1ms 后库才加载完、总闸其实是开的（2026-08-13 第二次）。
                    //
                    // overlayGate() 的 null 表示「还问不到」，与「明确是关」分开，
                    // 这里才能选择继续等而不是放弃。
                    Activity act = RestClient.getCurrentActivity();
                    // 提示条**先于总闸**挂上，而且完全不等它。
                    //
                    // 它归 CNDebugHud 管，挂在 decorView 上、不要悬浮窗权限、也不看
                    // native 总闸——因为它是「有开关正在生效就得说出来」，与「能不能
                    // 改开关」是两件事。公测结束后总闸关掉，某台设备上若还留着 flag
                    // 文件，这行字照样要出现（2026-08-13 维护者提出）。
                    // 没有开关生效时它自己隐藏，成本为零。
                    if (act != null && !hudMounted) {
                        hudMounted = true;
                        CNDebugHud.mount(act);
                    }
                    Boolean gate = CNDebugBridge.overlayGate();
                    if (act != null && gate != null) {
                        CNLog.i(TAG, "调试悬浮窗：就绪（等了 "
                                + (System.currentTimeMillis() - started) + "ms），总闸="
                                + (gate.booleanValue() ? "开" : "关"));
                        if (!gate.booleanValue()) return;
                        // WindowManager 只能在 UI 线程上碰。
                        act.runOnUiThread(new MountOnUi(act));
                        return;
                    }
                } catch (Throwable t) {
                    CNLog.w(TAG, "调试悬浮窗挂载出错（忽略）: " + t);
                    return;
                }
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            // 等不到总闸也要把提示条挂上——它跟总闸无关。
            try {
                Activity late = RestClient.getCurrentActivity();
                if (late != null && !hudMounted) {
                    hudMounted = true;
                    CNDebugHud.mount(late);
                }
            } catch (Throwable ignore) {}
            // 超时要说清楚缺的是哪一样，否则又回到「四种可能长得一样」那种局面。
            CNLog.i(TAG, "调试悬浮窗：等超时，本次不挂（不影响游戏）。Activity="
                    + (RestClient.getCurrentActivity() != null ? "有" : "无")
                    + " 总闸=" + (CNDebugBridge.overlayGate() == null ? "问不到" : "已知"));
        }
    }

    /** 见 {@link #mountDebugOverlay()}。 */
    private static final class MountOnUi implements Runnable {
        private final Activity act;
        MountOnUi(Activity act) { this.act = act; }
        @Override public void run() {
            boolean ok = CNDebugBridge.mount(act);
            // false 不一定是错：没权限时本体会挂引导页并自己轮询（见
            // CNDebugOverlay）。但这一行必须有，否则「挂上了」和「没挂上」
            // 在日志里分不出来。
            CNLog.i(TAG, "调试悬浮窗挂载返回 " + ok);
        }
    }

    /**
     * 版本检查放行后的接力动作：资源已装齐走热更检查，否则启动安装器。
     * 必须是 <b>static</b> 嵌套类——匿名/非静态内部类会带合成字段 this$0，
     * d8 撞上直接 NPE（CLAUDE.md 铁律 4，CI 有静态检查拦截）。
     */
    private static final class AfterVersionCheck implements Runnable {
        private final boolean installed;
        AfterVersionCheck(boolean installed) { this.installed = installed; }
        @Override public void run() {
            if (installed) {
                CNHotUpdateCheck.start();
                return;
            }
            if (CNDebugFlags.isOn(CNDebugFlags.SKIP_INSTALLER)) {
                CNLog.w(TAG, "调试开关 skipInstaller 生效，不跑首次安装"
                           + "（资源没装齐的话游戏会停在这里）");
                return;
            }
            CNLog.i(TAG, "版本检查放行，flag 不存在，启动安装器");
            runInstaller();
        }
    }

    // ==================================================================
    // 端点发现（SNAA）
    // ==================================================================

    /**
     * 端点发现。**同样由 native 经 JNI 调用**，因此与 {@link #runInstaller()} 一样
     * 不允许抛出：native 侧拿到挂起异常后的行为不受我们控制。失败时返回空串，
     * 这与原实现在两次请求都失败时的返回值一致。
     */
    public static String getEndpoint(int i) {
        try {
            // 本进程里最早被 native 调到的入口之一，在这里开日志能覆盖
            // 「安装器从未被调用」这种情况。
            CNLog.initEarly();
            return getEndpointInner(i);
        } catch (Throwable t) {
            try { CNLog.e(TAG, "getEndpoint 发生未预期错误，返回空串", t); } catch (Throwable ignore) {}
            return "";
        }
    }

    private static String getEndpointInner(int i) {
        int max = Math.max(i, MIN_SNAA_VERSION);
        String payload = "{\"version\":" + max + "}";
        CNLog.i(TAG, "snaa-request native_version=" + i + " sent_version=" + max);
        String viaProxy = null;
        try {
            viaProxy = postJson(snaaUrl(), payload, false);
            CNLog.i(TAG, "snaa-response direct=false body=" + viaProxy);
            if (isSnaaResponseCurrent(viaProxy, max)) {
                return viaProxy;
            }
            CNLog.w(TAG, "SNAA response is stale/incompatible; retrying direct");
            String direct = postJson(snaaUrl(), payload, true);
            CNLog.i(TAG, "snaa-response direct=true body=" + direct);
            return direct;
        } catch (IOException first) {
            CNLog.w(TAG, "SNAA via configured network failed; retrying direct", first);
            try {
                String direct = postJson(snaaUrl(), payload, true);
                CNLog.i(TAG, "snaa-response direct=true body=" + direct);
                return direct;
            } catch (IOException second) {
                second.addSuppressed(first);
                CNLog.e(TAG, "SNAA discovery failed", second);
                return viaProxy == null ? "" : viaProxy;
            }
        }
    }

    private static boolean isSnaaResponseCurrent(String body, int minVersion) {
        if (body == null || !body.matches("(?s).*\"endpoint\"\\s*:\\s*\"https://[^\"]+\".*")) {
            return false;
        }
        return extractJsonInt(body, "status") == 200
                && extractJsonInt(body, "version") >= minVersion
                && extractJsonInt(body, "max_threads") > 0;
    }

    private static int extractJsonInt(String body, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(body);
        if (!m.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 玩家在浮层上点了某个失败文件的「重试」。
     *
     * <p>把该文件的状态复位并唤醒主循环。安装器在有文件失败时不会返回，而是停在
     * 这里等待——既给了玩家重试的机会，也顺带保证 hook 不会拿回控制权去显示
     * 引擎自带的下载场景。
     */
    public static void requestRetry(int index) {
        try {
            if (index >= 0 && index < ARCHIVE_COUNT) {
                if (CNCNDownloadUI.fileStatus != null)   CNCNDownloadUI.fileStatus[index]   = 0;
                if (CNCNDownloadUI.fileProgress != null) CNCNDownloadUI.fileProgress[index] = 0;
                CNCNDownloadUI.setFileDownloaded(index, 0.0f);
                CNCNDownloadUI.setDownloadSpeed(index, 0.0f);
            }
            CNLog.i(TAG, "收到重试请求 index=" + index);
        } catch (Throwable ignore) {}
        synchronized (RETRY_LOCK) {
            retryRequested = true;
            RETRY_LOCK.notifyAll();
        }
    }

    // ==================================================================
    // 安装主流程
    // ==================================================================

    /**
     * 安装器入口。两条路进来：
     * <ul>
     *   <li>native hook 经 JNI：{@code DownloadAssetJsonState::checkParseJson}
     *       或 {@code MainScene::onError} → {@code triggerCNDownload()} →
     *       起一条 detached 线程调 {@code RestClient.startCNDownload} → 本方法；</li>
     *   <li>Java 侧 {@link #triggerInstaller()}（由 Application.onCreate 调用）。</li>
     * </ul>
     * 详见 {@link #triggerInstaller()} 的说明。
     *
     * <p><b>本方法绝不允许抛出任何东西。</b>hook 在 {@code CallStaticVoidMethod}
     * 之后会做 {@code ExceptionCheck} / {@code ExceptionClear}，一旦发现挂起的
     * Java 异常就清掉并放行引擎原本的下载场景——也就是玩家会看到**原生安装界面**，
     * 而那是无论如何都要避免出现的。所以整个方法体套在 catch(Throwable) 里：
     * 宁可停在我们自己的浮层上显示错误，也不能把控制权交回引擎。
     *
     * <p>本方法可被多次调用（native hook + Java 侧双重触发），内置哨兵保证
     * 只执行一次。
     */
    public static void runInstaller() {
        // ⚠ 从这里到方法结束，**一行都不能在 try 之外**。
        // 之前把 initEarly() 和下面的哨兵判断放在了 try 前面，结果日志初始化
        // （mkdirs / 读写 .seq / 开文件 / 装崩溃处理器）任何一步抛出都会直接
        // 漏进 JNI，hook 清掉异常后就放行了引擎自带的下载场景。
        try {
            // 日志必须尽早开：浮层没建起来、或安装器压根没被调用，都是最需要
            // 现场的时候，而那时 CreateUIRunnable 里的 init 根本不会执行。
            CNLog.initEarly();
            // 记下是谁把安装器叫起来的：出问题时这一行能直接回答
            // 「native hook 到底触发没有」，不必再靠猜。
            CNLog.i(TAG, "runInstaller 被调用，线程=" + Thread.currentThread().getName());
            if (!installerStarted.compareAndSet(false, true)) {
                CNLog.w(TAG, "安装器已在运行中，跳过重复调用");
                return;
            }
            runInstallerInner();
        } catch (Throwable t) {
            // 走到这里说明有意料之外的错误。绝不外抛：让浮层留在屏幕上显示错误，
            // 引擎的下载场景就不会被放行。
            try {
                CNLog.e(TAG, "安装器发生未预期错误，已拦截以避免回退到原生下载界面", t);
                failInstaller("安装器异常：" + t, t);
                // 意外错误路径是终态（没有重试循环），把看门狗停掉，
                // 免得非守护 executor 线程泄漏到进程结束。
                stopSpeedWatchdog();
            } catch (Throwable ignore) {}
        }
    }

    private static void runInstallerInner() {
        CNLog.i(TAG, "installer=v2 max_downloads=" + MAX_DOWNLOADS);
        try {
            // Activity 可能还没就绪（hook 在引擎切场景时就触发了）。原先只取一次，
            // 取不到就完全不显示浮层——屏幕上便直接露出引擎自带的下载场景。
            // 这里改为最多等 5 秒，与热更新路径的做法一致。
            // 强退后重启时 Activity 初始化可能更慢，因此比之前的 3 秒再放宽一些。
            Activity currentActivity = null;
            for (int i = 0; i < 50; i++) {
                currentActivity = RestClient.getCurrentActivity();
                if (currentActivity != null) break;
                try { Thread.sleep(100L); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (currentActivity != null) {
                CNCNDownloadUI.show(currentActivity);
                // show() 可能因为 UI 线程调度延迟而未能立即建成浮层
                // （isShowing 被置为 false）。在看门狗下一轮补刀之前，
                // 主动做一次 ensureVisible 提高首屏成功率。
                try { CNCNDownloadUI.ensureVisible(currentActivity); } catch (Throwable ignore) {}
            } else {
                CNLog.e(TAG, "取不到 Activity，浮层无法显示（引擎场景可能外露）");
            }
        } catch (Throwable th) {
            CNLog.e(TAG, "Unable to show installer UI", th);
        }

        File finalFlag = new File(FINAL_FLAG);
        if (finalFlag.isFile()) {
            CNLog.i(TAG, "Final flag already exists; installer skipped");
            CNCNDownloadUI.hide();
            return;
        }

        File stateDir = new File(STATE_ROOT);
        if (!stateDir.isDirectory() && !stateDir.mkdirs() && !stateDir.isDirectory()) {
            failInstaller("Cannot create installer state directory", null);
            return;
        }

        // 内置 fallback 从进程启动起就可用。远程 config 只是优化线路顺序/参数，
        // 不能成为首次安装的同步前置条件；服务器故障时直接用内置线路开跑，
        // 后台拿到新表后 pick() 会自然切到新配置。
        CNCNDownloadUI.updateSimple("准备中", "正在准备下载线路…", 0);
        CNMirrors.ensureLoadedAsync();
        // 云端 settings.force_aria2 要 config.json 到位才生效：给加载最多 3 秒。
        // 有界 + fail-open——服务器挂了就按「未强制」用内置引擎开跑，不阻塞安装。
        for (int i = 0; i < 30 && !CNMirrors.isLoaded(); i++) {
            try { Thread.sleep(100L); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int lineCount = CNMirrors.healthy().size();
        CNLog.i(TAG, "mirrors ready count=" + lineCount + " loaded=" + CNMirrors.isLoaded());
        // 备用引擎状态一目了然：可用性 / 云端强制 / 本地开关 / 最终是否启用。
        // 之前只在失败/成功时打一行，看不出「到底开没开」。
        boolean a2Avail = CNAria2.isAvailable();
        boolean a2Cloud = CNMirrors.forceAria2();
        boolean a2Local = CNDebugFlags.isOn(CNDebugFlags.USE_ARIA2);
        CNLog.i(TAG, "备用引擎 aria2: 可用=" + a2Avail
                + " 云端强制=" + a2Cloud + " 本地开关=" + a2Local
                + " → " + ((a2Cloud || a2Local)
                        ? (a2Avail ? "启用（下载优先走 aria2，失败回退主引擎）"
                                   : "已要求但 aria2c 加载失败，只能走主引擎")
                        : "未启用（走主引擎）"));
        CNCNDownloadUI.updateSimple("开始下载",
                "可用线路 " + lineCount + " 条，单文件分片 " + CNMirrors.chunks() + " 线程", 0);

        // ── 尽早启动看门狗：从网络操作阶段开始就保护浮层 ──
        // 首次打开后强退再进来的场景里，引擎可能在切场景时换掉 decorView 内容，
        // 浮层脱离视图树后就露出引擎原生下载界面。把看门狗提前到网络操作之前，
        // 确保整个安装周期都有浮层守护。
        startSpeedWatchdog();   // 看门狗句柄存静态字段，意外错误路径也能停掉

        resetUiForRun();

        // ── 开跑前先把所有文件的大小探一遍 ──
        // 不这样做的话，fileSize[] 是随着各文件陆续开工才逐个填上的，总进度的
        // 分母一直在变大，进度条就会来回跳。先探完再下，分母从一开始就是定值。
        probeAllSizes();

        boolean allOk = false;
        // 主循环：有文件失败就停在这里等玩家点「重试」，而不是直接返回。
        // 返回意味着把控制权交回 native hook，引擎随即显示它自带的下载场景。
        while (true) {
            // 每轮重建：上一轮的闸门已经放行完了，重试这一轮要重新等一次。
            prereqGate = new CountDownLatch(PREREQ_SLOTS.length);
            ExecutorService pool = Executors.newFixedThreadPool(MAX_DOWNLOADS);
            List<Future<Boolean>> futures = new ArrayList<Future<Boolean>>(ARCHIVE_COUNT);
            for (int i = 0; i < ARCHIVE_COUNT; i++) {
                futures.add(pool.submit(new ArchiveTask(i)));
            }
            pool.shutdown();

            allOk = true;
            for (int i = 0; i < futures.size(); i++) {
                try {
                    if (!futures.get(i).get().booleanValue()) {
                        allOk = false;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    CNLog.e(TAG, "Installer interrupted while waiting for " + FILE_NAMES[i], e);
                    allOk = false;
                } catch (ExecutionException e) {
                    CNLog.e(TAG, "Installer worker crashed for " + FILE_NAMES[i], e);
                    allOk = false;
                }
            }
            pool.shutdownNow();
            zeroAllSpeeds();

            if (allOk && allMarkersValid()) break;

            int failed = 0;
            if (CNCNDownloadUI.fileStatus != null) {
                for (int i = 0; i < ARCHIVE_COUNT; i++) {
                    if (CNCNDownloadUI.fileStatus[i] == 3) failed++;
                }
            }
            CNLog.w(TAG, "本轮有 " + failed + " 个文件失败，等待玩家重试");
            failInstaller("有 " + failed + " 个文件下载失败，点击文件右侧的「重试」继续", null);

            // 等重试信号。绝不返回——一返回引擎就会显示原生下载界面。
            synchronized (RETRY_LOCK) {
                while (!retryRequested) {
                    try {
                        RETRY_LOCK.wait();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        CNLog.w(TAG, "等待重试时被中断，继续等待以避免退回原生界面");
                    }
                }
                retryRequested = false;
            }
            CNCNDownloadUI.updateSimple("重试中", "正在重新下载失败的文件…", 0);
        }
        stopSpeedWatchdog();   // 正常收尾：所有文件已通过校验

        try {
            writeAtomic(finalFlag, "schema=2\narchives=15\n");
            CNCNDownloadUI.updateSimple("安装完成", "所有资源已验证并提交完成标记", 100);
            CNLog.i(TAG, "All archives installed; final flag committed atomically");

            // 完成标记刚落盘的这一瞬间，就是唯一一次自动询问「要不要播序章」的
            // 时机——玩家此刻正好处在「装完了、还没进过游戏」的状态。之后不再
            // 自动问，改主意就点浮层左上角的教程胶囊。
            //
            // 浮层要留到问完再收：询问框挂在浮层上，先 hide 就没地方显示了。
            awaitTutorialChoice();
            CNDownloadUiAssist.awaitReleaseIfRequested();
            CNCNDownloadUI.hide();

            if (CNDebugFlags.isOn(CNDebugFlags.SKIP_RESTART)) {
                CNLog.i(TAG, "调试开关 skipRestart 生效，装完不自动重启");
                return;
            }
            if (new File(NO_RESTART_FLAG).isFile()) {
                CNLog.i(TAG, "Test no-restart marker present; restart suppressed");
                return;
            }
            // 装完必须重启一次引擎才进得去。原先是闷头 sleep 2 秒然后重启，
            // 屏幕上什么都没有——玩家不知道发生了什么，也不知道要等。
            // 选了序章的话，序章在重启后的那个进程里播（native 侧在首个
            // pushSceneTop 上消费标记），所以两条路径的重启时机是一样的。
            noticeAndRestart("安装完成，3 秒后自动重启游戏");
        } catch (IOException e) {
            failInstaller("Final flag commit failed", e);
        }
    }

    /**
     * 弹出教程询问并<b>等玩家选完</b>。本方法跑在安装线程上，而询问框在 UI 线程，
     * 所以拿个闩卡住；超时 60 秒兜底，免得询问框因为任何原因没能建出来时，
     * 安装线程永远停在这里、连重启都不做（那才是真的「永远进不去」）。
     */
    private static void awaitTutorialChoice() {
        try {
            Activity act = RestClient.getCurrentActivity();
            if (act == null) {
                CNLog.w(TAG, "取不到 Activity，跳过教程询问");
                return;
            }
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            CNCNDownloadUI.askTutorialOnce(act, new Runnable() {
                @Override public void run() { latch.countDown(); }
            });
            if (!latch.await(60, TimeUnit.SECONDS)) {
                CNLog.w(TAG, "教程询问超时未选择，按「否」继续");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            CNLog.e(TAG, "教程询问出错，继续收尾", t);
        }
    }

    /**
     * Toast 告知即将重启，倒数 3 秒后重启。<b>会阻塞 3 秒</b>，别在 UI 线程上调。
     *
     * <p>包内可见：教程胶囊那条路（{@link CNCNDownloadUI} 的教程询问框）改完
     * 选择后也要走同一套「有提示的重启」，不另写一份。
     *
     * @param toastText 提示文案。两条路的上下文不同（装完 / 改了教程设置），
     *                  各说各的，但节奏一致。
     */
    static void noticeAndRestart(final String toastText) {
        // 重启本身交给 CNRestart。原先这里调的是 RestClient.restartApp()，
        // 那个实现真机上是坏的（会先重跑旧热更流程把浮层又拉出来，然后把新起的
        // Activity 连同自己一起杀掉），详见 CNRestart 的类注释。
        boolean ok = CNRestart.restartWithNotice(toastText, 3000L);
        if (!ok) CNLog.e(TAG, "自动重启未完成；当前进程保持存活，玩家仍可继续/手动重启");
    }

    /**
     * 开跑前把 15 个文件的大小探一遍，填进进度 UI。
     *
     * <p>为什么必须先探：总进度的分母是各文件大小之和，而这些值原本是随着文件
     * 陆续开工才逐个填上的——分母一路变大，已下总量除以它就会忽高忽低，进度条
     * 来回跳。先探完，分母从一开始就是定值。
     *
     * <p>已经装好的文件不发请求：它们的大小直接从完成标记里的 {@code bytes=}
     * 读出来，既省一次网络往返，也让它们照样计入分母。
     *
     * <p>整个过程是尽力而为：任何一个探测失败都只是让该文件暂时没有大小，
     * 不影响后续下载。
     */
    private static void probeAllSizes() {
        CNCNDownloadUI.updateSimple("准备中", "正在获取文件大小…", 0);
        ExecutorService pool = Executors.newFixedThreadPool(MAX_DOWNLOADS);
        List<Future<Boolean>> fs = new ArrayList<Future<Boolean>>(ARCHIVE_COUNT);
        for (int i = 0; i < ARCHIVE_COUNT; i++) {
            fs.add(pool.submit(new SizeProbeTask(i)));
        }
        pool.shutdown();
        for (int i = 0; i < fs.size(); i++) {
            try { fs.get(i).get(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Throwable ignore) {}
        }
        pool.shutdownNow();

        long known = 0L;
        int  n = 0;
        if (CNCNDownloadUI.fileSize != null) {
            for (int i = 0; i < ARCHIVE_COUNT; i++) {
                if (CNCNDownloadUI.fileSize[i] > 0f) { known += (long) CNCNDownloadUI.fileSize[i]; n++; }
            }
        }
        CNLog.i(TAG, "尺寸探测完成 " + n + "/" + ARCHIVE_COUNT + " 个，合计约 " + known + " MB");
        CNCNDownloadUI.updateSimple("开始下载",
                "已探明 " + n + "/" + ARCHIVE_COUNT + " 个文件，合计约 " + known + " MB");
        CNCNDownloadUI.throttledUpdate();
    }

    /** 探一个文件的大小：已装好的读标记，否则发一次探测请求。 */
    private static final class SizeProbeTask implements Callable<Boolean> {
        private final int index;
        SizeProbeTask(int index) { this.index = index; }
        @Override public Boolean call() {
            String name = FILE_NAMES[index];
            try {
                // 已完成的：大小直接从标记里取，不发网络请求
                long fromMarker = readMarkerBytes(markerFor(name));
                if (fromMarker > 0) {
                    updateSize(index, fromMarker);
                    return Boolean.TRUE;
                }
                // 本地已经下好但还没打标记的，用文件本身的长度
                File archive = new File(FILE_ROOT, name);
                if (archive.isFile() && archive.length() > 0) {
                    updateSize(index, archive.length());
                    return Boolean.TRUE;
                }
                // 网络探测：依次尝试多条健康线路，而不是只盯死第一条。
                // 第一条线路可能正处于冷却、被限速或暂时不可达，直接放弃的话
                // 该文件就会在开跑时没有大小——总进度的分母随之成为变量。
                java.util.List<CNMirrors.Mirror> healthy = CNMirrors.healthy();
                int maxProbe = Math.min(healthy.size(), 3);
                for (int attempt = 1; attempt <= maxProbe; attempt++) {
                    CNMirrors.Mirror m = CNMirrors.pick(attempt);
                    try {
                        CNChunkedDownload.Probe p = CNChunkedDownload.probe(
                                m.urlFor(name), false);
                        if (p.total > 0) {
                            updateSize(index, p.total);
                            return Boolean.TRUE;
                        }
                    } catch (Throwable t) {
                        CNLog.w(TAG, "尺寸探测异常（换线重试）: " + name
                                + " mirror=" + m.name, t);
                    }
                }
                CNLog.w(TAG, "尺寸探测失败（不影响下载）: " + name);
            } catch (Throwable t) {
                CNLog.w(TAG, "尺寸探测异常（不影响下载）: " + name, t);
            }
            return Boolean.FALSE;
        }
    }

    /** 从完成标记里读 {@code bytes=}；读不到返回 -1。 */
    private static long readMarkerBytes(File marker) {
        if (!marker.isFile() || marker.length() > 16384) return -1L;
        try {
            String[] lines = readSmallUtf8(marker).split("\\n");
            for (String line : lines) {
                if (line.startsWith("bytes=")) {
                    return parsePositiveLong(line.substring(6).trim(), -1L);
                }
            }
        } catch (Throwable ignore) {}
        return -1L;
    }

    /** 单个压缩包的安装任务。 */
    private static final class ArchiveTask implements Callable<Boolean> {
        private final int index;
        ArchiveTask(int index) { this.index = index; }
        @Override public Boolean call() {
            CNDownloadRestart.register(index);
            try {
                synchronized (ARCHIVE_LOCKS[index]) {
                    return Boolean.valueOf(installArchive(index));
                }
            } finally {
                CNDownloadRestart.unregister(index);
                // 成败都要减：见 prereqGate 的说明，只在成功时减会把热更包挂死。
                if (isPrereqSlot(index)) prereqGate.countDown();
            }
        }
    }

    private static boolean installArchive(int index) {
        String name         = FILE_NAMES[index];
        String canonicalUrl = RESOURCE_BASE_URL + name;
        File   archive      = new File(FILE_ROOT, name);
        File   marker       = markerFor(name);

        if (FORCE_REDOWNLOAD.get(index) == 0
                && isMarkerValid(marker, name, canonicalUrl)) {
            markDone(index);
            CNLog.i(TAG, "marker-hit file=" + name);
            return true;
        }
        if (FORCE_REDOWNLOAD.get(index) != 0) {
            CNLog.i(TAG, "manual-force-redownload file=" + name
                    + "（旧 marker 保留到新包成功）");
        }

        // 离线包注入兜底：玩家手动导入的官方 zip（分块清单已校验）优先，
        // 跳过网络下载，直接解压 + 写标记。只对基础资源包生效——热更两包
        // （cn_scenario_update.zip / cn_js_update.zip）走版本 json 通道，不纳入。
        if (FORCE_REDOWNLOAD.get(index) == 0
                && !CNOfflineImport.isHotUpdateFile(name)
                && CNOfflineImport.hasOffline(name)) {
            File offline = new File(CNOfflineImport.offlineDir(), name);
            long offlineBytes = offline.length();
            try {
                // 解压走与主引擎同一套事务：空间预检 + 逐条目 size/CRC + 断点续解压。
                // 全仓只此一套解压实现（2026-08-13 收敛）：空间预检、逐条目
                // size/CRC、两道膨胀比防护、断点续解压，都在它里面。
                File offState = CNArchiveInstallTx.stateFile(
                        new File(STATE_ROOT), name + ".offline");
                synchronized (EXTRACT_LOCK) {
                    CNArchiveInstallTx.extract(offline, new File(INSTALL_ROOT),
                            offState, null, null);
                }
                CNArchiveInstallTx.clearState(offState);
                writeMarker(marker, name, canonicalUrl,
                        new DownloadMetadata(offlineBytes, "offline"));
                if (!offline.delete() && offline.exists()) {
                    CNLog.w(TAG, "Offline archive retained: " + offline);
                }
                markDone(index);
                CNLog.i(TAG, "offline-installed file=" + name
                        + " bytes=" + offlineBytes);
                return true;
            } catch (CNDiskSpace.NotEnoughSpace e) {
                // 装不下**不是包的错**。这个包是玩家从网盘下了一两个 G、再手动导入
                // 进来的；因为磁盘满就把它删掉，等于让他从头再下一遍——而且删完接着
                // 走网络下载，只会以同样的方式再失败一次。留着，让他腾完空间点重试。
                reportNoSpace(index, name, e.getMessage());
                return false;
            } catch (ZipException e) {
                // 只有这一种才该删：包本身结构不合法或条目 size/CRC 对不上，
                // 留着也永远装不上。删掉回退网络下载是对的。
                CNLog.e(TAG, "offline-zip-bad file=" + name + "，删除离线包并回退网络: " + e, e);
                deleteQuietly(offline);
            } catch (Throwable t) {
                if (CNDiskSpace.isOutOfSpace(t)) {
                    reportNoSpace(index, name, CNDiskSpace.shortfall(
                            name + " 解压", 0L, CNDiskSpace.usableBytes(offline)));
                    return false;
                }
                // 其它失败（IO 抖动、被中断等）一律**保留**离线包：它多半还是好的，
                // 下一轮还能用；删了就得让玩家重新从网盘拉一两个 G。
                CNLog.e(TAG, "offline-extract-failed file=" + name
                        + "（保留离线包，下轮重试）: " + t, t);
            }
        }

        // 备用引擎：cloud=config.json 的 settings.force_aria2 强制启用；
        // 本地=debug 开关 CNDebugFlags.useAria2；构建期=CNBuildConfig.MAIN_ENGINE
        // 选 aria2c 时它就成了**默认主引擎**（每次先走 aria2）。三者任一打开都先
        // 用 aria2 拉一把，装好即返回；失败清掉 aria2 的半截产物（目标文件 +
        // .aria2 控制文件），走下面的主引擎整份重下。
        //
        // ⚠ 「默认关」这句话在 2026-08-13 之前就已经不成立了：线上 config.json
        // 里 force_aria2=true，也就是说**每个玩家的每个文件都先走这条路**。而这
        // 条路曾经是个平行宇宙——主引擎那边的验收与策略（分块清单、热更身份、
        // 空间预检、单线程模式、逐轮换线）它一条都不过。排查下载问题时如果只盯
        // 着主引擎的日志，看到的根本不是玩家实际走的那条路。
        //
        // 线路不在这里挑：交给 tryAria2Download 按 attempt 逐轮换（原先固定
        // pick(1)，三次尝试全钉在同一条线路上）。
        boolean aria2Forced = "aria2c".equals(CNBuildConfig.MAIN_ENGINE)
                || CNMirrors.forceAria2()
                || CNDebugFlags.isOn(CNDebugFlags.USE_ARIA2);
        if (aria2Forced && FORCE_REDOWNLOAD.get(index) == 0
                && CNAria2.isAvailable()) {
            int a2 = tryAria2Download(name, archive, index, marker, canonicalUrl);
            if (a2 == A2_INSTALLED) return true;
            if (a2 == A2_OFFLINE) {
                // 玩家选改用离线包：清掉 aria2 半截产物，跳过主引擎重试，交给
                // 玩家手动导入（导入写 marker 后，全局重试那一轮自然转正）。
                deleteQuietly(archive);
                deleteQuietly(new File(archive.getPath() + ".aria2"));
                deleteQuietly(new File(archive.getPath() + ".aria2.url"));
                markFailed(index);
                CNLog.w(TAG, "玩家选择改用离线包，跳过主引擎重试: " + name);
                return false;
            }
            if (a2 == A2_NOSPACE) {
                // 磁盘满：reportNoSpace 已在下层报过并内部 markFailed（与主引擎
                // ENOSPC 分支同一出口，UI 有专门的空间不足提示）。产物与断点
                // 全部保留——03 这种 1.4GB 的包删了，玩家腾完空间还得整份重下，
                // ENOSPC 时最不该删的就是半截产物。
                return false;
            }
            // a2 == A2_MAIN → 回退主引擎重试
        }
        // 不能删「已完整下载」的包：进程若在 03 下到 100% 之后、大 zip 还在
        // 解压时被杀，下次启动必须复用这份已校验的 1.4GB 包续解，而不是重新下载。
        // 也不能删 .aria2 断点文件：杀掉一半的 aria2 下载要靠它跨会话续传。半截
        // 产物由 fetchArchive 按 .aria2 是否存在区分「复用 / 清掉重下」。

        // 重试上限做成变量：用尽之后要问玩家，玩家选「再试 / 改用单线程」时
        // 就地续一轮，而不是把整个方法重入一遍（重入会连 marker 检查、离线包
        // 兜底、aria2 强制分支一起重跑，语义与「继续重试」并不相同）。
        int maxAttempts = MAX_ATTEMPTS;
        boolean askedFallback = false;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                markFailed(index);
                return false;
            }

            final int restartToken = CNDownloadRestart.generation(index);
            CNMirrors.Mirror mirror = CNMirrors.pick(attempt);
            // 资源下载一律直连（Proxy.NO_PROXY）：系统代理会劫持 CDN 大文件传输，
            // 损坏分片拼出的 zip 导致「完工校验失败」。曾按 attempt 奇偶交替走代理，
            // 玩家开着 VPN/抓包工具时奇数尝试必被劫持（cn_base_03 连败四次的根因），
            // 且只在 CNHotUpdate 修过、这里漏了。mirrors 与 proxy 是两套机制。
            boolean direct = true;

            setActive(index, true);
            CNCNDownloadUI.setDownloadSpeed(index, 0.0f);
            try {
                DownloadMetadata meta = fetchArchive(
                        mirror, name, archive, index, direct, restartToken);
                CNCNDownloadUI.setDownloadSpeed(index, 0.0f);
                CNCNDownloadUI.updateFileProgress(index, 100);
                CNCNDownloadUI.updateSimple("正在安装资源",
                        name + "：下载已验证，正在解压并提交…", 100);
                // 装之前先过闸：热更包可能覆盖其余包里的文件，装在它们之前
                // 会被原样盖回去，而且没有任何报错。下载已经做完，这里只挡「装」。
                if (!awaitPrereqInstalled(index, name)) {
                    CNCNDownloadUI.updateSimple("正在安装资源",
                            name + "：其余资源尚未装完，本轮先不装它", 100);
                    markFailed(index);
                    return false;
                }
                final int tokenForExtract = restartToken;
                File extractState = CNArchiveInstallTx.stateFile(new File(STATE_ROOT), name);
                try {
                    synchronized (EXTRACT_LOCK) {
                        CNArchiveInstallTx.extract(archive, new File(INSTALL_ROOT), extractState,
                                new CNArchiveInstallTx.Cancel() {
                                    @Override public boolean isCancelled() {
                                        return CNDownloadRestart.cancelled(index, tokenForExtract);
                                    }
                                },
                                new CNArchiveInstallTx.Progress() {
                                    @Override public void onProgress(int doneEntries, int totalEntries,
                                                                     long doneBytes, long totalBytes) {
                                        CNCNDownloadUI.updateFileProgress(index, 100);
                                        CNCNDownloadUI.updateSimple("正在安装资源",
                                                name + "：解压 " + doneEntries + "/" + totalEntries,
                                                100);
                                    }
                                });
                    }
                } catch (CNArchiveInstallTx.CancelledException e) {
                    throw new ResetRequired("manual restart during extraction");
                } catch (ZipException e) {
                    throw e;
                } catch (CNArchiveInstallTx.InstallIOException e) {
                    throw new ExtractionPaused(e.getMessage(), e);
                }
                writeMarker(marker, name, canonicalUrl, meta);
                if (!archive.delete() && archive.exists()) {
                    CNLog.w(TAG, "Installed archive retained because delete failed: " + archive);
                }
                // 两条下载路径的临时产物一并清掉：装完之后它们都是死数据，
                // 留着只会占空间，还可能在下一轮被当成可用断点去做无谓的判定
                deleteQuietly(new File(archive.getPath() + ".part"));
                deleteQuietly(new File(archive.getPath() + ".part.meta"));
                deleteQuietly(CNChunkedDownload.partFileFor(archive));
                deleteQuietly(CNChunkedDownload.metaFileFor(archive));
                CNMirrors.reportSuccess(mirror);
                markDone(index);
                CNLog.i(TAG, "installed file=" + name + " attempt=" + attempt
                        + " mirror=" + mirror.name);
                return true;
            } catch (ResetRequired e) {
                if (CNDownloadRestart.changed(index, restartToken)) {
                    CNLog.i(TAG, "manual-restart-active file=" + name
                            + " attempt=" + attempt + "：清除该文件断点并从头重下");
                    CNDownloadRestart.clearInterrupt();
                    // object-storage-01：清理是否保留离线候选由请求源决定（紫色重下=false、
                    // installOfflineNow=true），不能写死——写死 false 会把玩家
                    // 刚导入的离线包在持锁期间删掉；写死 true 会让紫色重下
                    // 被离线候选劫持（重跑 installArchive 直接装离线包）。
                    cleanupArchiveDownloadState(index,
                            CNDownloadRestart.keepOfflineRequested(index));
                    CNCNDownloadUI.resetFileProgress(index);
                    attempt = 0;
                    continue;
                }
                CNLog.w(TAG, "resume-reset file=" + name + " attempt=" + attempt
                        + " reason=" + e.getMessage());
            } catch (CNDiskSpace.NotEnoughSpace e) {
                // 设备装不下，不是线路的错。**不** reportFailure（线上
                // switch_after_failures=1，一次就够把一条无辜线路冷却 60 秒），
                // 也不再重试——空间不会因为多试四次就长出来。
                reportNoSpace(index, name, e.getMessage());
                return false;
            } catch (ExtractionPaused e) {
                if (CNDiskSpace.isOutOfSpace(e)) {
                    // 解压途中写满了。ZIP 与解压检查点都保留着，腾出空间后
                    // 重试会接着装——但得先让玩家知道要腾空间。
                    reportNoSpace(index, name, CNDiskSpace.shortfall(
                            name + " 解压", 0L, CNDiskSpace.usableBytes(archive)));
                    return false;
                }
                CNLog.e(TAG, "extract-paused file=" + name
                        + "（完整 ZIP 与解压进度均保留，重试将继续解压）", e);
                markFailed(index);
                return false;
            } catch (ZipException e) {
                CNLog.e(TAG, "corrupt-zip file=" + name + " attempt=" + attempt, e);
                // 损坏通常来自本地跨镜像混装/断点残留，不是线路的错——
                // 不再 reportFailure（否则健康线路会被误判进冷却，白白浪费重试窗口）。
                // 只清理本地断点，让下一次尝试整份重下。
                deleteQuietly(archive);
                deleteQuietly(new File(archive.getPath() + ".part"));
                deleteQuietly(new File(archive.getPath() + ".part.meta"));
                deleteQuietly(CNChunkedDownload.partFileFor(archive));
                deleteQuietly(CNChunkedDownload.metaFileFor(archive));
                CNArchiveInstallTx.clearState(
                        CNArchiveInstallTx.stateFile(new File(STATE_ROOT), name));
            } catch (HotIdentityMismatch e) {
                // 传输是好的，只是这条线路手里那份旧了：换线重来，别记冷却
                // （理由见 HotIdentityMismatch 的说明）。断点已由抛出方清干净。
                CNLog.w(TAG, "hot-identity-stale file=" + name + " attempt=" + attempt
                        + " mirror=" + mirror.name + " → 换线重下：" + e.getMessage());
            } catch (IOException e) {
                if (CNDownloadRestart.changed(index, restartToken)) {
                    CNLog.i(TAG, "manual-restart-active file=" + name
                            + " attempt=" + attempt + "：清除该文件断点并从头重下");
                    CNDownloadRestart.clearInterrupt();
                    // object-storage-01：同 ResetRequired 分支——keepOffline 由请求源决定。
                    cleanupArchiveDownloadState(index,
                            CNDownloadRestart.keepOfflineRequested(index));
                    CNCNDownloadUI.resetFileProgress(index);
                    attempt = 0;
                    continue;
                }
                // ENOSPC 也可能从写 .cpart 的中途冒出来，这时它长得就是一个
                // 普通 IOException。先认出来，别当成线路故障处理。
                if (CNDiskSpace.isOutOfSpace(e)) {
                    reportNoSpace(index, name, CNDiskSpace.shortfall(
                            name, 0L, CNDiskSpace.usableBytes(archive)));
                    return false;
                }
                CNLog.e(TAG, "archive-failed file=" + name + " attempt=" + attempt
                        + " mirror=" + mirror.name, e);
                CNMirrors.reportFailure(mirror, String.valueOf(e.getMessage()));
                if (archive.isFile()) {
                    deleteQuietly(archive);
                }
            } catch (RuntimeException e) {
                CNLog.e(TAG, "archive-runtime-failure file=" + name + " attempt=" + attempt, e);
                CNMirrors.reportFailure(mirror, "runtime:" + e);
            } finally {
                setActive(index, false);
                CNCNDownloadUI.setDownloadSpeed(index, 0.0f);
                CNCNDownloadUI.throttledUpdate();
            }

            if (attempt < maxAttempts) {
                // 退避按**本轮内**的序号算，续轮后不会一上来就等 16 秒
                long delay = 2000L << (Math.min(attempt, MAX_ATTEMPTS) - 1);
                CNLog.i(TAG, "retry-wait file=" + name + " delay_ms=" + delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    markFailed(index);
                    return false;
                }
            } else if (!askedFallback) {
                // 重试用尽：问玩家，而不是闷头放弃。整轮只问一次——第二次问时
                // 玩家已经没有新信息可给，只会变成反复弹框。
                askedFallback = true;
                boolean canAria2 = CNAria2.isAvailable();
                int choice = awaitDownloadFallbackChoice(name, canAria2);
                if (choice == CNCNDownloadUI.ARIA2_OFFLINE) {
                    CNCNDownloadUI.showOfflineImportDialog(RestClient.getCurrentActivity());
                    markFailed(index);
                    CNLog.w(TAG, "玩家选择改用离线包，停止网络重试: " + name);
                    return false;
                }
                if (choice == CNCNDownloadUI.ARIA2_RETRY && canAria2) {
                    int a2 = tryAria2Download(name, archive, index,
                                              marker, canonicalUrl);
                    if (a2 == A2_INSTALLED) return true;
                    if (a2 == A2_OFFLINE) {
                        markFailed(index);
                        return false;
                    }
                    if (a2 == A2_NOSPACE) {
                        // 磁盘满（与首调用点同一语义）：reportNoSpace 已在下层
                        // 报过并内部 markFailed。保留产物与断点，不续主引擎轮
                        // 白下一遍——否则 fetchArchive 会按 .aria2 存在把半截
                        // 1.4GB 产物连同断点一起删掉，正是 ENOSPC 时最不该删的。
                        return false;
                    }
                    // aria2 也不行 → 落到下面再给主引擎一轮
                }
                // DL_SINGLE 的模式切换已由弹窗完成（见 CNCNDownloadUI.Aria2Choice），
                // 这里只需要续一轮；ARIA2_CONTINUE=「再试一次」同样续一轮。
                maxAttempts += MAX_ATTEMPTS;
                CNLog.w(TAG, "玩家选择继续重试 file=" + name
                        + " 模式=" + CNDownloadMode.describe()
                        + " 追加 " + MAX_ATTEMPTS + " 次");
            }
        }

        markFailed(index);
        CNLog.e(TAG, "retry-exhausted file=" + name);
        return false;
    }

    /**
     * 主引擎/热更失败时的询问。与 {@link #awaitAria2FallbackChoice} 的区别只在
     * 措辞（弹窗里标题与按钮文案随失败来源变），走的是同一个框。
     *
     * @param canAria2 备用引擎在不在，决定给不给「改用备用引擎」这一项
     */
    static int awaitDownloadFallbackChoice(String name, boolean canAria2) {
        return awaitDownloadFallbackChoice(name, canAria2, true);
    }

    /** @param offerOffline 热更两包传 false——离线导入不覆盖它们。 */
    static int awaitDownloadFallbackChoice(String name, boolean canAria2,
                                           boolean offerOffline) {
        try {
            Activity act = RestClient.getCurrentActivity();
            if (act == null) {
                CNLog.w(TAG, "取不到 Activity，下载失败询问按「继续」: " + name);
                return CNCNDownloadUI.ARIA2_CONTINUE;
            }
            return CNCNDownloadUI.askDownloadFallback(act, name, canAria2, false, offerOffline);
        } catch (Throwable t) {
            CNLog.e(TAG, "下载失败询问出错，按「继续」: " + name, t);
            return CNCNDownloadUI.ARIA2_CONTINUE;
        }
    }

    /**
     * 用 aria2 备用引擎下载并安装单个文件。失败时（下载错/结构非法/异常）不再
     * 闷头回退，而是弹浮层询问框让玩家在「重试备用 / 继续主引擎 / 改用离线包」
     * 里选——玩家是唯一知道「现在该不该继续等网络」的人。
     *
     * <p>单文件同步下载：首选线路直连，aria2 多连接 + 断点续传。装好即解压 +
     * 写标记；任何失败先问玩家。重试有上限（{@link #A2_MAX_ATTEMPTS}），用尽后
     * 询问框不再给「重试备用」这一项。进度接到既有 UI，取消绑线程中断。
     *
     * @return {@link #A2_INSTALLED} 装好了；{@link #A2_MAIN} 走主引擎重试；
     *         {@link #A2_OFFLINE} 玩家选改用离线包（跳过主引擎，走手动导入）；
     *         {@link #A2_NOSPACE} 磁盘满（产物与断点保留，不续主引擎轮）。
     */
    private static int tryAria2Download(String name, File archive, int index,
                                        File marker, String canonicalUrl) {
        // 完整的包直接复用，别让 aria2 的 --allow-overwrite 把它重下一遍。场景是进程
        // 在下到 100% 之后、解压之前被杀（.aria2 控制文件在 = 没下完，不在此列）。
        // 交回主引擎，fetchArchive 按同一判据复用它。force_aria2 开着时这正是
        // 「03完整ZIP可复用」哨兵能真正生效的前提——否则下到 1.4GB 全白费。
        if (archive.isFile()
                && !new File(archive.getPath() + ".aria2").isFile()
                && FORCE_REDOWNLOAD.get(index) == 0) {
            CNLog.i(TAG, "aria2 引擎：文件已完整，交主引擎复用 file=" + name);
            return A2_MAIN;
        }
        // 跨会话断点续传的依据：本次 URL 必须和上次留下 .aria2 控制文件的那次一致，
        // aria2 才会续（--continue=true）。换线了就得清掉旧断点与半截产物，否则 aria2
        // 拿到一份对不上 URL 的控制文件，行为不可预期。首次尝试 prevUrl 为 null 不清，
        // 正好让「上次会话杀到一半、这次又挑回同一镜像」的续传能接上。
        String prevUrl = null;
        for (int attempt = 1; attempt <= A2_MAX_ATTEMPTS; attempt++) {
            // 换线走**与主引擎同一套**机制，不是简化版：逐轮 pick(attempt) 轮换，
            // 成败都回报给 CNMirrors 的健康表（失败记冷却、成功清计数）。原先
            // 固定 pick(1) 且从不回报——第一条线路对这个文件不行时三次全废在同
            // 一条上，而且它有多不行，健康表一无所知，主引擎回退后照样先挑它。
            CNMirrors.Mirror mirror = CNMirrors.pick(attempt);
            // 本轮重发代际（F-A-06）：下载/解压期间玩家点「重下」或「改用
            // 离线包」会让它过期（CNDownloadRestart.request → generation++
            // 并 interrupt 本线程）。声明在 try 外是因为 catch 里也要凭它
            // 把「玩家主动取消」与「线路失败」分开——前者不记冷却、不弹失败框。
            final int a2RestartToken = CNDownloadRestart.generation(index);
            try {
                final int idx = index;
                CNAria2.Progress progress = new CNAria2.Progress() {
                    @Override public void onProgress(long done, long total) {
                        LAST_PROGRESS_NS.set(idx, System.nanoTime());
                        if (total > 0) {
                            updateSize(idx, total);
                            updateProgress(idx, done, total);
                        }
                    }
                };
                // 取消：下载轮询 Cancel.isCancelled()。这里绑到线程中断——
                // 下载被外部 interrupt 即触发 aria2 取消。
                CNAria2.Cancel cancel = new CNAria2.Cancel() {
                    @Override public boolean isCancelled() {
                        return Thread.currentThread().isInterrupted();
                    }
                };

                // 同样按本轮身份取热更两包。这**不是**校验（aria2 模式下的校验按
                // 维护者口径一律旁路），是**取哪一个**的问题：不带 cnv_hot 就可能
                // 从 CDN 拿到一份结构完好的旧副本，装上去玩家看到的是上一版台词，
                // 直到随后的热更轮按版本号发现并重下——白下 185 MiB。取不到身份
                // 就用裸 URL，不因此让下载失败。
                CNHotUpdateValidate.VerMeta a2Meta = usesChunkManifest(name) ? null
                        : CNHotUpdateCheck.metaForSlot(index);
                String url = CNHotUpdate.withIdentity(mirror.urlFor(name), a2Meta);
                File aria2Ctrl = new File(archive.getPath() + ".aria2");
                File urlTag = new File(archive.getPath() + ".aria2.url");
                if (prevUrl != null && !prevUrl.equals(url)) {
                    // 换线了：上一轮留下的半截产物与断点文件对不上这条 URL，作废
                    deleteQuietly(archive);
                    deleteQuietly(aria2Ctrl);
                } else if (prevUrl == null && aria2Ctrl.isFile()) {
                    // 本会话首次 attempt 碰上已有断点 = 跨会话续传。aria2 的
                    // 控制文件只记位图不记内容哈希（官方对 --continue 的告诫正是
                    // 「不校验服务端内容是否已变」），「上次是用哪条 URL 起的」
                    // 只能问 sidecar——URL 里带着 cnv_hot=version-size-md5 的
                    // 包身份，比对 URL 就是比对身份。身份不符还续传，拼出来的
                    // 是前半旧、后半新的混合 zip（Z-01）。
                    String tagUrl = readUrlTag(urlTag);
                    if (tagUrl != null && !tagUrl.equals(url)) {
                        CNLog.w(TAG, "跨会话续传身份不符，清断点重下 file=" + name
                                + "（上次 " + tagUrl + "，本次 " + url + "）");
                        deleteQuietly(archive);
                        deleteQuietly(aria2Ctrl);
                    }
                    // tagUrl == null：断点来自本机制引入前的版本，身份无从比对，
                    // 放行续上（这轮起就有 sidecar 了）。残余缝隙（旧版断点恰逢
                    // 同 URL 内容已变）由解压期的逐条目 CRC 闸兜底——它现在
                    // 如实报错，不再静默坏装。
                }
                // 起步即把本次完整 URL 写进 sidecar：下一轮换线、下一次会话都凭
                // 它判定断点身份。必须写在 download 之前——下载中被杀，sidecar
                // 也得已经在了。
                writeUrlTag(urlTag, url);
                prevUrl = url;
                // 连接数过同一个判据。CNDownloadMode.cap() 原先只管主引擎那四处，
                // aria2 这里硬编码 16——于是玩家在失败弹窗里选了「改用单线程
                // 下载」之后，下一个文件照样先走 aria2、照样 16 条连接，正好是
                // 他要求的反面。而 force_aria2 开着时这是默认路径。
                int conns = Math.max(1, CNDownloadMode.cap(16));
                CNLog.i(TAG, "aria2 备用引擎下载 file=" + name + " url=" + url
                        + " 线路=" + mirror.name + " 连接数=" + conns
                        + " attempt=" + attempt + "/" + A2_MAX_ATTEMPTS);
                int rv = CNAria2.download(url, FILE_ROOT, name,
                        CNUserAgent.get(), null, null, conns, null, progress, cancel);
                if (rv == CNAria2.ERR_BUSY) {
                    // busy 门：同进程同时只放一个 aria2 下载（native g_inUse 语义）。
                    // 抢不到不是下载失败——是并发让位。静默交回主引擎，不空烧 attempt、
                    // 不弹失败框、不记线路失败（busy 不是线路的错）。主引擎 4 文件
                    // 并行本来就是批量主力，aria2 只服务抢到 slot 的那一个。
                    CNLog.i(TAG, "aria2 busy，让位主引擎 file=" + name);
                    return A2_MAIN;
                }
                if (rv == CNAria2.CANCELLED || Thread.currentThread().isInterrupted()) {
                    // 玩家取消 / 线程中断（F-A-06），不是线路失败：**不**
                    // reportFailure（线上 switch_after_failures=1，记一次就把
                    // 无辜线路冷却 60 秒）、不弹失败询问框，按主引擎同一套
                    // 取消语义处理（对照 fetchArchive 重试循环的
                    // manual-restart-active 分支）。中断也可能落在 OK/其它
                    // 返回码上（取消请求恰好压在下载收尾），同样按取消论。
                    if (CNDownloadRestart.changed(index, a2RestartToken)) {
                        // 手动重下 / 改用离线包：清掉该文件全部断点与半截产物
                        // （含 .aria2 控制文件与 sidecar），重置进度，本轮内
                        // 从头重下。中断标记是 request() 故意打的，循环前必须
                        // 清掉，否则下一轮 download 的取消回调立刻再触发。
                        CNLog.i(TAG, "manual-restart-active(aria2) file=" + name
                                + " attempt=" + attempt + "：清除该文件断点并从头重下");
                        CNDownloadRestart.clearInterrupt();
                        // object-storage-01/02：keepOffline 由请求源携带的原因码决定——
                        // installOfflineNow 登记 true（它就是奔着离线包来的，
                        // 删了等锁后必报「离线包已消失」、导入作废白下一遍）；
                        // 紫色「重下」登记 false（重跑 installArchive 时
                        // FORCE_REDOWNLOAD 未设，离线候选还在就会被直接装而
                        // 非真重下——「保留对重下无害」的论断不成立，离线分支
                        // 在 installArchive 开头，重跑必经过）。
                        cleanupArchiveDownloadState(index,
                                CNDownloadRestart.keepOfflineRequested(index));
                        CNCNDownloadUI.resetFileProgress(index);
                        attempt = 0;
                        continue;
                    }
                    // 无重发意图的中断（整体停止/池回收）：保留产物与断点供
                    // 下次会话续传，安静交回主引擎——它循环顶部的
                    // isInterrupted 检查会 markFailed 收尾，全程不记冷却。
                    CNLog.i(TAG, "aria2 下载被中断，安静退出（不记线路失败）file=" + name);
                    return A2_MAIN;
                }
                if (rv == CNAria2.OK && archive.isFile() && archive.length() > 0
                        && isAria2ArchiveUsable(archive, name)) {
                    // ⚠ aria2 模式下**不叠加**额外的内容校验（维护者决定，2026-08-13）：
                    // 不套基础包 manifest 的块指纹，也不做热更两包的 version json
                    // size/MD5 比对。判据是 ZIP 自带的完整性——没下全的包结构就不合法，
                    // 逐条目的 size/CRC 也会在解压时把它拦下来，压根打不开。
                    //
                    // 说清楚这条**换来了什么、放弃了什么**：拦得住「没下全 / 传坏了」，
                    // 拦不住「下全了但是旧的」——CDN 上一份结构完好的过期副本能一路
                    // 通过。今天 cn_scenario_update.zip 正是这种（尺寸一样、内容是上
                    // 一版）。这条路上它只能靠随后的热更新轮按版本号发现并补下。
                    //
                    // 解压走全仓唯一那套事务：它给的是空间预检与断点续解压，
                    // 那不是「内容校验」，去掉只会让 03 那类大包白解压半天再翻车。
                    //
                    // 与主引擎同一道前置闸：热更两包装在 13 个前置包之前，覆盖
                    // 修正会被基础包原样盖回且没有任何报错。原先 aria2 路径不过
                    // 这道闸（主路径在 fetchArchive 之后、extract 之前），线上
                    // force_aria2=true 时热更包可能抢跑。包已下好不用重下：交回
                    // 主引擎轮次，它按「完整包复用」接手，先过闸再解压。
                    if (!awaitPrereqInstalled(index, name)) {
                        CNLog.w(TAG, "aria2 已下载但前置包未就绪，交主引擎轮次安装: " + name);
                        return A2_MAIN;
                    }
                    // 解压取消也与主引擎同规格（原先传 null：解压全程不响应
                    // 「重下」请求，几十秒的大包解压期间玩家点了也没反应）。
                    final int a2ExtractToken = CNDownloadRestart.generation(index);
                    File a2State = CNArchiveInstallTx.stateFile(new File(STATE_ROOT), name);
                    synchronized (EXTRACT_LOCK) {
                        CNArchiveInstallTx.extract(archive, new File(INSTALL_ROOT),
                                a2State,
                                new CNArchiveInstallTx.Cancel() {
                                    @Override public boolean isCancelled() {
                                        return CNDownloadRestart.cancelled(index, a2ExtractToken);
                                    }
                                }, null);
                    }
                    CNMirrors.reportSuccess(mirror);
                    writeMarker(marker, name, canonicalUrl,
                            new DownloadMetadata(archive.length(), "aria2"));
                    deleteQuietly(urlTag);   // 装好了，身份凭据随产物一起清
                    if (!archive.delete() && archive.exists()) {
                        CNLog.w(TAG, "Installed archive retained: " + archive);
                    }
                    markDone(index);
                    CNLog.i(TAG, "aria2 备用引擎装好 file=" + name + " bytes=" + archive.length());
                    return A2_INSTALLED;
                }
                if (rv == CNAria2.OK) {
                    // 下到 100% 但结构校验没过：这是一份「完整但坏」的包，不是
                    // 续传素材——aria2 完成时已清控制文件，留着它，跨会话后顶部
                    // 的「完整包复用」判据（无 .aria2 即完整）会把它当好包无校验
                    // 装回（Z-03）。删掉，让下一轮/主引擎重下。
                    CNLog.w(TAG, "aria2 下载完成但结构非法，删除坏包重下 file=" + name);
                    deleteQuietly(archive);
                    deleteQuietly(urlTag);
                }
                CNLog.w(TAG, "aria2 备用引擎失败 code=" + rv + " attempt=" + attempt
                        + "/" + A2_MAX_ATTEMPTS + " 线路=" + mirror.name + " file=" + name);
                CNMirrors.reportFailure(mirror, "aria2 code=" + rv);
                // 保留半截产物 + .aria2 控制文件：同 URL 重试 / 跨会话续传要用它
                // （--continue=true）。换线时顶部的 URL 判定清掉它们；最终回退主引擎
                // 时 fetchArchive 按 .aria2 是否存在决定「复用 / 清掉重下」。
                // 没轮完就自己换下一条线，不打断玩家。原先每失败一次就弹一次框，
                // 三条线路要问三遍——而他能给的信息，前两遍就已经给完了。
                if (attempt < A2_MAX_ATTEMPTS) continue;
                int choice = awaitAria2FallbackChoice(name, false);
                if (choice == CNCNDownloadUI.ARIA2_RETRY) continue;
                if (choice == CNCNDownloadUI.ARIA2_OFFLINE) {
                    CNCNDownloadUI.showOfflineImportDialog(RestClient.getCurrentActivity());
                    return A2_OFFLINE;
                }
                // DL_SINGLE 也走这里，且是对的：模式已由弹窗切好，回退主引擎后
                // 那一轮自然是单线程的。不需要单独一个返回码。
                return A2_MAIN;
            } catch (CNDiskSpace.NotEnoughSpace e) {
                // 装不下不是引擎的问题，换个引擎/换条线路都没用。断点与已解压
                // 内容保留，直接把还差多少告诉玩家（与主引擎那条同一个出口）。
                // 返回 A2_NOSPACE 而不是 A2_OFFLINE：OFFLINE 会被调用方当成
                // 「玩家不要这个包了」清掉产物，与本注释承诺的「保留」正好相反。
                reportNoSpace(index, name, e.getMessage());
                return A2_NOSPACE;      // 别再回退主引擎白下一遍
            } catch (Throwable t) {
                if (CNDiskSpace.isOutOfSpace(t)) {
                    reportNoSpace(index, name, CNDiskSpace.shortfall(
                            name, 0L, CNDiskSpace.usableBytes(archive)));
                    return A2_NOSPACE;  // 同上：保留产物，别走 OFFLINE 的删除路径
                }
                if (t instanceof CNArchiveInstallTx.CancelledException
                        || Thread.currentThread().isInterrupted()) {
                    // 解压期的手动中止（extract 的取消回调接到
                    // CNDownloadRestart 后抛 CancelledException）与下载期
                    // 同口径（F-A-06）：取消不是线路失败，不 reportFailure、
                    // 不弹失败询问框。
                    if (CNDownloadRestart.changed(index, a2RestartToken)) {
                        CNLog.i(TAG, "manual-restart-active(aria2-extract) file=" + name
                                + "：清除该文件断点并从头重下");
                        CNDownloadRestart.clearInterrupt();
                        // object-storage-01/02：同下载期分支——keepOffline 读请求源原因码。
                        cleanupArchiveDownloadState(index,
                                CNDownloadRestart.keepOfflineRequested(index));
                        CNCNDownloadUI.resetFileProgress(index);
                        attempt = 0;
                        continue;
                    }
                    // 无重发意图的中断：保留完整 ZIP / 解压检查点，安静交回
                    // 主引擎，由它循环顶部的中断检查 markFailed 收尾。
                    CNLog.i(TAG, "aria2 安装被中断，安静退出（不记线路失败）file=" + name);
                    return A2_MAIN;
                }
                CNLog.w(TAG, "aria2 备用引擎异常 attempt=" + attempt + "/" + A2_MAX_ATTEMPTS
                        + " file=" + name + " : " + t);
                CNMirrors.reportFailure(mirror, "aria2 异常:" + t);
                // 同上：保留半截产物 + 控制文件供续传，换线/回退时由顶部与 fetchArchive 清理
                if (attempt < A2_MAX_ATTEMPTS) continue;
                int choice = awaitAria2FallbackChoice(name, false);
                if (choice == CNCNDownloadUI.ARIA2_RETRY) continue;
                if (choice == CNCNDownloadUI.ARIA2_OFFLINE) {
                    CNCNDownloadUI.showOfflineImportDialog(RestClient.getCurrentActivity());
                    return A2_OFFLINE;
                }
                return A2_MAIN;
            }
        }
        CNLog.w(TAG, "aria2 备用引擎重试次数用尽 file=" + name + "，回退主引擎");
        return A2_MAIN;
    }

    /** aria2 下到 100% 不代表拼装合法：结构可解析 + 非空才算可用。 */
    private static boolean isAria2ArchiveUsable(File archive, String name) {
        try {
            if (!CNArchiveValidate.isZipStructurallyValid(archive)) {
                CNLog.w(TAG, "aria2 下载的包结构非法: " + name);
                return false;
            }
            // 用内置 libarchive（libarchive JNI）验证条目（替代 ZipFile.entries()，见 CNZipTool 说明）。
            // libarchive 不可用时回退 ZipFile。
            if (CNZipTool.isAvailable()) {
                return CNZipTool.isValid(archive);
            }
            try (ZipFile zf = new ZipFile(archive)) {
                return zf.entries().hasMoreElements();
            }
        } catch (Throwable t) {
            CNLog.w(TAG, "aria2 下载的包不可用: " + name + " : " + t);
            return false;
        }
    }

    /**
     * 弹 aria2 失败询问框并<b>等玩家选完</b>。本方法跑在安装线程上，询问框在
     * UI 线程，阻塞 + 60 秒兜底由 {@link CNCNDownloadUI#askAria2Fallback} 内部
     * 处理；这里只处理「没 Activity / 询问出错」两种没条件问的情况。
     *
     * @param name     失败的资源包名
     * @param canRetry 是否还能给「重试备用引擎」这一项
     * @return 玩家在 aria2 失败后的选择：ARIA2_RETRY / CONTINUE / OFFLINE
     */
    private static int awaitAria2FallbackChoice(String name, boolean canRetry) {
        try {
            Activity act = RestClient.getCurrentActivity();
            if (act == null) {
                CNLog.w(TAG, "取不到 Activity，aria2 失败询问按「继续主引擎」: " + name);
                return CNCNDownloadUI.ARIA2_CONTINUE;
            }
            return CNCNDownloadUI.askAria2Fallback(act, name, canRetry);
        } catch (Throwable t) {
            CNLog.e(TAG, "aria2 失败询问出错，按「继续主引擎」: " + name, t);
            return CNCNDownloadUI.ARIA2_CONTINUE;
        }
    }

    // ==================================================================
    // 下载
    // ==================================================================

    /**
     * 在指定线路上取回压缩包：优先多线程分片，不满足条件时退回单线程续传。
     *
     * <p><b>热更两包不套基础包 manifest</b>。{@code cn_scenario_update.zip} 与
     * {@code cn_js_update.zip} 是动态对象：它们由热更流水线单独重新发布，而
     * {@code manifest.json} 只在基础包整批出包时重算。二者一旦脱节，清单里的块
     * 指纹就指向上一版内容——偏偏 {@code size} 常常不变（同结构 ZIP 重打包尺寸
     * 一致），于是 {@code validateManifest} 的「清单与文件是否同一身份」那道闸
     * 照样放行，随后<b>每一块</b>都校验失败，四条线路轮完只剩红条重试。
     *
     * <p>2026-08-13 真机就是这样：四个镜像给出同一个实得值 {@code bb4e7df4…}，
     * 而清单期望 {@code acb43c47…}。众口一词说明文件没坏，是清单旧了；同日核对
     * {@code cn_js_update.zip} 的整包 MD5 与 {@code version_js.json} 完全一致，
     * 而清单里那一块对不上——服务端发的文件是对的。
     *
     * <p>规则本身早就写在 {@code docs/DOWNLOAD_TRANSACTIONAL_CHUNKS.md}
     * 「动态热更新与静态分块的边界」里，也已在 {@link CNHotUpdate} 与
     * {@link CNOfflineImport} 落地；漏的只有首次安装器这一条路——而这两包恰恰
     * 被排在下载队列最前面，所以新玩家第一眼看到的就是它。
     *
     * <p>去掉清单不等于不校验：热更包的权威身份是 {@code version_*.json} 的
     * size + 整包 MD5，下载完工后按它核对（见 {@link #verifyHotIdentity}）。
     */
    private static DownloadMetadata fetchArchive(CNMirrors.Mirror mirror, String name,
                                                 File archive, int index, boolean direct,
                                                 int restartToken)
            throws IOException {
        if (archive.isFile() && !new File(archive.getPath() + ".aria2").isFile()) {
            // 复用「已完整下载」的包。.aria2 控制文件在 = aria2 没下完（aria2 在下到
            // 100% 时会清掉控制文件），半截 zip 绝不能当完整的复用——会一路拼进解压、
            // 解到一半才翻车。没有 .aria2 才是完整包，直接续解压。
            //
            // 但「完整」不等于「能装」（Z-03）：下到 100% 后校验失败的坏包同样
            // 满足上面的判据；「下完后服务端恰好重发」的陈旧热更包也是。复用前
            // 补两道：结构校验（读中央目录，1.4GB 也秒出）拦坏包；热更槽位补
            // verifyHotIdentity（size+整包 MD5，与下载完工路径同一套）拦陈旧——
            // 185 MiB 算一遍 MD5 只要几秒，换「坏包/旧包不装机」很值。
            if (!CNArchiveValidate.isZipStructurallyValid(archive)) {
                CNLog.w(TAG, "复用判据命中但结构非法，按损坏处理重下: " + name);
                deleteQuietly(archive);
                deleteQuietly(new File(archive.getPath() + ".aria2.url"));
            } else {
                verifyHotIdentity(name, archive, usesChunkManifest(name) ? null
                        : CNHotUpdateCheck.metaForSlot(index));
                long len = archive.length();
                updateSize(index, len);
                return new DownloadMetadata(len, readSidecarEtag(archive));
            }
        }
        // 半截 aria2 产物（控制文件还在）：主引擎重下前清掉它和断点文件。主引擎用
        // 自己的 .cpart 续传体系，aria2 的半截状态对它不可用，留着只会让上面的完整
        // 判断下次再被 .aria2 拦住。
        deleteQuietly(archive);
        deleteQuietly(new File(archive.getPath() + ".aria2"));
        deleteQuietly(new File(archive.getPath() + ".aria2.url"));

        final boolean useManifest = usesChunkManifest(name);
        // 热更两包（scenario / js）在同一个 URL 上被反复重发，所以 CDN 各节点上
        // 完全可能同时存在好几个版本。热更轮一直靠 cnv_hot=<version-size-md5>
        // 把它们隔开，安装器这条路却一次都没加——于是它可能下到一份旧副本，
        // 而校验用的是**当前**的 version json，必然对不上：换线、再下、再对不上，
        // 四轮 740 MiB 全白费，最后红条。这正是「scenario_update 特别容易下载
        // 失败」的那个诱因。
        //
        // 同一份 meta 既决定下哪个、又决定校验哪个——两者必须是同一个版本，
        // 分两次取会在重发的瞬间撞上不一致。取不到就退回裸 URL 并跳过校验，
        // 由随后的热更轮按版本号补齐。
        CNHotUpdateValidate.VerMeta hotMeta = useManifest ? null
                : CNHotUpdateCheck.metaForSlot(indexOfArchive(name));
        String url = CNHotUpdate.withIdentity(mirror.urlFor(name), hotMeta);
        if (hotMeta != null) {
            CNLog.i(TAG, "热更包按本轮身份取: " + name + " version=" + hotMeta.version);
        }
        int wanted = mirror.effectiveChunks();

        if (wanted > 1) {
            CNChunkedDownload.Probe probe = CNChunkedDownload.probe(url, direct);
            if (probe.rangeSupported && probe.total > 0) {
                int chunks = wanted;
                long minChunk = CNMirrors.minChunkBytes();
                if (minChunk > 0) {
                    long fit = probe.total / minChunk;
                    if (fit < chunks) chunks = (int) Math.max(1L, fit);
                }
                if (chunks > 1) {
                    // 探针刚给出真实长度，这是第一个能判断「装得下吗」的时刻。
                    // 放在这里而不是下完之后：03 这种包等写到最后一个块才 ENOSPC，
                    // 等于白下一个多小时（见 CNDiskSpace 的说明）。
                    //
                    // 要的是**安装峰值**而不是下载量：ZIP 要留到解压成功才删，所以
                    // 峰值是 ZIP + 解压后。两者的比例各包差得很远，03 是唯一真正
                    // 膨胀的那个（1.32→2.79 GiB，2.11x，其余都在 1.02–1.16x），
                    // 峰值 4.11 GiB 是 15 个包里最高的——而进度条上只写着 1.3 GB。
                    // 只按下载量预检，等于把这 2.79 GiB 瞒着玩家（见 CNZipPlan）。
                    long extract = CNZipPlan.extractedBytes(
                            new HttpRanges(url, direct), probe.total);
                    long peak = probe.total - partBytes(archive);
                    if (extract != CNZipPlan.UNKNOWN) {
                        peak += extract;
                        CNLog.i(TAG, "安装峰值预估 file=" + name
                                + " zip=" + CNDiskSpace.human(probe.total)
                                + " 解压后=" + CNDiskSpace.human(extract)
                                + " 峰值=" + CNDiskSpace.human(probe.total + extract));
                    }
                    CNDiskSpace.require(archive, peak, name);
                    CNLog.i(TAG, "chunked-download file=" + name + " mirror=" + mirror.name
                            + " chunks=" + chunks + " bytes=" + probe.total + " direct=" + direct);
                    updateSize(index, probe.total);
                    updateProgress(index, 0L, probe.total);
                    CNChunkedDownload.ChunkHashes hashes =
                            useManifest ? ChunkManifest.forFile(name) : null;
                    if (useManifest && hashes == null) {
                        // 该走分块清单的文件却拿不到清单：绝不能让 hashes==null
                        // 悄悄落到 downloadByteSegments（无认证的传统分段续传）——
                        // 跨镜像混装、缓存污染那类坏字节会一路拼进 zip，完工校验
                        // 才翻车（03 历史 corrupt-zip 的入口）。此处抛错交给上层
                        // 换线重试并重新拉清单，宁可重试也不降级认证。
                        throw new IOException("分块清单获取失败，拒绝无认证下载 file="
                                + name + " mirror=" + mirror.name);
                    }
                    CNChunkedDownload.Result r = CNChunkedDownload.download(
                            url, archive, chunks, direct, probe,
                            new ArchiveSink(index, restartToken),
                            mirror, name, true, hashes);
                    verifyHotIdentity(name, archive, hotMeta);
                    return new DownloadMetadata(r.totalBytes, r.etag);
                }
            }
            CNLog.i(TAG, "range-unsupported-or-small file=" + name + " mirror=" + mirror.name
                    + " → 单线程续传");
        }
        DownloadMetadata single = downloadOnce(url, archive, index, direct, restartToken);
        verifyHotIdentity(name, archive, hotMeta);
        return single;
    }

    /**
     * 把「空间不足」如实报给玩家，并把这一项标红。
     *
     * <p>和别的失败分开写，是因为玩家该做的事完全不同：别的失败点「重试」有意义，
     * 这个不点也罢——先去腾空间。所以话里要有<b>数字</b>，不能只说「失败，请重试」。
     * 断点与已解压的内容一律保留：腾出空间后重试是接着装，不是从头来。
     */
    private static void reportNoSpace(int index, String name, String msg) {
        CNLog.e(TAG, "no-space file=" + name + " " + msg);
        try {
            CNCNDownloadUI.updateSimple("存储空间不足",
                    msg + "。请清理后点「重试」，已下好的部分会保留。", 0);
        } catch (Throwable ignore) {}
        markFailed(index);
    }

    /**
     * 给 {@link CNZipPlan} 用的 Range 取字节器。
     *
     * <p>只读两段、总共两三兆（03 的中央目录 1.7 MB），用来在下 1.3 GB 之前把
     * 「解压后要占多少」问清楚。任何失败都往上抛，由 CNZipPlan 统一按「不知道」
     * 处理——这是提前量，不是关卡。
     */
    private static final class HttpRanges implements CNZipPlan.Ranges {
        private final String url;
        private final boolean direct;
        HttpRanges(String url, boolean direct) { this.url = url; this.direct = direct; }

        @Override public byte[] get(long start, long endInclusive) throws IOException {
            HttpURLConnection c = null;
            InputStream in = null;
            CNDownloadConcurrency.Lease lease = null;
            try {
                lease = CNDownloadConcurrency.acquire("zip-plan");
                URL u = new URL(url);
                c = (HttpURLConnection)
                        (direct ? u.openConnection(Proxy.NO_PROXY) : u.openConnection());
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                c.setUseCaches(false);
                c.setInstanceFollowRedirects(true);
                CNUserAgent.apply(c);
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("Range", "bytes=" + start + "-" + endInclusive);
                if (c.getResponseCode() != 206) {
                    throw new IOException("中央目录 Range 期望 206，实得 HTTP "
                            + c.getResponseCode());
                }
                int want = (int) (endInclusive - start + 1L);
                byte[] out = new byte[want];
                in = new BufferedInputStream(c.getInputStream(), 1 << 16);
                int off = 0;
                while (off < want) {
                    int n = in.read(out, off, want - off);
                    if (n < 0) break;
                    off += n;
                }
                if (off != want) throw new IOException("中央目录短读 " + off + "/" + want);
                return out;
            } finally {
                closeQuietly(in);
                if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
                if (lease != null) lease.close();
            }
        }
    }

    /** 已落盘的断点字节数（两条下载路径的残片文件名不同，都算上）。 */
    private static long partBytes(File archive) {
        long n = 0L;
        try {
            File a = new File(archive.getPath() + ".part");
            if (a.isFile()) n += a.length();
            File b = CNChunkedDownload.partFileFor(archive);
            if (b.isFile()) n += b.length();
        } catch (Throwable ignore) {}
        return n;
    }

    /**
     * 首次安装器是否给这个包套基础包 {@code manifest.json} 的块指纹。
     *
     * <p>13 个静态基础包用；{@code cn_scenario_update.zip} 与
     * {@code cn_js_update.zip} 不用——理由见 {@link #fetchArchive} 的说明。
     * 单列成判据是为了让回归测试钉住的是<b>安装器真正走的那条判断</b>，
     * 而不是另写一份同义的声明。
     */
    public static boolean usesChunkManifest(String name) {
        return !CNOfflineImport.isHotUpdateFile(name);
    }

    /**
     * 热更两包的完工校验：按 {@code version_*.json} 的 size + 整包 MD5 核对。
     *
     * <p>基础包由 manifest 的块指纹逐块认证，热更包没有那一层，所以这一步是它们
     * <b>唯一</b>的内容认证。不做的话就只剩 ZIP 结构预检，缓存里的旧包结构完好、
     * 一样能通过——那正是「下完了却是旧台词」的老毛病。
     *
     * <p>取不到版本 json 时<b>不阻断</b>：这两包紧接着还会走热更那一轮，届时会按
     * 版本号重新比对；此刻卡住安装只会把「服务端某个小 json 冷启动超时」升级成
     * 「装不上游戏」。核对不上则删包抛错，交给上层换线重试。
     */
    private static void verifyHotIdentity(String name, File archive,
                                          CNHotUpdateValidate.VerMeta meta) throws IOException {
        if (usesChunkManifest(name) || archive == null || !archive.isFile()) return;
        if (meta == null) {
            CNLog.w(TAG, "热更包取不到版本身份，本次只做结构预检 file=" + name
                    + "（热更那一轮会再按版本号核对）");
            return;
        }
        String err = CNHotUpdateValidate.verifyZip(archive, meta);
        if (err == null) {
            CNLog.i(TAG, "热更包身份校验通过 file=" + name + " version=" + meta.version);
            return;
        }
        deleteQuietly(archive);
        deleteQuietly(new File(archive.getPath() + ".part"));
        deleteQuietly(new File(archive.getPath() + ".part.meta"));
        deleteQuietly(CNChunkedDownload.partFileFor(archive));
        deleteQuietly(CNChunkedDownload.metaFileFor(archive));
        throw new HotIdentityMismatch("热更包身份校验失败 file=" + name
                + " version=" + meta.version + " 原因=" + err);
    }

    /** 把分片下载的进度接到既有的 UI/看门狗上。 */
    private static final class ArchiveSink implements CNChunkedDownload.Sink {
        private final int index;
        private final int restartToken;
        ArchiveSink(int index, int restartToken) {
            this.index = index;
            this.restartToken = restartToken;
        }

        @Override public void onTotal(long total) {
            updateSize(index, total);
        }
        @Override public void onProgress(long soFar, long total) {
            LAST_PROGRESS_NS.set(index, System.nanoTime());
            updateProgress(index, soFar, total);
        }
        @Override public void onSpeed(float mbps) {
            CNCNDownloadUI.setDownloadSpeed(index, mbps);
        }
        @Override public boolean isCancelled() {
            return CNDownloadRestart.cancelled(index, restartToken);
        }
    }

    /**
     * 单线程断点续传下载（改版前的实现，逐行保留其语义）。
     * 服务端不支持 Range、或文件太小不值得切片时走这里。
     */
    private static DownloadMetadata downloadOnce(String url, File archive,
                                                 int index, boolean direct,
                                                 int restartToken)
            throws IOException {
        if (archive.isFile()) {
            long len = archive.length();
            updateSize(index, len);
            return new DownloadMetadata(len, readSidecarEtag(archive));
        }

        File part  = new File(archive.getPath() + ".part");
        File sidecar = new File(archive.getPath() + ".part.meta");
        File parent = part.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create download directory: " + parent);
        }

        // ── 续传前自检 ──
        // 残片长度是本路径唯一的续传依据，所以先拿 sidecar 记录的总长度校一遍。
        // 残片比整份文件还长 = 上一轮写坏了（掉电导致文件长度已增长但数据没落盘
        // 是最常见的成因）。不清掉的话会发出一个越界的 Range，只能靠服务端回
        // 416 兜底；而一旦服务端把它当普通请求处理，坏数据就会被继续往后接。
        long offset = part.isFile() ? part.length() : 0L;
        if (offset > 0) {
            long declared = readSidecarBytes(archive);
            if (declared > 0 && offset > declared) {
                CNLog.w(TAG, "resume-reset file=" + archive.getName()
                        + " 残片超长 " + offset + " > " + declared + "，丢弃重下");
                truncate(part);
                deleteQuietly(sidecar);
                resetProgress(index);
                offset = 0L;
            }
        }
        CNLog.i(TAG, "download-open file=" + archive.getName() + " offset=" + offset
                + " direct=" + direct);

        CNDownloadConcurrency.Lease networkLease =
                CNDownloadConcurrency.acquire("base-single:" + archive.getName());
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection)
                (direct ? u.openConnection(Proxy.NO_PROXY) : u.openConnection());
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setUseCaches(false);
        CNUserAgent.apply(c);
        c.setRequestProperty("Accept-Encoding", "identity");
        // 不写 Connection: close——保留 keep-alive 复用连接池，
        // 分片/重试接连不断时省掉每段一次的 TCP+TLS 握手

        String localEtag = readSidecarEtag(archive);
        if (offset > 0) {
            c.setRequestProperty("Range", "bytes=" + offset + "-");
            if (localEtag.length() > 0) {
                c.setRequestProperty("If-Range", localEtag);
            }
        }

        InputStream  in  = null;
        OutputStream out = null;
        try {
            int    code = c.getResponseCode();
            String etag = cleanHeader(c.getHeaderField("ETag"));

            long    total;
            long    expectedBody;
            boolean append;

            if (offset > 0 && code == 200) {
                // 服务端忽略了 Range：本地残片作废，重来
                truncate(part);
                deleteQuietly(sidecar);
                resetProgress(index);
                throw new ResetRequired("server returned 200 for Range offset " + offset);
            } else if (offset > 0 && code == 206) {
                ContentRange cr = parseContentRange(c.getHeaderField("Content-Range"));
                if (cr == null || cr.start != offset || cr.end < cr.start || cr.total <= cr.end) {
                    truncate(part);
                    deleteQuietly(sidecar);
                    resetProgress(index);
                    throw new ResetRequired("invalid Content-Range for offset " + offset);
                }
                if (localEtag.length() > 0 && etag.length() > 0 && !localEtag.equals(etag)) {
                    truncate(part);
                    deleteQuietly(sidecar);
                    resetProgress(index);
                    throw new ResetRequired("ETag changed while resuming");
                }
                total        = cr.total;
                expectedBody = cr.end - cr.start + 1;
                append       = true;
            } else if (offset == 0 && code == 200) {
                total        = parsePositiveLong(c.getHeaderField("Content-Length"), -1L);
                expectedBody = total;
                append       = false;
            } else if (offset > 0 && code == 416) {
                long declared = parseUnsatisfiedTotal(c.getHeaderField("Content-Range"));
                if (declared <= 0 || declared != offset) {
                    truncate(part);
                    deleteQuietly(sidecar);
                    resetProgress(index);
                    throw new ResetRequired("HTTP 416 did not match local length");
                }
                // 本地残片长度恰好等于完整长度：直接提交
                promotePart(part, archive);
                deleteQuietly(sidecar);
                return new DownloadMetadata(declared, localEtag);
            } else {
                throw new IOException("Unexpected HTTP status " + code
                        + " offset=" + offset + " url=" + url);
            }

            long headerLen = parsePositiveLong(c.getHeaderField("Content-Length"), -1L);
            if (expectedBody >= 0 && headerLen >= 0 && expectedBody != headerLen) {
                throw new IOException("Content-Length mismatch expected=" + expectedBody
                        + " header=" + headerLen);
            }
            if (total <= 0) {
                throw new IOException("Response does not declare a positive total length");
            }

            writeSidecar(sidecar, etag, total);
            updateSize(index, total);
            updateProgress(index, offset, total);

            in  = new BufferedInputStream(c.getInputStream(), 65536);
            out = new FileOutputStream(part, append);
            FileOutputStream fos = (FileOutputStream) out;

            byte[] buf = new byte[65536];
            long windowStart   = System.nanoTime();
            long written       = 0L;
            long speedBaseline = 0L;
            long slowSinceNs   = 0L;  // 低速看门狗：半死镜像滴速下载时主动换线
            int  n;
            double smoothedMbps = 0.0d;
            while ((n = in.read(buf)) >= 0) {
                if (CNDownloadRestart.cancelled(index, restartToken)) {
                    throw new ResetRequired("manual restart during single-stream download");
                }
                fos.write(buf, 0, n);
                written += n;
                long now = System.nanoTime();
                LAST_PROGRESS_NS.set(index, now);
                updateProgress(index, offset + written, total);
                long dt = now - windowStart;
                if (dt >= TimeUnit.SECONDS.toNanos(3L)) {
                    long windowBytes = written - speedBaseline;
                    double instant = (windowBytes * 1.0E9d / dt) / 1000000.0d;
                    smoothedMbps = smoothedMbps <= 0.0d
                            ? instant : smoothedMbps * 0.70d + instant * 0.30d;
                    CNCNDownloadUI.setDownloadSpeed(index, (float) smoothedMbps);
                    // 持续低速（<100KB/s 超过 15s）视为镜像半死：
                    // read timeout 只在完全无字节时触发，滴速线路会永远卡在这里
                    if (windowBytes * 1000000000L / dt < MIN_OK_BPS) {
                        if (slowSinceNs == 0L) slowSinceNs = now;
                        else if (now - slowSinceNs >= SLOW_FAIL_NS) {
                            throw new IOException("镜像速度过慢（持续低于 "
                                    + (MIN_OK_BPS / 1024) + "KB/s），换线");
                        }
                    } else {
                        slowSinceNs = 0L;
                    }
                    speedBaseline = written;
                    windowStart   = now;
                }
            }
            fos.flush();
            fos.getFD().sync();

            if (expectedBody >= 0 && written != expectedBody) {
                throw new IOException("Short response expected=" + expectedBody
                        + " received=" + written);
            }
            long partLen = part.length();
            if (partLen != total) {
                throw new IOException("Partial file length mismatch expected=" + total
                        + " actual=" + partLen);
            }

            closeQuietly(out); out = null;
            closeQuietly(in);  in  = null;

            promotePart(part, archive);
            deleteQuietly(sidecar);
            return new DownloadMetadata(total, etag);
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            c.disconnect();
            networkLease.close();
        }
    }

    // ==================================================================
    // 解压
    // ==================================================================




    // ==================================================================
    // 速度看门狗
    // ==================================================================

    private static ScheduledExecutorService startSpeedWatchdog() {
        ScheduledExecutorService svc = Executors.newSingleThreadScheduledExecutor();
        svc.scheduleAtFixedRate(new SpeedWatchdog(), 1L, 1L, TimeUnit.SECONDS);
        speedWatchdog = svc;
        return svc;
    }

    /** 停掉看门狗（正常收尾与意外错误路径都要调用）。重复调用安全。 */
    private static void stopSpeedWatchdog() {
        ScheduledExecutorService s = speedWatchdog;
        speedWatchdog = null;
        if (s != null) {
            try { s.shutdownNow(); } catch (Throwable ignore) {}
        }
    }

    /** 一段时间没有新进度就把该文件的速度显示归零。 */
    private static final class SpeedWatchdog implements Runnable {
        @Override public void run() {
            // 顺带确保浮层没有从视图树上掉下去。引擎切场景时可能把 decorView
            // 的内容换掉，浮层一旦脱离，引擎自带的下载场景就露出来了。
            try {
                CNCNDownloadUI.ensureVisible(RestClient.getCurrentActivity());
            } catch (Throwable ignore) {}
            long now = System.nanoTime();
            boolean changed = false;
            for (int i = 0; i < ARCHIVE_COUNT; i++) {
                if (ACTIVE.get(i) == 0) continue;
                long last = LAST_PROGRESS_NS.get(i);
                if (last != 0L && now - last >= STALE_SPEED_NS
                        && LAST_PROGRESS_NS.compareAndSet(i, last, 0L)) {
                    CNCNDownloadUI.setDownloadSpeed(i, 0.0f);
                    CNLog.i(TAG, "stale-speed-zero file=" + FILE_NAMES[i]);
                    changed = true;
                }
            }
            if (changed) {
                CNCNDownloadUI.throttledUpdate();
            }
        }
    }

    // ==================================================================
    // HTTP / 状态文件
    // ==================================================================

    private static String postJson(String url, String body, boolean direct) throws IOException {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection)
                (direct ? u.openConnection(Proxy.NO_PROXY) : u.openConnection());
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setUseCaches(false);
        CNUserAgent.apply(c);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        // 不写 Connection: close——保留 keep-alive 复用连接池，
        // 分片/重试接连不断时省掉每段一次的 TCP+TLS 握手

        OutputStream out = null;
        InputStream  in  = null;
        try {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(payload.length);
            out = c.getOutputStream();
            out.write(payload);
            out.flush();

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("SNAA returned HTTP " + code);
            }
            in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            c.disconnect();
        }
    }

    private static void writeMarker(File marker, String name, String url,
                                    DownloadMetadata meta) throws IOException {
        writeAtomic(marker, "schema=1\nfile=" + name + "\nurl=" + url
                + "\nbytes=" + meta.totalBytes
                + "\netag=" + sanitizeLine(meta.etag) + "\n");
    }

    private static boolean isMarkerValid(File marker, String name, String url) {
        if (!marker.isFile() || marker.length() <= 0 || marker.length() > 16384) {
            return false;
        }
        try {
            String text = readSmallUtf8(marker);
            if (text.contains("schema=1\n")
                    && text.contains("file=" + name + "\n")
                    && text.contains("url=" + url + "\n")) {
                return text.matches("(?s).*\\nbytes=[1-9][0-9]*\\n.*");
            }
            return false;
        } catch (IOException e) {
            CNLog.e(TAG, "Cannot read marker " + marker, e);
            return false;
        }
    }

    private static boolean allMarkersValid() {
        for (String name : FILE_NAMES) {
            if (!isMarkerValid(markerFor(name), name, RESOURCE_BASE_URL + name)) {
                CNLog.e(TAG, "Marker verification failed for " + name);
                return false;
            }
        }
        return true;
    }

    private static File markerFor(String name) {
        return new File(STATE_ROOT, name + ".done");
    }

    private static void writeAtomic(File target, String content) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create parent directory: " + parent);
        }
        File tmp = new File(target.getPath() + ".tmp");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp, false);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.getFD().sync();
            closeQuietly(fos);
            fos = null;
            if (target.exists() && !target.delete()) {
                throw new IOException("Cannot replace " + target);
            }
            if (!tmp.renameTo(target)) {
                throw new IOException("Atomic rename failed: " + tmp + " -> " + target);
            }
        } finally {
            closeQuietly(fos);
        }
    }

    private static void writeSidecar(File sidecar, String etag, long bytes) throws IOException {
        writeAtomic(sidecar, "etag=" + sanitizeLine(etag) + "\nbytes=" + bytes + "\n");
    }

    /**
     * 读取 sidecar 记录的文件总长度；缺失或不可解析时返回 -1。
     * 供续传前自检用，判断残片长度是否已经超出整份文件。
     */
    private static long readSidecarBytes(File archive) {
        File sidecar = new File(archive.getPath() + ".part.meta");
        if (!sidecar.isFile() || sidecar.length() > 16384) {
            return -1L;
        }
        try {
            String[] lines = readSmallUtf8(sidecar).split("\\n");
            for (String line : lines) {
                if (line.startsWith("bytes=")) {
                    return parsePositiveLong(line.substring(6).trim(), -1L);
                }
            }
        } catch (IOException e) {
            CNLog.w(TAG, "Cannot read resume metadata " + sidecar, e);
        }
        return -1L;
    }

    private static String readSidecarEtag(File archive) {
        File sidecar = new File(archive.getPath() + ".part.meta");
        if (!sidecar.isFile() || sidecar.length() > 16384) {
            return "";
        }
        try {
            String[] lines = readSmallUtf8(sidecar).split("\\n");
            for (String line : lines) {
                if (line.startsWith("etag=")) {
                    return line.substring(5).trim();
                }
            }
        } catch (IOException e) {
            CNLog.w(TAG, "Cannot read resume metadata " + sidecar, e);
        }
        return "";
    }

    private static String readSmallUtf8(File file) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > 16384) {
                    throw new IOException("State file is too large: " + file);
                }
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * 读跨会话续传身份 sidecar（{@code <archive>.aria2.url}）：aria2 的控制
     * 文件只记位图不记内容哈希，「这条断点是上会话用哪条 URL 起的」只能问
     * 它。返回 null = 没有/读不出，按「身份不明」处理（调用方决定放行或
     * 重下，不要在这里猜）。
     */
    private static String readUrlTag(File tag) {
        try {
            if (tag == null || !tag.isFile()) return null;
            String s = readSmallUtf8(tag).trim();
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把本次下载的完整 URL 写进续传身份 sidecar。best-effort：写不进只记
     * 日志不阻断下载——代价是下一次会话的续传保护降级为「身份不明」。
     */
    private static void writeUrlTag(File tag, String url) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tag, false);
            out.write(url.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Throwable t) {
            CNLog.w(TAG, "续传身份 sidecar 写入失败，本次续传保护降级: " + tag);
        } finally {
            closeQuietly(out);
        }
    }

    private static void promotePart(File part, File target) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException("Cannot replace destination " + target);
        }
        if (!part.renameTo(target)) {
            throw new IOException("Cannot rename " + part + " to " + target);
        }
    }

    private static void truncate(File file) throws IOException {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file, false);
            fos.flush();
            fos.getFD().sync();
        } finally {
            closeQuietly(fos);
        }
    }

    private static ContentRange parseContentRange(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim().toLowerCase(Locale.US);
        if (!s.startsWith("bytes ")) {
            return null;
        }
        int dash = s.indexOf('-', 6);
        int slash = s.indexOf('/', dash + 1);
        if (dash < 0 || slash < 0) {
            return null;
        }
        try {
            return new ContentRange(
                    Long.parseLong(s.substring(6, dash).trim()),
                    Long.parseLong(s.substring(dash + 1, slash).trim()),
                    Long.parseLong(s.substring(slash + 1).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseUnsatisfiedTotal(String value) {
        if (value == null) {
            return -1L;
        }
        String s = value.trim().toLowerCase(Locale.US);
        if (!s.startsWith("bytes */")) {
            return -1L;
        }
        return parsePositiveLong(s.substring(8), -1L);
    }

    private static long parsePositiveLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            long v = Long.parseLong(value.trim());
            return v >= 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ==================================================================
    // UI 状态同步
    // ==================================================================

    /**
     * 按 15 个完成 marker 把 UI 恢复成真实已安装状态，供正常启动/热更新复用。
     *
     * <p><b>本轮不涉及的槽位标成「未检查」，不是「完成」。</b>这个方法只在热更
     * 场景下被调（首次安装走 runInstaller，不经过这里），而热更那一轮只检查
     * 台词包与前端脚本包两个；另外 13 个基础包压根不在本轮范围里。
     *
     * <p>原先它们一律沿用安装时的 marker 显示成绿色「✓ 完成」——把「上次装好过」
     * 说成了「本轮已确认」。玩家看到满屏绿勾，实际上这一轮什么都没查；热更没生效
     * 的时候，界面反而最像一切正常。这不是显示问题，是谎报。
     */
    static void syncInstalledUiState() {
        resetUiForRun();
        int installed = 0, missing = 0;
        for (int i = 0; i < ARCHIVE_COUNT; i++) {
            boolean hot = isHotSlot(i);
            int status = (CNCNDownloadUI.fileStatus != null) ? CNCNDownloadUI.fileStatus[i] : -1;
            if (hot) {
                // 本轮要查的两个：先回到「等待中」，查完由热更流程按真实结果落状态。
                // 保留 marker 恢复出来的大小，等待中那一支会把它显示出来。
                if (CNCNDownloadUI.fileStatus != null) {
                    CNCNDownloadUI.fileStatus[i] = CNCNDownloadUI.ST_WAIT;
                }
                CNLog.i(TAG, "[Hotupdate UI] slot=" + i + " " + FILE_NAMES[i] + " 本轮待检查");
                continue;
            }
            // 其余 13 个：本轮不检查。装没装过只是**背景信息**，不是本轮结论。
            boolean ok = (status == 2);
            if (ok) installed++; else missing++;
            CNCNDownloadUI.markFileUnchecked(i, ok ? "已装 · 本轮未检查" : "未装 · 本轮未检查");
        }
        CNLog.i(TAG, "[Hotupdate UI] 本轮不检查的基础包：已装 " + installed
                + " 未装 " + missing + "（均标记为未检查，不计入本轮进度）");
    }

    private static void resetUiForRun() {
        CNCNDownloadUI.resetOverallProgress();
        for (int i = 0; i < ARCHIVE_COUNT; i++) {
            if (!isMarkerValid(markerFor(FILE_NAMES[i]), FILE_NAMES[i],
                    RESOURCE_BASE_URL + FILE_NAMES[i])) {
                CNCNDownloadUI.resetFileProgress(i);
            } else {
                markDone(i);
            }
        }
        CNCNDownloadUI.throttledUpdate();
    }

    private static void updateSize(int index, long bytes) {
        CNCNDownloadUI.setFileSize(index, (float) (bytes / 1000000.0d));
    }

    private static void updateProgress(int index, long soFar, long total) {
        int pct;
        if (total > 0) {
            pct = (int) Math.min(100L, Math.max(0L, (soFar * 100) / total));
        } else {
            pct = 0;
        }
        // 进度条只增不减：aria2 多连接分片（completedLength 按 bitfield 算，
        // 单分片失败重拉时 bit 先清后补）与主引擎分块重拉，完成量都会瞬时回落，
        // 条往回退会让玩家以为下载出错。净进度始终向前，clamp 到已到过的高点
        // （2026-08-12 反馈）。
        int[] progress = CNCNDownloadUI.fileProgress;
        if (progress != null && index < progress.length && pct < progress[index]) {
            return;
        }
        CNCNDownloadUI.setFileDownloaded(index, (float) (soFar / 1000000.0d));
        CNCNDownloadUI.updateFileProgress(index, pct);
    }

    private static void resetProgress(int index) {
        CNCNDownloadUI.resetFileProgress(index);
    }

    private static void markDone(int index) {
        setActive(index, false);
        CNCNDownloadUI.setDownloadSpeed(index, 0.0f);
        CNCNDownloadUI.markFileDone(index);
    }

    private static void markFailed(int index) {
        setActive(index, false);
        CNCNDownloadUI.setDownloadSpeed(index, 0.0f);
        if (CNCNDownloadUI.fileStatus != null) {
            CNCNDownloadUI.fileStatus[index] = 3;
        }
        CNCNDownloadUI.throttledUpdate();
    }

    private static void setActive(int index, boolean active) {
        ACTIVE.set(index, active ? 1 : 0);
        LAST_PROGRESS_NS.set(index, active ? System.nanoTime() : 0L);
    }

    private static void zeroAllSpeeds() {
        for (int i = 0; i < ARCHIVE_COUNT; i++) {
            ACTIVE.set(i, 0);
            LAST_PROGRESS_NS.set(i, 0L);
            CNCNDownloadUI.setDownloadSpeed(i, 0.0f);
        }
        CNCNDownloadUI.throttledUpdate();
    }

    private static void failInstaller(String message, Throwable t) {
        zeroAllSpeeds();
        if (t == null) {
            CNLog.e(TAG, message);
        } else {
            CNLog.e(TAG, message, t);
        }
        CNCNDownloadUI.updateSimple("安装暂停", message, 0);
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static String cleanHeader(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            CNLog.w(TAG, "Cannot delete " + file);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException e) {
            }
        }
    }

    private static void closeQuietly(OutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
            }
        }
    }

    // ==================================================================
    // 值对象
    // ==================================================================

    static final class DownloadMetadata {
        final String etag;
        final long   totalBytes;
        DownloadMetadata(long totalBytes, String etag) {
            this.totalBytes = totalBytes;
            this.etag       = etag == null ? "" : etag;
        }
    }

    static final class ContentRange {
        final long end;
        final long start;
        final long total;
        ContentRange(long start, long end, long total) {
            this.start = start;
            this.end   = end;
            this.total = total;
        }
    }

    static final class ExtractionPaused extends IOException {
        private static final long serialVersionUID = 1L;
        ExtractionPaused(String message, Throwable cause) { super(message, cause); }
    }

    static final class ResetRequired extends IOException {
        private static final long serialVersionUID = 1;
        ResetRequired(String message) {
            super(message);
        }
    }

    /**
     * 热更包传输完好、但内容与 version json 的身份对不上。
     *
     * <p>单列一类是为了<b>不调</b> {@code CNMirrors.reportFailure}。这条线路刚把
     * 一两百兆稳稳传完，它不慢也不坏，只是手里那份是旧的；按「线路故障」记冷却
     * 会把健康线路一条条打进 60 秒冷却，而源站要是真的旧了，四条全灭之后连别的
     * 包都没线路可用。换线由 {@code CNMirrors.pick(attempt)} 逐轮轮换完成，本来
     * 就不需要冷却表配合——与上面 ZipException 那支同一个道理。
     */
    static final class HotIdentityMismatch extends IOException {
        private static final long serialVersionUID = 1L;
        HotIdentityMismatch(String message) {
            super(message);
        }
    }

    /** 基础包与热更新事务共用的提交锁：下载可并行，活动资源树修改必须串行。 */
    static Object extractCommitLock() { return EXTRACT_LOCK; }

    /**
     * 强制重新下载一个基础 ZIP。任何其它 marker / 总完成标记状态都不构成前置条件。
     * 旧 marker 在整个下载过程中保持原样；只有新包通过下载、ZIP 校验和解压后，
     * installArchive 才原子覆盖 marker。失败因此不会破坏当前可用版本。
     */
    static boolean redownloadArchive(int index) {
        if (index < 0 || index >= ARCHIVE_COUNT) return false;
        if (isHotSlot(index)) {
            return CNHotUpdateCheck.redownloadPackage(index);
        }
        CNDownloadRestart.register(index);
        try {
            synchronized (ARCHIVE_LOCKS[index]) {
            if (!FORCE_REDOWNLOAD.compareAndSet(index, 0, 1)) {
                CNLog.w(TAG, "同一文件已有强制重下载任务 index=" + index);
                return false;
            }
            try {
                cleanupArchiveDownloadState(index);
                CNCNDownloadUI.markFilePending(index);
                return installArchive(index);
            } finally {
                FORCE_REDOWNLOAD.set(index, 0);
            }
            }
        } finally {
            CNDownloadRestart.unregister(index);
        }
    }

    /** 热更新事务成功后补齐 0/1 号 ZIP marker；不触碰总完成标记。 */
    static void commitManualMarker(int index, long bytes, String etag) throws IOException {
        if (index < 0 || index >= ARCHIVE_COUNT || bytes <= 0) {
            throw new IOException("无法提交手动 marker index=" + index + " bytes=" + bytes);
        }
        String name = FILE_NAMES[index];
        writeMarker(markerFor(name), name, RESOURCE_BASE_URL + name,
                new DownloadMetadata(bytes, etag == null ? "manual" : etag));
    }

    /** 手动逐项补齐到 15 个 marker 时补回总完成标记；缺项时只返回 false。 */
    static boolean commitFinalFlagIfComplete() throws IOException {
        if (!allBaseMarkersValid()) return false;
        File flag = new File(FINAL_FLAG);
        if (!flag.isFile()) {
            writeAtomic(flag, "schema=2\narchives=15\n");
            CNLog.i(TAG, "手动任务已补齐全部 marker，提交总完成标记");
        }
        return true;
    }

    private static boolean allBaseMarkersValid() {
        // 按槽位语义挑基础包，绝不写死下标：热更两包曾从表头（0/1）挪到
        // 表尾（13/14），写死的「i 从 2 开始」随之从「跳过两个热更槽」
        // 悄悄变成「漏查 0/1 号基础包（db/json）、多查两个热更槽」——
        // 前者让总完成标记在 db/json 缺失时被提交（玩家进游戏缺数据，
        // 且标记在案永无自愈），后者让热更未跑时永远误拒补齐。
        // HOT_SLOT_* 注释里那段「跟表序绑死」的教训，这里原样适用。
        for (int i = 0; i < ARCHIVE_COUNT; i++) {
            if (isHotSlot(i)) continue;   // 热更两包由热更事务负责，不归这里查
            String name = FILE_NAMES[i];
            if (!isMarkerValid(markerFor(name), name, RESOURCE_BASE_URL + name)) return false;
        }
        return true;
    }

    /** Restart the active transfer/extraction for exactly one file. */
    static boolean requestActiveRestart(int index) {
        return index >= 0 && index < ARCHIVE_COUNT && CNDownloadRestart.request(index);
    }

    /** installOfflineNow 专用：中止在传下载但保留同名离线候选（object-storage-01 原因码）。 */
    static boolean requestActiveRestartKeepOffline(int index) {
        return index >= 0 && index < ARCHIVE_COUNT
                && CNDownloadRestart.request(index, true);
    }

    /** Wake the first-install retry loop after an external manual task completed. */
    static void signalExternalCompletion() {
        synchronized (RETRY_LOCK) {
            retryRequested = true;
            RETRY_LOCK.notifyAll();
        }
    }

    /**
     * 玩家刚导入了离线包，<b>立刻</b>用它，不必等下一次启动。
     *
     * <h3>为什么必须有这条路</h3>
     *
     * 离线检查在 {@link #installArchive} 的<b>开头</b>，而安装器对 15 个文件的
     * 循环在启动时只跑一次。于是真机上出现两种症状（2026-08-13 反馈）：
     *
     * <ul>
     *   <li>安装器<b>已经处理过</b>那个文件 → 导入完全没反应，要等下次启动；</li>
     *   <li>安装器<b>正在下</b>那个文件 → 重试循环在 installArchive <b>内部</b>，
     *       不会重新走开头的离线检查，于是红条一直重试，导入的包躺在那没人用。</li>
     * </ul>
     *
     * 导入成功后原先只改了胶囊文字和弹了个结果框，没有任何东西去消费那个包。
     *
     * <h3>怎么保证不打架</h3>
     *
     * {@code ArchiveTask} 全程持有 {@code ARCHIVE_LOCKS[index]}，本方法拿同一把
     * 锁，所以与正在跑的安装天然串行。进锁前先 {@link #requestActiveRestart}
     * 中止在传的下载——离线包已经过分块校验，继续下没有意义，而且不中止的话这把
     * 锁要等它整轮重试跑完（可能几分钟）。
     *
     * <p>清理中间产物时走 {@code keepOffline=true}：默认那条是给「重下」用的，
     * 它会把离线候选一起删掉，正好与这里相反。
     *
     * <p>不设 {@code FORCE_REDOWNLOAD}：设了的话 installArchive 开头的离线分支
     * 就被跳过了（那个标记的语义正是「这次必须走网络」）。
     */
    public static boolean installOfflineNow(int index) {
        if (index < 0 || index >= ARCHIVE_COUNT) return false;
        String name = FILE_NAMES[index];
        if (CNOfflineImport.isHotUpdateFile(name)) {
            CNLog.w(TAG, "热更包不走离线安装: " + name);
            return false;
        }
        if (!CNOfflineImport.hasOffline(name)) {
            CNLog.w(TAG, "离线区没有这个包，无法即时安装: " + name);
            return false;
        }
        CNLog.i(TAG, "离线包即时安装开始: " + name);
        // object-storage-01：登记「保留离线候选」的中断——worker 的 manual-restart 清理
        // 会读这个原因码；用默认 requestActiveRestart 会把刚导入的包删掉。
        boolean signalled = requestActiveRestartKeepOffline(index);
        if (signalled) CNLog.i(TAG, "已中止 " + name + " 在传的下载，改用离线包");
        CNDownloadRestart.register(index);
        try {
            synchronized (ARCHIVE_LOCKS[index]) {
                if (!CNOfflineImport.hasOffline(name)) {
                    CNLog.w(TAG, "等锁期间离线包已消失: " + name);
                    return false;
                }
                CNCNDownloadUI.markFilePending(index);
                cleanupArchiveDownloadState(index, true);
                boolean ok = installArchive(index);
                if (ok) {
                    try { commitFinalFlagIfComplete(); }
                    catch (Throwable t) { CNLog.w(TAG, "补齐总完成标记失败: " + t); }
                    signalExternalCompletion();
                    CNLog.i(TAG, "离线包即时安装完成: " + name);
                } else {
                    CNLog.w(TAG, "离线包即时安装未成功: " + name);
                }
                return ok;
            }
        } catch (Throwable t) {
            CNLog.e(TAG, "离线包即时安装异常: " + name, t);
            return false;
        } finally {
            CNDownloadRestart.unregister(index);
        }
    }

    /** 文件名 → 槽位下标；找不到返回 -1。 */
    public static int indexOfArchive(String name) {
        if (name == null) return -1;
        for (int i = 0; i < FILE_NAMES.length; i++) {
            if (FILE_NAMES[i].equals(name)) return i;
        }
        return -1;
    }

    private static Object[] createArchiveLocks() {
        Object[] out = new Object[ARCHIVE_COUNT];
        for (int i = 0; i < out.length; i++) out[i] = new Object();
        return out;
    }

    private static void cleanupArchiveDownloadState(int index) {
        cleanupArchiveDownloadState(index, false);
    }

    /**
     * @param keepOffline 保留同名离线候选。<b>「重下」要删它、「用刚导入的离线包」
     *     要留它</b>——两者清理的是同一批中间产物，只有这一处语义相反。
     */
    private static void cleanupArchiveDownloadState(int index, boolean keepOffline) {
        String name = FILE_NAMES[index];
        File archive = new File(FILE_ROOT, name);
        deleteQuietly(archive);
        deleteQuietly(new File(archive.getPath() + ".aria2"));
        deleteQuietly(new File(archive.getPath() + ".aria2.url"));
        deleteQuietly(new File(archive.getPath() + ".part"));
        deleteQuietly(new File(archive.getPath() + ".part.meta"));
        deleteQuietly(new File(archive.getPath() + ".part.meta.tmp"));
        File cpart = CNChunkedDownload.partFileFor(archive);
        File cmeta = CNChunkedDownload.metaFileFor(archive);
        deleteQuietly(cpart);
        deleteQuietly(cmeta);
        deleteQuietly(new File(cmeta.getPath() + ".tmp"));
        CNArchiveInstallTx.clearState(
                CNArchiveInstallTx.stateFile(new File(STATE_ROOT), name));
        File[] siblings = new File(FILE_ROOT).listFiles();
        String prefix = cpart.getName() + ".block.";
        if (siblings != null) {
            for (int i = 0; i < siblings.length; i++) {
                File f = siblings[i];
                if (f != null && f.getName().startsWith(prefix)) deleteQuietly(f);
            }
        }
        // “重下”必须走网络；同名离线候选会让安装器绕过下载，因此只清所选项。
        if (!keepOffline) {
            File offline = new File(CNOfflineImport.offlineDir(), name);
            deleteQuietly(offline);
            deleteQuietly(new File(offline.getPath() + ".importing"));
        }
    }

}
