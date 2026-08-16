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
#include <unistd.h>

// 前向声明（供 cnExtract 使用）
static void mkdir_recursive(const char* path);
static void ensure_parent(const char* out);

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
    int entriesDone = 0;
    long long bytesDone = 0;
    while ((r = archive_read_next_header(a, &e)) == ARCHIVE_OK) {
        if (cancelled) break;
        const char* name = archive_entry_pathname(e);
        if (!name) { archive_read_data_skip(a); continue; }

        char out[4096];
        snprintf(out, sizeof(out), "%s/%s", destPath, name);
        if (archive_entry_filetype(e) == AE_IFDIR) {
            mkdir_recursive(out);
        } else {
            ensure_parent(out);
            FILE* f = fopen(out, "wb");
            if (f) {
                char buf[65536];
                la_ssize_t got;
                while ((got = archive_read_data(a, buf, sizeof(buf))) > 0) {
                    fwrite(buf, 1, (size_t)got, f);
                    bytesDone += got;
                }
                fclose(f);
            } else {
                archive_read_data_skip(a);
            }
        }
        entriesDone++;
        // 注意：bytesDone 已在上面解压循环里按实际写入累加（got），这里不重复加。

        // 进度回调（每条目）；返回 false 取消
        if (jProgress && g_onProgress) {
            JNIEnv* e2 = env;
            bool detach = false;
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

    archive_read_free(a);
    env->ReleaseStringUTFChars(jZipPath, zipPath);
    env->ReleaseStringUTFChars(jDestPath, destPath);
    return cancelled ? JNI_FALSE : JNI_TRUE;
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
