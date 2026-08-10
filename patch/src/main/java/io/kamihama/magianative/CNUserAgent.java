package io.kamihama.magianative;

import android.os.Build;

import java.net.HttpURLConnection;

/**
 * 补丁侧统一 User-Agent。补丁发起的所有 HTTP 请求（镜像测速、config 拉取、
 * 热更下载、分片下载、版本检查）都带上它，CDN/服务端日志才能据此识别
 * 「这是我们的客户端、哪个版本」；不设时 Android 会填系统默认的
 * {@code Dalvik/2.1.0 (Linux; U; Android …)}，既随设备变，也没法和普通
 * 流量区分开。
 *
 * <p>UA 形如：
 * <pre>magireco-cn-legacy/1.0.86 (Android 13; SDK 33)</pre>
 *
 * <p>版本号与 native 侧 {@code CLIENT_VERSION} 同源：CI 出包时由
 * build-apk.yml 注入（1.0.&lt;run_number&gt;）；本文件里的字面量只是
 * build-local.sh 本地构建的兜底。
 *
 * <p><b>注意</b>：{@link CNWebProxy} 转发 WebView 游戏流量时<b>不要</b>套用本 UA
 * ——那是游戏自己的请求，透传原始 UA 才不会改变游戏在服务器侧的表现。
 */
public final class CNUserAgent {

    /** CI 注入点：build-apk.yml 按 CLIENT_VERSION 同样式 sed 替换。 */
    private static final String CLIENT_VERSION = "1.0.0";

    private static volatile String cached;

    private CNUserAgent() {}

    public static String get() {
        String ua = cached;
        if (ua == null) {
            ua = "magireco-cn-legacy/" + CLIENT_VERSION
                    + " (Android " + Build.VERSION.RELEASE
                    + "; SDK " + Build.VERSION.SDK_INT + ")";
            cached = ua;
        }
        return ua;
    }

    /**
     * 在连接上写入统一 UA。任何异常一律吞掉——UA 只是管理标识，
     * 绝不能反过来影响请求本身。
     */
    public static void apply(HttpURLConnection c) {
        try { c.setRequestProperty("User-Agent", get()); } catch (Throwable ignore) {}
    }
}
