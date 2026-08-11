package io.kamihama.magianative;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;

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
 */
public final class CNOfflineImport {
    private static final String TAG = "CNOfflineImport";
    /** 离线区相对 STATE_ROOT 的子目录名。 */
    public static final String OFFLINE_DIR = "offline";
    private static final long CHUNK = 16L * 1024 * 1024;

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
     * 拷贝玩家选中的 URI 到离线区并做分块校验。成功返回导入后的文件（已校验），
     * 失败返回 null 并清理临时文件。
     */
    public static File importZip(Context ctx, Uri uri, String fileName) throws Exception {
        if (ctx == null || uri == null || fileName == null) return null;
        File dir = offlineDir();
        File target = new File(dir, fileName);

        // TOCTOU 防护：一次性拷到临时文件，后续校验/解压都读它，避免 ContentProvider
        // 两次 openInputStream 返回不同字节。
        File tmp = new File(dir, fileName + ".importing");
        try (InputStream src = ctx.getContentResolver().openInputStream(uri);
             OutputStream dst = new FileOutputStream(tmp)) {
            if (src == null) throw new java.io.IOException("无法打开所选文件");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = src.read(buf)) != -1) dst.write(buf, 0, n);
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
        if (!verifyChunks(tmp, hashes)) {
            CNLog.w(TAG, "离线包分块校验失败: " + fileName);
            deleteQuietly(tmp);
            return null;
        }

        // 校验通过：改名就位
        if (target.exists() && !target.delete() && target.exists()) {
            CNLog.w(TAG, "无法替换旧离线包: " + target);
        }
        if (!tmp.renameTo(target)) {
            CNLog.w(TAG, "离线包改名失败: " + tmp);
            deleteQuietly(tmp);
            return null;
        }
        CNLog.i(TAG, "离线包导入成功: " + fileName + " (" + target.length() + " 字节)");
        return target;
    }

    /** 按 16MB 块逐块算 md5，与清单比对。公开供测试复用。 */
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

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            try { f.delete(); } catch (Throwable ignore) {}
        }
    }
}
