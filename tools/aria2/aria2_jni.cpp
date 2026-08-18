/*
 * Copyright (C) 2024-2026 MagirecoCN-Revival-Project
 *
 * aria2_jni.cpp — libaria2c_{ossl,gnutls}.so 的 JNI 胶水层。
 * 契约见 patch/src/main/java/io/kamihama/magianative/CNAria2Lib.java：
 *   nativeStart(String[] args)  → 0 成功 / -1 已有实例 / -2 线程创建失败 / -3 参数为空
 *   nativeIsRunning()           → aria2 线程是否存活
 *   nativeWaitStopped(long ms)  → aria2 退出码 / -2 超时 / -1 未运行
 *
 * 关键设计（踩坑记录，改动前先读完）：
 *
 * 1) 必须 Context(false, ...)，不是 main() 的 Context(true, ...)：
 *    standalone=true 时 option_processing 遇到 --help/参数错会**直接 exit()**——
 *    进程内库里 exit() 会杀掉整个 App。false 时改为抛 aria2::Exception，可捕获。
 *    副作用：不读 ~/.aria2/aria2.conf 等配置文件（进程内本来也不该读）。
 *    standalone 只影响选项处理/帮助路径，RPC 运行不受影响
 *    （CNAria2 恒带 --enable-rpc，不触发 "no files to download" 检查）。
 *
 * 2) 符号卫生：本文件只暴露 4 个 JNI 入口（由 libaria2c.map version script
 *    强制 + -fvisibility=hidden + -Wl,-Bsymbolic）。历史教训：旧版把 OpenSSL
 *    符号以 GLOBAL+JUMP_SLOT 暴露，被进程内其他 libssl/libcrypto 抢占污染而崩。
 *
 * 3) 关停路径：Java 侧走 RPC aria2.shutdown → run 返回 → 线程结束 →
 *    waitStopped() 有界等（pthread_cond_timedwait，不用 pthread_timedjoin_np，
 *    避开其 API 级别历史坑）→ pthread_join 收尸。
 *
 * 4) argv 内存：arg 字符串深拷贝进堆上 ArgvBlock，由工作线程在退出前自释，
 *    JNI 帧返回后 Java 侧 String[] 失效也不影响。
 */

#include <jni.h>
#include <pthread.h>
#include <time.h>

#include <atomic>
#include <string>
#include <vector>

#include <android/log.h>

// aria2 内部头（库源码内构建，-I 指到 src 与 src/includes）
#include "Context.h"
#include "MultiUrlRequestInfo.h"
#include "Platform.h"
#include "Exception.h"
#include "error_code.h"
#include "console.h"

#define LOG_TAG "CNAria2Lib"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 构建标记：strings 可探测，用于核验设备上的库确实出自本构建链
// （build-aria2 归档的同款惯例）。__attribute__((used)) 防 LTO 丢弃。
extern "C" __attribute__((used)) volatile const char kBuildMarker[] =
    "magirecocn-libaria2c: build-aria2.sh; aria2=1.37.0; minSdk=21; "
    "16K-aligned; symbol-hygiene=CNARIA2LIB_1.0";

