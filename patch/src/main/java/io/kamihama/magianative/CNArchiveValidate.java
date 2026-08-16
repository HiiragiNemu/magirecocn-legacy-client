package io.kamihama.magianative;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipFile;

/** 基础资源包的 ZIP 结构与分块清单校验。 */
public final class CNArchiveValidate {
    private static final String TAG = "CNArchiveValidate";

    private CNArchiveValidate() {}

    public static boolean isZipStructurallyValid(File f) {
        // 用内置 libcnzip（libarchive JNI）校验，替代 java.util.zip.ZipFile：资源包含「冗余 ZIP64」，
        // 老设备 ZipFile 可能报「结构非法」（见 CNZipTool 的说明）。libcnzip 不可用时
        // 回退 ZipFile（结构校验失败总比误拒好——旧路径在 libcnzip 缺失时仍可用）。
        if (f == null || !f.isFile()) return false;
        if (CNZipTool.isAvailable()) {
            return CNZipTool.isValid(f);
        }
        try (ZipFile zf = new ZipFile(f)) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 按清单块边界完整读取并计算 MD5。不能把一次 InputStream.read() 当成一整块：
     * read 允许短返回，旧实现因此可能把同一个 16MiB 块错误切成多个“小块”校验。
     */
    public static boolean verifyChunks(File f, CNChunkedDownload.ChunkHashes h) throws Exception {
        if (f == null || h == null || h.chunkSize <= 0 || h.total < 0
                || f.length() != h.total) return false;
        long expectedCount = h.total / h.chunkSize + (h.total % h.chunkSize == 0 ? 0 : 1);
        if (expectedCount != h.count) return false;

        byte[] buf = new byte[1 << 16];
        long offset = 0L;
        try (InputStream in = new FileInputStream(f)) {
            for (int block = 0; block < h.count; block++) {
                long blockLen = Math.min(h.chunkSize, h.total - offset);
                MessageDigest md = MessageDigest.getInstance("MD5");
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
            }
            return offset == h.total && in.read() < 0;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }
}
