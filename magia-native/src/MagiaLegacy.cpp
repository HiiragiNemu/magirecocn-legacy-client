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
// 保留资源下载流水线，并补回 libcn_hook 特有的「叫起 Java 安装器」触发。
// 强制新手教程曾经走过两个版本：v1 从 native 拦 pushSceneTop 改调
// pushScenePrologue（真机上只放得出战斗、放不出剧情）；v2 改走前端路由播
// 完整序章（CNPrologueNav），完整序章太难维护，弃用。v3 复活 v1 的 native
// 入口并修掉它的 bug——pushScenePrologue 的 JSON 补回 callback 字段。真机
// 意外收获：callback 一修，**完整序章（剧情 + 战斗）都能播了**。v4（当前）
// 解决 v3 真机暴露的两个问题：序章剧情段放完后 WebView 自己复出、把战斗
// 盖成背景板（改为序章全程压住 WebView）；序章结束后前端状态不可知（改为
// Toast + 3 秒 + 重启，回到干净主页）。见下文「强制序章」小节。
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
#include <memory>
#include <mutex>
#include <new>        // ::operator new（fontPathOverwrite 的独立缓冲分配）
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include <sys/stat.h>
#include <pthread.h>
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

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
static jclass gClsDownloaderFix   = nullptr; // io.kamihama.magianative.CNDownloaderFix
static jclass gClsRestClient      = nullptr; // io.kamihama.magianative.RestClient
static jclass gClsTutorialPrompt  = nullptr; // io.kamihama.magianative.CNTutorialPrompt
static jclass gClsVersionCheck    = nullptr; // io.kamihama.magianative.CNVersionCheck
static jclass gClsCNMirrors       = nullptr; // io.kamihama.magianative.CNMirrors

namespace cocos2d {
    struct Data { unsigned char* _bytes; ssize_t _size; };
}

// 安装完成标记。必须与 Java 侧 CNDownloaderFix.FINAL_FLAG 逐字一致，
// 也与 libcn_hook 内建的 BASE_DIR + "madomagi/" + "magica/cn_base_done.flag"
// 一致（已逐字节核对）。
static const std::string FLAG_PATH =
    "/data/data/io.kamihama.totentanz/files/madomagi/magica/cn_base_done.flag";

// 强制序章标记。由 Java 侧 CNTutorialPrompt 在玩家选「是」时写出，
// 我们在引擎首个「进主页」命令上消费它。放在与安装标记同一个目录，
// 那个目录在资源装完时必定存在，不必额外 mkdir。
// ⚠ 必须与 CNTutorialPrompt.FORCE_TUTORIAL_FLAG 逐字一致。
static const std::string FORCE_TUTORIAL_FLAG_PATH =
    "/data/data/io.kamihama.totentanz/files/madomagi/magica/cn_force_tutorial.flag";

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

// 强制序章：拦「进主页」，改走引擎自己的序章场景
static void (*pushSceneTopOld)(void*, const std::string&)     = nullptr;
static void (*notifyJsOld)(void*, const std::string&)         = nullptr;
static void (*prologueCtorOld)(void*, void*)                  = nullptr;
static void (*prologueDtorOld)(void*)                         = nullptr;
// 只取地址、不装 hook：命中标记时直接调它
static void (*pushScenePrologueFn)(void*, const std::string&) = nullptr;

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

// ─── 强制序章 ────────────────────────────────────────
//
// 复刻服对任何账号都下发「已通关」的存档，引擎的正常流程永远不会播教程。
// 唯一可靠的入口是拦下前端那条「进主页」命令（web::SceneCommand::pushSceneTop），
// 命中标记时改为进序章场景。
//
// ## 这版（v4）与历史几版的关系
//
// v1 从 native 压序章场景，真机上只放得出最后那场战斗、剧情文字一句都没有。
// 当时把这判定为失败，于是有了 v2（Java 侧前端路由播完整序章，CNPrologueNav）。
// v2 的完整序章太难维护，弃用。v3 复活 v1 的 native 入口并修掉它真正的 bug
// （见下），真机意外发现：**callback 一修，剧情和战斗就全都能播了**。
// v4（本版）解决 v3 真机暴露的两个收尾问题：
//
//   1. 剧情段放完后 WebView 自己复出（前端加载收尾或引擎界面管理所至，
//      没去细查——不必查，压住就行），把战斗盖成背景板。v3 只在序章图层
//      构造时藏一次，不够；v4 改为**序章全程**每 250ms 把前端界面按回隐藏，
//      直到图层析构。
//   2. 序章放完时前端状态不可知（主页加载到一半、被藏了整场、还收了一堆
//      段通知）。v3 的「补放吞掉的 pushSceneTop」并不能保证回到的主页是
//      好的；v4 改为与「安装完成」同一套的 Toast + 3 秒 + 重启，重启后
//      是干净的主页。标记在触发时已删，重启后不会再进序章。
//
// ## v1 的 bug：pushScenePrologue 的 JSON 缺 callback 字段
//
// v1 调的是 pushScenePrologue("{\"beginningId\":\"OP020\"}")。反汇编
// PrologueSceneLayerInfo 的构造（0xd1ecbc 起）可知 JSON 认两个字段：
// "beginningId" 与 "callback"，缺省都有兜底——callback 的兜底是一个单字符
// 的串。于是 PrologueSceneLayer::notifyJs 每次经 WebViewManager::evaluateJS
// 下发的语句都形如  x("OP020");  ——页面里没有这个函数，句句话都是
// ReferenceError，前端从头到尾收不到任何段通知，包括最后的完成信号。
//
// 修复是给上 callback："nativeCallback"。它是前端 js/_common/base.js 里
// 定义的全局函数（引擎二进制里也硬编码着这个名字），会把参数转发成
// #commandDiv 的 jQuery 事件。v3 真机验证：修好之后段通知真的到达前端，
// 剧情与战斗都按序章自己的流程播放——所谓「序章是前端驱动的流程」，
// 前端要的就是这条能用的通知信道。
//
// ## 为什么调 pushScenePrologue 而不是自己 new 一个 Info
//
// （旧 native 层） 那边是逐字段复刻调试菜单「播放序章」的构造：
//     new PrologueSceneLayerInfo(0x58 字节) → ctor(9, "OP020", "{}")
//     → SceneLayerManager::getInstance() → 虚表 [vptr+0x18] 压栈
// 这套在本仓库的 arm64 引擎上逐字节核对过，是对的（0xd1f054 起那段）。
// 但它把**结构体大小**和**虚表下标**写死了，而这两个值在 armeabi-v7a 上
// 必然不同（指针 4 字节），得再逆一遍 arm32 才敢用。
//
// 引擎自己的 web::SceneCommand::pushScenePrologue(const std::string& json)
// 干的就是同一件事——解析 json，然后走上面那段构造。两个 ABI 都导出这个
// 符号，直接调它就把「结构体多大、虚表第几项」整件事交还给引擎，一份代码
// 两个 ABI 通用。
//
// ## 为什么要一直吞掉 pushSceneTop，而不是只吞第一次
//
// 第一版只在命中标记时把首个 pushSceneTop 换成序章，之后放行。真机结果是
// **序章被压在主界面后面，成了主界面的背景**。
//
// 原因在 SceneLayerManager::pushSceneLayer（0xb82610）：它不按 ESceneLayerType
// 排层序，只是把任务塞进一个 deque，之后按**入队顺序**处理。也就是说后 push
// 的盖在先 push 的上面。序章（type=9）先入队，随后又来的 pushSceneTop
// （type=11）后入队，于是主页盖在序章上。
//
// 所以：从强制那一刻起，到序章图层真正销毁为止，期间所有 pushSceneTop 一律
// 吞掉。销毁时不再补放（v4 改为 Toast + 重启，见本节开头），但吞掉的最后
// 一次仍留着——重启通道万一 JNI 不通，补放是别把玩家留在黑屏上的兜底。
static std::atomic<bool> g_tutorialForced{false};
// 强制教程进行中：置位于强制那一刻，清除于 PrologueSceneLayer 析构。
// 必须比「序章图层存在」更宽——push 只是入队，图层要等队列被处理才构造，
// 这中间的窗口同样不能放主页进来。
static std::atomic<bool> g_tutorialActive{false};
// 被吞掉的最后一次 pushSceneTop，供序章结束后原样补放
static std::mutex   g_savedTopMutex;
static void*        g_savedTopSelf = nullptr;
static std::string  g_savedTopArg;
static bool         g_savedTopValid = false;

