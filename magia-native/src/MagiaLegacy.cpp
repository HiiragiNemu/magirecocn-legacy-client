// MagiaLegacy.cpp —— 归档版老客户端的 native hook 库
//
// ## 它要取代谁
//
// 包内原有两个预编译的 Dobby hook 库，都没有源码：
//
//   libcn_hook.so  资源下载流水线。拦 DownloadAssetJsonState / SelectURL /
//                  DownloadSceneLayer 那一串，让引擎不要自己去拉十几 GB，
//                  改为经 JNI 叫起我们的 Java 安装器。
//   libuwasa.so    英文汉化组（Kamihama）的补丁库。它 hook 了
//                  StoryMessageUnit / StoryNarrationUnit / StoryLogUnit /
//                  StoryCharaUnit / LbUtility / cocos2d::Label 等一大票，
//                  按**英文行宽**重排剧情文本与对话框几何。
//
// libuwasa 那套在中文汉化下是**净损害**：中文字宽与英文完全不同，套英文的
// 换行与标签尺寸只会排错；而我们的对话框图集本来就是按中文宽度做的
// （story_ui_fukidashi 574x178，英文版是 844x198）。
//
// （旧客户端仓库） 早就得出同样结论并落地了：它的 （旧 native 层）/ 把 libuwasa
// 里**唯一值得留的两个 hook** 逆向移植了过去，然后停用整个 libuwasa。见那边
// 源码里的注释「性能 hook（从 libuwasa 逆向移植，已停用 libuwasa 加载）」。
// 本文件照搬这个做法。
//
// ## 与 （旧 native 层） 的差异
//
// 去掉了 cnv 专有的部分：
//   - setURI 代理后端（ProxyBackends 是复兴客户端自己的服务端设施）
//   - 强制新手教程（pushSceneTop / PrologueSceneLayer::notifyJs）
// 保留资源下载流水线，并补回 libcn_hook 特有的「叫起 Java 安装器」触发。
//
// ## 端点重定向（原 libuwasa 的核心职责）已接管
//
// 三层改造的关系（由维护者确认，并经反汇编印证）：
//
//   日服原版包 ──libuwasa（一改）──▶ 把引擎的资源下载地址改指 Totentanz
//                                        │
//                            libcn_hook（二改）──▶ 拦下载入口，接我们的浮层
//
// libuwasa 靠 hook UrlConfig::resource(Resource::Type) 做重定向。逆向结果：
//
//   引擎侧 UrlConfig::resource(Resource::Type) const @0x90855c（32 字节）：
//       x8 = *(*(0x1d01db0))    ; UrlConfig::Impl 单例
//       x0 = x8 + type*24 + 8   ; 24 = sizeof(std::string)
//       ret
//   → **返回 const std::string&**（x0 里是指针，不是 sret）。
//     同结构的 UrlConfig::api 只是偏移换成 +0x68，可交叉印证。
//     引擎的 Impl::setResourceUrl 也只写 impl+0x8/+0x20/+0x38，
//     正好是 type=0/1/2，与 libuwasa 的「type > 2 → 调原版」吻合。
//
//   libuwasa 的替换函数 @0x67160：
//       type > 2 / 越界 / 表项为空  → 尾调原版
//       否则                        → 返回表[type]（std::string*）
//     表在 0xea080，是 std::vector<std::shared_ptr<std::string>>
//     （元素 16 字节 = {T*, 控制块}，返回的正是 .first）。
//
//   三个槽位由 SNAA 响应的 endpoint 加固定后缀拼成。后缀是 .bss 里的三个
//   std::string 全局，由静态构造器 @0x68858 填（strb 的立即数即 size<<1）：
//       0xea130 = "/magica/resource"        (0x20>>1 = 16)
//       0xea148 = "/download/asset/master"  (0x2c>>1 = 22)
//       0xea160 = "/resource/scenario"      (0x24>>1 = 18)
//   拼装顺序（照 0x66550-0x667d0 的临时量流向）：
//       slot0 = base + "/magica/resource"
//       slot1 = slot0 + "/download/asset/master"
//       slot2 = slot1 + "/resource/scenario"
//
// 本文件按上述规格重新实现，端点经 CNDownloaderFix.getEndpoint(I)（静态方法）
// 取得。传 0 即可：Java 侧会 max(i, MIN_SNAA_VERSION=128)，与 libuwasa 实际
// 发出的 sent_version=128 一致。
//
// ## ⚠ 仍未切换加载
//
// 代码就位不等于可以切。libuwasa 与本库若同时加载，会对同一地址装两次 hook
// （Dobby vs shadowhook），行为未定义。切换必须与「停用 libuwasa」同一步做，
// 且需真机验证。本提交仍不改 com/loadLib/libLoader、不删任何 .so。

