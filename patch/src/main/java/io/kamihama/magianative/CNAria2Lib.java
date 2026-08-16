/*
 * Copyright (C) 2024-2026 MagirecoCN-Revival-Project
 *
 * CNAria2Lib — 进程内 aria2 下载引擎（libaria2c.so，JNI 加载）。
 *
 * 控制面仍是 loopback JSON-RPC，但 aria2 以线程方式跑在调用进程内，不再 exec
 * 二进制。规避：
 *  1) SELinux exec 闸（targetSdk>=29 时 untrusted_app 不得执行 app_data_file）；
 *  2) 打包 aria2c 二进制的 16KB 页对齐问题（p_align=0x1000 在 Android 15+ 16KB 设备上起不来）。
 *
 * 历史：最早一版 JNI（libaria2.so）把 OpenSSL 符号以 GLOBAL + JUMP_SLOT 暴露，
 * 被进程内其他 libssl/libcrypto 抢占污染而崩。本构建做了彻底符号卫生（导出符号
 * 仅 4 个 JNI 入口，见 THIRD-PARTY-NOTICES.md），不再抢占。armeabi-v7a 版
 * minSdk=24：API 21-23 的 32 位老机 loadLibrary 失败 → isAvailable() 为 false，
 * CNAria2 回退主引擎，不影响功能。
 *
 * 关停顺序：RPC 调 aria2.shutdown（或 forceShutdown）→ waitStopped()。
 * aria2 运行期间不要 System.exit()。
 */
package io.kamihama.magianative;

public final class CNAria2Lib {
    private static final String TAG = "CNAria2Lib";
    private static boolean sLoaded;

    static {
        try {
            System.loadLibrary("aria2c");
            sLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.e(TAG, "libaria2c.so load failed", e);
            sLoaded = false;
        }
    }

    private CNAria2Lib() {}

    /** @return libaria2c.so 是否加载成功。 */
    public static boolean isAvailable() {
        return sLoaded;
    }

    /**
     * 进程内启动 aria2。
     *
     * @param args aria2 命令行参数（不含 argv[0]），与 CNAria2 传给子进程二进制的
     *             参数完全一致即可，例如：
     *             "--enable-rpc", "--rpc-listen-port=6800",
     *             "--rpc-secret=...", "--daemon=false", ...
     * @return 0 成功；-1 已有实例运行；-2 线程创建失败；-3 参数为空
     * @throws IllegalStateException 库未加载
     */
    public static int start(String[] args) {
        if (!sLoaded) throw new IllegalStateException("libaria2c.so not loaded");
        return nativeStart(args);
    }

    /** @return aria2 线程是否存活。 */
    public static boolean isRunning() {
        return sLoaded && nativeIsRunning();
    }

    /**
     * 等待（有界）aria2 线程退出（RPC shutdown 之后调用）。
     *
     * @param timeoutMs 最长等待毫秒；<=0 表示无限等待
     * @return aria2 退出码；超时 -2；未运行 -1
     */
    public static int waitStopped(long timeoutMs) {
        if (!sLoaded) return -1;
        return nativeWaitStopped(timeoutMs);
    }

    private static native int nativeStart(String[] args);
    private static native boolean nativeIsRunning();
    private static native int nativeWaitStopped(long timeoutMs);
}
