// aria2_jni.cpp —— libaria2（aria2 的 C++ 库）的 JNI 桥接
//
// ## 这是什么
//
// 主分块下载器（CNChunkedDownload）的备用引擎：万一主引擎修不动，用它兜底拉
// 资源文件。aria2 自带稳健的多连接 + 断点续传（max-connection-per-server /
// continue），对「服务器返回稀奇古怪字节」类问题比自研分片更抗造。
//
// ## 构建位置
//
// 本文件**不在 CI 构建**：aria2 的静态库需要整套交叉编译环境（zlib/OpenSSL/
// c-ares/libxml2 + aria2 本体），只有 hk 机器上有。产出的 libaria2.so 是
// 预置二进制，提交在 lib/<abi>/libaria2.so，apktool 打包时随 lib/ 进 APK。
// 构建步骤见 scripts/build-aria2.sh（hk 机器）。
//
// ## 与 Java 的约定
//
// 用「Java_ 命名约定」暴露 JNI 函数（不需要 JNI_OnLoad/RegisterNatives），
// 符号保持默认可见性（编译时勿加 -fvisibility=hidden）。Java 侧
// io.kamihama.magianative.CNAria2 经 System.loadLibrary("aria2") 惰性加载。
//
// ## 形态
//
// 单文件、同步下载：一次一个 URL，run 循环跑到完成/取消/出错。session 每调用
// 创建销毁（libaria2 示例即如此）；用原子标志串行化，防并发 download() 撞
// session（libaria2 说明 session 是进程内单例）。
//
// 返回码（与 CNAria2 的常量对齐）：
//   0  成功
//   1  addUri 失败（URL 无效等）
//   2  下载出错（aria2 报告 error 事件）
//   3  run 循环异常退出
//  -1  被取消
//  -2  初始化 / session 创建失败
//  -3  其它
//  -4  已有下载在跑（串行化冲突）
//
// 进度：run 循环里每 ~250ms 回调一次 CNAria2$Progress.onProgress(done,total)。
// 取消：run 循环里轮询传入的 AtomicBoolean.get()，为 true 即 removeDownload。

#include <jni.h>

#include <atomic>
#include <chrono>
#include <string>
#include <vector>

#include <aria2/aria2.h>

#include <android/log.h>

#define LOG_TAG "CNAria2"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// 本次下载的最终状态：1=完成 2=出错 0=未知（事件回调置位）。
// 单文件、单 session，全局即可；串行化由 g_inUse 保证。
std::atomic<int> g_lastStatus(0);

