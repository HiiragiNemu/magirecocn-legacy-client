package io.kamihama.magianative;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

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
     * 是否为热更包文件名（cn_scenario_update.zip / cn_js_update.zip）。
     *
     * <p>热更走版本 json（version_*.json 的 size/md5）通道，不该离线导入；
     * 离线导入只管 13 个基础资源包。列表/导入入口用这个方法把热更两包排除。
     */
    public static boolean isHotUpdateFile(String name) {
        if (name == null) return false;
        return "cn_scenario_update.zip".equals(name)
                || "cn_js_update.zip".equals(name);
    }

    /**
     * 拷贝玩家选中的 URI 到离线区并做分块校验。成功返回导入后的文件（已校验），
     * 失败返回 null 并清理临时文件。
     */
    public static File importZip(Context ctx, Uri uri, String fileName) throws Exception {
        if (ctx == null || uri == null || fileName == null) return null;
        // 热更包（cn_scenario_update.zip / cn_js_update.zip）走版本 json 通道，
        // 不该离线导入——直接拒收（见 isHotUpdateFile）。
        if (isHotUpdateFile(fileName)) {
            CNLog.w(TAG, "热更包不支持离线导入: " + fileName);
            return null;
        }
        File dir = offlineDir();
        File target = new File(dir, fileName);

        // 上一次导入被中途杀掉留下的半截文件。它们跟目标同名加后缀，hasOffline
        // 看不见，于是既不会被用上、也永远没人删——而这类文件动辄一两个 G。
        sweepStaleTemps(dir, fileName);

        // 先看装不装得下。导入是**再拷一份**：玩家自己下的那份还在（多半在下载
        // 目录里），我们又要在私有区放一份等大的。不预检的话，拷到最后几十兆才
        // ENOSPC，前面几十分钟白费，而且半截文件还留在盘上。
        long need = sizeOf(ctx, uri);
        if (need > 0) CNDiskSpace.require(dir, need, fileName + " 导入");

        // TOCTOU 防护：一次性拷到临时文件，后续校验/解压都读它，避免 ContentProvider
        // 两次 openInputStream 返回不同字节。
        File tmp = new File(dir, fileName + ".importing");
        try (InputStream src = ctx.getContentResolver().openInputStream(uri);
             OutputStream dst = new FileOutputStream(tmp)) {
            if (src == null) throw new java.io.IOException("无法打开所选文件");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = src.read(buf)) != -1) dst.write(buf, 0, n);
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
        if (!CNArchiveValidate.verifyChunks(tmp, hashes)) {
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
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
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
