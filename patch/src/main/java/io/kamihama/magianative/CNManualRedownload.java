package io.kamihama.magianative;

import android.app.Activity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 玩家手动选择单个 ZIP 后，安全地安排一次“只重下这一包”的安装。
 *
 * <p>不在正在运行的 WebView/引擎进程里直接覆盖资源。确认后先验证另外 14 个
 * 完成标记均有效，再原子备份所选标记、撤掉总完成标记并重启。下一进程沿用正式
 * 安装器：14 个有效 marker 直接跳过，只有所选包进入下载、分块校验、解压与写标记。
 */
public final class CNManualRedownload {
    private static final String TAG = "CNManualRedownload";
    private static final String CANONICAL_BASE = "https://assets.example.test/";
    private static final String REQUEST_NAME = "manual-redownload.request";
    private static final String BACKUP_SUFFIX = ".manual-redownload.bak";
    private static final AtomicBoolean PREPARING = new AtomicBoolean(false);

    private CNManualRedownload() {}

    /** 由原下载列表每一行的“重下”按钮调用。 */
    public static void request(Activity activity, int index) {
        Activity act = activity != null ? activity : RestClient.getCurrentActivity();
        if (!validIndex(index)) {
            CNCNDownloadUI.toast(act, "资源编号无效");
            return;
        }
        if (!PREPARING.compareAndSet(false, true)) {
            CNCNDownloadUI.toast(act, "已有手动重下载任务正在准备");
            return;
        }
        holdPage(true);
        CNCNDownloadUI.markFilePending(index);
        CNCNDownloadUI.updateSimple("准备重新下载",
                CNCNDownloadUI.FILE_NAMES[index] + "：正在校验其余资源标记…", 0);
        Thread t = new Thread(new PrepareTask(act, index), "cnv-manual-redownload");
        t.setDaemon(true);
        t.start();
    }

    /** 新进程安装成功后清掉上一次留下的请求日志与 marker 备份。 */
    public static void recoverCompletedRequest() {
        File state = stateRoot();
        File request = new File(state, REQUEST_NAME);
        if (!request.isFile()) return;
        try {
            String name = readRequestName(request);
            int index = indexOf(name);
            if (!validIndex(index)) return;
            File marker = markerFor(state, name);
            if (finalFlag().isFile() && markerValid(marker, name)) {
                deleteQuietly(new File(marker.getPath() + BACKUP_SUFFIX));
                deleteQuietly(request);
                CNLog.i(TAG, "手动重下载已完成并清理请求: " + name);
            }
        } catch (Throwable t) {
            CNLog.w(TAG, "检查手动重下载请求失败: " + t);
        }
    }

    private static final class PrepareTask implements Runnable {
        private final Activity act;
        private final int index;
        PrepareTask(Activity act, int index) { this.act = act; this.index = index; }

        @Override public void run() {
            Mutation mutation = null;
            boolean handedOff = false;
            try {
                CNLog.initEarly();
                recoverCompletedRequest();
                if (CNDownloaderFix.isInstalling()) {
                    throw new IOException("正式安装器正在运行，不能同时安排手动重下载");
                }
                mutation = prepare(index);
                String name = CNCNDownloadUI.FILE_NAMES[index];
                CNCNDownloadUI.updateSimple("重新下载已安排",
                        name + "：即将重启到下载页；其他资源保持不变", 0);
                boolean restarted = CNRestart.restartWithNotice(
                        "将只重新下载 " + name + "，3 秒后重启进入下载页", 3000L);
                if (!restarted) {
                    throw new IOException("重启握手失败，已取消本次重下载安排");
                }
                handedOff = true;
                CNLog.i(TAG, "手动重下载已交给下一进程: index=" + index + " file=" + name);
            } catch (Throwable t) {
                CNLog.e(TAG, "安排手动重下载失败", t);
                rollback(mutation);
                holdPage(true);
                CNCNDownloadUI.updateSimple("无法重新下载", safeMessage(t), 0);
                CNCNDownloadUI.toast(act, "重新下载未开始：" + safeMessage(t));
            } finally {
                if (!handedOff) PREPARING.set(false);
            }
        }
    }

    /** 所有破坏性动作都在验证另外 14 个 marker 后执行。 */
    private static Mutation prepare(int selected) throws IOException {
        if (!validIndex(selected)) throw new IOException("资源编号无效");
        File state = stateRoot();
        if (!state.isDirectory() && !state.mkdirs() && !state.isDirectory()) {
            throw new IOException("无法创建安装状态目录");
        }
        File finalFlag = finalFlag();
        if (!finalFlag.isFile()) {
            throw new IOException("当前安装尚未完整结束，不能进入单文件重下载模式");
        }
        String otherBad = firstInvalidOther(state, selected);
        if (otherBad != null) {
            throw new IOException("无法保证只下载一个文件：" + otherBad + " 的安装标记也不完整");
        }

        String name = CNCNDownloadUI.FILE_NAMES[selected];
        File marker = markerFor(state, name);
        File backup = new File(marker.getPath() + BACKUP_SUFFIX);
        File request = new File(state, REQUEST_NAME);
        String finalContent = readSmall(finalFlag);
        Mutation m = new Mutation(finalFlag, finalContent, marker, backup, request);

        writeAtomic(request, "schema=1\nindex=" + selected + "\nfile=" + name
                + "\ntime=" + System.currentTimeMillis() + "\n");
        try {
            if (backup.exists() && !backup.delete() && backup.exists()) {
                throw new IOException("无法清理旧 marker 备份");
            }
            if (marker.isFile()) {
                moveFile(marker, backup);
                m.markerBackedUp = true;
            }
            if (!finalFlag.delete() && finalFlag.exists()) {
                throw new IOException("无法撤销总完成标记");
            }
            m.finalFlagRemoved = true;
            cleanupArtifacts(fileRoot(), state, selected);
            return m;
        } catch (Throwable t) {
            rollback(m);
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException("准备重下载失败: " + t, t);
        }
    }

