/*
 * magia-tls-probe —— 用**引擎自带的那份 OpenSSL** 去连一个端点，看握手成不成。
 *
 * ## 为什么要它
 *
 * 「native 引擎能不能跟我们自建的服务端说话」是整条自建服务端路线唯一的技术
 * 死穴。之前只能静态论证：libmadomagi_native.so 里 SSL_CTX_set_verify 一次都
 * 没调用、内置 CA 没有、OPENSSLDIR 指向打包机路径，所以**它压根不验证证书**；
 * 而 0x140920E3（SSL3_GET_SERVER_HELLO / SSL_R_PARSE_TLSEXT）是 OpenSSL 1.0.2s
 * 听不懂现代 TLS 栈的扩展，属于代差不是信任。
 *
 * 静态论证再密也是论证。这个程序把它变成一次真实握手：
 *
 *   ▸ 不重新编译引擎，也不需要整个游戏跑起来；
 *   ▸ dlopen 原版 libmadomagi_native.so，dlsym 它导出的 OpenSSL API
 *     （实测 18 个需要的符号一个不缺），用的就是引擎运行时用的那份 1.0.2s；
 *   ▸ 逐行复刻 http2::Http2SessionManager::run（arm64 0xa00434）的调用序列：
 *
 *         ctx = SSL_CTX_new(TLSv1_2_method())        // boost tlsv12 = method 0xf
 *         SSL_CTX_set_default_verify_paths(ctx)      // 挂一个不存在的目录
 *         SSL_CTX_set_alpn_protos(ctx, "\x02h2", 3)  // configure_tls_context 只干这个
 *         // ⚠ 故意不调 SSL_CTX_set_verify —— 引擎就是不调，这正是被测的那一点
 *
 * 判据只有一条：**SSL_connect 对着一张自签名证书返回不返回 1。**
 * 返回 1 = 死穴解除，自建服务端这条路通；返回 <=0 = 打印完整 error 栈，
 * 对照 0x140920E3 看是不是同一个原因。
 *
 * ## 为什么是可执行文件而不是 APK
 *
 * 引擎 .so 的 DT_NEEDED 全是系统库（libdl/libGLESv2/libEGL/liblog/libandroid/
 * libOpenSLES/libm/libstdc++/libc），没有一个需要 JavaVM——dlopen 只跑静态
 * 构造，不会调 JNI_OnLoad。所以命令行进程就够，不必套 Activity、不必打包签名，
 * 也就不会把「测 TLS」和「APK 能不能装起来」两件事搅在一起。
 *
 * 万一某个静态构造真的要 VM 而崩了，再退回 APK 形态——那时 dlopen 换成
 * System.loadLibrary，本文件的探测逻辑一行都不用改。
 *
 * ## 怎么用
 *
 *     # 1) 编译（NDK r27c，两个 ABI 都建议编）
 *     $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang \
 *         -O2 -o magia-tls-probe-arm64 tools/magia-tls-probe.c -ldl -llog
 *
 *     # 2) 连同**原版**引擎 so 一起推到设备
 *     adb push magia-tls-probe-arm64 /data/local/tmp/
 *     adb push libmadomagi_native.so /data/local/tmp/
 *
 *     # 3) 先在电脑上起测试端点（同一局域网）
 *     python3 tools/tls12-endpoint-probe.py --host 0.0.0.0 --port 8443 --serve
 *
 *     # 4) 在设备上连它
 *     adb shell 'cd /data/local/tmp && chmod +x magia-tls-probe-arm64 && \
 *         LD_LIBRARY_PATH=. ./magia-tls-probe-arm64 <电脑IP> 8443'
 *
 * 结果同时打到 stdout 和 logcat（tag=MagiaTlsProbe），方便和游戏日志对齐。
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>
#include <netdb.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>

#define TAG "MagiaTlsProbe"
#define LOGI(...) do { __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__); \
                       printf(__VA_ARGS__); printf("\n"); fflush(stdout); } while (0)

/* OpenSSL 1.0.2 的常量。这里刻意不引 OpenSSL 头文件——引了就得配一套和引擎
 * 版本一致的头，反而容易和实际 ABI 对不上。用到几个就写死几个。 */
