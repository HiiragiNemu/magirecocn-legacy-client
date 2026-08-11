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
            // 名字里带启动序号与时间戳，字典序即时间序（File 实现 Comparable，
            // 直接用自然排序，不用 Comparator——泛型 Comparator 会踩 d8 的坑，
            // 见 CLAUDE.md 铁律 4）。升序后 reverse 成新→旧。
            java.util.Collections.sort(logs);
            java.util.Collections.reverse(logs);
            if (logs.size() > MAX_FILES) {
                logs = new java.util.ArrayList<File>(
                        logs.subList(0, MAX_FILES));
            }

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