// 消费标记：存在则删除（一次性）并返回 true。
// 删除是关键——只强制一次；序章结束后的 pushSceneTop 必须放行，
// 否则就卡在序章里出不来。
static bool consumeForceTutorial() {
    if (!fileExists(FORCE_TUTORIAL_FLAG_PATH)) return false;
    if (::remove(FORCE_TUTORIAL_FLAG_PATH.c_str()) != 0) {
        // 删不掉就不能强制——否则每次进主页都会被打回序章，玩家永远进不去。
        LOGE("[Tutorial] 标记删不掉（%s），本次不强制，以免陷入死循环",
             FORCE_TUTORIAL_FLAG_PATH.c_str());
        return false;
    }
    return true;
}

static void saveTop(void* self, const std::string& arg) {
    std::lock_guard<std::mutex> lk(g_savedTopMutex);
    g_savedTopSelf  = self;
    g_savedTopArg   = arg;
    g_savedTopValid = true;
}

static void setGameUiVisible(bool visible);   // 前向声明：定义在 pushSceneTopNew 之后

// ─── 下载浮层期间的引擎闸门 ──────────────────────────────
// 浮层（首装/热更下载）激活期间：吞掉 pushSceneTop、挂起 BGM；浮层撤掉后
// 补推主页跳转并补放最后的 BGM。标记文件由 Java 侧 CNCNDownloadUI 维护：
// show 时创建、每 2 秒心跳 touch、hide 时删除。mtime 超过窗口视为进程
// 被杀留下的残留，自动失效——宁可闸不住也绝不能把引擎闸死在加载页。
// 窗口从 6s 放宽到 10s：Oppo Watch X 这类弱机在并行分片下载 + WebView
// 渲染同时打满 CPU 时，守护心跳线程可能被饿过 6s（约 3 次心跳），过早
// 失效会让引擎在下载中途抢跑主页跳转/BGM。10s 对应约 5 次心跳的容错。
static const std::string OVERLAY_FLAG_PATH =
    "/data/data/io.kamihama.totentanz/files/madomagi/cn_overlay_active.flag";

static bool overlayActive() {
    struct stat st;
    if (::stat(OVERLAY_FLAG_PATH.c_str(), &st) != 0) return false;
    return (::time(nullptr) - st.st_mtime) < 10;
}

static std::atomic<bool> g_topDeferred{false};
static std::string       g_deferredTopArg;
static void*             g_deferredTopSelf = nullptr;
static std::atomic<bool> g_bgmDeferred{false};
static std::string       g_deferredBgm;
// 上面的 string/指针字段跨线程读写（playBgmDirectNew 可能来自音频线程、
// pushSceneTopNew 来自引擎线程），必须用锁保护。原子布尔只做快速路径判断。
static std::mutex        g_deferredMutex;

static void pushSceneTopNew(void* self, const std::string& arg);  // 前向声明

using PlayBgmFn = void (*)(const char*);
static PlayBgmFn playBgmDirectOld = nullptr;
static void playBgmDirectNew(const char* name) {
    if (overlayActive()) {
        LOGI("[Overlay] 浮层激活，挂起 BGM: %s", name ? name : "(null)");
        if (name) {
            std::lock_guard<std::mutex> lk(g_deferredMutex);
            g_deferredBgm = name;
            g_bgmDeferred.store(true);
        }
        return;
    }
    playBgmDirectOld(name);
}

// 在引擎主线程的周期性回调里被调用（setString/setText 系列钩子）。
static void maybeReleaseDeferredTop() {
    if (!g_topDeferred.load() && !g_bgmDeferred.load()) return;
    if (overlayActive()) return;
    // 锁内取出并消费标记，锁外执行：不把引擎调用（playBgmDirectOld /
    // pushSceneTopNew）关在锁里，避免在引擎主线程上持锁等待。
    std::string bgm;
    void* self = nullptr;
    std::string arg;
    bool relBgm = false, relTop = false;
    {
        std::lock_guard<std::mutex> lk(g_deferredMutex);
        if (g_bgmDeferred.exchange(false)) { bgm = g_deferredBgm; relBgm = true; }
        if (g_topDeferred.exchange(false)) { self = g_deferredTopSelf; arg = g_deferredTopArg; relTop = true; }
    }
    if (relBgm && playBgmDirectOld) {
        LOGI("[Overlay] 浮层已撤，补放 BGM: %s", bgm.c_str());
        playBgmDirectOld(bgm.c_str());
    }
    if (relTop) {
        LOGI("[Overlay] 浮层已撤，补推被闸住的主页跳转(arg=%s)", arg.c_str());
        pushSceneTopNew(self, arg);  // 走完整逻辑（含教程闸门）
    }
}


// 序章全程压住前端界面的看门狗。置位于触发那一刻，收尾于序章图层析构
// （g_tutorialActive 清零）。v3 真机发现：剧情段放完后 WebView 会自己复出，
// 把战斗盖成背景板——只在图层构造时藏一次不够，得全程按回去。
// 日志不吵：Java 侧只在「真的又被放出来了」时才记（见 setGameUiVisible）。
static std::atomic<bool> g_uiWatchdogOn{false};
static void* uiWatchdogMain(void*) {
    while (g_tutorialActive.load()) {
        setGameUiVisible(false);
        usleep(250 * 1000);
    }
    g_uiWatchdogOn.store(false);
    return nullptr;
}

static void pushSceneTopNew(void* self, const std::string& arg) {
    // ── 下载浮层闸门：浮层（首装/热更）激活期间吞掉主页跳转 ──
    // 不然引擎在浮层后面直接推进到主页并开始放 BGM。
    // 被吞的跳转在浮层撤掉后由 maybeReleaseDeferredTop 补推（走完整逻辑）。
    if (overlayActive()) {
        LOGI("[Overlay] 下载浮层激活，闸住 pushSceneTop(arg=%s)", arg.c_str());
        std::lock_guard<std::mutex> lk(g_deferredMutex);
        g_deferredTopSelf = self;
        g_deferredTopArg  = arg;
        g_topDeferred.store(true);
        return;
    }
    // 教程进行中：一律吞掉，别让主页盖在序章上面（理由见上）
    if (g_tutorialActive.load()) {
        LOGI("[Tutorial] 教程进行中，吞掉 pushSceneTop(arg=%s)", arg.c_str());
        saveTop(self, arg);
        return;
    }
    // ⚠ 绝不在「与引擎无关的时刻」自己往队列里塞场景跳转——那才会和引擎
    // 正在进行的切场景撞车（两条切换命令都入了队，谁后处理谁盖上面，白屏
    // 就是这么来的）。本函数只在引擎**自己**发起 pushSceneTop 的这一刻被
    // 调用，我们把这条命令**原替换**成 pushScenePrologue：同一时刻队列里
    // 永远只有一条切换命令，不存在撞车窗口。引擎侧 SceneCommand 全部走
    // 游戏主线程的 deque（见 0xb82610），入队动作本身是串行的。
    if (pushScenePrologueFn && consumeForceTutorial()) {
        // callback=nativeCallback 是 v1 缺的字段，缺了它 notifyJs 下发的
        // JS 全是残的，前端收不到任何段通知（见本节开头的 bug 分析）。
        static const std::string kPrologueArg =
            "{\"beginningId\":\"OP020\",\"callback\":\"nativeCallback\"}";
        LOGI("[Tutorial] 命中强制教程标记 → 改走 pushScenePrologue(OP020)"
             "（原 pushSceneTop arg=%s）", arg.c_str());
        saveTop(self, arg);
        g_tutorialForced.store(true);
        g_tutorialActive.store(true);
        // 序章全程压住前端界面的看门狗：v3 真机发现剧情段放完后 WebView 会
        // 自己复出把战斗盖成背景板，只藏一次不够。
        if (!g_uiWatchdogOn.exchange(true)) {
            pthread_t t;
            if (pthread_create(&t, nullptr, uiWatchdogMain, nullptr) == 0) {
                pthread_detach(t);
            } else {
                g_uiWatchdogOn.store(false);
                LOGE("[Tutorial] 界面看门狗线程起不来（ctor 的一次性隐藏仍在）");
            }
        }
        pushScenePrologueFn(self, kPrologueArg);
        return;
    }
    LOGI("[SceneCmd] pushSceneTop(arg=%s) 放行", arg.c_str());
    pushSceneTopOld(self, arg);
}