namespace {

struct ArgvBlock {
  std::vector<std::string> storage; // 持有字符串本体
  std::vector<char*> argv;          // 指向 storage 的指针 + argv[0]
};

pthread_mutex_t gMu = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t gCond = PTHREAD_COND_INITIALIZER;
pthread_t gThread{};
bool gThreadAlive = false;  // 线程已创建、尚未 join
std::atomic<bool> gRunning{false};
int gExitCode = -1;

void* aria2ThreadMain(void* opaque) {
  std::unique_ptr<ArgvBlock> blk(static_cast<ArgvBlock*>(opaque));
  int rc;
  try {
    aria2::global::initConsole(false);
    aria2::Platform platform;
    // 注意 false——见文件头注释 1)，true 会在参数错误时 exit() 杀进程。
    aria2::Context context(false, static_cast<int>(blk->argv.size()),
                           blk->argv.data(), aria2::KeyVals());
    aria2::error_code::Value r = aria2::error_code::FINISHED;
    if (context.reqinfo) {
      r = context.reqinfo->execute();
    }
    rc = static_cast<int>(r);
  } catch (aria2::Exception& ex) {
    LOGE("aria2 exception: %s", ex.stackTrace().c_str());
    rc = static_cast<int>(ex.getErrorCode());
  } catch (const std::exception& ex) {
    LOGE("std exception: %s", ex.what());
    rc = static_cast<int>(aria2::error_code::UNKNOWN_ERROR);
  } catch (...) {
    LOGE("unknown exception in aria2 thread");
    rc = static_cast<int>(aria2::error_code::UNKNOWN_ERROR);
  }

  pthread_mutex_lock(&gMu);
  gExitCode = rc;
  gRunning.store(false);
  pthread_cond_broadcast(&gCond); // 唤醒所有 waitStopped
  pthread_mutex_unlock(&gMu);
  LOGI("aria2 thread exited, code=%d", rc);
  return nullptr;
}

jint nativeStart(JNIEnv* env, jclass, jobjectArray args) {
  if (args == nullptr || env->GetArrayLength(args) == 0) {
    return -3;
  }
  pthread_mutex_lock(&gMu);
  if (gRunning.load() || gThreadAlive) {
    pthread_mutex_unlock(&gMu);
    return -1; // 已有实例（运行中或退出未 join）
  }
  pthread_mutex_unlock(&gMu);

  auto blk = std::make_unique<ArgvBlock>();
  const jsize n = env->GetArrayLength(args);
  blk->storage.reserve(n + 1);
  blk->storage.emplace_back("aria2c"); // argv[0]，Context 的 parseArg 需要
  for (jsize i = 0; i < n; ++i) {
    auto js = static_cast<jstring>(env->GetObjectArrayElement(args, i));
    if (js == nullptr) { // OOM 已挂起
      return -2;
    }
    const char* utf = env->GetStringUTFChars(js, nullptr);
    blk->storage.emplace_back(utf != nullptr ? utf : "");
    if (utf != nullptr) env->ReleaseStringUTFChars(js, utf);
    env->DeleteLocalRef(js);
    if (env->ExceptionCheck()) return -2; // OOM 等
  }
  blk->argv.reserve(blk->storage.size());
  for (auto& s : blk->storage) blk->argv.push_back(s.data());

  // 与原胶水取证对齐：显式 attr（joinable + 栈大小）。
  // 原构建 UND 含 pthread_attr_setdetachstate/pthread_attr_setstacksize。
  // aria2 事件循环+TLS 栈深度有限，显式 1MB 栈防老设备默认栈缩水。
  pthread_attr_t attr;
  pthread_t th;
  int prc = pthread_attr_init(&attr);
  if (prc == 0) prc = pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);
  if (prc == 0) prc = pthread_attr_setstacksize(&attr, 1024 * 1024);
  if (prc == 0) prc = pthread_create(&th, &attr, aria2ThreadMain, blk.get());
  pthread_attr_destroy(&attr);
  if (prc != 0) {
    LOGE("pthread_create failed: %d", prc);
    return -2;
  }
  blk.release(); // 所有权移交工作线程
  LOGI("aria2 thread start, argc=%d", (int)n); // 与原胶水同款日志串

  pthread_mutex_lock(&gMu);
  gThread = th;
  gThreadAlive = true;
  gRunning.store(true);
  gExitCode = -1;
  pthread_mutex_unlock(&gMu);
  LOGI("aria2 thread started (%s)", kBuildMarker);
  return 0;
}

jboolean nativeIsRunning(JNIEnv*, jclass) {
  return gRunning.load() ? JNI_TRUE : JNI_FALSE;
}

jint nativeWaitStopped(JNIEnv*, jclass, jlong timeoutMs) {
  pthread_mutex_lock(&gMu);
  if (!gThreadAlive) {
    pthread_mutex_unlock(&gMu);
    return -1; // 未运行（从未启动或已 join）
  }
  jint rc;
  if (!gRunning.load()) {
    rc = gExitCode; // 已退出，直接收
  }
  else if (timeoutMs <= 0) {
    while (gRunning.load()) {
      pthread_cond_wait(&gCond, &gMu);
    }
    rc = gExitCode;
  }
  else {
    timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += timeoutMs / 1000;
    ts.tv_nsec += (timeoutMs % 1000) * 1000000L;
    if (ts.tv_nsec >= 1000000000L) { ts.tv_sec += 1; ts.tv_nsec -= 1000000000L; }
    int wrc = 0;
    while (gRunning.load() && wrc == 0) {
      wrc = pthread_cond_timedwait(&gCond, &gMu, &ts);
    }
    if (gRunning.load()) {
      pthread_mutex_unlock(&gMu);
      return -2; // 超时
    }
    rc = gExitCode;
  }
  pthread_mutex_unlock(&gMu);
  // 出锁后 join：线程已结束，join 立即返回；重复 waitStopped 时 gThreadAlive
  // 仍为 true 的窗口由「已退出直接收」分支覆盖，join 幂等性靠下方标志保证。
  pthread_mutex_lock(&gMu);
  if (gThreadAlive) {
    pthread_join(gThread, nullptr);
    gThreadAlive = false;
  }
  pthread_mutex_unlock(&gMu);
  return rc;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
  LOGI("JNI_OnLoad: %s", kBuildMarker);
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_kamihama_magianative_CNAria2Lib_nativeStart(JNIEnv* env, jclass cls,
                                                    jobjectArray args) {
  return nativeStart(env, cls, args);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_kamihama_magianative_CNAria2Lib_nativeIsRunning(JNIEnv* env,
                                                        jclass cls) {
  return nativeIsRunning(env, cls);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_kamihama_magianative_CNAria2Lib_nativeWaitStopped(JNIEnv*, jclass,
                                                          jlong timeoutMs) {
  return nativeWaitStopped(nullptr, nullptr, timeoutMs);
}