#include <jni.h>
#include <android/log.h>
#include <shadowhook.h>

#include <atomic>
#include <functional>
#include <mutex>
#include <string>
#include <string_view>
#include <unordered_map>

#include <sys/stat.h>
#include <pthread.h>

#define LOG_TAG "MagiaCN_Legacy"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* gJvm = nullptr;

// ⚠ App 类的全局引用**必须在 JNI_OnLoad 阶段**缓存。
//
// JNI_OnLoad 跑在 System.loadLibrary 的调用线程上，那个线程持有 App ClassLoader，
// FindClass 能解析到我们自己的类。而 hook 回调与我们起的工作线程走的是
// AttachCurrentThread —— 那里的 FindClass 用**系统 ClassLoader**，看不到 App 类，
// 只会返回 null 并挂一个 ClassNotFoundException。
//
// 这个坑在 （旧客户端仓库） 的 MagiaClient.cpp 里有白纸黑字的警告
// （ProxyBackends 那段），第一版还是照着踩了：真机日志里是
//     E/MagiaCN_Legacy: [UrlConfig] 找不到 CNDownloaderFix
static jclass gClsDownloaderFix = nullptr;   // io.kamihama.magianative.CNDownloaderFix
static jclass gClsRestClient    = nullptr;   // io.kamihama.magianative.RestClient

namespace cocos2d {
    struct Data { unsigned char* _bytes; ssize_t _size; };
}

// 安装完成标记。必须与 Java 侧 CNDownloaderFix.FINAL_FLAG 逐字一致，
// 也与 libcn_hook 内建的 BASE_DIR + "madomagi/" + "magica/cn_base_done.flag"
// 一致（已逐字节核对）。
static const std::string FLAG_PATH =
    "/data/data/io.kamihama.totentanz/files/madomagi/magica/cn_base_done.flag";

// ─── 原函数指针 ──────────────────────────────────────────
static bool (*checkParseJsonOld)(void*, const cocos2d::Data&) = nullptr;
static void (*dlJsonOnRespOld)(void*, void*, void*)  = nullptr;
static void (*dlJsonOnErrOld)(void*, void*, int)     = nullptr;
static void (*dlJsonOnRespErrOld)(void*)             = nullptr;
static void (*selectURLOnRespOld)(void*, void*, void*) = nullptr;
static void (*selectURLOnErrOld)(void*, void*, int)  = nullptr;
static void (*mainSceneOnErrOld)(void*, void*, int)  = nullptr;
static void (*qbSceneOnRespOld)(void*, void*, void*) = nullptr;
static void (*questDataOnRespOld)(void*, void*, void*) = nullptr;
static void (*assetLoadOnDownloadedOld)(void*)       = nullptr;

static void (*dslInfoCtorOld)(void*, int, const std::function<void()>&,
                              const std::string&, int) = nullptr;
static void (*downloadSceneLayerCtorOld)(void*, void*) = nullptr;
static bool (*downloadSceneLayerInitOld)(void*)        = nullptr;
static void (*downloadSceneLayerOnEnterOld)(void*)     = nullptr;

// libuwasa 逆向移植来的两个
static int  (*criNcvGetHwSampleRateOld)(void)        = nullptr;
static void (*setMaxConnectionNumOld)(void*, int)    = nullptr;

// 端点重定向：返回 const std::string&，故原型返回 const std::string*
static const std::string* (*urlConfigResourceOld)(void*, int) = nullptr;

// ─── info/layer 映射 ─────────────────────────────────────
static std::unordered_map<void*, std::function<void()>> g_infoCallbackMap;
static std::mutex g_infoCallbackMutex;
static std::unordered_map<void*, void*> g_layerInfoMap;
static std::mutex g_layerInfoMutex;

// 安装器只叫一次
static std::atomic<bool> g_downloadTriggered{false};

