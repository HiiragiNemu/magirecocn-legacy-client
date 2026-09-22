package io.kamihama.magianative;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 离线包注入：把玩家手动选择的官方 zip 拷到私有离线区，用 16MB 分块清单
 * （manifest.json）校验后标记「离线就位」。installArchive 下载前检查离线区，
 * 有对应文件则跳过网络下载、直接解压。
 *
 * <p>离线区目录：{@code <files>/madomagi/magica/.cn_installer/r128-downloader-v1/offline/}
 * 与安装器状态目录同根。文件名必须与 {@code FILE_NAMES} 匹配（如 cn_base_03.zip）。
 *
 * <p>校验：用 {@link ChunkManifest.forFile(name)} 的块指纹逐块比对——与在线下载
 * 同源，保证导入的是官方发布版。清单缺失/校验失败即拒收并删除临时文件。
 *
 * <p><b>进度与重入</b>：拷贝 1GB+ 的包要几十秒，{@link #importZip} 的拷贝循环会
 * 逐 4MB 回调 {@link Progress}，调用方必须把它显示出来，否则玩家看到的是「选完
 * 文件后界面定住」，极可能再导一次——两个导入线程会并发写同一个 {@code .importing}
 * 临时文件、互相覆盖，{@code sweepStaleTemps} 还会把正在导的残留误删。因此
 * {@code importZip} 开始时置 {@link #IMPORTING} 互斥位，重复请求直接拒绝。
 */
public final class CNOfflineImport {
    private static final String TAG = "CNOfflineImport";
    /** 离线区相对 STATE_ROOT 的子目录名。 */
    public static final String OFFLINE_DIR = "offline";
    private static final long CHUNK = 16L * 1024 * 1024;
    /** 拷贝进度回调粒度（字节），约 4MB 一次，避免高频回调刷爆主线程。 */
    private static final long PROGRESS_STEP = 4L * 1024 * 1024;

    /**
     * 拷贝/校验进度。拷贝阶段按字节上报（{@code total} 是 URI 声明大小，可能为
     * -1 未知）；校验阶段 {@code phase} 置 true 且 {@code done} 为已校验块数。
     */
    public interface Progress {
        /** @param done 已完成字节数（拷贝）/ 已完成块数（校验）
         *  @param total 总字节数 / 总块数；拷贝时可能为 -1（未知）
         *  @param verifying true = 已进入分块校验阶段 */
        void onProgress(long done, long total, boolean verifying);
    }

    /** 全局互斥：同一时刻只允许一个导入在进行，防并发写同一 {@code .importing}。 */
    private static final AtomicBoolean importing = new AtomicBoolean(false);

    /** 是否有导入正在进行（供 UI 禁用重复入口）。 */
    public static boolean isImporting() { return importing.get(); }

    private CNOfflineImport() {}

    /** 离线区目录（不存在时创建）。 */
    public static File offlineDir() {
        String stateRoot = CNPaths.filesDir() + "/madomagi/magica/.cn_installer/r128-downloader-v1";
        File dir = new File(stateRoot, OFFLINE_DIR);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            CNLog.w(TAG, "无法创建离线区: " + dir);
        }
        return dir;
    }

    /** 某文件的离线包是否已就位（文件存在且长度>0）。 */
    public static boolean hasOffline(String fileName) {
        if (fileName == null) return false;
        File f = new File(offlineDir(), fileName);
        return f.isFile() && f.length() > 0;
    }

    /**
     * 是否为热更包文件名（cn_scenario_update.zip / cn_js_update.zip）。
     *
     * <p>热更走版本 json（version_*.json 的 size/md5）通道，不该离线导入；
     * 离线导入只管 13 个基础资源包。列表/导入入口用这个方法把热更两包排除。
     */
    public static boolean isHotUpdateFile(String name) {
        if (name == null) return false;
        return "cn_scenario_update.zip".equals(name)
                || "cn_js_update.zip".equals(name)
                || "cn_js_delta.zip".equals(name);
    }

    /**
     * 拷贝玩家选中的 URI 到离线区并做分块校验。成功返回导入后的文件（已校验），
     * 失败返回 null 并清理临时文件。
     *
     * <p><b>重入保护</b>：同一时刻只允许一个导入。已有导入在进行时立即返回
     * null（调用方据此提示「上一个导入还没完成」），避免两个线程并发写同一个
     * {@code .importing} 临时文件、以及 {@code sweepStaleTemps} 误删正在导的残留。
     *
     * @param progress 进度回调（可为 null）。拷贝阶段每约 4MB 回调一次；
     *                 校验阶段按块回调。
     */
    public static File importZip(Context ctx, Uri uri, String fileName,
                                 Progress progress) throws Exception {
        if (ctx == null || uri == null || fileName == null) return null;
        // 热更包（cn_scenario_update.zip / cn_js_update.zip）走版本 json 通道，
        // 不该离线导入——直接拒收（见 isHotUpdateFile）。
        if (isHotUpdateFile(fileName)) {
            CNLog.w(TAG, "热更包不支持离线导入: " + fileName);
            return null;
        }
        // CAS：检查与占用原子完成，volatile 版「先查再赋值」会被两个线程同时穿过。
        if (!importing.compareAndSet(false, true)) {
            CNLog.w(TAG, "已有导入在进行，拒绝重复导入: " + fileName);
            return null;
        }
        try {
            return importZipLocked(ctx, uri, fileName, progress);
        } finally {
            importing.set(false);
        }
    }

    /** {@link #importZip} 的持锁实现。 */
    private static File importZipLocked(Context ctx, Uri uri, String fileName,
                                        Progress progress) throws Exception {
        File dir = offlineDir();
        File target = new File(dir, fileName);

        // 上一次导入被中途杀掉留下的半截文件。它们跟目标同名加后缀，hasOffline
        // 看不见，于是既不会被用上、也永远没人删——而这类文件动辄一两个 G。
        // 有 importing 互斥位在，这里删的一定不是「正在导」的那份。
        sweepStaleTemps(dir, fileName);

        // 先看装不装得下。导入是**再拷一份**：玩家自己下的那份还在（多半在下载
        // 目录里），我们又要在私有区放一份等大的。不预检的话，拷到最后几十兆才
        // ENOSPC，前面几十分钟白费，而且半截文件还留在盘上。
        long need = sizeOf(ctx, uri);
        if (need > 0) CNDiskSpace.require(dir, need, fileName + " 导入");

        // TOCTOU 防护：一次性拷到临时文件，后续校验/解压都读它，避免 ContentProvider
        // 两次 openInputStream 返回不同字节。
        File tmp = new File(dir, fileName + ".importing");
        long total = sizeOf(ctx, uri);
        try (InputStream src = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream dst = new FileOutputStream(tmp)) {
            if (src == null) throw new java.io.IOException("无法打开所选文件");
            byte[] buf = new byte[1 << 16];
            long done = 0L;
            long lastReport = 0L;
            int n;
            while ((n = src.read(buf)) != -1) {
                dst.write(buf, 0, n);
                done += n;
                if (progress != null && done - lastReport >= PROGRESS_STEP) {
                    lastReport = done;
                    progress.onProgress(done, total, false);
                }
            }
            if (progress != null) progress.onProgress(done, total, false);
            // F-026：内容落盘后再进入替换，掉电/被杀不会留下「写了一半的完整文件」。
            dst.flush();
            dst.getFD().sync();
        } catch (Throwable t) {
            // 拷贝失败必须把半截文件带走，否则它就是下一个「永远没人删」。
            deleteQuietly(tmp);
            throw t;
        }

        // 分块校验：逐块算 md5 与清单比对
        CNChunkedDownload.ChunkHashes hashes = ChunkManifest.forFile(fileName);
        if (hashes == null || hashes.count == 0) {
            CNLog.w(TAG, "无清单或清单空，离线包拒收: " + fileName);
            deleteQuietly(tmp);
            return null;
        }
        if (tmp.length() != hashes.total) {
            CNLog.w(TAG, "离线包大小不符 " + tmp.length() + " != " + hashes.total
                    + " : " + fileName);
            deleteQuietly(tmp);
            return null;
        }
        if (!verifyChunksWithProgress(tmp, hashes, progress)) {
            CNLog.w(TAG, "离线包分块校验失败: " + fileName);
            deleteQuietly(tmp);
            return null;
        }

        // F-026：校验通过后以 Linux rename(2) 原子替换。绝不先删旧目标——
        // 旧代码「先 delete 再 rename」在两步之间被杀，目标与临时文件都消失，
        // 旧离线包（最后一次可用状态）就此丢失。Os.rename 同一目录要么完整
        // 替换，要么保持旧目标不变。
        if (!commitImportedFile(tmp, target, dir)) {
            return null;
        }
        CNLog.i(TAG, "离线包导入成功: " + fileName + " (" + target.length() + " 字节)");
        return target;
    }

    /**
     * 以 Linux rename(2) 原子替换已验证离线包，并同步父目录。
     *
     * @return true = 新文件已落盘且目录项已同步；false = 替换失败（旧目标保持
     *         不变）或目录同步失败（本次保守不报成功，新文件留待下次重新校验）
     */
    private static boolean commitImportedFile(File tmp, File target, File dir) {
        try {
            android.system.Os.rename(tmp.getAbsolutePath(), target.getAbsolutePath());
            try {
                CNArchiveInstallTx.syncDirOrThrow(dir);
            } catch (Throwable t) {
                // rename 已发生，无法安全「撤回」为旧文件；但目录项未确认落盘时
                // 绝不能向上层报告导入成功。调用方保守失败，新目标留作下次重验。
                CNLog.w(TAG, "离线包已原子替换，但父目录同步失败；本次不报告成功: " + t);
                return false;
            }
            return target.isFile() && target.length() > 0;
        } catch (Throwable t) {
            CNLog.w(TAG, "离线包原子替换失败，旧目标保持不变: " + t);
            return false;
        }
    }


    /** {@link CNArchiveValidate#verifyChunks} 的带进度版本。 */
    private static boolean verifyChunksWithProgress(File f,
            CNChunkedDownload.ChunkHashes h, Progress progress) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                byte[] buf = new byte[1 << 16];
                long offset = 0L;
                long lastReport = 0L;
                for (int block = 0; block < h.count; block++) {
                    long blockLen = Math.min(h.chunkSize, h.total - offset);
                    java.security.MessageDigest md =
                            java.security.MessageDigest.getInstance("MD5");
                    long read = 0L;
                    while (read < blockLen) {
                        int want = (int) Math.min((long) buf.length, blockLen - read);
                        int n = in.read(buf, 0, want);
                        if (n < 0) return false;
                        if (n == 0) continue;
                        md.update(buf, 0, n);
                        read += n;
                    }
                    long end = offset + blockLen;
                    String got = hex(md.digest());
                    String exp = h.hashFor(offset, end);
                    if (exp == null || !exp.equalsIgnoreCase(got)) {
                        CNLog.w(TAG, "块校验失败 offset=" + offset
                                + " 期望=" + (exp == null ? "?" : exp) + " 实得=" + got);
                        return false;
                    }
                    offset = end;
                    if (progress != null && block - lastReport >= 4) {
                        lastReport = block;
                        progress.onProgress(block + 1L, h.count, true);
                    }
                }
                if (progress != null) progress.onProgress(h.count, h.count, true);
                return offset == h.total && in.read() < 0;
            } finally {
                CNIo.closeQuietly(in);
            }
        } catch (Throwable t) {
            CNLog.w(TAG, "分块校验异常: " + t);
            return false;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }

    /** 所选内容的字节数；取不到返回 -1（此时不预检，照旧拷，写满才失败）。 */
    private static long sizeOf(Context ctx, Uri uri) {
        android.database.Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Throwable ignore) {
        } finally {
            CNIo.closeQuietly(c);
        }
        return -1L;
    }

    /** 清掉本文件遗留的 {@code *.importing} 半截产物。 */
    private static void sweepStaleTemps(File dir, String fileName) {
        try {
            File stale = new File(dir, fileName + ".importing");
            if (stale.isFile()) {
                CNLog.w(TAG, "清理上次未完成的导入残留: " + stale.getName()
                        + "（" + stale.length() + " 字节）");
                deleteQuietly(stale);
            }
        } catch (Throwable ignore) {}
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            try { f.delete(); } catch (Throwable ignore) {}
        }
    }
}
