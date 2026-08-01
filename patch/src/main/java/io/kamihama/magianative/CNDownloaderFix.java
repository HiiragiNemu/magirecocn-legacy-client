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
    private static final int    CONNECT_TIMEOUT_MS = 15000;
    private static final String FILE_ROOT = "/data/data/io.kamihama.totentanz/files";
    private static final String FINAL_FLAG = "/data/data/io.kamihama.totentanz/files/madomagi/magica/cn_base_done.flag";
    private static final String INSTALL_ROOT = "/data/data/io.kamihama.totentanz/files/";
    private static final int    MAX_ATTEMPTS = 4;
    private static final int    MAX_DOWNLOADS = 4;
    private static final int    MIN_SNAA_VERSION = 128;
    private static final String NO_RESTART_FLAG = "/data/data/io.kamihama.totentanz/files/madomagi/magica/.cn_installer/r128-downloader-v1/no_restart";
    private static final int    READ_TIMEOUT_MS = 30000;
    /** 规范资源地址：仅用于生成完成标记里的 url 字段，不代表实际下载线路。 */
    private static final String RESOURCE_BASE_URL = "https://assets.example.test/";
    private static final String STATE_ROOT = "/data/data/io.kamihama.totentanz/files/madomagi/magica/.cn_installer/r128-downloader-v1";
    private static final String TAG = "MagiaCNDownloader";

    private static final long   STALE_SPEED_NS = TimeUnit.SECONDS.toNanos(2);
    private static final Object EXTRACT_LOCK   = new Object();

    private static final String[] FILE_NAMES = {
        "cn_base_00_db.zip", "cn_base_01_json.zip", "cn_base_02.zip",
        "cn_base_03.zip", "cn_base_04.zip", "cn_base_05.zip",
        "cn_base_06.zip", "cn_magica_resource.zip", "cn_scenario_img.zip",
        "cn_voice_01.zip", "cn_voice_02_done.zip", "cn_js_update.zip",
        "movie.zip", "movie2.zip", "cn_scenario_update.zip"
    };

    private static final int ARCHIVE_COUNT = 15;

    /** 玩家点「重试」时用来唤醒安装器主循环。 */
    private static final Object RETRY_LOCK = new Object();
    private static volatile boolean retryRequested = false;

    private static final AtomicLongArray    LAST_PROGRESS_NS = new AtomicLongArray(ARCHIVE_COUNT);
    private static final AtomicIntegerArray ACTIVE           = new AtomicIntegerArray(ARCHIVE_COUNT);

    private CNDownloaderFix() {
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
            viaProxy = postJson(BOOTSTRAP_URL, payload, false);
            CNLog.i(TAG, "snaa-response direct=false body=" + viaProxy);
            if (isSnaaResponseCurrent(viaProxy, max)) {
                return viaProxy;
            }
            CNLog.w(TAG, "SNAA response is stale/incompatible; retrying direct");
            String direct = postJson(BOOTSTRAP_URL, payload, true);
            CNLog.i(TAG, "snaa-response direct=true body=" + direct);
            return direct;
        } catch (IOException first) {
            CNLog.w(TAG, "SNAA via configured network failed; retrying direct", first);
            try {
                String direct = postJson(BOOTSTRAP_URL, payload, true);
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
     * 安装器入口。**由 native hook 经 JNI 调用**（hook 拦下引擎的
     * {@code DownloadSceneLayer::init} 后转调 {@code RestClient.startCNDownload}，
     * 后者直接转调本方法）。
     *
     * <p><b>本方法绝不允许抛出任何东西。</b>hook 在 {@code CallStaticVoidMethod}
     * 之后会做 {@code ExceptionCheck} / {@code ExceptionClear}，一旦发现挂起的
     * Java 异常就清掉并放行引擎原本的下载场景——也就是玩家会看到**原生安装界面**，
     * 而那是无论如何都要避免出现的。所以整个方法体套在 catch(Throwable) 里：
     * 宁可停在我们自己的浮层上显示错误，也不能把控制权交回引擎。
     */
    public static void runInstaller() {
        try {
            runInstallerInner();
        } catch (Throwable t) {
            // 走到这里说明有意料之外的错误。绝不外抛：让浮层留在屏幕上显示错误，
            // 引擎的下载场景就不会被放行。
            try {
                CNLog.e(TAG, "安装器发生未预期错误，已拦截以避免回退到原生下载界面", t);
                failInstaller("安装器异常：" + t, t);
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

        // 线路列表：先走系统网络，失败再直连；两次都失败就用内置默认线路
        CNCNDownloadUI.updateSimple("准备中", "正在获取下载线路…", 0);
        CNMirrors.refresh(false);
        if (!CNMirrors.isLoaded()) {
            CNMirrors.refresh(true);
        }
        int lineCount = CNMirrors.healthy().size();
        CNLog.i(TAG, "mirrors ready count=" + lineCount + " loaded=" + CNMirrors.isLoaded());
        CNCNDownloadUI.updateSimple("开始下载",
                "可用线路 " + lineCount + " 条，单文件分片 " + CNMirrors.chunks() + " 线程", 0);

        // ── 尽早启动看门狗：从网络操作阶段开始就保护浮层 ──
        // 首次打开后强退再进来的场景里，引擎可能在切场景时换掉 decorView 内容，
        // 浮层脱离视图树后就露出引擎原生下载界面。把看门狗提前到网络操作之前，
        // 确保整个安装周期都有浮层守护。
        ScheduledExecutorService watchdog = startSpeedWatchdog();

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
        watchdog.shutdownNow();

        try {
            writeAtomic(finalFlag, "schema=2\narchives=15\n");
            CNCNDownloadUI.updateSimple("安装完成", "所有资源已验证并提交完成标记", 100);
            CNCNDownloadUI.hide();
            CNLog.i(TAG, "All archives installed; final flag committed atomically");
            if (new File(NO_RESTART_FLAG).isFile()) {
                CNLog.i(TAG, "Test no-restart marker present; restart suppressed");
                return;
            }
            try {
                Thread.sleep(2000L);
                RestClient.restartApp();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (IOException e) {
            failInstaller("Final flag commit failed", e);
        }
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

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                markFailed(index);
                return false;
            }

            CNMirrors.Mirror mirror = CNMirrors.pick(attempt);
            boolean direct = attempt % 2 == 0;

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
                CNMirrors.reportFailure(mirror, "corrupt-zip");
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
                    CNChunkedDownload.Result r = CNChunkedDownload.download(
                            url, archive, chunks, direct, probe, new ArchiveSink(index), mirror);
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
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Connection", "close");

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
            int  n;
            while ((n = in.read(buf)) >= 0) {
                fos.write(buf, 0, n);
                written += n;
                long now = System.nanoTime();
                LAST_PROGRESS_NS.set(index, now);
                updateProgress(index, offset + written, total);
                long dt = now - windowStart;
                if (dt >= TimeUnit.MILLISECONDS.toNanos(500L)) {
                    CNCNDownloadUI.setDownloadSpeed(index,
                            (float) ((((written - speedBaseline) * 1.0E9d) / dt) / 1000000.0d));
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

    private static void extractChecked(File archive, File root) throws IOException {
        if (!archive.isFile()) {
            throw new IOException("Archive is missing: " + archive);
        }
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
            throw new IOException("Cannot create extraction root: " + root);
        }
        String rootCanonical = root.getCanonicalPath();
        String prefix        = rootCanonical + File.separator;

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
        return svc;
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
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Connection", "close");

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
