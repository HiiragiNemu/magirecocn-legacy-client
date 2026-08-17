// JNI 封装：libarchive 进程内解压，替代 exec bsdtar。
//
// 为什么用 JNI 而不是子进程：Android 10+ 的 SELinux W^X 闸禁止应用从
// filesDir exec 二进制（error=13），16KB 页设备还要求 ELF 段 16KB 对齐。
// 共享库经 System.loadLibrary 由 linker 加载，落点在只读的 nativeLibraryDir，
// 完全绕开这两个限制——这就是 libarchive 编成 .so 的原因。
//
// 对 Java 暴露三个能力（对应原 CNZipTool 的 exec 版）：
//   cnList(zipPath)     -> int[]  条目未压缩大小表（走中央目录，不扫数据区）
//   cnIsValid(zipPath)  -> boolean 结构可解析
//   cnExtract(zipPath, destPath, progress) -> boolean 解压带进度回调
//
// 进度回调：archive_read_next_header 每解完一个条目回调一次 Java
// （onProgress(entriesDone, bytesDone)），字节级精确。取消：回调返回
// false 即中止（对应 Java 侧 Cancel 接口）。
#include <jni.h>
#include <archive.h>
#include <archive_entry.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <sys/stat.h>
#include <limits.h>
#include <unistd.h>

// 构建标记（2026-08-17）：libarchive 3.7.4 重建后编入 .so 的 .rodata。
// `used` 属性保证即使无引用也不会被链接器/剥离去掉——check-cnzip-guards.py
// 用 strings 探测它，确认「补丁 01/02/03 的修复已进二进制」（源码侧修了、
// 二进制没重建的漂移由此现形）。改 build-aria2 同款惯例：升级时换新值。
static const char kBuildMarker[] __attribute__((used)) =
        "libarchive-cn-jni-3.7.4-fix-20260817";

// 前向声明（供 cnExtract 使用）
static void mkdir_recursive(const char* path);
static void ensure_parent(const char* out);

// ── 条目路径安全校验（Zip Slip 防护）─────────────────────────────────
// 恶意 ZIP 可以把条目名写成 "../shared_prefs/x.xml" 或 "/data/..." 之
// 类，拼到解压根之后逃逸到应用沙箱的任意位置写入。本函数在**写盘之前**
// 对条目名做字符串级校验，命中任一规则即拒绝该条目：
//   1. 绝对路径（'/' 开头）；
//   2. 任何按 '/' 切分后等于 ".." 的段（上层目录逃逸）；
//   3. 反斜杠 '\'（在部分平台上会被当作目录分隔符，统一拒绝最干净）；
//   4. 空条目名。
// 注意：只做「逐段相等」判断，不做子串匹配——"a..b"、"...、“./x” 这类
// 正常名字不受影响（"." 段在 Linux 下原地不动，无穿越能力，放行）。
static bool is_safe_entry_name(const char* name) {
    if (!name || !name[0]) return false;          // 空名
    if (name[0] == '/') return false;             // 绝对路径
    const char* seg = name;
    for (const char* p = name; ; p++) {
        if (*p == '\\') return false;             // 反斜杠
        if (*p == '/' || *p == '\0') {
            // 段 [seg, p)：恰好是两个点即为 ".."
            if (p - seg == 2 && seg[0] == '.' && seg[1] == '.') return false;
            if (*p == '\0') break;
            seg = p + 1;
        }
    }
    return true;
}

static JavaVM* g_vm = nullptr;

// 进度监听器类：io.kamihama.magianative.CNZipTool$JniProgress
//   方法: boolean onProgress(int entriesDone, long bytesDone)
//   返回 false = 取消
static jclass g_progressClass = nullptr;
static jmethodID g_onProgress = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// （envFor 未使用，删除——解压 JNI 在调用线程上跑，Java 侧保证线程已 attach）

// ── cnList：返回 long[]（条目未压缩大小），null = 结构不可解析 ──────────
extern "C" JNIEXPORT jlongArray JNICALL
Java_io_kamihama_magianative_CNZipTool_cnList(JNIEnv* env, jclass,
                                              jstring jZipPath) {
    const char* zipPath = env->GetStringUTFChars(jZipPath, nullptr);
    if (!zipPath) return nullptr;

    struct archive* a = archive_read_new();
    archive_read_support_filter_all(a);
    archive_read_support_format_all(a);
    int r = archive_read_open_filename(a, zipPath, 65536);
    if (r != ARCHIVE_OK) {
        archive_read_free(a);
        env->ReleaseStringUTFChars(jZipPath, zipPath);
        return nullptr;
    }

    // 第一遍数条目数
    struct archive_entry* e;
    int count = 0;
    while ((r = archive_read_next_header(a, &e)) == ARCHIVE_OK) count++;
    archive_read_free(a);

    // 第二遍收大小（需要重新打开）
    a = archive_read_new();
    archive_read_support_filter_all(a);
    archive_read_support_format_all(a);
    r = archive_read_open_filename(a, zipPath, 65536);
    if (r != ARCHIVE_OK) {
        archive_read_free(a);
        env->ReleaseStringUTFChars(jZipPath, zipPath);
        return nullptr;
    }
    jlongArray sizes = env->NewLongArray(count);
    if (!sizes) {
        archive_read_free(a);
        env->ReleaseStringUTFChars(jZipPath, zipPath);
        return nullptr;
    }
    jlong* body = env->GetLongArrayElements(sizes, nullptr);
    int idx = 0;
    while ((r = archive_read_next_header(a, &e)) == ARCHIVE_OK) {
        if (idx < count) body[idx++] = (jlong)archive_entry_size(e);
        archive_read_data_skip(a);
    }
    env->ReleaseLongArrayElements(sizes, body, 0);
    archive_read_free(a);
    env->ReleaseStringUTFChars(jZipPath, zipPath);
    return sizes;
}

