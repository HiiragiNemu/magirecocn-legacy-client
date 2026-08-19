#include "archive_core.h"

#include <jni.h>

#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace {

class UtfChars {
public:
    UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
        if (value_ != nullptr) chars_ = env_->GetStringUTFChars(value_, nullptr);
    }
    ~UtfChars() {
        if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
    }
    const char* get() const { return chars_; }
private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_ = nullptr;
};

struct JavaProgress {
    JNIEnv* env = nullptr;
    jobject callback = nullptr;
    jmethodID method = nullptr;
    bool callback_failed = false;
};

bool invoke_progress(void* opaque, std::int32_t entries_done, std::int64_t bytes_done) {
    auto* state = static_cast<JavaProgress*>(opaque);
    if (state == nullptr || state->callback == nullptr || state->method == nullptr) return true;
    const jboolean keep_going = state->env->CallBooleanMethod(
            state->callback, state->method, static_cast<jint>(entries_done),
            static_cast<jlong>(bytes_done));
    if (state->env->ExceptionCheck()) {
        state->env->ExceptionClear();
        state->callback_failed = true;
        return false;
    }
    return keep_going == JNI_TRUE;
}

}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_kamihama_magianative_CNZipTool_cnList(JNIEnv* env, jclass, jstring zip_path) {
    if (zip_path == nullptr) return nullptr;
    UtfChars path(env, zip_path);
    if (path.get() == nullptr) return nullptr;
    std::vector<cnzip::EntryInfo> entries;
    std::int64_t total = 0;
    std::string error;
    if (!cnzip::list_zip(path.get(), &entries, &total, &error) ||
        entries.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }
    jlongArray output = env->NewLongArray(static_cast<jsize>(entries.size()));
    if (output == nullptr) return nullptr;
    std::vector<jlong> sizes;
    sizes.reserve(entries.size());
    for (const auto& entry : entries) sizes.push_back(static_cast<jlong>(entry.size));
    env->SetLongArrayRegion(output, 0, static_cast<jsize>(sizes.size()), sizes.data());
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return output;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_kamihama_magianative_CNZipTool_cnIsValid(JNIEnv* env, jclass, jstring zip_path) {
    if (zip_path == nullptr) return JNI_FALSE;
    UtfChars path(env, zip_path);
    if (path.get() == nullptr) return JNI_FALSE;
    std::vector<cnzip::EntryInfo> entries;
    std::int64_t total = 0;
    std::string error;
    return cnzip::list_zip(path.get(), &entries, &total, &error) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_kamihama_magianative_CNZipTool_cnExtract(
        JNIEnv* env, jclass, jstring zip_path, jstring destination,
        jobject callback) {
    if (zip_path == nullptr || destination == nullptr) return JNI_FALSE;
    UtfChars path(env, zip_path);
    UtfChars dest(env, destination);
    if (path.get() == nullptr || dest.get() == nullptr) return JNI_FALSE;

    JavaProgress state;
    cnzip::Progress progress;
    if (callback != nullptr) {
        jclass callback_class = env->GetObjectClass(callback);
        if (callback_class == nullptr) return JNI_FALSE;
        state.env = env;
        state.callback = callback;
        state.method = env->GetMethodID(callback_class, "onProgress", "(IJ)Z");
        env->DeleteLocalRef(callback_class);
        if (state.method == nullptr || env->ExceptionCheck()) {
            env->ExceptionClear();
            return JNI_FALSE;
        }
        progress.fn = invoke_progress;
        progress.opaque = &state;
    }
    std::string error;
    const bool ok = cnzip::extract_zip(path.get(), dest.get(), 0, progress, &error);
    return ok && !state.callback_failed ? JNI_TRUE : JNI_FALSE;
}

// 构建标记（2026-08-19）：hardened cnzip 核心（archive_core.cpp）重写后编入
// .so 的 .rodata。`used` 属性保证即使无引用也不会被链接器/剥离去掉——
// check-cnzip-guards.py 用 strings 探测它，确认「F-001~005 的修复已进二进制」。
static const char kBuildMarker[] __attribute__((used)) =
        "libarchive-cn-jni-hardened-20260819";

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) { return JNI_VERSION_1_6; }
