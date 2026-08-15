package io.kamihama.magianative;

import android.app.Activity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * 任意 ZIP 的手动强制重下载协调器。
 *
 * <p>不要求另外 14 个 marker 齐全，不撤销总完成标记，也不为了开始下载而重启。
 * 不同文件最多三个并行下载；同一文件去重。基础包下载可并行，真正解压仍由
 * {@link CNDownloaderFix} 的全局提交锁串行；热更新走版本 JSON 的 size/MD5 与
 * {@link CNHotUpdateTx} 事务。下载/校验失败不会触碰活动资源；基础包解压仍沿用
 * 现有串行提交，旧 marker 只有在新包完整安装成功后才会覆盖。
 */
public final class CNManualRedownload {
    private static final String TAG = "CNManualRedownload";
    private static final int MAX_PARALLEL_FILES = 3;
    private static final String CANONICAL_BASE = CNMirrors.CANONICAL_BASE;
    private static final String REQUEST_NAME = "manual-redownload.request";
    private static final String BACKUP_SUFFIX = ".manual-redownload.bak";

    private static final AtomicIntegerArray RUNNING = new AtomicIntegerArray(15);
    private static final AtomicInteger RUNNING_COUNT = new AtomicInteger(0);
    private static final AtomicBoolean RESTART_REQUIRED = new AtomicBoolean(false);
    private static final AtomicBoolean ANY_FAILURE = new AtomicBoolean(false);
    /**
     * 并行文件池。用 ThreadPoolExecutor 而不是 newFixedThreadPool 的返回值，
     * 是为了能在单线程可靠模式下把上限调到 1（见 {@link #applyMode()}）。
     */
    private static final java.util.concurrent.ThreadPoolExecutor POOL =
            new java.util.concurrent.ThreadPoolExecutor(
                    MAX_PARALLEL_FILES, MAX_PARALLEL_FILES, 0L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<Runnable>(),
                    new ManualThreadFactory());

    /**
     * 把当前下载模式下发到并行文件数。
     *
     * <p>只影响<b>还没开始</b>的任务：已经在跑的不会被掐断（ThreadPoolExecutor
     * 缩容的既定行为），与连接闸门那边一致——玩家切模式是为了让下载成功，不是
     * 为了把正在传的东西砍掉。
     *
     * <p>缩容要先 core 后 max、扩容要先 max 后 core，反过来会撞
     * IllegalArgumentException（max 必须 &gt;= core）。
     */
    static void applyMode() {
        try {
            int want = CNDownloadMode.cap(MAX_PARALLEL_FILES);
            if (want == POOL.getMaximumPoolSize()) return;
            if (want < POOL.getCorePoolSize()) {
                POOL.setCorePoolSize(want);
                POOL.setMaximumPoolSize(want);
            } else {
                POOL.setMaximumPoolSize(want);
                POOL.setCorePoolSize(want);
            }
            CNLog.i(TAG, "并行文件数 → " + want + "（" + CNDownloadMode.describe() + "）");
        } catch (Throwable t) {
            CNLog.w(TAG, "调整并行文件数失败（沿用原值）: " + t);
        }
    }

    private CNManualRedownload() {}

