/*
 * Copyright (C) 2024-2026 MagirecoCN-Revival-Project
 *
 * CNAria2Lib — 进程内 aria2 下载引擎（libaria2c_{ossl,gnutls}.so，JNI 加载）。
 *
 * 双后端共存：APK 同时携带 libaria2c_ossl.so（OpenSSL 1.1.1w）与
 * libaria2c_gnutls.so（GnuTLS 3.8.3），两枚均 minSdk 21（armv7 已用
 * -D_LIBCPP_HAS_NO_OFF_T_FUNCTIONS + compat 重编，动态符号表对 32 位 bionic
 * 零 API23/24 缺口）。两组导出**同名 JNI 符号**，因此【硬约束】同一进程只允许
 * 加载一个后端——用 {@link #load(Backend)} 显式选择，重复调用返回已加载后端，
 * 不会双载。后端选择 / 崩溃换组的 dead-man's switch 逻辑见
 * {@link Aria2EngineFailover}。
 *
 * 为什么是进程内 JNI（与 exec 二进制的区别）：
 *  1) SELinux exec 闸（targetSdk>=29 时 untrusted_app 不得执行 app_data_file）；
 *  2) 打包 aria2c 二进制的 16KB 页对齐问题（p_align=0x1000 在 Android 15+ 16KB
 *     设备上起不来）。共享库由 linker 加载，两个都绕开（与 libarchive 同思路）。
 *
 * 历史：最早一版 JNI（libaria2.so）把 OpenSSL 符号以 GLOBAL + JUMP_SLOT 暴露，
 * 被进程内其他 libssl/libcrypto 抢占污染而崩。本构建做彻底符号卫生（导出仅 4 个
 * JNI 入口 + `-Wl,-Bsymbolic` + version script），不再抢占。构建与验证记录见
 * THIRD-PARTY-NOTICES.md。
 *
 * 关停顺序：RPC 调 aria2.shutdown（或 forceShutdown）→ waitStopped()
 * → Aria2EngineFailover.disarm()。aria2 运行期间不要 System.exit()。
 */
package io.kamihama.magianative;

public final class CNAria2Lib {
    private static final String TAG = "CNAria2Lib";

    /** TLS 后端。库文件名/SONAME 与枚举一一对应。 */
    public enum Backend {
        OSSL("aria2c_ossl"),     // libaria2c_ossl.so  — OpenSSL 1.1.1w
        GNUTLS("aria2c_gnutls"); // libaria2c_gnutls.so — GnuTLS 3.8.3

        final String libName;
        Backend(String libName) { this.libName = libName; }
    }

    private static Backend sLoaded;

    private CNAria2Lib() {}

    /**
     * 加载指定后端。同一进程只允许一个后端：重复调用返回已加载的后端
     * （若与请求不同，说明本进程无法切换——那由 failover 在下次进程启动时处理）。
     *
     * @return 实际生效的后端
     * @throws UnsatisfiedLinkError 加载失败（符号缺失/格式错误）——可捕获，
     *         调用方可当场换另一个后端重试（无需等下次启动）
     */
    public static synchronized Backend load(Backend b) {
        if (sLoaded != null) {
            if (sLoaded != b) {
                android.util.Log.w(TAG, "already loaded " + sLoaded + ", ignore request " + b);
            }
            return sLoaded;
        }
        System.loadLibrary(b.libName); // 失败抛 UnsatisfiedLinkError，sLoaded 保持 null
        sLoaded = b;
        android.util.Log.i(TAG, "loaded backend " + b);
        return b;
    }

    /** @return 已加载的后端；未加载返回 null。 */
    public static synchronized Backend loadedBackend() {
        return sLoaded;
    }

    /**
     * 进程内启动 aria2。
     *
     * @param args aria2 命令行参数（不含 argv[0]）："--enable-rpc"、
     *             "--rpc-listen-port=...", "--rpc-secret=...", "--daemon=false" 等，
     *             与 CNAria2 传给旧子进程二进制的参数一致即可。
     * @return 0 成功；-1 已有实例运行；-2 线程创建失败；-3 参数为空
     * @throws IllegalStateException 库未加载
     */
    public static int start(String[] args) {
        if (sLoaded == null) throw new IllegalStateException("call load(backend) first");
        return nativeStart(args);
    }

    /** @return aria2 线程是否存活。 */
    public static boolean isRunning() {
        return sLoaded != null && nativeIsRunning();
    }

    /**
     * 等待（有界）aria2 线程退出（RPC shutdown 之后调用）。
     *
     * @param timeoutMs 最长等待毫秒；<=0 表示无限等待
     * @return aria2 退出码；超时 -2；未运行 -1
     */
    public static int waitStopped(long timeoutMs) {
        if (sLoaded == null) return -1;
        return nativeWaitStopped(timeoutMs);
    }

    private static native int nativeStart(String[] args);
    private static native boolean nativeIsRunning();
    private static native int nativeWaitStopped(long timeoutMs);
}