// ─── 辅助 ────────────────────────────────────────────────
static bool fileExists(const std::string& p) {
    struct stat st;
    return ::stat(p.c_str(), &st) == 0;
}
static bool resourcesReady() { return fileExists(FLAG_PATH); }

static JNIEnv* attachEnv(bool& attached) {
    attached = false;
    if (!gJvm) return nullptr;
    JNIEnv* env = nullptr;
    if (gJvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if (gJvm->AttachCurrentThread(&env, nullptr) == JNI_OK) { attached = true; return env; }
    return nullptr;
}

// 叫起 Java 侧安装器（RestClient.startCNDownload → CNDownloaderFix.runInstaller）。
//
// 必须换线程：本函数从引擎的网络/GL 线程调进来，而 runInstaller 会一直阻塞到
// 装完（有文件失败时甚至会停在那里等玩家点重试）。在原线程上直接调会把引擎挂死。
//
// ⚠ CallStaticVoidMethod 之后必须 ExceptionCheck + ExceptionClear：Java 侧一旦
// 漏出异常而我们不清，后续 JNI 调用行为未定义。Java 侧 runInstaller 已经整体
// 套了 catch(Throwable) 保证不外抛，这里是第二道。
static void* triggerThreadMain(void*) {
    bool attached = false;
    JNIEnv* env = attachEnv(attached);
    if (!env) { LOGE("[trigger] 拿不到 JNIEnv"); return nullptr; }

    // 用 JNI_OnLoad 缓存的全局引用，不要在这里 FindClass（见文件顶部的说明）
    jclass cls = gClsRestClient;
    if (!cls) {
        LOGE("[trigger] RestClient 全局引用缺失（JNI_OnLoad 阶段没缓存上）");
        if (attached) gJvm->DetachCurrentThread();
        return nullptr;
    }
    jmethodID mid = env->GetStaticMethodID(cls, "startCNDownload", "()V");
    if (!mid) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGE("[trigger] 找不到 startCNDownload()V");
        if (attached) gJvm->DetachCurrentThread();
        return nullptr;
    }
    LOGI("[trigger] ★ 调用 RestClient.startCNDownload()");
    env->CallStaticVoidMethod(cls, mid);
    if (env->ExceptionCheck()) {
        LOGE("[trigger] startCNDownload 抛出异常，已清除");
        env->ExceptionClear();
    }
    if (attached) gJvm->DetachCurrentThread();
    return nullptr;
}

static void triggerCNDownload(const char* reason) {
    bool expected = false;
    if (!g_downloadTriggered.compare_exchange_strong(expected, true)) return;
    LOGI("[trigger] reason=%s", reason);
    pthread_t t;
    if (pthread_create(&t, nullptr, triggerThreadMain, nullptr) == 0) {
        pthread_detach(t);
    } else {
        LOGE("[trigger] pthread_create 失败");
        g_downloadTriggered.store(false);   // 允许后续重试
    }
}

// ─── 端点重定向（取代 libuwasa 的 UrlConfig::resource）────
//
// 三个槽位在 SNAA 响应回来后一次性写好，之后只读不改；读侧靠 acquire 看到
// release 之前的全部写入。返回的是 static 数组元素的地址，生命周期与进程同寿，
// 引擎拿去当 const std::string& 用是安全的。
static std::string       g_resourceUrl[3];
static std::atomic<bool> g_resourceReady{false};

static const std::string* urlConfigResourceNew(void* self, int type) {
    if (g_resourceReady.load(std::memory_order_acquire) && type >= 0 && type < 3) {
        return &g_resourceUrl[type];
    }
    return urlConfigResourceOld(self, type);
}

// 从 SNAA 响应里抠出 endpoint。响应形如
//   {"message":"snaa","response":{"endpoint":"https://...","max_threads":40,...},"status":200}
// 只取第一个 "endpoint" 的字符串值；不引 JSON 库（本库不该为这点事背依赖）。
static std::string extractEndpoint(const std::string& json) {
    static const std::string KEY = "\"endpoint\"";
    size_t k = json.find(KEY);
    if (k == std::string::npos) return std::string();
    size_t c = json.find(':', k + KEY.size());
    if (c == std::string::npos) return std::string();
    size_t q1 = json.find('"', c);
    if (q1 == std::string::npos) return std::string();
    size_t q2 = json.find('"', q1 + 1);
    if (q2 == std::string::npos) return std::string();
    return json.substr(q1 + 1, q2 - q1 - 1);
}