// 切换前端界面（Cocos2dxWebView）的可见性。
//
// 主界面是个盖在 GL SurfaceView 之上的 Android WebView，引擎的场景图层都画在
// 它下面。正常走剧情时是前端 JS 自己发起跳转、顺手把自己藏起来；我们从 native
// 直接压场景，前端不知情，于是主界面照旧盖在最上层，序章成了它的背景。
// 由我们代劳：序章开始时藏，结束时放回来。
static void setGameUiVisible(bool visible) {
    if (!gClsTutorialPrompt) {
        LOGE("[Tutorial] CNTutorialPrompt 全局引用缺失，无法隐藏前端界面");
        return;
    }
    bool attached = false;
    JNIEnv* env = attachEnv(attached);
    if (!env) { LOGE("[Tutorial] 拿不到 JNIEnv"); return; }
    jmethodID mid = env->GetStaticMethodID(gClsTutorialPrompt, "setGameUiVisible", "(Z)V");
    if (mid) {
        env->CallStaticVoidMethod(gClsTutorialPrompt, mid, (jboolean)visible);
        if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    } else {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGE("[Tutorial] 找不到 setGameUiVisible(Z)V");
    }
    if (attached) gJvm->DetachCurrentThread();
}

// 序章结束后的重启通道：JNI 叫起 Java 侧的「Toast + 3 秒 + 重启」
// （CNTutorialPrompt.restartAfterPrologue，与安装完成同一套收尾）。
// 重启要睡 3 秒，Java 侧自己另起线程，不堵游戏线程。
// 返回 false 表示 JNI 不通，调用方走兜底。
static bool requestPrologueRestart() {
    if (!gClsTutorialPrompt) {
        LOGE("[Tutorial] CNTutorialPrompt 全局引用缺失，无法叫起重启");
        return false;
    }
    bool attached = false;
    JNIEnv* env = attachEnv(attached);
    if (!env) { LOGE("[Tutorial] 拿不到 JNIEnv"); return false; }
    jmethodID mid = env->GetStaticMethodID(gClsTutorialPrompt, "restartAfterPrologue", "()V");
    if (!mid) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGE("[Tutorial] 找不到 restartAfterPrologue()V");
        if (attached) gJvm->DetachCurrentThread();
        return false;
    }
    env->CallStaticVoidMethod(gClsTutorialPrompt, mid);
    bool ok = true;
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); ok = false; }
    if (attached) gJvm->DetachCurrentThread();
    return ok;
}

// 兜底：把吞掉的那次 pushSceneTop 补放回去。序章期间的 Top 全被我们吞了，
// 栈里没有主页可退，不补的话玩家会停在空场景上。
//
// 这里只是往 SceneLayerManager 的 deque 里再排一个任务（pushSceneLayer
// 就是个入队函数，见 0xb82610），引擎自己也常在图层里 push 别的图层，
// 不是重入路径。
static void replaySavedTop() {
    void* self = nullptr;
    std::string arg;
    bool ok = false;
    {
        std::lock_guard<std::mutex> lk(g_savedTopMutex);
        if (g_savedTopValid) { self = g_savedTopSelf; arg = g_savedTopArg; ok = true; }
        g_savedTopValid = false;
    }
    if (ok && pushSceneTopOld) {
        LOGI("[Tutorial] 序章结束，补放 pushSceneTop(arg=%s)", arg.c_str());
        pushSceneTopOld(self, arg);
    } else {
        LOGE("[Tutorial] 序章结束但没有可补放的 pushSceneTop，可能停在空场景");
    }
}

// 序章图层的构造/析构。析构是「序章真的结束了」最可靠的信号——比 notifyJs
// 可靠，后者在序章过程中可能发多次。
static void prologueCtorNew(void* _this, void* info) {
    prologueCtorOld(_this, info);
    bool forced = g_tutorialForced.load();
    LOGI("[Tutorial] PrologueSceneLayer 已构造 _this=%p（forced=%d）", _this, (int)forced);
    if (forced) setGameUiVisible(false);
}

static void prologueDtorNew(void* _this) {
    bool wasActive = g_tutorialActive.exchange(false);
    bool forced = g_tutorialForced.load();
    LOGI("[Tutorial] PrologueSceneLayer 析构 _this=%p（active=%d forced=%d）",
         _this, (int)wasActive, (int)forced);
    if (wasActive && forced) {
        // 序章放完了。前端此刻状态不可知（主页加载到一半、被我们藏了整场、
        // 还收了一堆段通知），就地收拾不如干脆重启——与「安装完成」同一套
        // Toast + 3 秒 + 重启，回来是干净的主页。标记在触发时已删，
        // 重启后不会再进序章。
        //
        // 前端界面**保持隐藏**直到进程退出：恢复出来也只会把加载到一半的
        // 主页亮给玩家看 3 秒，不如不亮。Toast 是系统级窗口，不受影响。
        if (requestPrologueRestart()) {
            LOGI("[Tutorial] 序章结束，已叫起 Toast + 3 秒 + 重启");
        } else {
            // JNI 不通时的兜底：恢复前端界面 + 补放吞掉的 pushSceneTop，
            // 至少别把玩家留在黑屏上。
            LOGE("[Tutorial] 重启通道不通，退兜底：恢复界面 + 补放 pushSceneTop");
            setGameUiVisible(true);
            replaySavedTop();
        }
    }
    prologueDtorOld(_this);
}

// 序章向前端发通知。无条件记录：callback 修好之后这些信号应该真的到达前端，
// 日志里要能看到 OP 段与最终的「prologue」完成信号逐个过去。
static void notifyJsNew(void* _this, const std::string& arg) {
    LOGI("[Tutorial::notifyJs] arg=%s", arg.c_str());
    notifyJsOld(_this, arg);
}

