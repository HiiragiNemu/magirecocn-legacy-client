package io.kamihama.magianative;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把日志目录里的启动日志文件合并导出为一个 txt，供「分享日志」走系统分享。
 *
 * <p>直接复制文本会被 QQ 等即时通讯截断，不是人人都会用 Termux/adb 取文件——所以
 * 这里按文件拼：本次启动的日志 + 最近若干次启动的日志一起打成一个文本文件，通过
 * ACTION_SEND 交给任意 App 转发（QQ/微信文件传输对 MB 级文本文件都友好）。
 *
 * <p>导出目录：{@code cacheDir/share/}，配合 {@link CNLogShareProvider}（自定义
 * ContentProvider，编译 classpath 没有 androidx 的 FileProvider）以 read 权限分享
 * ——只开这一个子目录，不外泄 files/ 与存储卡。
 */
public final class CNLogBundle {
    private static final String TAG = "CNLogBundle";
    /** 合并最近的多少次启动日志。 */
    private static final int MAX_FILES = 5;
    /** 单个日志文件最多读入的字节数（防异常暴涨的日志打爆分享包）。 */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    /** 整个分享包上限。 */
    private static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024;

    private CNLogBundle() {}

    /**
     * 把 {@code logDir} 里的 {@code NNNN_yyyyMMdd-HHmmss.log} 按名字倒序（新→旧）
     * 合并写入 {@code cacheDir/share/magireco_cn_log.txt}。写不出/没日志返回 null。
     */
    public static File write(Context ctx, String logDirPath) {
        try {
            if (ctx == null || logDirPath == null) return null;
            File dir = new File(logDirPath);
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) return null;

            java.util.List<File> logs = new java.util.ArrayList<File>();
            for (File f : files) {
                String n = f.getName();
                if (f.isFile() && n.endsWith(".log")
                        && n.matches("\\d{4,}_\\d{8}-\\d{6}\\.log")) {
                    logs.add(f);
                }
            }
            if (logs.isEmpty()) return null;
            // 名字 = 启动序号_时间戳。字典序只在序号**等宽**时等于时间序（File
            // 的 Comparable 是名字字典序）；序号到 10000 后成了 5 位，9999 会被
            // 排到 10001 后面，「最近 N 次」就选错了。这里把序号补零到定宽再比，
            // 字典序即序号序。刻意不用 Comparator——泛型 Comparator 会踩 d8 的坑
            // （CLAUDE.md 铁律 4），补零后的字符串自然序是纯 String 比较，安全。
            java.util.List<String> order = new java.util.ArrayList<String>(logs.size());
            java.util.Map<String, File> byKey = new java.util.HashMap<String, File>();
            for (File f : logs) {
                String n = f.getName();
                String seq = n.substring(0, n.indexOf('_'));
                // 左补零到 10 位：10^10 次启动才可能溢出，而那时个位序也不影响
                // 相对顺序（10 位内已按数字序排好）。
                String padded = ("0000000000" + seq);
                padded = padded.substring(padded.length() - 10);
                String key = padded + n.substring(n.indexOf('_'));
                byKey.put(key, f);
                order.add(key);
            }
            java.util.Collections.sort(order);    // 升序 = 旧→新
            java.util.Collections.reverse(order); // 新→旧
            java.util.List<File> newest = new java.util.ArrayList<File>(MAX_FILES);
            for (String key : order) {
                newest.add(byKey.get(key));
                if (newest.size() >= MAX_FILES) break;
            }
            logs = newest;

            File outDir = new File(ctx.getCacheDir(), "share");
            if (!outDir.isDirectory() && !outDir.mkdirs() && !outDir.isDirectory()) {
                CNLog.w(TAG, "无法创建分享目录: " + outDir);
                return null;
            }
            File out = new File(outDir, "magireco_cn_log.txt");
            OutputStream os = new BufferedOutputStream(new FileOutputStream(out));
            try {
                StringBuilder head = new StringBuilder();
                head.append("========== 魔纪中文化下载日志 ==========\n");
                head.append("导出时间：")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                            Locale.US).format(new Date())).append('\n');
                head.append("日志目录：").append(logDirPath).append('\n');
                head.append("本次启动：第 ").append(CNLog.launchSeq()).append(" 次\n");
                head.append("共 ").append(logs.size()).append(" 份日志（新→旧）\n\n");
                os.write(head.toString().getBytes("UTF-8"));

                long total = 0L;
                for (File f : logs) {
                    byte[] sep = ("\n\n──────── " + f.getName()
                            + " ────────\n").getBytes("UTF-8");
                    if (total + sep.length > MAX_TOTAL_BYTES) break;
                    os.write(sep);
                    total += sep.length;
                    long got = copyBounded(f, os, MAX_FILE_BYTES);
                    total += got;
                    if (total >= MAX_TOTAL_BYTES) break;
                }
            } finally {
                try { os.close(); } catch (Throwable ignore) {}
            }
            if (out.length() == 0) {
                // 空包没有分享价值
                try { out.delete(); } catch (Throwable ignore) {}
                return null;
            }
            CNLog.i(TAG, "日志打包完成: " + out.getAbsolutePath()
                    + " (" + out.length() + " 字节)");
            return out;
        } catch (Throwable t) {
            CNLog.e(TAG, "日志打包失败", t);
            return null;
        }
    }

    /** 把文件内容拷到 os，最多 {@code max} 字节；返回实际写入字节数。 */
    private static long copyBounded(File f, OutputStream os, long max) {
        try {
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[1 << 16];
                long done = 0L;
                int n;
                while (done < max && (n = in.read(buf)) != -1) {
                    int take = (int) Math.min((long) n, max - done);
                    os.write(buf, 0, take);
                    done += take;
                }
                return done;
            } finally {
                try { in.close(); } catch (Throwable ignore) {}
            }
        } catch (Throwable t) {
            CNLog.w(TAG, "读取日志文件失败 " + f.getName() + ": " + t);
            return 0L;
        }
    }
}