    /** 紫色“重下”入口：任何 ZIP、任何 marker 状态都可安排。 */
    public static void request(Activity activity, int index) {
        Activity act = activity != null ? activity : RestClient.getCurrentActivity();
        if (!validIndex(index)) {
            CNCNDownloadUI.toast(act, "资源编号无效");
            return;
        }
        int[] status = CNCNDownloadUI.fileStatus;
        boolean active = (status != null && index < status.length
                && status[index] == CNCNDownloadUI.ST_RUNNING)
                || RUNNING.get(index) != 0;
        if (active) {
            boolean signalled = index < 2
                    ? CNHotUpdate.requestActiveRestart(index)
                    : CNDownloaderFix.requestActiveRestart(index);
            CNCNDownloadUI.resetFileProgress(index);
            CNCNDownloadUI.updateSimple("重新开始下载",
                    CNCNDownloadUI.FILE_NAMES[index]
                            + "：正在停止当前传输并清除该文件断点…", 0);
            CNCNDownloadUI.toast(act, signalled
                    ? "已停止当前传输，将从头重新下载该文件"
                    : "已登记从头重下；当前阶段结束后立即执行");
            return;
        }
        if (!RUNNING.compareAndSet(index, 0, 1)) {
            CNCNDownloadUI.toast(act, "该文件已在重新下载");
            return;
        }

        if (RUNNING_COUNT.get() == 0) CNCNDownloadUI.resetOverallProgress();
        RUNNING_COUNT.incrementAndGet();
        CNDownloadUiAssist.setStayOnPage(true);
        CNCNDownloadUI.markFilePending(index);
        refreshSummary("已加入重下载队列");
        CNDownloadUiAssist.ensureInstalled();
        try {
            POOL.execute(new ManualTask(act, index));
        } catch (Throwable t) {
            RUNNING.set(index, 0);
            RUNNING_COUNT.decrementAndGet();
            markFailed(index);
            ANY_FAILURE.set(true);
            CNLog.e(TAG, "无法提交手动重下载任务 index=" + index, t);
            CNCNDownloadUI.toast(act, "无法启动重新下载：" + safeMessage(t));
            refreshSummary("任务启动失败");
        }
    }

    /** 红色“重试”入口：首次安装器仍在跑时交还其队列，否则转为独立强制重下。 */
    public static void retry(Activity activity, int index) {
        Activity act = activity != null ? activity : RestClient.getCurrentActivity();
        if (CNDownloaderFix.isInstalling()) {
            CNLog.i(TAG, "安装器仍在运行，重试交还原安装队列 index=" + index);
            CNDownloaderFix.requestRetry(index);
            CNCNDownloadUI.toast(act, "已加入安装器重试队列");
            return;
        }
        request(act, index);
    }

    public static boolean isRunning(int index) {
        return validIndex(index) && RUNNING.get(index) != 0;
    }

    public static boolean hasRunningTasks() { return RUNNING_COUNT.get() > 0; }
    public static int runningCount() { return Math.max(0, RUNNING_COUNT.get()); }
    public static boolean restartRequired() { return RESTART_REQUIRED.get(); }

    /**
     * “进入游戏”按钮先问本协调器。返回 true 表示本次点击已经被消费：
     * 仍有任务时保持页面；基础包成功过时安全重启；纯热更则返回 false 走普通离页。
     */
    public static boolean handleLeaveRequest(Activity act) {
        int n = runningCount();
        if (n > 0) {
            CNDownloadUiAssist.setStayOnPage(true);
            CNCNDownloadUI.toast(act, "还有 " + n + " 个资源正在处理，完成后才能进入游戏");
            refreshSummary("仍在下载/校验/应用");
            return true;
        }
        if (RESTART_REQUIRED.compareAndSet(true, false)) {
            CNDownloadUiAssist.setStayOnPage(false);
            Thread t = new Thread(new RestartTask(), "cnv-manual-restart");
            t.setDaemon(true);
            t.start();
            return true;
        }
        return false;
    }

    private static final class ManualTask implements Runnable {
        private final Activity act;
        private final int index;
        ManualTask(Activity act, int index) { this.act = act; this.index = index; }

