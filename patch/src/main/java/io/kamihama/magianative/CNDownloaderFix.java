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
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
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
import java.util.zip.ZipEntry;
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
    private static final String RESOURCE_BASE_URL = "https://assets.example.test/";
    private static final String STATE_ROOT = FILE_ROOT + "/madomagi/magica/.cn_installer/r128-downloader-v1";
    private static final String TAG = "MagiaCNDownloader";

    private static final long   STALE_SPEED_NS = TimeUnit.SECONDS.toNanos(2);
    private static final Object EXTRACT_LOCK   = new Object();

    /**
     * 下载顺序。
     *
     * <p><b>热更新的两个包排在最前面</b>：{@code cn_scenario_update.zip}（台词）与
     * {@code cn_js_update.zip}（前端脚本）是热更新真正依赖的内容，先拿到它们，
     * 玩家在剩下十几 GB 还没下完时就已经是最新的汉化与前端。排在后面的话，
     * 要等 voice/movie 那几个大包下完才轮得到，热更新形同虚设。
     *
     * <p>顺序只是下载次序，不是身份：所有逻辑都按**文件名**索引，完成标记也是
     * {@code <文件名>.done}，所以调整顺序不会让既有安装失效、也不会触发重下。
     * 与 {@link CNCNDownloadUI#FILE_NAMES} / {@link CNCNDownloadUI#FILE_URLS}
     * <b>必须逐项对齐</b>——三张表是按下标并行的。
     */
    private static final String[] FILE_NAMES = {
        "cn_scenario_update.zip", "cn_js_update.zip",
        "cn_base_00_db.zip", "cn_base_01_json.zip", "cn_base_02.zip",
        "cn_base_03.zip", "cn_base_04.zip", "cn_base_05.zip",
        "cn_base_06.zip", "cn_magica_resource.zip", "cn_scenario_img.zip",
        "cn_voice_01.zip", "cn_voice_02_done.zip",
        "movie.zip", "movie2.zip"
    };

    private static final int ARCHIVE_COUNT = 15;

    /** 热更那一轮真正检查的两个槽位，与 {@code CNHotUpdateCheck.PACKAGES} 的 slot 对应。 */
    static final int HOT_SLOT_SCENARIO = 0;   // cn_scenario_update.zip
    static final int HOT_SLOT_JS       = 1;   // cn_js_update.zip

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
                        CNWebProxy.install();

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
                                   : "已要求但 libaria2 加载失败，只能走主引擎")
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
            return Boolean.valueOf(installArchive(index));
        }
    }

    private static boolean installArchive(int index) {
        String name         = FILE_NAMES[index];
        String canonicalUrl = RESOURCE_BASE_URL + name;
        File   archive      = new File(FILE_ROOT, name);
        File   marker       = markerFor(name);

        if (isMarkerValid(marker, name, canonicalUrl)) {
            markDone(index);
            CNLog.i(TAG, "marker-hit file=" + name);
            return true;
        }

        // 离线包注入兜底：玩家手动导入的官方 zip（分块清单已校验）优先，
        // 跳过网络下载，直接解压 + 写标记。只对基础资源包生效——热更两包
        // （cn_scenario_update.zip / cn_js_update.zip）走版本 json 通道，不纳入。
        if (!CNOfflineImport.isHotUpdateFile(name)
                && CNOfflineImport.hasOffline(name)) {
            File offline = new File(CNOfflineImport.offlineDir(), name);
            try {
                synchronized (EXTRACT_LOCK) {
                    extractChecked(offline, new File(INSTALL_ROOT));
                }
                writeMarker(marker, name, canonicalUrl,
                        new DownloadMetadata(offline.length(), "offline"));
                if (!offline.delete() && offline.exists()) {
                    CNLog.w(TAG, "Offline archive retained: " + offline);
                }
                markDone(index);
                CNLog.i(TAG, "offline-installed file=" + name
                        + " bytes=" + offline.length());
                return true;
            } catch (Throwable t) {
                CNLog.e(TAG, "offline-extract-failed file=" + name + ": " + t, t);
                // 解压失败：删掉离线包，回退网络下载
                deleteQuietly(offline);
            }
        }

        // 备用引擎（默认关）：cloud=config.json 的 settings.force_aria2 强制启用；
        // 本地=debug 开关 CNDebugFlags.useAria2。任一打开就先用首选线路的 aria2
        // 拉一把，装好即返回；失败清掉 aria2 的半截产物（目标文件 + .aria2
        // 控制文件），走下面的主引擎整份重下。
        boolean aria2Forced = CNMirrors.forceAria2()
                || CNDebugFlags.isOn(CNDebugFlags.USE_ARIA2);
        if (aria2Forced && CNAria2.isAvailable()
                && tryAria2Download(CNMirrors.pick(1), name, archive, index,
                                    marker, canonicalUrl)) {
            return true;
        }
        deleteQuietly(archive);
        deleteQuietly(new File(archive.getPath() + ".aria2"));

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                markFailed(index);
                return false;
            }

            CNMirrors.Mirror mirror = CNMirrors.pick(attempt);
            // 资源下载一律直连（Proxy.NO_PROXY）：系统代理会劫持 CDN 大文件传输，
            // 损坏分片拼出的 zip 导致「完工校验失败」。曾按 attempt 奇偶交替走代理，
            // 玩家开着 VPN/抓包工具时奇数尝试必被劫持（cn_base_03 连败四次的根因），
            // 且只在 CNHotUpdate 修过、这里漏了。mirrors 与 proxy 是两套机制。
            boolean direct = true;

            setActive(index, true);
            CNCNDownloadUI.setDownloadSpeed(index, 0.0f);
            try {
                DownloadMetadata meta = fetchArchive(mirror, name, archive, index, direct);
                synchronized (EXTRACT_LOCK) {
                    extractChecked(archive, new File(INSTALL_ROOT));
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
                CNLog.w(TAG, "resume-reset file=" + name + " attempt=" + attempt
                        + " reason=" + e.getMessage());
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
            } catch (IOException e) {
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

            if (attempt < MAX_ATTEMPTS) {
                long delay = 2000L << (attempt - 1);
                CNLog.i(TAG, "retry-wait file=" + name + " delay_ms=" + delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    markFailed(index);
                    return false;
                }
            }
        }

        markFailed(index);
        CNLog.e(TAG, "retry-exhausted file=" + name);
        return false;
    }

    /**
     * 用 libaria2 备用引擎下载并安装单个文件（debug 开关 useAria2 打开时才走）。
     *
     * <p>单文件同步下载：首选线路直连，aria2 多连接 + 断点续传。装好即解压 +
     * 写标记 + 返回 true；任何失败（下载错、结构校验不过、异常）返回 false，
     * 调用方清掉半截产物后回退主引擎。进度接到既有 UI，取消绑线程中断。
     */
    private static boolean tryAria2Download(CNMirrors.Mirror mirror, String name,
                                            File archive, int index, File marker,
                                            String canonicalUrl) {
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
            // 取消：原生 run 循环轮询 Cancel.isCancelled()。这里绑到线程中断——
            // 下载被外部 interrupt 即触发 aria2 取消（AtomicBoolean.get() 是
            // final 不能覆写，所以用接口而非子类）。
            CNAria2.Cancel cancel = new CNAria2.Cancel() {
                @Override public boolean isCancelled() {
                    return Thread.currentThread().isInterrupted();
                }
            };

            String url = mirror.urlFor(name);
            CNLog.i(TAG, "aria2 备用引擎下载 file=" + name + " url=" + url
                    + " 连接数=16");
            int rv = CNAria2.download(url, FILE_ROOT, name,
                    CNUserAgent.get(), null, null, 16, null, progress, cancel);
            if (rv != CNAria2.OK || !archive.isFile() || archive.length() <= 0) {
                CNLog.w(TAG, "aria2 备用引擎失败 code=" + rv + "，回退主引擎: " + name);
                return false;
            }
            // zip 结构预检：aria2 下到 100% 不代表拼装合法
            if (!CNArchiveValidate.isZipStructurallyValid(archive)) {
                CNLog.w(TAG, "aria2 下载的包结构非法，回退主引擎: " + name);
                return false;
            }
            try (ZipFile zf = new ZipFile(archive)) {
                if (!zf.entries().hasMoreElements()) {
                    CNLog.w(TAG, "aria2 下载的包为空，回退主引擎: " + name);
                    return false;
                }
            } catch (Throwable t) {
                CNLog.w(TAG, "aria2 下载的包结构非法，回退主引擎: " + name + " : " + t);
                return false;
            }
            synchronized (EXTRACT_LOCK) {
                extractChecked(archive, new File(INSTALL_ROOT));
            }
            writeMarker(marker, name, canonicalUrl,
                    new DownloadMetadata(archive.length(), "aria2"));
            if (!archive.delete() && archive.exists()) {
                CNLog.w(TAG, "Installed archive retained: " + archive);
            }
            markDone(index);
            CNLog.i(TAG, "aria2 备用引擎装好 file=" + name + " bytes=" + archive.length());
            return true;
        } catch (Throwable t) {
            CNLog.w(TAG, "aria2 备用引擎异常，回退主引擎: " + name + " : " + t);
            return false;
        }
    }

    // ==================================================================
    // 下载
    // ==================================================================

    /**
     * 在指定线路上取回压缩包：优先多线程分片，不满足条件时退回单线程续传。
     */
    private static DownloadMetadata fetchArchive(CNMirrors.Mirror mirror, String name,
                                                 File archive, int index, boolean direct)
            throws IOException {
        if (archive.isFile()) {
            long len = archive.length();
            updateSize(index, len);
            return new DownloadMetadata(len, readSidecarEtag(archive));
        }

        String url = mirror.urlFor(name);
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
                    CNLog.i(TAG, "chunked-download file=" + name + " mirror=" + mirror.name
                            + " chunks=" + chunks + " bytes=" + probe.total + " direct=" + direct);
                    updateSize(index, probe.total);
                    updateProgress(index, 0L, probe.total);
                    CNChunkedDownload.ChunkHashes hashes = ChunkManifest.forFile(name);
                    CNChunkedDownload.Result r = CNChunkedDownload.download(
                            url, archive, chunks, direct, probe, new ArchiveSink(index),
                            mirror, name, true, hashes);
                    return new DownloadMetadata(r.totalBytes, r.etag);
                }
            }
            CNLog.i(TAG, "range-unsupported-or-small file=" + name + " mirror=" + mirror.name
                    + " → 单线程续传");
        }
        return downloadOnce(url, archive, index, direct);
    }

    /** 把分片下载的进度接到既有的 UI/看门狗上。 */
    private static final class ArchiveSink implements CNChunkedDownload.Sink {
        private final int index;
        ArchiveSink(int index) { this.index = index; }

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
            return Thread.currentThread().isInterrupted();
        }
    }

    /**
     * 单线程断点续传下载（改版前的实现，逐行保留其语义）。
     * 服务端不支持 Range、或文件太小不值得切片时走这里。
     */
    private static DownloadMetadata downloadOnce(String url, File archive,
                                                 int index, boolean direct)
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
            while ((n = in.read(buf)) >= 0) {
                fos.write(buf, 0, n);
                written += n;
                long now = System.nanoTime();
                LAST_PROGRESS_NS.set(index, now);
                updateProgress(index, offset + written, total);
                long dt = now - windowStart;
                if (dt >= TimeUnit.MILLISECONDS.toNanos(500L)) {
                    long windowBytes = written - speedBaseline;
                    CNCNDownloadUI.setDownloadSpeed(index,
                            (float) ((windowBytes * 1.0E9d / dt) / 1000000.0d));
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
        }
    }

    // ==================================================================
    // 解压
    // ==================================================================

    /**
     * 解压膨胀比上限。超过它、且已经写出 {@link #EXTRACT_MIN_BYTES_BEFORE_RATIO}
     * 之后才判定为 zip 炸弹。
     *
     * <p>游戏资源本来就是 PNG / 音频 / 已压缩的容器，实测膨胀比接近 1，
     * 离 200 差着两个数量级，正常包不可能误伤。
     */
    private static final long EXTRACT_MAX_RATIO = 200L;

    /**
     * 小包不看比例。几 KB 的包里放一个高度可压的小文件，比例很容易冲上去，
     * 但那点绝对量根本谈不上「把磁盘写满」，没必要为它中止。
     */
    private static final long EXTRACT_MIN_BYTES_BEFORE_RATIO = 256L * 1024 * 1024;

    /**
     * 解压 {@code archive} 到 {@code root}，带 Zip Slip 防护、逐条目大小核对
     * 与膨胀比上限。
     *
     * <p>包内可见（而非 private）是因为热更新走的是同一套解压要求：
     * {@link CNHotUpdateCheck} 直接复用，不再另写一份。
     *
     * <h3>为什么要看膨胀比</h3>
     *
     * 归档的 md5/大小校验（只有热更包有，见 {@code CNHotUpdateCheck.verifyZip}）
     * 管的是<b>压缩后</b>那份，管不到解压出来有多大。一个几十 MB 的包完全可以
     * 炸出几十 GB，把玩家的存储写满——而写满之后倒霉的不只是游戏，整台机器都会
     * 开始出问题。这一条与内容是否可信无关，纯粹是别让一个坏包能造成不可逆的破坏。
     */
    static void extractChecked(File archive, File root) throws IOException {
        if (!archive.isFile()) {
            throw new IOException("Archive is missing: " + archive);
        }
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
            throw new IOException("Cannot create extraction root: " + root);
        }
        String rootCanonical = root.getCanonicalPath();
        String prefix        = rootCanonical + File.separator;

        final long archiveBytes = Math.max(1L, archive.length());
        final long ratioCap     = archiveBytes * EXTRACT_MAX_RATIO;
        long writtenTotal = 0L;

        ZipFile zip = new ZipFile(archive);
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            boolean sawFile = false;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File out = new File(root, entry.getName());
                String canonical = out.getCanonicalPath();
                // Zip Slip 防护：条目必须落在解压根之内
                if (!canonical.equals(rootCanonical) && !canonical.startsWith(prefix)) {
                    throw new ZipException("ZIP entry escapes extraction root: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs() && !out.isDirectory()) {
                        throw new IOException("Cannot create directory " + out);
                    }
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()
                        && !parent.isDirectory()) {
                    throw new IOException("Cannot create directory " + parent);
                }
                InputStream  in  = null;
                OutputStream os  = null;
                try {
                    in = new BufferedInputStream(zip.getInputStream(entry), 65536);
                    os = new BufferedOutputStream(new FileOutputStream(out), 65536);
                    byte[] buf = new byte[65536];
                    long copied = 0L;
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        os.write(buf, 0, n);
                        copied += n;
                        writtenTotal += n;
                        // 边写边看，而不是写完再算——zip 炸弹的伤害就在于「写出去」本身，
                        // 等它铺完磁盘再报错已经晚了
                        if (writtenTotal > EXTRACT_MIN_BYTES_BEFORE_RATIO
                                && writtenTotal > ratioCap) {
                            throw new ZipException("解压膨胀比超限（已写 " + writtenTotal
                                    + "B，归档 " + archiveBytes + "B，上限 "
                                    + EXTRACT_MAX_RATIO + "x）：" + archive.getName());
                        }
                    }
                    os.flush();
                    if (entry.getSize() >= 0 && copied != entry.getSize()) {
                        throw new ZipException("Entry size mismatch: " + entry.getName());
                    }
                    sawFile = true;
                } finally {
                    closeQuietly(os);
                    closeQuietly(in);
                }
            }
            if (!sawFile) {
                throw new ZipException("Archive contains no file entries: " + archive);
            }
        } finally {
            zip.close();
        }
    }

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
            boolean hot = (i == HOT_SLOT_SCENARIO || i == HOT_SLOT_JS);
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
        for (int i = 0; i < ARCHIVE_COUNT; i++) {
            if (!isMarkerValid(markerFor(FILE_NAMES[i]), FILE_NAMES[i],
                    RESOURCE_BASE_URL + FILE_NAMES[i])) {
                if (CNCNDownloadUI.fileStatus != null) {
                    CNCNDownloadUI.fileStatus[i] = 0;
                }
                if (CNCNDownloadUI.fileProgress != null) {
                    CNCNDownloadUI.fileProgress[i] = 0;
                }
                CNCNDownloadUI.setDownloadSpeed(i, 0.0f);
                CNCNDownloadUI.setFileDownloaded(i, 0.0f);
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
        CNCNDownloadUI.setFileDownloaded(index, (float) (soFar / 1000000.0d));
        CNCNDownloadUI.updateFileProgress(index, pct);
    }

    private static void resetProgress(int index) {
        CNCNDownloadUI.setDownloadSpeed(index, 0.0f);
        CNCNDownloadUI.setFileDownloaded(index, 0.0f);
        CNCNDownloadUI.updateFileProgress(index, 0);
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

    static final class ResetRequired extends IOException {
        private static final long serialVersionUID = 1;
        ResetRequired(String message) {
            super(message);
        }
    }
}