// 解析 pushScenePrologue 的地址。它在两个 ABI 的 .dynsym 里都是
// GLOBAL DEFAULT，普通 dlsym 就能拿到，不必动用 shadowhook 的符号解析。
static void resolvePrologueEntry(const char* lib) {
    void* h = ::dlopen(lib, RTLD_NOW | RTLD_NOLOAD);   // 引擎早已加载，只取句柄
    if (!h) h = ::dlopen(lib, RTLD_NOW);
    if (!h) { LOGE("[Tutorial] dlopen(%s) 失败：%s", lib, ::dlerror()); return; }
    void* p = ::dlsym(h,
        "_ZN3web12SceneCommand17pushScenePrologueERKNSt6__ndk1"
        "12basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE");
    if (p) {
        pushScenePrologueFn =
            reinterpret_cast<void(*)(void*, const std::string&)>(p);
        LOGI("[Tutorial] pushScenePrologue = %p", p);
    } else {
        LOGE("[Tutorial] 找不到 pushScenePrologue，强制教程将不可用");
    }
    ::dlclose(h);
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

// ─── 客户端版本号 ────────────────────────────────────────
//
// 本客户端自己的版本号，不读也不改 APK 的 versionName/versionCode（那是上游
// 包的身份，动了会影响覆盖安装）。**CI 构建时会把这个常量改写成
// 1.0.<run_number>**（见 build-apk.yml 的「注入客户端版本号」步骤，每个构建
// 单调递增）——这里的字面量只是本地构建（tools/build-local.sh）的兜底，
// 发版不需要手改本文件。云端 config.json 的 client.version 抬过某个构建号，
// 低于它的包启动时就弹强制更新框（Java 侧 CNVersionCheck）。
static const char* CLIENT_VERSION = "1.0.0";

// 经 RegisterNatives 绑给 CNVersionCheck.nativeClientVersion()。
static jstring nativeClientVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(CLIENT_VERSION);
}

// ─── Totentanz 代理: Http2Session::setURI URL 改写 ─────────────────
//
// 把走代理白名单的引擎请求从
//     https://<host>/<path>
// 改写为
//     <proxyBase><host><path>   (proxyBase 如 https://api.example.test/stream/)
// 代理入口与域名白名单由 CNMirrors 从 config.json 的 "proxy" 字段解析后
// 经 nativeSetProxyConfig 注入——不在本文件硬编码, 换节点只改 config.json。
// 配置缺失(未下发)时原样直连, 兼容旧版。
//
// 安全要点(同 fontPathOverwrite 5df4b46d 的教训):
//   - 不原地改 const std::string&; 用局部 std::string 传给原函数
//     (引擎 setURI 会把传入串拷进 m_uri, 不持有引用, 局部串安全)
//   - 不用共享静态缓冲: 网络线程并发调 setURI, 引擎 dtor 会 free
//   - 解析/分配失败一律透传原 URL
//
// setURI 是引擎唯一 URL 入口(12 个调用点全走同一 PLT stub), 引擎后续从
// 同一个字符串解析 DNS/TLS-SNI/TCP + :authority/:path, 一次改写即同时改
// 连接目标与 HTTP/2 伪头——无需 DNS hook / 证书 hook。

static std::string jniToStdString(JNIEnv* env, jstring js) {
    if (!js) return "";
    const char* utf = env->GetStringUTFChars(js, nullptr);
    if (!utf) return "";
    std::string s(utf);
    env->ReleaseStringUTFChars(js, utf);
    return s;
}

static std::mutex g_proxyMutex;
static std::string g_proxyBase;
static std::vector<std::string> g_proxyDomains;

using SetURIFn = void (*)(void* self, const std::string& uri);
static SetURIFn g_origSetURI = nullptr;

static bool proxyEndsWith(const std::string& s, const char* suffix) {
    size_t n = strlen(suffix);
    return s.size() >= n && s.compare(s.size() - n, n, suffix) == 0;
}

static bool proxySnapshot(std::string& base, std::vector<std::string>& domains) {
    std::lock_guard<std::mutex> lk(g_proxyMutex);
    base = g_proxyBase;
    domains = g_proxyDomains;
    return !base.empty() && !domains.empty();
}

// 后缀白名单: "magi-reco.com" 匹配 "totentanz-9b.magi-reco.com"
static bool proxyHostMatches(const std::string& host,
                             const std::vector<std::string>& domains) {
    for (size_t i = 0; i < domains.size(); i++) {
        const std::string& suf = domains[i];
        if (suf.empty()) continue;
        if (host.size() == suf.size() && host == suf) return true;
        if (host.size() > suf.size() && host[host.size() - suf.size() - 1] == '.' &&
            host.compare(host.size() - suf.size(), suf.size(), suf) == 0)
            return true;
    }
    return false;
}

// 排除自身: example.test 是 config/线路表/资源所在, 重写它会死循环
static bool proxyIsSelfHost(const std::string& host) {
    return host == "example.test" || proxyEndsWith(host, ".example.test");
}

static bool tryRewriteUrl(const std::string& uri, const std::string& base,
                          const std::vector<std::string>& domains,
                          std::string& out) {
    // base 必须非空且以 '/' 结尾，否则视为未配置/畸形，透传直连（防御 config 下发异常）
    if (base.empty() || base[base.size() - 1] != '/') return false;
    if (uri.compare(0, 8, "https://") != 0) return false;   // 只改 https
    const size_t hostStart = 8;
    const size_t sep = uri.find_first_of("/?#", hostStart);
    std::string host, rest;
    if (sep == std::string::npos) { host = uri.substr(hostStart); }
    else { host = uri.substr(hostStart, sep - hostStart); rest = uri.substr(sep); }
    if (host.empty()) return false;

    std::string hostMatch = host;
    const size_t pc = hostMatch.rfind(':');
    if (pc != std::string::npos) {
        bool digits = true;
        for (size_t i = pc + 1; i < hostMatch.size(); i++)
            if (hostMatch[i] < '0' || hostMatch[i] > '9') { digits = false; break; }
        if (digits) hostMatch = hostMatch.substr(0, pc);
    }
    if (hostMatch.empty() || proxyIsSelfHost(hostMatch)) return false;
    if (!proxyHostMatches(hostMatch, domains)) return false;

    out = base;
    out += host;
    out += rest.empty() ? "/" : rest;
    return true;
}

static void setURI_hook(void* self, const std::string& uri) {
    if (!g_origSetURI) { LOGI("[proxy] setURI called but g_origSetURI NULL"); return; }
    std::string base;
    std::vector<std::string> domains;
    std::string rewritten;
    if (proxySnapshot(base, domains) && tryRewriteUrl(uri, base, domains, rewritten)) {
        LOGI("[proxy] setURI: %s -> %s", uri.c_str(), rewritten.c_str());
        g_origSetURI(self, rewritten);
        return;
    }
    if (!base.empty()) {
        LOGI("[proxy] setURI(no-rewrite): %s (base=%s)", uri.c_str(), base.c_str());
    }
    g_origSetURI(self, uri);
}

// ─── WebView 页面加载 URL 改写 ─────────────────────────────
// 引擎 WebView 的网络走 Http2Session(setURI 已覆盖其 XHR), loadURL 是页面
// 加载入口, 一并改写保证「尽量全代理」: 页面 HTML/JS/资源也经 /stream 走 hk。
// 签名同 setURI(const std::string&), WebViewManager::loadURL 多一个 bool。

using LoadURLFn = void (*)(void* self, const std::string& url);
static LoadURLFn g_origWebViewLoadURL       = nullptr;
static LoadURLFn g_origWebViewImplLoadURL   = nullptr;

static void webViewLoadURL_hook(void* self, const std::string& url) {
    if (!g_origWebViewLoadURL) return;
    std::string base;
    std::vector<std::string> domains;
    std::string rewritten;
    if (proxySnapshot(base, domains) && tryRewriteUrl(url, base, domains, rewritten)) {
        LOGI("[proxy] WebView.loadURL: %s -> %s", url.c_str(), rewritten.c_str());
        g_origWebViewLoadURL(self, rewritten);
        return;
    }
    g_origWebViewLoadURL(self, url);
}