int downloadEventCallback(aria2::Session* session, aria2::DownloadEvent event,
                          aria2::A2Gid gid, void* userData) {
    switch (event) {
    case aria2::EVENT_ON_DOWNLOAD_COMPLETE:
        g_lastStatus.store(1);
        LOGI("download complete gid=%s", aria2::gidToHex(gid).c_str());
        break;
    case aria2::EVENT_ON_DOWNLOAD_ERROR:
        g_lastStatus.store(2);
        LOGW("download error gid=%s", aria2::gidToHex(gid).c_str());
        break;
    default:
        break;
    }
    return 0;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_io_kamihama_magianative_CNAria2_nativeAvailable(JNIEnv* env, jclass) {
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_io_kamihama_magianative_CNAria2_download(
    JNIEnv* env, jclass,
    jstring jurl, jstring joutDir, jstring joutName,
    jstring jua, jstring jreferer, jobjectArray jheaders,
    jint maxConns, jstring jproxy, jobject jprogress, jobject jcancel)
{
    static std::atomic<int> g_inUse(0);
    if (g_inUse.exchange(1) != 0) return -4;

    // ---- 取参 ----
    std::string url, outDir, outName, ua, referer, proxy;
    if (jurl) {
        const char* s = env->GetStringUTFChars(jurl, nullptr);
        if (s) { url = s; env->ReleaseStringUTFChars(jurl, s); }
    }
    if (joutDir) {
        const char* s = env->GetStringUTFChars(joutDir, nullptr);
        if (s) { outDir = s; env->ReleaseStringUTFChars(joutDir, s); }
    }
    if (joutName) {
        const char* s = env->GetStringUTFChars(joutName, nullptr);
        if (s) { outName = s; env->ReleaseStringUTFChars(joutName, s); }
    }
    if (jua) {
        const char* s = env->GetStringUTFChars(jua, nullptr);
        if (s) { ua = s; env->ReleaseStringUTFChars(jua, s); }
    }
    if (jreferer) {
        const char* s = env->GetStringUTFChars(jreferer, nullptr);
        if (s) { referer = s; env->ReleaseStringUTFChars(jreferer, s); }
    }
    if (jproxy) {
        const char* s = env->GetStringUTFChars(jproxy, nullptr);
        if (s) { proxy = s; env->ReleaseStringUTFChars(jproxy, s); }
    }

    // ---- aria2 选项（per-download） ----
    aria2::KeyVals options;
    if (!outDir.empty())  options.emplace_back("dir", outDir);
    if (!outName.empty()) options.emplace_back("out", outName);
    if (!ua.empty())      options.emplace_back("user-agent", ua);
    if (!referer.empty()) options.emplace_back("referer", referer);
    if (!proxy.empty())   options.emplace_back("all-proxy", proxy);
    if (maxConns >= 1 && maxConns <= 16) {
        options.emplace_back("max-connection-per-server", std::to_string(maxConns));
    }
    options.emplace_back("continue", "true");  // 断点续传
    if (jheaders) {
        jsize n = env->GetArrayLength(jheaders);
        for (jsize i = 0; i < n; i++) {
            jstring js = (jstring) env->GetObjectArrayElement(jheaders, i);
            if (js == nullptr) continue;
            const char* h = env->GetStringUTFChars(js, nullptr);
            if (h) {
                options.emplace_back("header", h);
                env->ReleaseStringUTFChars(js, h);
            }
            env->DeleteLocalRef(js);
        }
    }

    // ---- 取消与进度的方法 id（每次查找，成本可忽略） ----
    jclass atomicCls   = env->FindClass("java/util/concurrent/atomic/AtomicBoolean");
    jmethodID atomicGet = atomicCls ? env->GetMethodID(atomicCls, "get", "()Z") : nullptr;
    jclass progressCls = env->FindClass("io/kamihama/magianative/CNAria2$Progress");
    jmethodID progressOn = progressCls
            ? env->GetMethodID(progressCls, "onProgress", "(JJ)V") : nullptr;
    if (atomicCls)   env->DeleteLocalRef(atomicCls);
    if (progressCls) env->DeleteLocalRef(progressCls);

    std::vector<std::string> uris;
    uris.push_back(url);

    g_lastStatus.store(0);

    int result = 0;
    if (aria2::libraryInit() < 0) {
        g_inUse.store(0);
        return -2;
    }
    aria2::SessionConfig config;
    config.downloadEventCallback = downloadEventCallback;
    aria2::Session* session = aria2::sessionNew(aria2::KeyVals(), config);
    if (session == nullptr) {
        aria2::libraryDeinit();
        g_inUse.store(0);
        return -2;
    }

    aria2::A2Gid gid = 0;
    int rv = aria2::addUri(session, &gid, uris, options);
    if (rv < 0) {
        LOGW("addUri failed rv=%d", rv);
        aria2::sessionFinal(session);
        aria2::libraryDeinit();
        g_inUse.store(0);
        return 1;
    }

    auto lastCb = std::chrono::steady_clock::now();
    int runResult = 0;
    for (;;) {
        int r = aria2::run(session, aria2::RUN_ONCE);
        if (r != 1) { runResult = r; break; }

        // 取消：轮询 AtomicBoolean
        if (jcancel && atomicGet
                && env->CallBooleanMethod(jcancel, atomicGet)) {
            aria2::removeDownload(session, gid, true);
            result = -1;
            break;
        }
        // 进度：节流 ~250ms
        if (jprogress && progressOn) {
            auto now = std::chrono::steady_clock::now();
            if (std::chrono::duration_cast<std::chrono::milliseconds>(
                    now - lastCb).count() >= 250) {
                lastCb = now;
                aria2::DownloadHandle* dh = aria2::getDownloadHandle(session, gid);
                if (dh) {
                    env->CallVoidMethod(jprogress, progressOn,
                            (jlong) dh->getCompletedLength(),
                            (jlong) dh->getTotalLength());
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    aria2::deleteDownloadHandle(dh);
                }
            }
        }
    }

    if (result == 0) {
        // run 循环自然结束：看事件回调里记下的最终状态
        if (g_lastStatus.load() == 2) result = 2;
        else if (runResult != 0)      result = 3;
    }

    aria2::sessionFinal(session);
    aria2::libraryDeinit();
    g_inUse.store(0);
    return result;
}

}  // extern "C"