        @Override public void run() {
            String name = CNCNDownloadUI.FILE_NAMES[index];
            boolean ok = false;
            try {
                CNLog.initEarly();
                recoverCompletedRequest();
                CNCNDownloadUI.updateSimple("手动重新下载",
                        name + (CNDownloadMode.singleThread()
                                ? "：单线程可靠下载中（其它文件排队）"
                                : "：正在下载（可同时处理其他文件）"), 0);
                if (index == CNDownloaderFix.HOT_SLOT_SCENARIO
                        || index == CNDownloaderFix.HOT_SLOT_JS) {
                    ok = CNHotUpdateCheck.redownloadPackage(index);
                } else {
                    ok = CNDownloaderFix.redownloadArchive(index);
                }
                if (ok) {
                    CNCNDownloadUI.markFileDone(index);
                    if (index >= 2) RESTART_REQUIRED.set(true);
                    try { CNDownloaderFix.commitFinalFlagIfComplete(); }
                    catch (Throwable t) { CNLog.w(TAG, "补齐总完成标记失败: " + t); }
                    CNLog.i(TAG, "手动重下载成功 index=" + index + " file=" + name);
                } else {
                    ANY_FAILURE.set(true);
                    markFailed(index);
                    CNLog.w(TAG, "手动重下载失败 index=" + index + " file=" + name);
                }
            } catch (Throwable t) {
                ANY_FAILURE.set(true);
                markFailed(index);
                CNLog.e(TAG, "手动重下载异常 index=" + index + " file=" + name, t);
                CNCNDownloadUI.toast(act, name + " 重下载失败：" + safeMessage(t));
            } finally {
                RUNNING.set(index, 0);
                int left = RUNNING_COUNT.decrementAndGet();
                if (left < 0) {
                    RUNNING_COUNT.set(0);
                    left = 0;
                }
                if (ok) CNDownloaderFix.signalExternalCompletion();
                if (left == 0) finishSummary();
                else refreshSummary("已有任务完成，剩余 " + left + " 个");
                CNDownloadUiAssist.ensureInstalled();
            }
        }
    }

    private static final class RestartTask implements Runnable {
        @Override public void run() {
            CNDownloaderFix.noticeAndRestart("资源重新下载已完成，3 秒后重启使基础资源完全生效");
        }
    }