static void webViewImplLoadURL_hook(void* self, const std::string& url) {
    if (!g_origWebViewImplLoadURL) return;
    std::string base;
    std::vector<std::string> domains;
    std::string rewritten;
    if (proxySnapshot(base, domains) && tryRewriteUrl(url, base, domains, rewritten)) {
        LOGI("[proxy] WebViewImpl.loadURL: %s -> %s", url.c_str(), rewritten.c_str());
        g_origWebViewImplLoadURL(self, rewritten);
        return;
    }
    g_origWebViewImplLoadURL(self, url);
}

using LoadURLMgrFn = void (*)(void* self, const std::string& url, bool);
static LoadURLMgrFn g_origWebViewManagerLoadURL = nullptr;

static void webViewManagerLoadURL_hook(void* self, const std::string& url, bool flag) {
    if (!g_origWebViewManagerLoadURL) return;
    std::string base;
    std::vector<std::string> domains;
    std::string rewritten;
    if (proxySnapshot(base, domains) && tryRewriteUrl(url, base, domains, rewritten)) {
        LOGI("[proxy] WebViewManager.loadURL: %s -> %s", url.c_str(), rewritten.c_str());
        g_origWebViewManagerLoadURL(self, rewritten, flag);
        return;
    }
    g_origWebViewManagerLoadURL(self, url, flag);
}

// ─── 引擎真实请求入口: host_service_from_uri + session::submit ─────
// 分析证实: Http2Session::setURI 运行时 0 调用(废弃)。引擎实际路径:
//   · Http2SessionManager::run() → nghttp2::asio_http2::host_service_from_uri
//     (uri → host/service/path)。改 host 输出 → 连接/TLS/SNI/证书校验全走代理
//     host(api.example.test 真实证书, 免证书 hook)。
//   · client::session::submit(ec, method, path, headers, prio): path 参数是完整
//     URL(Http2Request+0x10), 直接改写为 base+host+path → :authority/:path 走
//     /stream。两个 hook 缺一不可(:path 只来自 submit, host 只来自 host_service)。
// 仍由 nativeSetProxyConfig 下发配置; 未下发即全部透传直连。

// 从 base("https://api.example.test/stream/") 提取代理 host("api.example.test")
static std::string proxyHostOf(const std::string& base) {
    if (base.compare(0, 8, "https://") != 0 && base.compare(0, 7, "http://") != 0) return "";
    size_t hs = base.find("://") + 3;
    size_t he = base.find('/', hs);
    if (he == std::string::npos) return "";
    return base.substr(hs, he - hs);
}

// void(boost::system::error_code& ec, std::string& host, std::string& service,
//      std::string& path, const std::string& uri)  → x0=ec x1=host x2=service x3=path x4=uri
using HostServiceFn = void (*)(void* ec, std::string& host, std::string& service,
                               std::string& path, const std::string& uri);
static HostServiceFn g_origHostService = nullptr;

static void hostServiceHook(void* ec, std::string& host, std::string& service,
                            std::string& path, const std::string& uri) {
    if (g_origHostService) g_origHostService(ec, host, service, path, uri);
    std::string base;
    std::vector<std::string> domains;
    if (!proxySnapshot(base, domains)) return;
    if (host.empty() || proxyIsSelfHost(host) || !proxyHostMatches(host, domains)) return;
    std::string proxyHost = proxyHostOf(base);
    if (proxyHost.empty()) return;
    LOGI("[proxy] host_service: %s -> %s", host.c_str(), proxyHost.c_str());
    host.assign(proxyHost);   // 连接目标/TLS SNI/证书校验 host 全变代理 host
}

// session::submit(ec, method, path, headers, priority_spec): path 是完整 URL。
// 改「像完整 URL 的」string 参数(不依赖哪个是 path——method 不会是 https://)。
using SubmitFn = void (*)(void* self, void* ec, std::string& a, std::string& b,
                          void* headers, void* prio);
static SubmitFn g_origSubmit = nullptr;

static void submitHook(void* self, void* ec, std::string& a, std::string& b,
                       void* headers, void* prio) {
    if (g_origSubmit) {
        std::string base;
        std::vector<std::string> domains;
        std::string rw;
        if (proxySnapshot(base, domains)) {
            if (tryRewriteUrl(a, base, domains, rw)) a = rw;
            else if (tryRewriteUrl(b, base, domains, rw)) b = rw;
        }
        g_origSubmit(self, ec, a, b, headers, prio);
        return;
    }
}

// session::submit(ec, method, path, body, headers, prio) —— 带 body string 的重载(0x1119ef0)。
// 改写「像完整 URL 的」string(path)。POST 等带 body 的请求走这个重载。
using SubmitBodyFn = void (*)(void* self, void* ec, std::string& a, std::string& b,
                              std::string& c, void* headers, void* prio);
static SubmitBodyFn g_origSubmitBody = nullptr;

static void submitBodyHook(void* self, void* ec, std::string& a, std::string& b,
                           std::string& c, void* headers, void* prio) {
    if (g_origSubmitBody) {
        std::string base;
        std::vector<std::string> domains;
        std::string rw;
        if (proxySnapshot(base, domains)) {
            if (tryRewriteUrl(a, base, domains, rw)) a = rw;
            else if (tryRewriteUrl(b, base, domains, rw)) b = rw;
            else if (tryRewriteUrl(c, base, domains, rw)) c = rw;
        }
        g_origSubmitBody(self, ec, a, b, c, headers, prio);
        return;
    }
}

// 经 RegisterNatives 绑给 CNMirrors.nativeSetProxyConfig(String, String[])。
// config.json 的 "proxy" 字段解析后调用, 下发代理入口与域名白名单。
static void nativeSetProxyConfig(JNIEnv* env, jclass, jstring base, jobjectArray domains) {
    std::lock_guard<std::mutex> lk(g_proxyMutex);
    g_proxyBase = jniToStdString(env, base);
    g_proxyDomains.clear();
    if (domains) {
        jsize n = env->GetArrayLength(domains);
        for (jsize i = 0; i < n; i++) {
            jstring s = (jstring)env->GetObjectArrayElement(domains, i);
            if (s) {
                g_proxyDomains.push_back(jniToStdString(env, s));
                env->DeleteLocalRef(s);
            }
        }
    }
    LOGI("[proxy] nativeSetProxyConfig base=%s domains=%zu",
         g_proxyBase.c_str(), g_proxyDomains.size());
}

// ─── 引擎硬编码串翻译（cocos2d::Label 系列钩子）────────────────────
//
// 背景：菜单/弹窗文本走 Web 层（热更 zip 已覆盖），但原生引擎（cocos2d-x，
// 全在 libmadomagi_native.so 里）渲染的文本——网络报错、下载引导、战斗效果
// 说明、关卡续玩确认等——硬编码在 .rodata，smali 层帮不上忙。
//
// 方案：钩住文本进入渲染管线的总闸，命中翻译表就换掉内容再放行：
//   cocos2d::Label::setString            对话框/UI 文本主入口
//   cocos2d::LabelAtlas::setString       战斗数字/效果文本
//   cocos2d::MenuItemLabel::setString    菜单项
//   LoadingSceneLayerInfo::setText       下载/加载界面
//   LbUtility::initLabel                 游戏自建标签（const char* 直传）
//
// 翻译表来自热更文件，改译文不用重出 APK（铁律：补丁可热维护）：
//   /data/data/io.kamihama.totentanz/files/madomagi/engine_i18n.tsv
// 格式与 legacy-client 的对照表一致：每行 ja<TAB>zhCN，换行/制表/反斜杠
// 写作 \n \t \\；zhCN 为空表示**删除**该串（拼接式文案的语序调整用）。
// 表在启动时加载，之后每 3 秒节流行检查一次 mtime，热更替换后免重启生效。

static const std::string ENGINE_I18N_PATH =
    "/data/data/io.kamihama.totentanz/files/madomagi/engine_i18n.tsv";