    private static final class Mutation {
        final File finalFlag;
        final String finalContent;
        final File marker;
        final File backup;
        final File request;
        boolean markerBackedUp;
        boolean finalFlagRemoved;
        Mutation(File finalFlag, String finalContent, File marker, File backup, File request) {
            this.finalFlag = finalFlag;
            this.finalContent = finalContent;
            this.marker = marker;
            this.backup = backup;
            this.request = request;
        }
    }

    private static void rollback(Mutation m) {
        if (m == null) return;
        try {
            if (m.markerBackedUp && m.backup.isFile() && !m.marker.exists()) {
                moveFile(m.backup, m.marker);
            }
        } catch (Throwable t) {
            CNLog.e(TAG, "恢复所选 marker 失败", t);
        }
        try {
            if (m.finalFlagRemoved && !m.finalFlag.isFile()) {
                writeAtomic(m.finalFlag, m.finalContent.length() == 0
                        ? "schema=2\narchives=15\n" : m.finalContent);
            }
        } catch (Throwable t) {
            CNLog.e(TAG, "恢复总完成标记失败", t);
        }
        deleteQuietly(m.request);
    }

    /** 删除的范围严格限定为所选 ZIP 的下载产物，不碰解压后的活动资源树。 */
    private static void cleanupArtifacts(File root, File state, int index) {
        if (!validIndex(index)) return;
        String name = CNCNDownloadUI.FILE_NAMES[index];
        File archive = new File(root, name);
        deleteQuietly(archive);
        deleteQuietly(new File(archive.getPath() + ".aria2"));
        deleteQuietly(new File(archive.getPath() + ".part"));
        deleteQuietly(new File(archive.getPath() + ".part.meta"));
        deleteQuietly(new File(archive.getPath() + ".part.meta.tmp"));
        deleteQuietly(new File(archive.getPath() + ".tmp"));

        File cpart = CNChunkedDownload.partFileFor(archive);
        File cmeta = CNChunkedDownload.metaFileFor(archive);
        deleteQuietly(cpart);
        deleteQuietly(cmeta);
        deleteQuietly(new File(cmeta.getPath() + ".tmp"));

        File[] siblings = root.listFiles();
        String blockPrefix = cpart.getName() + ".block.";
        if (siblings != null) {
            for (int i = 0; i < siblings.length; i++) {
                File f = siblings[i];
                if (f != null && f.getName().startsWith(blockPrefix)) deleteQuietly(f);
            }
        }

        File offline = new File(new File(state, CNOfflineImport.OFFLINE_DIR), name);
        deleteQuietly(offline);
        deleteQuietly(new File(offline.getPath() + ".importing"));
        CNLog.i(TAG, "已清理所选 ZIP 的下载产物: " + name);
    }

    private static String firstInvalidOther(File state, int selected) {
        String[] names = CNCNDownloadUI.FILE_NAMES;
        if (names == null) return "资源列表不可用";
        for (int i = 0; i < names.length; i++) {
            if (i == selected) continue;
            if (!markerValid(markerFor(state, names[i]), names[i])) return names[i];
        }
        return null;
    }

    private static boolean markerValid(File marker, String name) {
        if (marker == null || !marker.isFile() || marker.length() <= 0 || marker.length() > 16384) {
            return false;
        }
        try {
            String text = readSmall(marker);
            if (!text.contains("schema=1\n")
                    || !text.contains("file=" + name + "\n")
                    || !text.contains("url=" + CANONICAL_BASE + name + "\n")) {
                return false;
            }
            String[] lines = text.split("\\n");
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].startsWith("bytes=")) continue;
                long n = Long.parseLong(lines[i].substring(6).trim());
                return n > 0;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    private static File fileRoot() {
        return new File(CNPaths.filesDir());
    }

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
            if (tmp.exists() && !tmp.equals(target)) deleteQuietly(tmp);
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

    /** 与 UI 辅助类松耦合：升级过程里两份 class 可能短暂不是同一提交。 */
    private static void holdPage(boolean stay) {
        try {
            java.lang.reflect.Method m = CNDownloadUiAssist.class.getDeclaredMethod(
                    "setStayOnPage", boolean.class);
            m.setAccessible(true);
            m.invoke(null, Boolean.valueOf(stay));
        } catch (Throwable t) {
            try { CNCNDownloadUI.noteInteraction(); } catch (Throwable ignore) {}
        }
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "未知错误";
        String s = t.getMessage();
        return s == null || s.length() == 0 ? t.toString() : s;
    }

    // ---- JVM 回归测试入口（不触碰 Android 框架） ----
    public static String firstInvalidOtherForTest(File state, int selected) {
        return firstInvalidOther(state, selected);
    }

    public static boolean markerValidForTest(File marker, String name) {
        return markerValid(marker, name);
    }

    public static void cleanupArtifactsForTest(File root, File state, int index) {
        cleanupArtifacts(root, state, index);
    }
}
