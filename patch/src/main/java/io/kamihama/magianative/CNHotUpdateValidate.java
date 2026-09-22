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
    private static final String TAG = "MagiaCNHotUpdate";

    private CNHotUpdateValidate() {}

    /**
     * 版本 json 的三元组：version 用来比对，size/md5 用于下载后的完工校验。
     */
    public static final class VerMeta {
        public final int    version;
        public final long   size;
        public final String md5;
        public final String sourceBase;
        /** Only publishers that returned this exact version/size/digest this round. */
        public final java.util.List<String> sourceBases;
        public VerMeta(int v, long s, String m) { this(v,s,m,null); }
        public VerMeta(int v, long s, String m, String source) {
            this(v,s,m,source,source==null ? null : java.util.Collections.singletonList(source));
        }
        public VerMeta(int v, long s, String m, String source, java.util.List<String> sources) {
            version=v; size=s; md5=m; sourceBase=source;
            sourceBases=sources==null ? null : java.util.Collections.unmodifiableList(
                    new java.util.ArrayList<String>(sources));
        }
    }

    /**
     * 下载完工校验：size 对得上、md5 对得上才放行；返回 null 表示通过。
     *
     * <p><b>F-B-07（fail-closed）</b>：size 与 md5 <b>都缺</b>时不再放行。
     * 原先两道校验各自「缺字段就跳过」，两者皆缺就直接 return null——
     * 「校验通过」与「根本没有校验」在日志与 UI 上无法区分，version JSON
     * 格式漂移/服务端漏发字段后，热更包（要写进拦截层本地优先目录的可执行
     * JS）的完整性校验会整体静默消失。取舍：宁可因配置事故误拒一轮热更
     * （下次启动还会重试），也不让未校验的字节流产出「完工」结论。
     */
    public static String verifyZip(File f, VerMeta meta) {
        if (meta == null || f == null || !f.isFile()) return "文件缺失";
        boolean hasSize = meta.size > 0;
        boolean hasMd5 = meta.md5 != null && meta.md5.length() > 0;
        if (!hasSize && !hasMd5) {
            // 校验无法进行 = 不通过。记 WARN 让「这轮没有完整性校验」在日志里
            // 可见，而不是和「校验通过」长得一模一样。
            CNLog.w(TAG, "version JSON 缺 size/md5，完工校验无法进行，按失败处理: "
                    + f + " version=" + meta.version);
            return "version JSON 缺少 size/md5，完工校验无法进行";
        }
        if (hasSize && f.length() != meta.size) {
            return "大小不符 " + f.length() + " != " + meta.size;
        }
        if (hasMd5) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                InputStream in = new BufferedInputStream(new FileInputStream(f), 65536);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
                } finally {
                    // R4-01：流在 finally 关——md.update 中途抛异常（文件读取中断、
                    // IO 错误）时也保证 FD 归还，否则每次 md5 中断就漏一个描述符。
                    CNIo.closeQuietly(in);
                }
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