static std::unordered_map<std::string, std::string> g_engineI18n;
static std::vector<std::pair<std::string, std::string>> g_enginePrefixRules;  // '^' 前缀规则
static std::atomic<bool>     g_engineI18nReady{false};
static std::atomic<time_t>   g_engineI18nLastCheck{0};
static time_t                g_engineI18nMtime = 0;
static std::atomic<uint64_t> g_engineI18nHits{0};

static std::string i18nUnescape(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size(); i++) {
        if (s[i] == '\\' && i + 1 < s.size()) {
            char c = s[++i];
            if (c == 'n')      out += '\n';
            else if (c == 't') out += '\t';
            else               out += c;  // 含 '\\' 自身
        } else {
            out += s[i];
        }
    }
    return out;
}

static void loadEngineI18n() {
    FILE* f = fopen(ENGINE_I18N_PATH.c_str(), "rb");
    if (!f) {
        if (g_engineI18nReady || g_engineI18nMtime != 0)
            LOGI("[i18n] 表文件暂缺，保持现状: %s", ENGINE_I18N_PATH.c_str());
        return;
    }
    std::unordered_map<std::string, std::string> fresh;
    std::vector<std::pair<std::string, std::string>> freshPrefix;
    char buf[8192];
    size_t lineno = 0, bad = 0;
    while (fgets(buf, sizeof(buf), f)) {
        lineno++;
        std::string line(buf);
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r'))
            line.pop_back();
        if (line.empty() || line[0] == '#') continue;
        size_t tab = line.find('\t');
        if (tab == std::string::npos) { bad++; continue; }
        // '^' 行 → 前缀规则（命中后替换前缀、保留后缀）
        if (line[0] == '^') {
            std::string ja = i18nUnescape(line.substr(1, tab - 1));
            std::string zh = i18nUnescape(line.substr(tab + 1));
            if (!ja.empty()) freshPrefix.emplace_back(ja, zh);
            continue;
        }
        std::string ja = i18nUnescape(line.substr(0, tab));
        std::string zh = i18nUnescape(line.substr(tab + 1));
        if (!ja.empty()) fresh[ja] = zh;
    }
    fclose(f);
    struct stat st;
    if (::stat(ENGINE_I18N_PATH.c_str(), &st) == 0) g_engineI18nMtime = st.st_mtime;
    g_engineI18n.swap(fresh);
    g_enginePrefixRules.swap(freshPrefix);
    g_engineI18nReady.store(!g_engineI18n.empty());
    LOGI("[i18n] 已加载 %zu 条 + %zu 前缀规则（第 %zu 行止，坏行 %zu）",
         g_engineI18n.size(), g_enginePrefixRules.size(), lineno, bad);
}

// 节流重载检查：热更可能在我们启动后才把表放进来/换掉
static void maybeReloadEngineI18n() {
    time_t now = ::time(nullptr);
    time_t last = g_engineI18nLastCheck.load();
    if (now - last < 3) return;
    if (!g_engineI18nLastCheck.compare_exchange_strong(last, now)) return;
    struct stat st;
    if (::stat(ENGINE_I18N_PATH.c_str(), &st) != 0) return;
    if (st.st_mtime != g_engineI18nMtime) {
        LOGI("[i18n] 检测到表变更，重新加载");
        loadEngineI18n();
    }
}

// NDK libc++ std::string（arm64）只读视图。
// __short: 首字节 = size<<1（LSB=0），数据在 +1；
// __long : 首 size_t 的 LSB=1 作标记，+8 是 size，+16 是数据指针。
struct NdkStrView { const char* data; size_t size; };
static NdkStrView ndkStrRead(const void* strObj) {
    const unsigned char* s = (const unsigned char*)strObj;
    if (s[0] & 1) return { *(const char* const*)(s + 16), *(const size_t*)(s + 8) };
    return { (const char*)(s + 1), (size_t)(s[0] >> 1) };
}

static const std::string* engineLookup(const void* strObj) {
    if (!g_engineI18nReady.load()) return nullptr;
    NdkStrView v = ndkStrRead(strObj);
    if (v.size == 0 || v.size > 8192) return nullptr;
    auto it = g_engineI18n.find(std::string(v.data, v.size));
    if (it == g_engineI18n.end()) return nullptr;
    uint64_t n = ++g_engineI18nHits;
    if (n <= 10 || n % 100 == 0)
        LOGI("[i18n] 替换 #%llu: %.40s", (unsigned long long)n, v.data);
    return &it->second;
}

// 前缀规则查找：命中返回「zh前缀 + 原串剩余部分」（写入 out，调用期内有效）。
// 用于尾部带变量的文案，如 「ネットワーク接続に失敗しました。再接続しますか？\nエラーコード：1」。
// 只在文本含假名（UTF-8 lead 0xE3/0xE4）时才扫规则，未翻译的英文/数字串零开销。
static bool enginePrefixLookup(const char* data, size_t size, std::string& out) {
    if (g_enginePrefixRules.empty()) return false;
    bool hasKana = false;
    for (size_t i = 0; i < size; i++) {
        unsigned char b = (unsigned char)data[i];
        if (b == 0xE3 || b == 0xE4) { hasKana = true; break; }
    }
    if (!hasKana) return false;
    for (const auto& rule : g_enginePrefixRules) {
        const std::string& pre = rule.first;
        if (size >= pre.size() && memcmp(data, pre.data(), pre.size()) == 0) {
            out = rule.second;
            out.append(data + pre.size(), size - pre.size());
            uint64_t n = ++g_engineI18nHits;
            if (n <= 10 || n % 100 == 0)
                LOGI("[i18n] 前缀替换 #%llu: %.40s", (unsigned long long)n, data);
            return true;
        }
    }
    return false;
}

// 伪造一个 long 布局的 std::string 传给原函数（原函数只在调用期内读它）。
// zh 是表内 static 存储，指针在整个调用期有效。
struct FakeNdkStr { size_t cap; size_t size; const char* data; };
static void fakeNdkStr(FakeNdkStr& fk, const std::string& zh) {
    fk.cap  = (zh.size() + 1) | 1;
    fk.size = zh.size();
    fk.data = zh.c_str();
}

using SetStringFn = void (*)(void*, const void*);
static SetStringFn labelSetStringOld      = nullptr;
static SetStringFn labelAtlasSetStringOld = nullptr;
static SetStringFn menuItemSetStringOld   = nullptr;
static SetStringFn loadingSetTextOld      = nullptr;
static SetStringFn loadingSetTitleOld     = nullptr;

static void setStringTrampoline(SetStringFn old, void* self, const void* text,
                                const char* /*label*/) {
    maybeReloadEngineI18n();
    maybeReleaseDeferredTop();  // 浮层若在刚才撤掉，这里补推主页跳转/补放 BGM
    const std::string* zh = engineLookup(text);
    if (zh) {
        FakeNdkStr fk;
        fakeNdkStr(fk, *zh);
        old(self, &fk);
        return;
    }
    // 精确未命中 → 前缀规则（尾部带变量的文案）
    NdkStrView v = ndkStrRead(text);
    if (v.size && g_engineI18nReady.load()) {
        std::string combined;
        if (enginePrefixLookup(v.data, v.size, combined)) {
            FakeNdkStr fk;
            fakeNdkStr(fk, combined);
            old(self, &fk);
            return;
        }
    }
    old(self, text);
}
static void labelSetStringNew(void* self, const void* text) {
    setStringTrampoline(labelSetStringOld, self, text, "Label::setString");
}
static void labelAtlasSetStringNew(void* self, const void* text) {
    setStringTrampoline(labelAtlasSetStringOld, self, text, "LabelAtlas::setString");
}
static void menuItemSetStringNew(void* self, const void* text) {
    setStringTrampoline(menuItemSetStringOld, self, text, "MenuItemLabel::setString");
}
static void loadingSetTextNew(void* self, const void* text) {
    setStringTrampoline(loadingSetTextOld, self, text, "LoadingSceneLayerInfo::setText");
}