static void buildResourceUrls(const std::string& base) {
    // 后缀与拼装顺序照抄 libuwasa 的逆向结果，见文件头。
    g_resourceUrl[0] = base + "/magica/resource";
    g_resourceUrl[1] = g_resourceUrl[0] + "/download/asset/master";
    g_resourceUrl[2] = g_resourceUrl[1] + "/resource/scenario";
    g_resourceReady.store(true, std::memory_order_release);
    for (int i = 0; i < 3; i++) LOGI("[UrlConfig] resource[%d] = %s", i, g_resourceUrl[i].c_str());
}

// 取端点。走 CNDownloaderFix.getEndpoint(I)（静态方法）而不是 RestClient.GetEndpoint
// （那是实例方法，还得先造对象）。传 0 即可，Java 侧会 max(i, 128)。
static void* endpointThreadMain(void*) {
    bool attached = false;
    JNIEnv* env = attachEnv(attached);
    if (!env) { LOGE("[UrlConfig] 拿不到 JNIEnv"); return nullptr; }
    do {
        // 同上：用全局引用，不 FindClass
        jclass cls = gClsDownloaderFix;
        if (!cls) { LOGE("[UrlConfig] CNDownloaderFix 全局引用缺失"); break; }
        jmethodID mid = env->GetStaticMethodID(cls, "getEndpoint", "(I)Ljava/lang/String;");
        if (!mid) { if (env->ExceptionCheck()) env->ExceptionClear();
                    LOGE("[UrlConfig] 找不到 getEndpoint(I)"); break; }
        jobject js = env->CallStaticObjectMethod(cls, mid, (jint)0);
        if (env->ExceptionCheck()) { env->ExceptionClear(); LOGE("[UrlConfig] getEndpoint 抛异常"); }
        std::string json;
        if (js) {
            const char* utf = env->GetStringUTFChars((jstring)js, nullptr);
            if (utf) { json = utf; env->ReleaseStringUTFChars((jstring)js, utf); }
            env->DeleteLocalRef(js);
        }
        std::string base = extractEndpoint(json);
        if (base.empty()) { LOGE("[UrlConfig] 响应里没有 endpoint，放弃重定向（将回落到原版地址）"); break; }
        while (!base.empty() && base.back() == '/') base.pop_back();   // 去掉尾斜杠，免得拼成 //
        LOGI("[UrlConfig] endpoint = %s", base.c_str());
        buildResourceUrls(base);
    } while (false);
    if (attached) gJvm->DetachCurrentThread();
    return nullptr;
}

// ─── 下载场景三连 ────────────────────────────────────────
static void dslInfoCtorNew(void* _this, int type,
                           const std::function<void()>& cb,
                           const std::string& category, int running) {
    dslInfoCtorOld(_this, type, cb, category, running);
    { std::lock_guard<std::mutex> lk(g_infoCallbackMutex); g_infoCallbackMap[_this] = cb; }
    LOGI("[DSLInfo::ctor] _this=%p 已保存 callback 副本", _this);
}

static void downloadSceneLayerCtorNew(void* _this, void* info) {
    downloadSceneLayerCtorOld(_this, info);
    { std::lock_guard<std::mutex> lk(g_layerInfoMutex); g_layerInfoMap[_this] = info; }
    LOGI("[DSL::ctor] _this=%p info=%p ready=%d", _this, info, (int)resourcesReady());
}

static bool downloadSceneLayerInitNew(void* _this) {
    bool r = downloadSceneLayerInitOld(_this);
    LOGI("[DSL::init] result=%d ready=%d", (int)r, (int)resourcesReady());
    return r;
}

