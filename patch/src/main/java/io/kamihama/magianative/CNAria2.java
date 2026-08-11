package io.kamihama.magianative;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * libaria2（aria2 库）的 JNI 包装——备用下载引擎。
 *
 * <p>主分块下载器（{@link CNChunkedDownload}）修不动时兜底：单个 URL 同步下载
 * 到本地，走 aria2 的多连接 + 断点续传。默认<b>不启用</b>——只有 debug 开关
 * {@link CNDebugFlags#USE_ARIA2} 打开时才被 {@link CNDownloaderFix} 调用。
 *
 * <p>加载：{@code System.loadLibrary("aria2")} 惰性加载 {@code libaria2.so}
 * （APK 里 {@code lib/<abi>/libaria2.so}，hk 机器交叉编译的预置二进制，非 CI
 * 构建）。加载失败只让 {@link #isAvailable()} 返回 false，不影响主流程。
 * JNI 桥接源码在 {@code magia-native/src/aria2_jni.cpp}。
 *
 * <p>线程：{@link #download} 是阻塞调用，必须在后台线程执行；进度回调
 * {@link Progress#onProgress} 在下载线程上触发，实现方自行切 UI 线程。
 */
public final class CNAria2 {
    /** 下载进度回调（下载线程上触发；total=0 表示未知）。 */
    public interface Progress {
        void onProgress(long done, long total);
    }

    /** 成功。 */
    public static final int OK           = 0;
    /** addUri 失败（URL 无效等）。 */
    public static final int ERR_ADD      = 1;
    /** 下载出错（aria2 报 error 事件）。 */
    public static final int ERR_DOWNLOAD = 2;
    /** run 循环异常退出。 */
    public static final int ERR_RUN      = 3;
    /** 被取消。 */
    public static final int CANCELLED    = -1;
    /** 初始化 / session 创建失败。 */
    public static final int ERR_INIT     = -2;
    /** 其它。 */
    public static final int ERR_OTHER    = -3;
    /** 已有下载在跑（串行化冲突）。 */
    public static final int ERR_BUSY     = -4;

    private static final String TAG = "CNAria2";

    private static final boolean loaded;
    static {
        boolean ok = false;
        try {
            System.loadLibrary("aria2");
            ok = nativeAvailable();
        } catch (Throwable t) {
            CNLog.w(TAG, "libaria2 加载失败，备用引擎不可用: " + t);
        }
        loaded = ok;
    }

    private CNAria2() {}

    /** 备用引擎是否可用（lib 能加载且 JNI 符号齐全）。 */
    public static boolean isAvailable() {
        return loaded;
    }

    /** 原生就绪性（loadLibrary 成功即 true）。 */
    private static native boolean nativeAvailable();

    /**
     * 同步下载单个 URL 到 {@code outDir/outName}。阻塞直到完成/出错/取消，
     * 必须在后台线程调用。同一时刻只允许一个下载。
     *
     * @param url      直链（下载源）
     * @param outDir   输出目录（须已存在的绝对路径）
     * @param outName  输出文件名
     * @param ua       User-Agent（可为 null）
     * @param referer  Referer（可为 null）
     * @param headers  附加请求头（"Name: value" 数组，可为 null）
     * @param maxConns 每服务器连接数（1-16；越界自动忽略）
     * @param proxy    代理 URL（可为 null 表示直连）
     * @param progress 进度回调（可为 null）
     * @param cancel   置 true 即取消（可为 null）
     * @return {@link #OK} 或错误码
     */
    public static native int download(String url, String outDir, String outName,
            String ua, String referer, String[] headers, int maxConns,
            String proxy, Progress progress, AtomicBoolean cancel);
}