// 加载场景标题。此前只钩了 setText（message），title 从未被 i18n 覆盖——于是
// 加载场景里「正在加载中…」（message，已翻译）+「Connecting...」（title，英文）
// 两个加载提示同时出现。这里：1) 把多余的英文连接提示「Connecting...」置空；
// 2) 其余标题走 i18n 表翻译（顺带补上场景标题的日文缺口）。
static void loadingSetTitleNew(void* self, const void* text) {
    NdkStrView v = ndkStrRead(text);
    if (v.size >= 10 && v.size <= 24) {
        // 大小写不敏感匹配 "connecting" 前缀（覆盖 Connecting... / Connecting…）
        static const char kConn[] = "connecting";   // 10 字节
        bool isConn = true;
        const char* p = v.data;
        for (size_t i = 0; i < sizeof(kConn) - 1; i++) {
            char c = (p[i] >= 'A' && p[i] <= 'Z') ? (char)(p[i] + 32) : p[i];
            if (c != kConn[i]) { isConn = false; break; }
        }
        if (isConn) {
            static const std::string empty;
            FakeNdkStr fk;
            fakeNdkStr(fk, empty);
            LOGI("[i18n] LoadingSceneLayerInfo::setTitle: 置空英文连接提示");
            loadingSetTitleOld(self, &fk);
            return;
        }
    }
    setStringTrampoline(loadingSetTitleOld, self, text, "LoadingSceneLayerInfo::setTitle");
}

// LbUtility::initLabel(Node*, Label*&, const char* text, float, Vec2, int, Size, Color4B, int)
// const char* 直传，命中就换指针。钩子与原函数用完全相同的原型声明，
// 由编译器保证两侧参数布局一致（不用变参转发，避免 HFA/小聚合体 ABI 坑）。
struct CNVec2    { float x, y; };
struct CNSize    { float w, h; };
struct CNColor4B { unsigned char r, g, b; };
using InitLabelFn = void (*)(void*, void*, const char*, float,
                             CNVec2, int, CNSize, CNColor4B, int);
static InitLabelFn initLabelOld = nullptr;
static void initLabelNew(void* node, void* label, const char* text, float f,
                         CNVec2 v2, int i1, CNSize sz, CNColor4B c4b, int i2) {
    maybeReloadEngineI18n();
    const char* use = text;
    static thread_local std::string combined;  // 前缀规则命中时的拼接缓冲
    if (text && g_engineI18nReady.load()) {
        auto it = g_engineI18n.find(text);
        if (it != g_engineI18n.end()) {
            uint64_t n = ++g_engineI18nHits;
            if (n <= 10 || n % 100 == 0)
                LOGI("[i18n] 替换 #%llu: %.40s", (unsigned long long)n, text);
            use = it->second.c_str();
        } else if (enginePrefixLookup(text, strlen(text), combined)) {
            use = combined.c_str();
        }
    }
    initLabelOld(node, label, use, f, v2, i1, sz, c4b, i2);
}



// NDK libc++ std::string 原地改写（font 段复用 i18n 段的 NdkStrView）
static void fontPathOverwrite(void* strObj, const char* nv, size_t n) {
    unsigned char* s = (unsigned char*)strObj;
    if (s[0] & 1) {  // long：直接在原缓冲上改写（新路径不长于原路径才走这里）
        size_t cap = (*(size_t*)s) & ~(size_t)1;
        if (n + 1 <= cap) {   // 要写 n 个字符 + 结尾 NUL，共 n+1 字节
            memcpy(*(char**)(s + 16), nv, n + 1);
            *(size_t*)(s + 8) = n;
            return;
        }
    } else if (n <= 22) {  // short
        s[0] = (unsigned char)(n << 1);
        memcpy(s + 1, nv, n + 1);
        return;
    }
    // 放不下：切 long，为这次重定向分配**独立**缓冲，交给引擎 string 持有。
    // 引擎 string 析构时会释放它（libc++ 的 ::operator delete 与这里 ::operator
    // new 匹配）。⚠ 绝不能用共享的 static std::string：多个引擎 string 被重定向
    // 到同一块静态缓冲后，各自的析构都会 free 它 → 双 free / 写已释放内存
    // （堆破坏，表现为「切换界面时不定时崩溃」）。一对象一缓冲，谁持有谁释放。
    // nothrow + 判空：分配失败就干脆不重定向（引擎回落原字体），绝不把异常
    // 抛过 hook 边界。
    char* buf = static_cast<char*>(::operator new(n + 1, std::nothrow));
    if (!buf) return;
    memcpy(buf, nv, n);
    buf[n] = '\0';
    *(const char**)(s + 16) = buf;
    *(size_t*)(s + 8)  = n;
    *(size_t*)s        = (n + 1) | 1;
}

static void fontPathFix(void* strObj, const char* tag) {
    static const char kFrom[] = "fonts/MTF4a5kp.ttf";
    static const char kTo[]   = "fonts/TTZhiHeiGB3-W4.ttf";
    NdkStrView v = ndkStrRead(strObj);
    if (v.size == sizeof(kFrom) - 1 && memcmp(v.data, kFrom, sizeof(kFrom) - 1) == 0) {
        fontPathOverwrite(strObj, kTo, sizeof(kTo) - 1);
        LOGI("[font] %s: MTF4a5kp → TTZhiHeiGB3-W4", tag);
    }
}

using CreateWithTtfCfgFn = void* (*)(void*, const void*, int, int);
using CreateWithTtfStrFn = void* (*)(void*, const void*, float, void*, int, int);
using SetTtfCfgFn = void (*)(void*, const void*);
static CreateWithTtfCfgFn createWithTtfCfgOld = nullptr;
static CreateWithTtfStrFn createWithTtfStrOld = nullptr;
static SetTtfCfgFn        setTtfCfgInternalOld = nullptr;