// 资源已就位时，直接在 GL 主线程调完成回调，完全跳过引擎自带的下载 UI。
// flag 不存在说明还没装（或装到一半），放行原版——此时我们的浮层正盖在上面。
static void downloadSceneLayerOnEnterNew(void* _this) {
    if (!resourcesReady()) {
        LOGI("[DSL::onEnter] flag 不存在，放行原版");
        downloadSceneLayerOnEnterOld(_this);
        return;
    }
    void* info = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_layerInfoMutex);
        auto it = g_layerInfoMap.find(_this);
        if (it != g_layerInfoMap.end()) { info = it->second; g_layerInfoMap.erase(it); }
    }
    if (!info) {
        LOGE("[DSL::onEnter] 未找到 info _this=%p，降级放行", _this);
        downloadSceneLayerOnEnterOld(_this);
        return;
    }
    std::function<void()> cb;
    {
        std::lock_guard<std::mutex> lk(g_infoCallbackMutex);
        auto it = g_infoCallbackMap.find(info);
        if (it != g_infoCallbackMap.end()) { cb = it->second; g_infoCallbackMap.erase(it); }
    }
    if (!cb) {
        LOGE("[DSL::onEnter] 未找到 callback 副本 info=%p，降级放行", info);
        downloadSceneLayerOnEnterOld(_this);
        return;
    }
    LOGI("[DSL::onEnter] ★ 跳过下载 UI，直接调完成回调 info=%p", info);
    cb();
    LOGI("[DSL::onEnter] ✓ 回调执行完毕");
}

static void assetLoadOnDownloadedNew(void* _this) {
    LOGI("[AssetLoad::onDownloaded] _this=%p ready=%d", _this, (int)resourcesReady());
    assetLoadOnDownloadedOld(_this);
}

// ─── 资源清单解析 ────────────────────────────────────────
static cocos2d::Data g_emptyData{ (unsigned char*)"[]", 2 };

static bool checkParseJsonNew(void* _this, const cocos2d::Data& data) {
    if (!resourcesReady()) {
        // 还没装：叫起 Java 安装器，并让引擎拿到空清单（它就不会自己去下）
        triggerCNDownload("checkParseJson");
        LOGE("[checkParseJson] flag 缺失，返回空列表");
        return checkParseJsonOld(_this, g_emptyData);
    }
    if (data._bytes && data._size > 0) {
        // 有界搜索：缓冲区不保证 NUL 结尾，strstr 会越界
        std::string_view sv(reinterpret_cast<const char*>(data._bytes),
                            static_cast<size_t>(data._size));
        if (sv.find("asset_optimize") != std::string_view::npos) {
            LOGI("[checkParseJson] 修正 asset_optimize");
            std::string patched(sv);          // 栈上副本，避免静态存储的竞态
            size_t pos = 0;
            while ((pos = patched.find(":1", pos)) != std::string::npos) {
                patched.replace(pos, 2, ":0");
                pos += 2;
            }
            cocos2d::Data d;
            d._bytes = reinterpret_cast<unsigned char*>(patched.data());
            d._size  = static_cast<ssize_t>(patched.size());
            return checkParseJsonOld(_this, d);
        }
    }
    return checkParseJsonOld(_this, data);
}

// ─── 下载相关回调：资源已就位时一律静默 ──────────────────
static void selectURLOnRespNew(void* a, void* b, void* c) {
    if (resourcesReady()) { LOGI("[SelectURL::onResp] 静默"); return; }
    selectURLOnRespOld(a, b, c);
}
static void selectURLOnErrNew(void* a, void* b, int code) {
    if (resourcesReady()) { LOGI("[SelectURL::onErr] 静默 code=%d", code); return; }
    selectURLOnErrOld(a, b, code);
}
static void dlJsonOnRespNew(void* a, void* b, void* c) {
    if (resourcesReady()) return;
    dlJsonOnRespOld(a, b, c);
}
static void dlJsonOnErrNew(void* a, void* b, int code) {
    if (resourcesReady()) { LOGI("[DLJson::onErr] 静默 code=%d", code); return; }
    dlJsonOnErrOld(a, b, code);
}
static void dlJsonOnRespErrNew(void* a) {
    if (resourcesReady()) return;
    dlJsonOnRespErrOld(a);
}
static void qbSceneOnRespNew(void* a, void* b, void* c) {
    if (!resourcesReady()) return;
    qbSceneOnRespOld(a, b, c);
}
static void questDataOnRespNew(void* a, void* b, void* c) {
    if (!resourcesReady()) return;
    questDataOnRespOld(a, b, c);
}

// 出错路径：还没装完就报错，说明引擎在等资源——叫起安装器并吞掉错误，
// 免得它弹自己的错误框。
static void mainSceneOnErrNew(void* a, void* b, int code) {
    LOGI("[MainScene::onErr] code=%d ready=%d", code, (int)resourcesReady());
    if (!resourcesReady()) {
        triggerCNDownload("MainScene::onError");
        LOGE("[MainScene::onErr] flag 缺失，静默丢弃 code=%d", code);
        return;
    }
    mainSceneOnErrOld(a, b, code);
}

