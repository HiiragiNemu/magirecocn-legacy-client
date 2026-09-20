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
 * <p>版本号<b>只有 native 一份</b>（native 侧 {@code CLIENT_VERSION}，由源码显式声明）。
 * CI 出包时只读取这个值作为客户端语义版本；GitHub Run ID/Number 只进入构建旁注，
 * 不参与版本号生成。这里不再存字面量：
 * {@code static final String} 会被 javac <b>内联到每一个引用处</b>，等于把版本号
 * 明文撒进整个 dex，而拿 APK 管理器改包的人第一步就是全局搜这个串。
 * 理由与 native 侧的编译期混淆是同一条，详见那边的注释。
 *
 * <p><b>注意</b>：{@link CNWebProxy} 转发 WebView 游戏流量时<b>不要</b>套用本 UA
 * ——那是游戏自己的请求，透传原始 UA 才不会改变游戏在服务器侧的表现。
 */
public final class CNUserAgent {

    /** 向 native 问到之后就缓存下来；问不到时保持 null，下次再问。 */
    private static volatile String version;

    /**
     * 客户端版本号，向 native 取（唯一来源）。
     *
     * <p>{@link Aria2EngineFailover} 用作「版本变更即重置」的判据：新版本可能修了
     * 后端/换了构建，aria2 的 giveUp 计数不该跨版本继承。
     *
     * <p>native 库要等 {@code Cocos2dxActivity} 起来才加载，早于它的调用会撞
     * {@link UnsatisfiedLinkError}。那时返回<b>空串</b>——UA 里省掉版本段，而不是
     * 编一个假的填进去；调用方看到空串就知道「还不知道」，不会把它当成真版本。
     */
    public static String clientVersion() {
        String v = version;
        if (v != null) return v;
        try {
            v = CNVersionCheck.nativeClientVersion();
        } catch (Throwable ignore) {
            return "";          // 库还没加载，下次再问
        }
        if (v == null || v.isEmpty()) return "";
        version = v;
        return v;
    }

    private static volatile String cached;

    private CNUserAgent() {}

    public static String get() {
        String ua = cached;
        if (ua != null) return ua;
        String v = clientVersion();
        ua = "magireco-cn-legacy/" + (v.isEmpty() ? "unknown" : v)
                + " (Android " + Build.VERSION.RELEASE
                + "; SDK " + Build.VERSION.SDK_INT + ")";
        // 版本还没拿到就先别缓存：否则 unknown 会被钉死到进程结束，
        // 而 native 通常只晚一步就绪。
        if (!v.isEmpty()) cached = ua;
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