// ── cnIsValid：结构可解析 ───────────────────────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_io_kamihama_magianative_CNZipTool_cnIsValid(JNIEnv* env, jclass,
                                                 jstring jZipPath) {
    const char* zipPath = env->GetStringUTFChars(jZipPath, nullptr);
    if (!zipPath) return JNI_FALSE;
    struct archive* a = archive_read_new();
    archive_read_support_filter_all(a);
    archive_read_support_format_all(a);
    int r = archive_read_open_filename(a, zipPath, 65536);
    int ok = (r == ARCHIVE_OK);
    if (ok) {
        // 至少能读出一个条目才算「结构可解析」
        struct archive_entry* e;
        r = archive_read_next_header(a, &e);
        ok = (r == ARCHIVE_OK);
    }
    archive_read_free(a);
    env->ReleaseStringUTFChars(jZipPath, zipPath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ── cnExtract：解压到 destPath，带进度回调 ───────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_io_kamihama_magianative_CNZipTool_cnExtract(JNIEnv* env, jclass,
                                                 jstring jZipPath,
                                                 jstring jDestPath,
                                                 jobject jProgress) {
    const char* zipPath = env->GetStringUTFChars(jZipPath, nullptr);
    const char* destPath = env->GetStringUTFChars(jDestPath, nullptr);
    if (!zipPath || !destPath) {
        if (zipPath) env->ReleaseStringUTFChars(jZipPath, zipPath);
        if (destPath) env->ReleaseStringUTFChars(jDestPath, destPath);
        return JNI_FALSE;
    }

    // ── zip 炸弹的两道「写出前/写出中」闸 ─────────────────────────────
    // Java 侧的比例预检读的是中央目录**声明**的未压缩总量，而声明是可以
    // 撒谎的：每条目都报小尺寸即可通过预检，deflate 实际膨胀比可达
    // ~1000x。所以 native 必须在**写盘的同时**盯着真实字节数：
    //   闸一（逐条目）：条目实际解出字节 > 中央目录声明尺寸 → 撒谎，中止；
    //   闸二（总量）：累计写出 > zip 体积 ×200 且已超 256MB → 炸弹，中止。
    // 与 Java 回退路径（CNArchiveInstallTx.EXTRACT_MAX_RATIO /
    // EXTRACT_MIN_BYTES_BEFORE_RATIO）同一标准，自产包 ~2x 不会误伤。
    long long zipSize = -1;
    {
        struct stat st;
        if (stat(zipPath, &st) == 0) zipSize = (long long)st.st_size;
    }
    const long long maxWriteBytes =
            (zipSize > 0 && zipSize < (LLONG_MAX / 200))
                    ? zipSize * 200 : LLONG_MAX;
    const long long minBytesBeforeRatio = 256LL * 1024 * 1024;

    // 缓存进度回调类/方法（首次）
    if (!g_progressClass && jProgress) {
        jclass cls = env->GetObjectClass(jProgress);
        g_progressClass = (jclass)env->NewGlobalRef(cls);
        g_onProgress = env->GetMethodID(g_progressClass, "onProgress",
                                        "(IJ)Z");
        env->DeleteLocalRef(cls);
    }

    struct archive* a = archive_read_new();
    archive_read_support_filter_all(a);
    archive_read_support_format_all(a);
    int r = archive_read_open_filename(a, zipPath, 65536);
    if (r != ARCHIVE_OK) {
        archive_read_free(a);
        env->ReleaseStringUTFChars(jZipPath, zipPath);
        env->ReleaseStringUTFChars(jDestPath, destPath);
        return JNI_FALSE;
    }

    struct archive_entry* e;
    bool cancelled = false;
    bool ioError = false;   // 任一读写错误：整次解压按失败上报
    int entriesDone = 0;
    long long bytesDone = 0;
    while ((r = archive_read_next_header(a, &e)) == ARCHIVE_OK) {
        if (cancelled) break;
        const char* name = archive_entry_pathname(e);
        if (!name) { archive_read_data_skip(a); continue; }

        // Zip Slip 防护：非法条目名（../、绝对路径、反斜杠）一律跳过。
        // 跳过而不是中止：一个恶意条目不该让整个安装失败，但它一个字节
        // 都不许落到解压根之外。libarchive 读 zip 时不会替我们做这层校验。
        if (!is_safe_entry_name(name)) {
            archive_read_data_skip(a);
            continue;
        }

        // 符号链接/硬链接/FIFO 等特殊条目只跳过不落地：fopen 不会创建
        // 符号链接，但把链接目标路径写成普通文件内容既没有意义也可能
        // 被后续清理逻辑误用。ZIP 资源包只需要目录与常规文件。
        mode_t ftype = archive_entry_filetype(e);
        if (ftype != AE_IFDIR && ftype != AE_IFREG) {
            archive_read_data_skip(a);
            continue;
        }

        char out[4096];
        int outLen = snprintf(out, sizeof(out), "%s/%s", destPath, name);
        // 路径被 4096 截断时宁可跳过：截断后的路径可能指向完全不同的
        // 位置（截掉后半段后落进错误的目录），静默写入比跳过更危险。
        if (outLen <= 0 || outLen >= (int)sizeof(out)) {
            archive_read_data_skip(a);
            continue;
        }
        if (archive_entry_filetype(e) == AE_IFDIR) {
            mkdir_recursive(out);
        } else {
            // 条目声明的未压缩尺寸（可撒谎；<0 表示未知，跳过逐条目闸，
            // 此时只剩总量闸兜底）。
            la_int64_t declared = archive_entry_size(e);
            long long entryWritten = 0;
            ensure_parent(out);
            FILE* f = fopen(out, "wb");
            if (f) {
                char buf[65536];
                la_ssize_t got;
                // got > 0：正常数据；got == 0：本条目 EOF；
                // got < 0（ARCHIVE_WARN/FAILED/FATAL）：数据错误（含 CRC 校验
                // 失败）——必须当失败处理，原来与 EOF 混为一谈会静默收下截断/
                // 损坏的文件。
                while ((got = archive_read_data(a, buf, sizeof(buf))) > 0) {
                    // fwrite 返回值必须核对：ENOSPC 时静默截断会让半截文件
                    // 落盘而被当成完整品。
                    size_t written = fwrite(buf, 1, (size_t)got, f);
                    if (written != (size_t)got) {
                        ioError = true;
                        break;
                    }
                    bytesDone += written;   // 进度按真实写入计
                    entryWritten += written;
                    // 闸一：实际解出超过声明尺寸 → 声明撒谎，按炸弹中止
                    if (declared >= 0 && entryWritten > (long long)declared) {
                        ioError = true;
                        break;
                    }
                    // 闸二：累计写出超 zip 体积 200 倍且已过 256MB → 炸弹中止
                    if (bytesDone > minBytesBeforeRatio && bytesDone > maxWriteBytes) {
                        ioError = true;
                        break;
                    }
                }
                if (got < 0) ioError = true;            // 读侧错误（CRC/数据）
                if (fclose(f) != 0) ioError = true;     // 写缓冲 flush 失败
                if (ioError) {
                    // 半截文件不许留在盘上：上层拿到失败会走重试/回退，
                    // 留下半截只会被后续「文件存在」判据误当完整品。
                    remove(out);
                }
            } else {
                // fopen 失败（权限/路径/FD 耗尽）同样是硬错误，不能 skip
                // 了事还报成功。
                ioError = true;
            }
        }
        if (ioError) break;
        entriesDone++;
        // 注意：bytesDone 已在上面解压循环里按实际写入累加，这里不重复加。

        // 进度回调（每条目）；返回 false 取消
        if (jProgress && g_onProgress) {
            JNIEnv* e2 = env;
            // 解压可能在 Java 后台线程调，JNI 环境一致即可
            jboolean cont = e2->CallBooleanMethod(jProgress, g_onProgress,
                                                  (jint)entriesDone,
                                                  (jlong)bytesDone);
            if (e2->ExceptionCheck()) {
                e2->ExceptionClear();
                cancelled = true;
            } else if (cont == JNI_FALSE) {
                cancelled = true;
            }
        }
    }

    // 循环退出只有两种正常理由：读完全部条目（r == ARCHIVE_EOF）或被取消。
    // 其它退出（archive_read_next_header 返回 WARN/FAILED/FATAL）都是包损坏，
    // 原先不检查 r 会把「解到一半 CRC 炸了」报成成功。
    if (!cancelled && !ioError && r != ARCHIVE_EOF) ioError = true;

    archive_read_free(a);
    env->ReleaseStringUTFChars(jZipPath, zipPath);
    env->ReleaseStringUTFChars(jDestPath, destPath);
    return (cancelled || ioError) ? JNI_FALSE : JNI_TRUE;
}

// ── 辅助：mkdir -p ─────────────────────────────────────────────────
static void mkdir_recursive(const char* path) {
    char tmp[4096];
    snprintf(tmp, sizeof(tmp), "%s", path);
    size_t len = strlen(tmp);
    if (tmp[len - 1] == '/') tmp[len - 1] = '\0';
    for (char* p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            mkdir(tmp, 0755);
            *p = '/';
        }
    }
    mkdir(tmp, 0755);
}
static void ensure_parent(const char* out) {
    char tmp[4096];
    snprintf(tmp, sizeof(tmp), "%s", out);
    char* slash = strrchr(tmp, '/');
    if (slash) { *slash = '\0'; mkdir_recursive(tmp); }
}
