package io.kamihama.magianative;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 热更包（cn_scenario_update.zip / cn_js_update.zip）的下载完工校验。
 *
 * <p>与 {@link CNArchiveValidate} 区分：热更包有版本 json 下发的 size/md5，
 * 下载后按它逐字节核对才放行；基础包没有 md5/size，走结构预检 + 分块指纹。
 * 只有热更的版本 json 带 size/md5 三元组，所以这套校验是热更专属。
 *
 * <p>纯静态工具类，编译 classpath 只有 android.jar + OkHttp/Okio。
 */
public final class CNHotUpdateValidate {
    private CNHotUpdateValidate() {}

    /**
     * 版本 json 的三元组：version 用来比对，size/md5 用于下载后的完工校验。
     */
    public static final class VerMeta {
        public final int    version;
        public final long   size;
        public final String md5;
        public VerMeta(int v, long s, String m) { version = v; size = s; md5 = m; }
    }

    /**
     * 下载完工校验：size 对得上、md5 对得上才放行；返回 null 表示通过。
     */
    public static String verifyZip(File f, VerMeta meta) {
        if (meta == null || f == null || !f.isFile()) return "文件缺失";
        if (meta.size > 0 && f.length() != meta.size) {
            return "大小不符 " + f.length() + " != " + meta.size;
        }
        if (meta.md5 != null && meta.md5.length() > 0) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                InputStream in = new BufferedInputStream(new FileInputStream(f), 65536);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
                in.close();
                StringBuilder sb = new StringBuilder(32);
                for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
                if (!meta.md5.equalsIgnoreCase(sb.toString())) {
                    return "md5 不符";
                }
            } catch (Throwable t) {
                return "md5 计算失败: " + t;
            }
        }
        return null;
    }
}