#define SSL_CTRL_SET_TLSEXT_HOSTNAME 55
#define TLSEXT_NAMETYPE_host_name     0

typedef void *(*fn_method)(void);
typedef void *(*fn_ctx_new)(void *method);
typedef int   (*fn_ctx_setpaths)(void *ctx);
typedef int   (*fn_ctx_alpn)(void *ctx, const unsigned char *p, unsigned int len);
typedef int   (*fn_ctx_ciphers)(void *ctx, const char *str);
typedef void *(*fn_ssl_new)(void *ctx);
typedef int   (*fn_ssl_setfd)(void *ssl, int fd);
typedef long  (*fn_ssl_ctrl)(void *ssl, int cmd, long larg, void *parg);
typedef int   (*fn_ssl_connect)(void *ssl);
typedef int   (*fn_ssl_geterr)(const void *ssl, int ret);
typedef const char *(*fn_ssl_version)(const void *ssl);
typedef void *(*fn_ssl_curcipher)(const void *ssl);
typedef const char *(*fn_cipher_name)(const void *c);
typedef long  (*fn_ssl_verifyres)(const void *ssl);
typedef void  (*fn_alpn_selected)(const void *ssl, const unsigned char **d, unsigned int *l);
typedef int   (*fn_lib_init)(void);
typedef unsigned long (*fn_err_get)(void);
typedef void  (*fn_err_str)(unsigned long e, char *buf, size_t len);