// createWithTTF(const _ttfConfig& cfg, ...)：fontFilePath 在 cfg 偏移 0
static void* createWithTtfCfgNew(void* cfg, const void* text, int h, int i) {
    fontPathFix(cfg, "createWithTTF(cfg)");
    return createWithTtfCfgOld(cfg, text, h, i);
}
// createWithTTF(const std::string& text, const std::string& fontFile, float, ...)
static void* createWithTtfStrNew(void* text, const void* font, float size,
                                 void* dims, int h, int v) {
    fontPathFix((void*)font, "createWithTTF(str)");
    return createWithTtfStrOld(text, font, size, dims, h, v);
}
// Label::setTTFConfigInternal(const _ttfConfig&)
static void setTtfCfgInternalNew(void* self, const void* cfg) {
    fontPathFix((void*)cfg, "setTTFConfigInternal");
    setTtfCfgInternalOld(self, cfg);
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
            { "io/kamihama/magianative/CNDownloaderFix",   &gClsDownloaderFix  },
            { "io/kamihama/magianative/RestClient",        &gClsRestClient     },
            { "io/kamihama/magianative/CNTutorialPrompt",  &gClsTutorialPrompt },
            { "io/kamihama/magianative/CNVersionCheck",    &gClsVersionCheck   },
            { "io/kamihama/magianative/CNMirrors",         &gClsCNMirrors      },
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

        // 把 nativeClientVersion 绑到 CNVersionCheck 上（客户端版本号硬编码在
        // 本文件，见 CLIENT_VERSION 的注释）。找不到类就跳过——Java 侧读不到
        // 版本会按「不强制更新」放行，不会崩。
        if (gClsVersionCheck) {
            JNINativeMethod m[] = {
                { (char*)"nativeClientVersion", (char*)"()Ljava/lang/String;",
                  (void*)nativeClientVersion },
            };
            if (env->RegisterNatives(gClsVersionCheck, m, 1) != 0) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                LOGE("[JNI] RegisterNatives(CNVersionCheck) 失败——版本检查将放行");
            }
        }
        // Totentanz 代理配置注入: CNMirrors 解析 config.json 的 proxy 字段后调
        // nativeSetProxyConfig 下发代理入口与域名白名单(见 nativeSetProxyConfig)。
        if (gClsCNMirrors) {
            JNINativeMethod m[] = {
                { (char*)"nativeSetProxyConfig",
                  (char*)"(Ljava/lang/String;[Ljava/lang/String;)V",
                  (void*)nativeSetProxyConfig },
            };
            if (env->RegisterNatives(gClsCNMirrors, m, 1) != 0) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                LOGE("[JNI] RegisterNatives(CNMirrors.nativeSetProxyConfig) 失败——代理将不生效");
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

    // ── 强制序章：拦「进主页」，命中标记时改走序章场景 ──
    // 先解析 pushScenePrologue：拿不到就别装 hook，省得白拦一道。
    resolvePrologueEntry(LIB);
    if (pushScenePrologueFn) {
        H("_ZN3web12SceneCommand12pushSceneTopERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE",
          (void*)pushSceneTopNew, (void**)&pushSceneTopOld,
          "SceneCommand::pushSceneTop(强制教程闸门)");
        // 序章完成信号日志。替换编号是 NS0_ 而非 NS1_：Itanium mangling 的
        // S_/S0_/S1_ 按首次出现顺序编号，本符号在 std::__ndk1 之前只出现过
        // PrologueSceneLayer 一个名字（S_），故 std::__ndk1 是 S0_。
        // 对照 pushSceneTop 用 NS1_ 才对（web=S_、web::SceneCommand=S0_、
        // std::__ndk1=S1_）——两者不能照抄，写错了查不到符号、hook 静默失效。
        H("_ZN18PrologueSceneLayer8notifyJsERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE",
          (void*)notifyJsNew, (void**)&notifyJsOld,
          "PrologueSceneLayer::notifyJs(教程信号日志)");
        // 序章图层的生存期。析构是「序章真的结束了」最可靠的信号；D1 与 D2
        // 在两个 ABI 上都是同一个地址（别名），删除型析构 D0 也走 D2，
        // 所以只 hook D2 就能覆盖全部销毁路径。
        H("_ZN18PrologueSceneLayerC1EP22PrologueSceneLayerInfo",
          (void*)prologueCtorNew, (void**)&prologueCtorOld,
          "PrologueSceneLayer::ctor");
        H("_ZN18PrologueSceneLayerD2Ev",
          (void*)prologueDtorNew, (void**)&prologueDtorOld,
          "PrologueSceneLayer::dtor(序章结束闸门)");
    }

    // 尽早发起 SNAA 查询：引擎很快就会问资源地址。取不到就保持 ready=false，
    // resource() 会一路回落到原版，不会卡住也不会崩。
    {
        pthread_t t;
        if (pthread_create(&t, nullptr, endpointThreadMain, nullptr) == 0) pthread_detach(t);
        else LOGE("[UrlConfig] 端点线程起不来");
    }

    // ── 引擎硬编码串翻译（cocos2d::Label 系列）──
    // 先装表再装钩；表缺失时钩子空转放行，不影响其他功能。
    loadEngineI18n();
    H("_ZN7cocos2d5Label9setStringERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE",
      (void*)labelSetStringNew, (void**)&labelSetStringOld, "i18n: Label::setString");
    H("_ZN7cocos2d10LabelAtlas9setStringERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE",
      (void*)labelAtlasSetStringNew, (void**)&labelAtlasSetStringOld, "i18n: LabelAtlas::setString");
    H("_ZN7cocos2d13MenuItemLabel9setStringERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE",
      (void*)menuItemSetStringNew, (void**)&menuItemSetStringOld, "i18n: MenuItemLabel::setString");
    H("_ZN21LoadingSceneLayerInfo7setTextENSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE",
      (void*)loadingSetTextNew, (void**)&loadingSetTextOld, "i18n: LoadingSceneLayerInfo::setText");
    H("_ZN21LoadingSceneLayerInfo8setTitleENSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE",
      (void*)loadingSetTitleNew, (void**)&loadingSetTitleOld, "i18n: LoadingSceneLayerInfo::setTitle");

    // Totentanz 代理: Http2Session::setURI URL 改写(代理入口/白名单由
    // config.json 的 proxy 字段经 nativeSetProxyConfig 下发, 见上方实现)
    // ⚠ Totentanz 代理 hook 二次禁用(2026-08-06): 真机直接闪退崩溃(非黑屏)。
    // host_service_from_uri + session::submit hook 疑似参数/改写问题导致崩溃。
    // 待分析 submit 签名与改写安全性后重新实现。config 无 proxy 时本就透传, 但 hook
    // 本身仍可能破坏引擎, 故完全禁用。
    // H("_ZN7nghttp210asio_http221host_service_from_uriERN5boost6system10error_codeERNSt6__ndk112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEESC_SC_RKSB_",
    //   (void*)hostServiceHook, (void**)&g_origHostService, "proxy: host_service_from_uri");
    // H("_ZNK7nghttp210asio_http26client7session6submitERN5boost6system10error_codeERKNSt6__ndk112basic_stringIcNS7_11char_traitsIcEENS7_9allocatorIcEEEESF_NS7_8multimapISD_NS0_12header_valueENS7_4lessISD_EENSB_INS7_4pairISE_SH_EEEEEENS1_13priority_specE",
    //   (void*)submitHook, (void**)&g_origSubmit, "proxy: session::submit");
    // H("_ZNK7nghttp210asio_http26client7session6submitERN5boost6system10error_codeERKNSt6__ndk112basic_stringIcNS7_11char_traitsIcEENS7_9allocatorIcEEEESF_SD_NS7_8multimapISD_NS0_12header_valueENS7_4lessISD_EENSB_INS7_4pairISE_SH_EEEEEENS1_13priority_specE",
    //   (void*)submitBodyHook, (void**)&g_origSubmitBody, "proxy: session::submit(body)");
    H("_ZN9LbUtility9initLabelEPN7cocos2d4NodeERPNS0_5LabelEPKcfNS0_4Vec2EiNS0_4SizeENS0_7Color4BEi",
      (void*)initLabelNew, (void**)&initLabelOld, "i18n: LbUtility::initLabel");

    // ── 引擎 UI 字体路径重定向（MTF4a5kp → TTZhiHeiGB3-W4）──
    H("_ZN7cocos2d5Label13createWithTTFERKNS_10_ttfConfigERKNSt6__ndk112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEENS_14TextHAlignmentEi",
      (void*)createWithTtfCfgNew, (void**)&createWithTtfCfgOld, "font: createWithTTF(cfg)");
    H("_ZN7cocos2d5Label13createWithTTFERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_fRKNS_4SizeENS_14TextHAlignmentENS_14TextVAlignmentE",
      (void*)createWithTtfStrNew, (void**)&createWithTtfStrOld, "font: createWithTTF(str)");
    H("_ZN7cocos2d5Label20setTTFConfigInternalERKNS_10_ttfConfigE",
      (void*)setTtfCfgInternalNew, (void**)&setTtfCfgInternalOld, "font: setTTFConfigInternal");

    // ── 下载浮层期间挂起引擎 BGM（QbUtility::playBgmDirect）──
    H("_ZN9QbUtility13playBgmDirectEPKc",
      (void*)playBgmDirectNew, (void**)&playBgmDirectOld, "Overlay: playBgmDirect 挂起");

    LOGI("[JNI] hooks 安装完成：成功 %d 个，失败 %d 个", hookOk, hookFail);
    return JNI_VERSION_1_6;
}