// ─── 从 libuwasa 逆向移植的两个（其余一概不移植）──────────
//
// ADX2 在初始化时查硬件采样率；部分设备返回 44100，导致内部重采样后音调
// 轻微偏移。锁 48000 与内容母带一致。
static int criNcvGetHwSampleRateNew(void) {
    int orig = criNcvGetHwSampleRateOld ? criNcvGetHwSampleRateOld() : 0;
    LOGI("[ADX2] GetHardwareSamplingRate: device=%d → 48000", orig);
    return 48000;
}
// 游戏初始化调 setMaxConnectionNum(4)，4 条并发 HTTP/2 stream 拉资产。
// 提到 10 能明显缩短首次资产加载。
static void setMaxConnectionNumNew(void* _this, int n) {
    int patched = (n == 4) ? 10 : n;
    if (patched != n) LOGI("[http2] setMaxConnectionNum %d → %d", n, patched);
    setMaxConnectionNumOld(_this, patched);
}

// ─── JNI_OnLoad ──────────────────────────────────────────
extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    gJvm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    LOGI("========== MagiaLegacy JNI_OnLoad ==========");
    LOGI("[VERSION] magia-native v1（取代 libuwasa；下载流水线待接管）");

    // ── 先缓存 App 类的全局引用 ──
    // 本函数所在线程持有 App ClassLoader，这是唯一能 FindClass 到我们自己类的时机。
    {
        struct { const char* name; jclass* slot; } want[] = {
            { "io/kamihama/magianative/CNDownloaderFix", &gClsDownloaderFix },
            { "io/kamihama/magianative/RestClient",      &gClsRestClient    },
        };
        for (size_t i = 0; i < sizeof(want) / sizeof(want[0]); i++) {
            jclass local = env->FindClass(want[i].name);
            if (local) {
                *want[i].slot = (jclass)env->NewGlobalRef(local);
                env->DeleteLocalRef(local);
                LOGI("[JNI] 已缓存 %s", want[i].name);
            } else {
                if (env->ExceptionCheck()) env->ExceptionClear();
                LOGE("[JNI] 找不到 %s —— 相关功能将不可用", want[i].name);
            }
        }
    }

    const char* LIB = "libmadomagi_native.so";

    int rc = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
    if (rc != 0) {
        // init 失败时后面每个 hook 都会以同样的 errno 失败，刷十几行同样的错
        // 没有意义，直接收工——本库不装 hook 也不影响进程存活。
        LOGE("[shadowhook] init 失败 rc=%d errno=%d %s，本次不装任何 hook",
             rc, shadowhook_get_errno(), shadowhook_to_errmsg(shadowhook_get_errno()));
        LOGE("[shadowhook] version=%s", shadowhook_get_version());
        return JNI_VERSION_1_6;
    }
    LOGI("[shadowhook] init OK version=%s", shadowhook_get_version());
    // 提醒：我们在构建期把「linker mod 初始化失败」改成了非致命（见 CMakeLists
    // 的 PATCH_COMMAND）。代价是延迟 hook 不可用，因此下面任何一个 hook 若返回
    // PENDING（目标库当时没加载）就等于永久失败，H() 会把它当错误报出来。

    int hookOk = 0, hookFail = 0;
    auto H = [&](const char* sym, void* fn, void** old, const char* label) -> bool {
        void* stub = shadowhook_hook_sym_name(LIB, sym, fn, old);
        if (stub) { LOGI("[Hook] ✓ %s", label); hookOk++; return true; }
        int e = shadowhook_get_errno();
        // 关掉 linker mod 后 PENDING 永远不会被补上，等同于失败，单独点名。
        if (e == SHADOWHOOK_ERRNO_PENDING) {
            LOGE("[Hook] ✗ %s PENDING —— 目标库未加载，且延迟 hook 已禁用", label);
        } else {
            LOGE("[Hook] ✗ %s errno=%d %s", label, e, shadowhook_to_errmsg(e));
        }
        hookFail++;
        return false;
    };

    // ── 资源下载流水线 ──
    H("_ZN22DownloadAssetJsonState14checkParseJsonERKN7cocos2d4DataE",
      (void*)checkParseJsonNew, (void**)&checkParseJsonOld, "checkParseJson");
    H("_ZN22DownloadAssetJsonState10onResponseEPN5http212Http2SessionEPNS0_13Http2ResponseE",
      (void*)dlJsonOnRespNew, (void**)&dlJsonOnRespOld, "DLJson::onResp");
    H("_ZN22DownloadAssetJsonState7onErrorEPN5http212Http2SessionEi",
      (void*)dlJsonOnErrNew, (void**)&dlJsonOnErrOld, "DLJson::onErr");
    H("_ZN22DownloadAssetJsonState15onResponseErrorEv",
      (void*)dlJsonOnRespErrNew, (void**)&dlJsonOnRespErrOld, "DLJson::onRespErr");
    H("_ZN29SelectURLGetResourceListState10onResponseEPN5http212Http2SessionEPNS0_13Http2ResponseE",
      (void*)selectURLOnRespNew, (void**)&selectURLOnRespOld, "SelectURL::onResp");
    H("_ZN29SelectURLGetResourceListState7onErrorEPN5http212Http2SessionEi",
      (void*)selectURLOnErrNew, (void**)&selectURLOnErrOld, "SelectURL::onErr");
    H("_ZN9MainScene7onErrorEPN5http212Http2SessionEi",
      (void*)mainSceneOnErrNew, (void**)&mainSceneOnErrOld, "MainScene::onErr");
    H("_ZN20QbSceneJsonGetServer10onResponseEPN5http212Http2SessionEPNS0_13Http2ResponseE",
      (void*)qbSceneOnRespNew, (void**)&qbSceneOnRespOld, "QbScene::onResp");
    H("_ZN25QuestStoredDataSceneLayer10onResponseEPN5http212Http2SessionEPNS0_13Http2ResponseE",
      (void*)questDataOnRespNew, (void**)&questDataOnRespOld, "QuestData::onResp");
    H("_ZN14AssetLoadState12onDownloadedEv",
      (void*)assetLoadOnDownloadedNew, (void**)&assetLoadOnDownloadedOld,
      "AssetLoadState::onDownloaded");

    // ── 下载场景三连 ──
    H("_ZN22DownloadSceneLayerInfoC2E15ESceneLayerTypeRKNSt6__ndk18functionIFvvEEERKNS1_12basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEN19DownloadRunningType21DownloadRunningType__E",
      (void*)dslInfoCtorNew, (void**)&dslInfoCtorOld, "DownloadSceneLayerInfo::ctor");
    H("_ZN18DownloadSceneLayerC1EP22DownloadSceneLayerInfo",
      (void*)downloadSceneLayerCtorNew, (void**)&downloadSceneLayerCtorOld, "DSL::ctor");
    H("_ZN18DownloadSceneLayer4initEv",
      (void*)downloadSceneLayerInitNew, (void**)&downloadSceneLayerInitOld, "DSL::init");
    H("_ZN18DownloadSceneLayer7onEnterEv",
      (void*)downloadSceneLayerOnEnterNew, (void**)&downloadSceneLayerOnEnterOld, "DSL::onEnter");

    // ── 从 libuwasa 移植的性能 hook ──
    H("criNcv_GetHardwareSamplingRate_ANDROID",
      (void*)criNcvGetHwSampleRateNew, (void**)&criNcvGetHwSampleRateOld,
      "criNcv_GetHardwareSamplingRate(→48000)");
    H("_ZN5http212Http2Session19setMaxConnectionNumEi",
      (void*)setMaxConnectionNumNew, (void**)&setMaxConnectionNumOld,
      "http2::setMaxConnectionNum(4→10)");

    // ── 端点重定向（原 libuwasa 的核心职责）──
    H("_ZNK9UrlConfig8resourceENS_8Resource4TypeE",
      (void*)urlConfigResourceNew, (void**)&urlConfigResourceOld,
      "UrlConfig::resource(端点重定向)");

    // 尽早发起 SNAA 查询：引擎很快就会问资源地址。取不到就保持 ready=false，
    // resource() 会一路回落到原版，不会卡住也不会崩。
    {
        pthread_t t;
        if (pthread_create(&t, nullptr, endpointThreadMain, nullptr) == 0) pthread_detach(t);
        else LOGE("[UrlConfig] 端点线程起不来");
    }

    LOGI("[JNI] hooks 安装完成：成功 %d 个，失败 %d 个", hookOk, hookFail);
    return JNI_VERSION_1_6;
}