static void *SYM(void *h, const char *n) {
    void *p = dlsym(h, n);
    if (!p) LOGI("✘ dlsym 失败: %s", n);
    return p;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        printf("用法: %s <host> <port> [so路径]\n", argv[0]);
        return 2;
    }
    const char *host = argv[1];
    const char *port = argv[2];
    const char *sopath = (argc > 3) ? argv[3] : "libmadomagi_native.so";

    LOGI("=== 用引擎自带的 OpenSSL 做真实握手 ===");
    LOGI("目标: %s:%s   引擎 so: %s", host, port, sopath);

    void *h = dlopen(sopath, RTLD_NOW | RTLD_LOCAL);
    if (!h) { LOGI("✘ dlopen 失败: %s", dlerror()); return 1; }
    LOGI("✔ dlopen 成功（静态构造没要 VM）");

    fn_lib_init      f_init    = (fn_lib_init)      SYM(h, "SSL_library_init");
    fn_method        f_method  = (fn_method)        SYM(h, "TLSv1_2_method");
    fn_ctx_new       f_ctxnew  = (fn_ctx_new)       SYM(h, "SSL_CTX_new");
    fn_ctx_setpaths  f_paths   = (fn_ctx_setpaths)  SYM(h, "SSL_CTX_set_default_verify_paths");
    fn_ctx_alpn      f_alpn    = (fn_ctx_alpn)      SYM(h, "SSL_CTX_set_alpn_protos");
    fn_ssl_new       f_sslnew  = (fn_ssl_new)       SYM(h, "SSL_new");
    fn_ssl_setfd     f_setfd   = (fn_ssl_setfd)     SYM(h, "SSL_set_fd");
    fn_ssl_ctrl      f_ctrl    = (fn_ssl_ctrl)      SYM(h, "SSL_ctrl");
    fn_ssl_connect   f_conn    = (fn_ssl_connect)   SYM(h, "SSL_connect");
    fn_ssl_geterr    f_geterr  = (fn_ssl_geterr)    SYM(h, "SSL_get_error");
    fn_ssl_version   f_ver     = (fn_ssl_version)   SYM(h, "SSL_get_version");
    fn_ssl_curcipher f_cur     = (fn_ssl_curcipher) SYM(h, "SSL_get_current_cipher");
    fn_cipher_name   f_cname   = (fn_cipher_name)   SYM(h, "SSL_CIPHER_get_name");
    fn_ssl_verifyres f_vres    = (fn_ssl_verifyres) SYM(h, "SSL_get_verify_result");
    fn_alpn_selected f_alpnsel = (fn_alpn_selected) SYM(h, "SSL_get0_alpn_selected");
    fn_err_get       f_errget  = (fn_err_get)       SYM(h, "ERR_get_error");
    fn_err_str       f_errstr  = (fn_err_str)       SYM(h, "ERR_error_string_n");
    if (!f_init || !f_method || !f_ctxnew || !f_sslnew || !f_conn) {
        LOGI("✘ 关键符号缺失，无法继续"); return 1;
    }

    f_init();

    /* ── 逐行复刻 Http2SessionManager::run ───────────────────────── */
    void *ctx = f_ctxnew(f_method());
    if (!ctx) { LOGI("✘ SSL_CTX_new 失败"); return 1; }
    if (f_paths) f_paths(ctx);                       /* 挂不存在的目录，同引擎 */
    if (f_alpn) f_alpn(ctx, (const unsigned char *)"\x02h2", 3);
    /* ⚠ 这里**故意不调** SSL_CTX_set_verify —— 引擎就是不调。
     *   如果哪天想反过来验证「加上验证就会失败」，在这里加一行即可。 */

    struct addrinfo hints, *res = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    if (getaddrinfo(host, port, &hints, &res) != 0 || !res) {
        LOGI("✘ 解析 %s:%s 失败", host, port); return 1;
    }
    int fd = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (fd < 0 || connect(fd, res->ai_addr, res->ai_addrlen) != 0) {
        LOGI("✘ TCP 连不上 %s:%s", host, port); return 1;
    }
    freeaddrinfo(res);
    LOGI("✔ TCP 已连上");

    void *ssl = f_sslnew(ctx);
    f_setfd(ssl, fd);
    if (f_ctrl) f_ctrl(ssl, SSL_CTRL_SET_TLSEXT_HOSTNAME,
                       TLSEXT_NAMETYPE_host_name, (void *)host);   /* SNI */

    int rc = f_conn(ssl);
    if (rc == 1) {
        const char *ver = f_ver ? f_ver(ssl) : "?";
        const char *cn = "?";
        if (f_cur && f_cname) { void *c = f_cur(ssl); if (c) cn = f_cname(c); }
        const unsigned char *ap = NULL; unsigned int al = 0;
        if (f_alpnsel) f_alpnsel(ssl, &ap, &al);
        long vr = f_vres ? f_vres(ssl) : -1;

        LOGI("");
        LOGI("✔✔ 握手成功 —— 引擎这份 OpenSSL 接受了这个端点");
        LOGI("   协议    : %s", ver);
        LOGI("   cipher  : %s", cn);
        LOGI("   ALPN    : %.*s", (int)al, ap ? (const char *)ap : "");
        LOGI("   验证结果: %ld  %s", vr,
             vr == 0 ? "(0=ok)"
                     : "(非 0，但握手照样成了 —— 正是 SSL_VERIFY_NONE 的证据)");
        LOGI("");
        LOGI("结论：自签名证书可用，死穴解除。");
        return 0;
    }

    int e = f_geterr ? f_geterr(ssl, rc) : -1;
    LOGI("");
    LOGI("✘ 握手失败: SSL_connect=%d SSL_get_error=%d", rc, e);
    if (f_errget && f_errstr) {
        unsigned long code;
        while ((code = f_errget()) != 0) {
            char buf[256];
            f_errstr(code, buf, sizeof(buf));
            /* 十进制也打一份：游戏界面上报的就是十进制（如 336142563） */
            LOGI("   err 0x%08lx (%lu): %s", code, code, buf);
        }
    }
    LOGI("");
    LOGI("对照：0x140920E3 = 336142563 = SSL3_GET_SERVER_HELLO / PARSE_TLSEXT，");
    LOGI("      那是服务端发了 1.0.2 看不懂的扩展（多半是 TLS 1.3）。");
    LOGI("      若这次是同一个码，把服务端强制成 TLS 1.2 再试。");
    return 1;
}