    private static final class ManualThreadFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger(0);
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "cnv-manual-download-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    private static void refreshSummary(String detail) {
        int n = runningCount();
        CNCNDownloadUI.updateSimple("手动重新下载",
                detail + (n > 0 ? "（进行中 " + n + " 个）" : ""), 0);
        CNCNDownloadUI.throttledUpdate();
    }

    private static void finishSummary() {
        boolean failed = ANY_FAILURE.getAndSet(false);
        if (failed) {
            CNCNDownloadUI.updateSimple("部分重新下载失败",
                    "失败项可直接点红色“重试”或紫色“重下”；其他成功项已保留", 0);
        } else if (RESTART_REQUIRED.get()) {
            CNCNDownloadUI.updateSimple("重新下载完成",
                    "基础资源已更新；点击“进入游戏”执行一次安全重启", 100);
        } else {
            CNCNDownloadUI.updateSimple("重新下载完成",
                    "热更新已校验并事务应用；点击“进入游戏”继续", 100);
        }
        CNDownloadUiAssist.setStayOnPage(true);
        CNCNDownloadUI.throttledUpdate();
    }

    private static void markFailed(int index) {
        int[] status = CNCNDownloadUI.fileStatus;
        if (status != null && index >= 0 && index < status.length) {
            status[index] = CNCNDownloadUI.ST_ERROR;
        }
        CNCNDownloadUI.setDownloadSpeed(index, 0.0f);
        CNCNDownloadUI.throttledUpdate();
    }

    /**
     * 兼容上一版“撤 marker + 撤总 flag + 重启”的遗留状态。升级后优先恢复旧 marker，
     * 所有 marker 均有效时补回总完成标记，再删除旧请求；不会继续旧事务。
     */
    public static void recoverCompletedRequest() {
        File state = stateRoot();
        File request = new File(state, REQUEST_NAME);
        if (!request.isFile()) return;
        try {
            String name = readRequestName(request);
            int index = indexOf(name);
            if (validIndex(index)) {
                File marker = markerFor(state, name);
                File backup = new File(marker.getPath() + BACKUP_SUFFIX);
                if (!marker.isFile() && backup.isFile()) moveFile(backup, marker);
                else deleteQuietly(backup);
            }
            if (!finalFlag().isFile() && allMarkersValid(state)) {
                writeAtomic(finalFlag(), "schema=2\narchives=15\n");
                CNLog.i(TAG, "已从旧式手动重下载遗留状态补回总完成标记");
            }
            deleteQuietly(request);
        } catch (Throwable t) {
            CNLog.w(TAG, "恢复旧式手动重下载状态失败: " + t);
        }
    }

    private static boolean allMarkersValid(File state) {
        String[] names = CNCNDownloadUI.FILE_NAMES;
        if (names == null || names.length != 15) return false;
        for (int i = 0; i < names.length; i++) {
            if (!markerValid(markerFor(state, names[i]), names[i])) return false;
        }
        return true;
    }

    private static boolean markerValid(File marker, String name) {
        if (marker == null || !marker.isFile() || marker.length() <= 0 || marker.length() > 16384) {
            return false;
        }
        try {
            String text = readSmall(marker);
            if (!text.contains("schema=1\n")
                    || !text.contains("file=" + name + "\n")
                    || !text.contains("url=" + CANONICAL_BASE + name + "\n")) return false;
            String[] lines = text.split("\\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith("bytes=")) {
                    return Long.parseLong(lines[i].substring(6).trim()) > 0L;
                }
            }
        } catch (Throwable ignore) {}
        return false;
    }

    private static File fileRoot() { return new File(CNPaths.filesDir()); }
    private static File stateRoot() {
        return new File(fileRoot(), "madomagi/magica/.cn_installer/r128-downloader-v1");
    }
    private static File finalFlag() {
        return new File(fileRoot(), "madomagi/magica/cn_base_done.flag");
    }
    private static File markerFor(File state, String name) {
        return new File(state, name + ".done");
    }

    private static boolean validIndex(int index) {
        String[] names = CNCNDownloadUI.FILE_NAMES;
        return names != null && index >= 0 && index < names.length;
    }

    private static int indexOf(String name) {
        String[] names = CNCNDownloadUI.FILE_NAMES;
        if (names == null || name == null) return -1;
        for (int i = 0; i < names.length; i++) if (name.equals(names[i])) return i;
        return -1;
    }

    private static String readRequestName(File request) throws IOException {
        String[] lines = readSmall(request).split("\\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("file=")) return lines[i].substring(5).trim();
        }
        return "";
    }

    private static String readSmall(File file) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                total += n;
                if (total > 16384) throw new IOException("状态文件过大: " + file);
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
        }
    }

    private static void writeAtomic(File target, String content) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建目录: " + parent);
        }
        File tmp = new File(target.getPath() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp, false);
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
            if (target.exists() && !target.delete()) throw new IOException("无法替换 " + target);
            if (!tmp.renameTo(target)) throw new IOException("原子改名失败: " + target);
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignore) {}
            if (tmp.exists()) deleteQuietly(tmp);
        }
    }

    private static void moveFile(File src, File dst) throws IOException {
        if (dst.exists() && !dst.delete() && dst.exists()) throw new IOException("无法替换 " + dst);
        if (src.renameTo(dst)) return;
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst, false);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) if (n > 0) out.write(buf, 0, n);
            out.flush();
            out.getFD().sync();
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignore) {}
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
        }
        if (!src.delete() && src.exists()) throw new IOException("无法删除旧文件 " + src);
    }

    private static void deleteQuietly(File file) {
        if (file == null || !file.exists()) return;
        try {
            if (!file.delete() && file.exists()) CNLog.w(TAG, "无法删除 " + file);
        } catch (Throwable ignore) {}
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "未知错误";
        String s = t.getMessage();
        return s == null || s.length() == 0 ? t.toString() : s;
    }

    // ---- JVM 回归测试入口 ----
    public static int maxParallelForTest() { return MAX_PARALLEL_FILES; }
    public static boolean requiresOtherMarkersForTest() { return false; }
    public static boolean claimForTest(int index) {
        if (!validIndex(index)) return false;
        boolean ok = RUNNING.compareAndSet(index, 0, 1);
        if (ok) RUNNING_COUNT.incrementAndGet();
        return ok;
    }
    public static void releaseForTest(int index) {
        if (validIndex(index) && RUNNING.compareAndSet(index, 1, 0)) {
            RUNNING_COUNT.decrementAndGet();
        }
    }
    public static boolean markerValidForTest(File marker, String name) {
        return markerValid(marker, name);
    }
}
