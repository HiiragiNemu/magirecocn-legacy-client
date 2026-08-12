package io.kamihama.magianative;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * 非热更（基础资源包）的下载/导入校验。
 *
 * <p>与 {@link CNHotUpdateValidate} 区分：热更包（cn_scenario_update.zip /
 * cn_js_update.zip）走版本 json 的 size/md5 校验，放另一个文件；这里收的是
 * 基础包的两种校验：
 * <ul>
 *   <li>{@link #isZipStructurallyValid}：zip 结构预检（EOCD+CEN 可读）——分片跨
 *       镜像/断点续传可能把异源字节混进同一文件，凑满即坏，promote 前拦一道。</li>
 *   <li>{@link #verifyChunks}：按 16MB 块逐一比 {@link CNChunkedDownload.ChunkHashes}
 *       指纹——离线导入包（与在线下载同源）的完整性验证。</li>
 * </ul>
 *
 * <p>纯静态工具类，编译 classpath 只有 android.jar + OkHttp/Okio，无第三方依赖。
 */
public final class CNArchiveValidate {
    private static final String TAG = "CNArchiveValidate";

    private CNArchiveValidate() {}

    /**
     * zip 结构预检：能打开 {@link ZipFile}（EOCD+中央目录可解析）即视为结构合法。
     * 不逐条读文件，对 GB 级大包也是毫秒级；坏则让上层整份作废重下。
     */
    public static boolean isZipStructurallyValid(File f) {
        try (ZipFile zf = new ZipFile(f)) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 按 16MB 块逐块算 md5 与清单比对。公开供测试复用。
     *
     * @return true 表示每个完整块都通过；长度/指纹不符返回 false。
     */
    public static boolean verifyChunks(File f, CNChunkedDownload.ChunkHashes h) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        long off = 0L;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) h.chunkSize];
            int n;
            while ((n = in.read(buf)) >= 0 && off < h.total) {
                if (n == 0) continue;
                int want = (int) Math.min((long) n, h.chunkSize);
                md.update(buf, 0, want);
                long blkEnd = off + want;
                if (blkEnd == off + h.chunkSize || blkEnd == h.total) {
                    String got = hex(md.digest());
                    String exp = h.hashFor(off, blkEnd);
                    if (exp == null || !exp.equalsIgnoreCase(got)) {
                        CNLog.w(TAG, "块校验失败 offset=" + off
                                + " 期望=" + (exp == null ? "?" : exp) + " 实得=" + got);
                        return false;
                    }
                    off = blkEnd;
                    md = MessageDigest.getInstance("MD5");
                }
            }
        }
        return off == h.total;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }
}
