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
#include <chrono>   // probeEndpointSlots 的节流用稳定时钟
#include <functional>
#include <memory>
#include <mutex>
#include <new>        // ::operator new（fontPathOverwrite 的独立缓冲分配）
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>   // logI18nMiss 的去重集
#include <vector>

#include <dirent.h>
#include <errno.h>    // loadDebugFlags 报「目录读不进去」时带上 errno
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

// ═══ 调试开关目录 ════════════════════════════════════════════════════
//
//     /data/data/io.kamihama.totentanz/debug/<开关名>
//
// 目录里**建一个同名空文件就是打开该开关**，删掉就是关闭，重启游戏生效。
//
// ## 为什么做成这个形状
//
// 本仓库反复遇到同一类问题：某个 hook 疑似干扰引擎，表现是黑屏/卡死/闪退，
// 而定位手段只有「改代码 → 重打包 → 找人真机走一遍」。setURI、nghttp2 逐请求、
// web 端点、以及这次的战斗崩溃，每一次都烧掉整轮往返，一次 CI 还只能验一个假设。
//
// 有了这个目录，**一次构建就能验多个假设**：装一次包，在设备上建/删文件、重启，
// 逐个排除。可以把排查交给手上有设备的人，不必每次都回到构建流程。
//
// ## 为什么放在 app 私有目录
//
// 这里在非 root 的正式包上**玩家碰不到**（`run-as` 只对 debuggable 包有效），
// 所以不构成面向普通玩家的风险面；而有能力自查的人拿 root 或 debuggable 包
// 就能用。这正是想要的分界。
//
// ## 边界：只关我们自己加的东西
//
// 这些开关一律只做一件事——**把客户端退回更接近原包的行为**。绝不设置任何
// 削弱安全判定的开关（外链白名单、签名/完整性校验、https 强制等一概不做成开关），
// 否则这个目录就从排查工具变成了攻击面：一旦有人能写进这里，就能把防线一条条关掉。
//
// ## 用法
//
//     adb shell "run-as io.kamihama.totentanz mkdir -p debug"
//     adb shell "run-as io.kamihama.totentanz touch debug/noFontHook"
//     # 重启游戏；logcat 里 [DEBUG] 会把当前生效的开关列出来
//
// 启动时无论开没开都会打印全表，所以「有哪些开关」看一眼日志就知道，
// 不必回来翻源码。写错名字也会被单独列出来——否则你会以为开关没用。
// 落点在私有目录根下，与 CNLog 的 log/ **平级**（<priv>/log 与 <priv>/debug）。
// 不放 files/ 里：那是热更的解压根，CNHotUpdateTx 会按前缀算孤儿并删除。
// 现在 cleanupPrefixes("scenario") 只清 madomagi/resource/scenario/json/，
// 碰不到这里——但那是巧合不是保证，前缀哪天放宽到 madomagi/，开关就会在某次
// 热更后集体消失且查不出原因。挪出来就不存在这个问题。
static const std::string DEBUG_DIR =
    "/data/data/io.kamihama.totentanz/debug";

// 开关名用**小驼峰**，与 Java 侧保持一致（同一个目录，两边名字风格不该分裂）。
static bool g_dbgNoFontHook      = false;
static bool g_dbgNoI18nLabel     = false;
static bool g_dbgNoI18nSetString = false;
static bool g_dbgNoTutorialGuard = false;
static bool g_dbgNoTutorialForce = false;
static bool g_dbgNoOverlayGate   = false;
static bool g_dbgNoProxyEndpoint = false;
static bool g_dbgNoHttp2Bump     = false;
static bool g_dbgNoAdxSampleRate = false;
// 下面两个是「**根本不装**这个钩子」，与上面「装了但空转」是两回事。
//
// 分开是必须的：2026-08-08 那次战斗崩溃的元凶是 initLabel 钩子的**原型声明错了**
// （CNColor4B 少了 alpha 字节）。这类错在「装了但空转」时照样发生——空转那一支
// 仍然要按同一个错原型把参数转发回去。也就是说 noI18nLabel 根本测不出它。
// 想把「钩子存在本身」排除掉，只能连 H() 安装一起跳过。
static bool g_dbgNoInitLabelHook = false;
static bool g_dbgNoTtfHooks      = false;
// 下面两个既不关行为也不注入故障，只**记录**：把流经文本钩子却没被翻译的串打进
// logcat。加它的理由是钩子原本只记命中、不记未命中——
// 「这句为什么没汉化」因此天然无解：串不在表里时，无论它有没有流经钩子，日志
// 都是同一片空白。于是每问一次都得靠猜，再出一次包去试。
// 有了它，跑一局就能拿到「这一局所有本该翻却没翻的串」，一次抓全。
static bool g_dbgLogI18nMiss     = false;
static bool g_dbgLogI18nMissAll  = false;

struct DebugFlagDef { const char* name; bool* slot; const char* desc; };
static const DebugFlagDef kDebugFlags[] = {
    // ── 关掉我们加的引擎改动（按启动链顺序）──
    { "noOverlayGate",   &g_dbgNoOverlayGate,   "浮层期间不闸住 pushSceneTop/BGM（引擎照常推进）" },
    { "noTutorialForce", &g_dbgNoTutorialForce, "不强制序章（即使标记在，也照常进主页）" },
    { "noTutorialGuard", &g_dbgNoTutorialGuard, "序章期间不起 WebView 看门狗" },
    { "noProxyEndpoint", &g_dbgNoProxyEndpoint, "UrlConfig::api/chat 只观测不重写（直连）" },
    // ── 关掉渲染/文案改动 ──
    { "noFontHook",      &g_dbgNoFontHook,      "不重定向字体路径（UI 字体回原包 MTF4a5kp）" },
    { "noI18nLabel",     &g_dbgNoI18nLabel,     "initLabel 不替换文案（引擎侧标签回日文）" },
    { "noI18nSetString", &g_dbgNoI18nSetString, "setString 系不替换文案" },
    // ── 关掉从 libuwasa 移植的两条性能/音频改动 ──
    { "noHttp2Bump",     &g_dbgNoHttp2Bump,     "HTTP/2 并发数保持引擎原本的 4，不提到 10" },
    { "noAdxSampleRate", &g_dbgNoAdxSampleRate, "不锁 ADX2 采样率 48000，用设备实际值" },
    // ── 「根本不装」，用来排除「钩子存在本身」（含原型声明错）──
    { "noInitLabelHook", &g_dbgNoInitLabelHook, "**不安装** LbUtility::initLabel 钩子（排除原型/ABI 问题）" },
    { "noTtfHooks",      &g_dbgNoTtfHooks,      "**不安装** createWithTTF/setTTFConfig 三个钩子（同上）" },
    // ── 只记录，不改行为：把「流经钩子但没翻到」的串打出来 ──
    { "logI18nMiss",     &g_dbgLogI18nMiss,     "记录未命中翻译表的**含假名**串（tsv 行格式，去重）" },
    { "logI18nMissAll",  &g_dbgLogI18nMissAll,  "同上但不筛内容（含英文/数字，噪音大，用于确认某串走没走 native 标签）" },
};

static void loadDebugFlags() {
    int on = 0;
    LOGI("[DEBUG] 调试开关目录: %s", DEBUG_DIR.c_str());

    // 先判目录本身读不读得了，且**打在开关表前面**。
    //
    // 为什么必须单独说：读不到目录时，下面整张表会全部打成「关」，而这和「确实
    // 一个都没开」在日志里一模一样。2026-08-08 就撞上了——有人建好了
    // logI18nMissAll，日志里却全是 [   ]，两边都看不出区别，只能靠猜。
    // 最常见的成因是拿 su/root 建目录：属主 root、模式 700，应用（uid 10xxx）
    // 连遍历都进不去，于是每个 stat() 都失败 → 每个开关都读成「关」。
    bool dirReadable = false;
    struct stat dst;
    if (::stat(DEBUG_DIR.c_str(), &dst) != 0) {
        LOGI("[DEBUG] 目录不存在，全部开关按关闭处理（正常状态）");
    } else if (!S_ISDIR(dst.st_mode)) {
        LOGE("[DEBUG] ⚠ 这个路径不是目录——所有开关都会读成「关」");
    } else if (::access(DEBUG_DIR.c_str(), R_OK | X_OK) != 0) {
        LOGE("[DEBUG] ⚠ 目录在，但应用读不进去（errno=%d，属主 uid=%d，模式 0%o）"
             "——所有开关都会读成「关」，这**不是**「一个都没开」。"
             "多半是用 su/root 建的；请改用 run-as 重建：",
             errno, (int)dst.st_uid, (unsigned)(dst.st_mode & 07777));
        LOGE("[DEBUG]   adb shell \"run-as io.kamihama.totentanz mkdir -p debug\"");
    } else {
        dirReadable = true;
    }

    for (size_t i = 0; i < sizeof(kDebugFlags) / sizeof(kDebugFlags[0]); i++) {
        const DebugFlagDef& f = kDebugFlags[i];
        struct stat st;
        *f.slot = (::stat((DEBUG_DIR + "/" + f.name).c_str(), &st) == 0);
        if (*f.slot) on++;
        LOGI("[DEBUG]   [%s] %-18s %s", *f.slot ? "ON " : "   ", f.name, f.desc);
    }
    // 把目录里不认识的文件单独列出来：名字打错时最容易的误判是「开关没用」。
    // 目录读不了时跳过——上面已经点破原因了，这里再报一遍只是噪音。
    DIR* d = dirReadable ? ::opendir(DEBUG_DIR.c_str()) : nullptr;
    if (d) {
        struct dirent* e;
        while ((e = ::readdir(d)) != nullptr) {
            if (e->d_name[0] == '.') continue;
            bool known = false;
            for (size_t i = 0; i < sizeof(kDebugFlags) / sizeof(kDebugFlags[0]); i++) {
                if (::strcmp(e->d_name, kDebugFlags[i].name) == 0) { known = true; break; }
            }
            if (!known) LOGE("[DEBUG] ⚠ 目录里有不认识的文件 %s —— 名字打错了？", e->d_name);
        }
        ::closedir(d);
    }
    if (on > 0) {
        LOGE("[DEBUG] ⚠ 共 %d 个开关生效——这是排查用的降级模式，不是正常配置", on);
    }
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
// The Top command that triggered forced prologue is our only positively identified homepage
// takeover. During tutorial we suppress repeats of this exact command; different Top args are
// treated as potential tutorial-internal transitions and are allowed through with explicit logs.
static std::string  g_tutorialHomeTopArg;
static void*        g_tutorialHomeTopSelf = nullptr;

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
    if (overlayActive() && !g_dbgNoOverlayGate) {
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


// v5: stage-transition-aware WebView guard. v4 forced INVISIBLE every 250ms and could
// race ADV -> battle. A notifyJs/internal Top transition grants a grace window; after it
// expires, the guard hides a WebView only if the tutorial is still active.
static std::atomic<uint64_t> g_tutorialWebGraceUntilMs{0};
static uint64_t tutorialNowMs() {
    return (uint64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
static void grantTutorialWebGrace(const char* why, uint64_t ms) {
    uint64_t until = tutorialNowMs() + ms;
    g_tutorialWebGraceUntilMs.store(until, std::memory_order_relaxed);
    LOGI("[Tutorial] WebView guard grace reason=%s duration=%llums",
         why ? why : "unknown", (unsigned long long)ms);
}
static std::atomic<bool> g_uiWatchdogOn{false};
static void* uiWatchdogMain(void*) {
    LOGI("[Tutorial] WebView guard started mode=transition-aware interval=500ms");
    while (g_tutorialActive.load()) {
        uint64_t now = tutorialNowMs();
        uint64_t until = g_tutorialWebGraceUntilMs.load(std::memory_order_relaxed);
        if (now >= until) {
            setGameUiVisible(false);
        }
        usleep(500 * 1000);
    }
    g_uiWatchdogOn.store(false);
    LOGI("[Tutorial] WebView guard stopped");
    return nullptr;
}


// Java 浮层在 hide() 完成时会通过 Cocos2dxHelper.runOnGLThread() 调这里。
// 这样 deferred top/BGM 的释放有一个确定事件，不再依赖“之后也许还会发生”的
// Label::setString / LoadingSceneLayerInfo::setText 回调。
static void nativeReleaseDeferredTop(JNIEnv*, jclass) {
    LOGI("[Overlay] Java 通知浮层已撤，立即释放 deferred top/BGM");
    maybeReleaseDeferredTop();
}

static void pushSceneTopNew(void* self, const std::string& arg) {
    // ── 下载浮层闸门：浮层（首装/热更）激活期间吞掉主页跳转 ──
    // 不然引擎在浮层后面直接推进到主页并开始放 BGM。
    // 被吞的跳转在浮层撤掉后由 maybeReleaseDeferredTop 补推（走完整逻辑）。
    if (overlayActive() && !g_dbgNoOverlayGate) {
        LOGI("[Overlay] 下载浮层激活，闸住 pushSceneTop(arg=%s)", arg.c_str());
        std::lock_guard<std::mutex> lk(g_deferredMutex);
        g_deferredTopSelf = self;
        g_deferredTopArg  = arg;
        g_topDeferred.store(true);
        return;
    }
    // Tutorial v5: suppress only the homepage Top we positively identified at the trigger.
    // Unknown/different Top commands may be tutorial-internal stage transitions; blanket
    // swallowing them can strand ADV->battle on an empty scene.
    if (g_tutorialActive.load()) {
        bool homepage = false;
        {
            std::lock_guard<std::mutex> lk(g_savedTopMutex);
            homepage = !g_tutorialHomeTopArg.empty() && arg == g_tutorialHomeTopArg;
        }
        if (homepage) {
            LOGI("[Tutorial] pushSceneTop classify=homepage suppress arg=%s", arg.c_str());
            saveTop(self, arg);
            return;
        }
        LOGI("[Tutorial] pushSceneTop classify=internal/unknown allow arg=%s", arg.c_str());
        grantTutorialWebGrace("internal-top", 2500);
        pushSceneTopOld(self, arg);
        return;
    }
    // ⚠ 绝不在「与引擎无关的时刻」自己往队列里塞场景跳转——那才会和引擎
    // 正在进行的切场景撞车（两条切换命令都入了队，谁后处理谁盖上面，白屏
    // 就是这么来的）。本函数只在引擎**自己**发起 pushSceneTop 的这一刻被
    // 调用，我们把这条命令**原替换**成 pushScenePrologue：同一时刻队列里
    // 永远只有一条切换命令，不存在撞车窗口。引擎侧 SceneCommand 全部走
    // 游戏主线程的 deque（见 0xb82610），入队动作本身是串行的。
    // ⚠ 开关放在最前面是有意的:短路之后 consumeForceTutorial() 不会执行,
    // 也就**不会消费掉标记文件**。关掉开关重启,序章照样还能触发——调试开关
    // 不该顺手把玩家的状态改了。
    if (!g_dbgNoTutorialForce && pushScenePrologueFn && consumeForceTutorial()) {
        // callback=nativeCallback 是 v1 缺的字段，缺了它 notifyJs 下发的
        // JS 全是残的，前端收不到任何段通知（见本节开头的 bug 分析）。
        static const std::string kPrologueArg =
            "{\"beginningId\":\"OP020\",\"callback\":\"nativeCallback\"}";
        LOGI("[Tutorial] 命中强制教程标记 → 改走 pushScenePrologue(OP020)"
             "（原 pushSceneTop arg=%s）", arg.c_str());
        saveTop(self, arg);
        {
            std::lock_guard<std::mutex> lk(g_savedTopMutex);
            g_tutorialHomeTopSelf = self;
            g_tutorialHomeTopArg = arg;
        }
        LOGI("[Tutorial] homepage Top identity captured arg=%s", arg.c_str());
        g_tutorialForced.store(true);
        g_tutorialActive.store(true);
        // v5 guard: hide resurfaced WebView, but honor grace windows around actual
        // front-end/native stage transitions so ADV -> battle is not interrupted.
        if (!g_dbgNoTutorialGuard && !g_uiWatchdogOn.exchange(true)) {
            pthread_t t;
            if (pthread_create(&t, nullptr, uiWatchdogMain, nullptr) == 0) {
                pthread_detach(t);
            } else {
                g_uiWatchdogOn.store(false);
                LOGE("[Tutorial] WebView guard thread failed; ctor one-shot hide remains");
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
    LOGI("[Tutorial] WebView visibility request visible=%d", (int)visible);
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

static void nativeTutorialRestartFailed(JNIEnv*, jclass) {
    LOGE("[Tutorial] Java restart handshake failed -> restore WebView + replay saved Top");
    g_tutorialActive.store(false);
    g_tutorialForced.store(false);
    setGameUiVisible(true);
    replaySavedTop();
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
    {
        std::lock_guard<std::mutex> lk(g_savedTopMutex);
        g_tutorialHomeTopArg.clear();
        g_tutorialHomeTopSelf = nullptr;
    }
    prologueDtorOld(_this);
}

// 序章向前端发通知。无条件记录：callback 修好之后这些信号应该真的到达前端，
// 日志里要能看到 OP 段与最终的「prologue」完成信号逐个过去。
static void notifyJsNew(void* _this, const std::string& arg) {
    LOGI("[Tutorial::notifyJs] before callback arg=%s active=%d forced=%d",
         arg.c_str(), (int)g_tutorialActive.load(), (int)g_tutorialForced.load());
    if (g_tutorialActive.load()) grantTutorialWebGrace("notifyJs", 2500);
    notifyJsOld(_this, arg);
    LOGI("[Tutorial::notifyJs] after callback arg=%s", arg.c_str());
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
    if (g_dbgNoAdxSampleRate) {
        LOGI("[ADX2] GetHardwareSamplingRate: device=%d（调试开关：不锁 48000）", orig);
        return orig;
    }
    LOGI("[ADX2] GetHardwareSamplingRate: device=%d → 48000", orig);
    return 48000;
}
// 游戏初始化调 setMaxConnectionNum(4)，4 条并发 HTTP/2 stream 拉资产。
// 提到 10 能明显缩短首次资产加载。
static void setMaxConnectionNumNew(void* _this, int n) {
    int patched = (!g_dbgNoHttp2Bump && n == 4) ? 10 : n;
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

// ═══ 【已停用 · v1】setURI 改写 —— 当前没有任何 H() 安装 setURI_hook ═══
//
// 停用于 9183ab8c（真机黑屏卡死）。下面的实现完整保留，作为反向工程记录。
//
// ⚠ 关于「setURI 运行时 0 调用，是废弃 API」这个说法，证据没有看上去那么硬：
//   那次观测（ccbbcdbe 加了无条件日志、9183ab8c 记录结果）的现场是**游戏黑屏
//   卡死**。「一次都没调」与「根本没跑到会调它的阶段」在那份日志里区分不开。
//   后续 v2/v3/v4 都建立在「setURI 已废弃」之上，但这个前提**从未在游戏能正常
//   进入的会话里复验过**。
//
// 重新启用前必须先做的事：拿一次**游戏能正常跑起来**的日志，确认 setURI 的
// 调用次数究竟是不是 0。在那之前，不要把「已废弃」当成事实引用。
//
// ─── 原始设计说明（保留）────────────────────────────────────
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

// 【已停用 · v1】没有 H() 安装它。停用于 9183ab8c（真机黑屏卡死）。
// 「setURI 运行时 0 调用」这个前提未在能正常进游戏的会话里复验过，详见上文。
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

// ═══ 【已停用】WebView loadURL 改写 —— 没有任何 H() 安装这两个钩子 ═══
//
// 与 setURI 一起停用于 9183ab8c。它是「黑屏嫌疑人」之一，但**从未被单独验证过**
// ——那次两个钩子是一起装、一起撤的，谁的责任分不开。
//
// 后来 45289988 从另一条路（端点级 web 重写）复现了黑屏，并查明原因：
// **WebView 的本地文件拦截规则只认原始域名**，页面一旦走代理，拦截失效、
// 本地资源取不到，页面加载卡死。这条结论同样适用于 loadURL——所以即便要重启
// 这个钩子，也得先解决拦截规则按代理后域名匹配的问题，否则必然重蹈覆辙。
//
// ─── 原始设计说明（保留）────────────────────────────────────
// 引擎 WebView 的网络走 Http2Session(setURI 已覆盖其 XHR), loadURL 是页面
// 加载入口, 一并改写保证「尽量全代理」: 页面 HTML/JS/资源也经 /stream 走 hk。
// 签名同 setURI(const std::string&), WebViewManager::loadURL 多一个 bool。

using LoadURLFn = void (*)(void* self, const std::string& url);
static LoadURLFn g_origWebViewLoadURL       = nullptr;
static LoadURLFn g_origWebViewImplLoadURL   = nullptr;

// 【已停用】没有 H() 安装它。停用于 9183ab8c；黑屏责任未单独验证过，
// 但 45289988 已从另一条路查明根因：WebView 本地文件拦截只认原始域名。
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

// 【已停用】没有 H() 安装它。与 webViewLoadURL_hook 同批停用于 9183ab8c。
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

// ─── 端点级代理改写（UrlConfig::api/web/chat getter 钩子）────────────
// 游戏所有 API/Web/Chat 地址都经这三个 getter 取出（Impl 内字符串槽位），
// 命中白名单就返回 <proxyBase><原host><原路径> 的重写地址，游戏随后以代理
// 为真实 host 建连——TLS/SNI/:authority 与请求路径天然一致，无需碰 nghttp2。
// 重写结果按 (getter,type) 缓存，同一槽位只写一次。

using UrlGetterFn = const std::string* (*)(void*, int);
static UrlGetterFn urlConfigApiOld  = nullptr;
static UrlGetterFn urlConfigWebOld  = nullptr;
static UrlGetterFn urlConfigChatOld = nullptr;
/**
 * UrlConfig::Impl 里四个端点数组的布局（2026-08-07 反汇编 arm64 版引擎所得）。
 *
 * <p>四个 getter 的机器码形状完全一样，只差最后那个字段偏移：
 *
 * <pre>
 *   ldr    x8, [x8, #0xdb0]     ; Impl 单例（注意：**根本没用 this**）
 *   orr    w9, wzr, #0x18       ; 步长 24 = sizeof(std::string)（libc++ 64 位）
 *   umaddl x8, w1, w9, x8       ; Impl + type * 24
 *   add    x0, x8, #&lt;偏移&gt;      ; + 字段偏移
 *   ret                          ; ← 没有任何边界检查
 * </pre>
 *
 * 字段偏移 resource=0x08、api=0x68、chat=0x1b8、web=0x248，相邻差值全是 24 的
 * 整数倍，说明它们是**连续的 std::string 数组**，长度可由间隔直接算出：
 *
 * <pre>
 *   resource  0x08          间隔 0x60  →  4 个（type 0..3）
 *   api       0x68          间隔 0x150 → 14 个（type 0..13）
 *   chat      0x1b8         间隔 0x90  →  6 个（type 0..5）
 *   web       0x248         上界未知（后面没有可定位的字段，accessToken 是个空桩）
 * </pre>
 *
 * <p><b>因为没有边界检查，传超范围的 type 会读到数组之外的内存，再当成
 * std::string 解引用——直接崩在玩家设备上。</b>所以主动探测只能在上面算出的
 * 范围内做；web 的上界既然定不了，就<b>只被动观测、绝不主动探</b>。
 */
static const int URLCFG_API_SLOTS  = 14;   // type 0..13
static const int URLCFG_CHAT_SLOTS = 6;    // type 0..5
static const int URLCFG_MAX_SLOTS  = 16;   // 数组容量，取整到 16

static std::string g_endpointCache[3][URLCFG_MAX_SLOTS];   // [api/web/chat][type] 改写结果

/**
 * 观测去重用的指纹表：存**哈希**而不是字符串。
 *
 * <p>这些 getter 由引擎的网络线程并发调用。若用 std::string 去重，
 * 「比较 + 赋值」在无锁并发下会撕裂——最坏情况是 LOGI 读到一个正在重分配的
 * 缓冲区，直接崩在日志里。而这只是个日志去重，不值得为它上锁（在钩子里持锁
 * 更危险）。
 *
 * <p>换成 64 位原子整数后，竞争的最坏后果只是多打一行重复日志。
 */
static std::atomic<uint64_t> g_endpointSeen[3][URLCFG_MAX_SLOTS];

/** FNV-1a：够用的去重指纹，不需要抗碰撞。 */
static uint64_t fnv1a(const std::string& s) {
    uint64_t h = 1469598103934665603ULL;
    for (size_t i = 0; i < s.size(); i++) {
        h ^= (unsigned char)s[i];
        h *= 1099511628211ULL;
    }
    return h ? h : 1ULL;   // 0 留作「还没观测过」
}

/**
 * 无条件观测：每个 (getter, type) 的原始取值变化时记一行。
 *
 * <p>2026-08-07 那次真机加的。当时 [proxy] 全场只有「预读缓存」一行，于是
 * <b>分不清两件完全不同的事</b>：
 *
 *   · getter 压根没被引擎调用；
 *   · 调用了，但原地址没命中白名单，于是静默透传。
 *
 * 只在改写成功时记日志（原先的做法）永远区分不了这两者，而它们指向完全相反的
 * 下一步。所以这里改成先无条件记一次原值——去重后每个槽位最多几行，不吵。
 */
static void endpointObserve(int slot, int type, const std::string& orig,
                            const char* tag) {
    if (slot < 0 || slot > 2 || type < 0 || type >= URLCFG_MAX_SLOTS) return;
    uint64_t h = fnv1a(orig);
    uint64_t prev = g_endpointSeen[slot][type].exchange(h, std::memory_order_relaxed);
    if (prev == h) return;                       // 取值没变，不重复记
    LOGI("[proxy] %s[%d] 取值 = %s", tag, type, orig.c_str());
}

static const std::string* endpointRewrite(UrlGetterFn old, void* self, int type,
                                        int slot, const char* tag) {
    const std::string* orig = old(self, type);
    if (type < 0 || type >= URLCFG_MAX_SLOTS) return orig;
    try {
        endpointObserve(slot, type, *orig, tag);
        if (g_dbgNoProxyEndpoint) return orig;   // 调试开关：只观测，不重写
        std::string base;
        std::vector<std::string> domains;
        if (!proxySnapshot(base, domains)) return orig;
        std::string rw;
        if (!tryRewriteUrl(*orig, base, domains, rw)) return orig;
        if (g_endpointCache[slot][type] != rw) {
            LOGI("[proxy] %s[%d]: %s -> %s", tag, type, orig->c_str(), rw.c_str());
            g_endpointCache[slot][type] = rw;
        }
        return &g_endpointCache[slot][type];
    } catch (...) {
        return orig;   // 钩子边界绝不外抛
    }
}

/**
 * 主动把 api / chat 的**全部槽位**读一遍记下来。
 *
 * <h3>为什么要主动探</h3>
 *
 * 被动观测只看得见引擎自己读过的槽位。2026-08-07 那次真机（0105）玩了一整轮
 * ——标题页、主页、巡逻、Scene0、任务、领每日奖励——<b>引擎自始至终只读过
 * api[0]</b>，而它的取值是个<b>裸主机名</b>（{@code dorothy.magi-reco.com}，
 * 没有 scheme），于是 tryRewriteUrl 第一道 "https://" 判断就返回 false，
 * 静默透传，代理从来没生效过。
 *
 * <p>要决定「代理该改写哪个槽位」，就得知道其余槽位里装的是什么——有没有哪个
 * 是完整 URL。那种槽位才是安全的改写点，比赌「往裸主机名里塞路径」稳得多。
 *
 * <h3>为什么这么探是安全的</h3>
 *
 * getter <b>没有边界检查</b>（见上方反汇编），传超范围的 type 会读到数组之外再
 * 当 std::string 解引用——直接崩在玩家设备上。所以范围严格取自「字段偏移间隔 ÷
 * 24」算出的数组长度：api 14 个、chat 6 个。都在数组内，读到的一定是构造好的
 * std::string（没赋过值的就是空串），安全。
 *
 * <p>web <b>不探</b>：它后面没有可定位的字段（accessToken 是个被优化空的桩），
 * 上界定不了。定不了就不赌，只保留被动观测。
 *
 * <h3>为什么要探多轮</h3>
 *
 * 这些槽位是引擎启动过程中陆续填的。0105 日志里 api[0] 在 49.692 就被读到，而
 * 代理配置 49.749 才下发——只探一次会看到一堆空串。所以按节流重复探几轮，靠
 * endpointObserve 的取值去重保证日志不吵：值没变就不会重复记。
 */
static void probeEndpointSlots(void* self) {
    static std::atomic<int>      probeCount{0};
    static std::atomic<uint64_t> lastProbe{0};

    int done = probeCount.load(std::memory_order_relaxed);
    if (done >= 8) return;                       // 总轮数封顶，不留长期开销

    // 用稳定时钟而不是 clock()：后者量的是 CPU 时间，多线程下跑得比墙钟快，
    // 节流会名存实亡；而且它靠传递包含才拿得到，NDK 下不保证。
    uint64_t now = (uint64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    if (done > 0 && now - lastProbe.load(std::memory_order_relaxed) < 2000ULL) {
        return;
    }
    // 抢到名额才探，避免多线程同时刷同一轮
    if (!probeCount.compare_exchange_strong(done, done + 1,
                                            std::memory_order_relaxed)) {
        return;
    }
    lastProbe.store(now, std::memory_order_relaxed);

    for (int t = 0; t < URLCFG_API_SLOTS; t++) {
        const std::string* v = urlConfigApiOld ? urlConfigApiOld(self, t) : nullptr;
        if (v) endpointObserve(0, t, *v, "api");
    }
    for (int t = 0; t < URLCFG_CHAT_SLOTS; t++) {
        const std::string* v = urlConfigChatOld ? urlConfigChatOld(self, t) : nullptr;
        if (v) endpointObserve(2, t, *v, "chat");
    }
}

static const std::string* urlConfigApiNew(void* self, int type) {
    try { probeEndpointSlots(self); } catch (...) {}   // 钩子边界绝不外抛
    return endpointRewrite(urlConfigApiOld, self, type, 0, "api");
}
// 【已停用】web 端点的**改写**没有 H() 安装它（45289988）。
// 原因是查明的、可复现的：web 端点走代理后页面加载卡死黑屏
// （真机表现：只剩厂商 logo 的点击特效）。
// 实现本身是好的，留着是因为解决了 origin/跨域问题之后就能直接复用。
//
// ⚠ 注意区分：下面 urlConfigWebObserve 是**另一个函数**，它只记日志不改写，
// 是装着的。别把两者搞混——改写的这个仍然禁用。
static const std::string* urlConfigWebNew(void* self, int type) {
    return endpointRewrite(urlConfigWebOld, self, type, 1, "web");
}
static const std::string* urlConfigChatNew(void* self, int type) {
    try { probeEndpointSlots(self); } catch (...) {}
    return endpointRewrite(urlConfigChatOld, self, type, 2, "chat");
}

/**
 * web 端点的**观测专用**钩子：只记日志，一个字节都不改。
 *
 * <p>为什么单独写一个而不复用 endpointRewrite：那个函数会改写。web 端点一改写
 * 就黑屏（45289988 真机复现），但我们又确实需要知道它的取值——2026-08-07 那次
 * 真机查明，游戏的 API 流量根本不走 UrlConfig::api，而是走 WebView 的
 * {@code shouldInterceptRequest}；WebView 加载哪个 origin，前端就往哪里发请求。
 * 所以 web 端点的值是理解整条链路的关键，却又是最不能乱动的一个。
 *
 * <p>拆成两个函数，是为了让「装着的那个绝不可能改写」成为**结构上的保证**，
 * 而不是靠调用方记得传对参数。tools/check-proxy-hooks.py 会核对两者的启用状态。
 */
static const std::string* urlConfigWebObserve(void* self, int type) {
    const std::string* orig = urlConfigWebOld(self, type);
    try {
        if (orig) endpointObserve(1, type, *orig, "web(只读)");
        // 也从这里触发一轮 api/chat 全槽位探测。原因见 probeEndpointSlots：
        // 0105 日志里 api[0] 整场只被读了一次(49.692)，而且早于代理配置下发
        // (49.749)；只挂在 api 上就只能探到一轮空值。web 是 54.7s 才被读的，
        // 从这里再探一轮，能拿到「引擎跑起来之后」的快照。
        probeEndpointSlots(self);
    } catch (...) {}      // 钩子边界绝不外抛
    return orig;          // 原样返回，绝不改写
}

// ═══ 【已停用 · v2/v3】nghttp2 逐请求改写 —— 没有 H() 安装这三个钩子 ═══
//
// 这是**唯一一条有硬证据判死刑**的路线，两次真机、两种形态：
//   v2 (1bde225c) → 直接闪退，697b3688 禁用
//   v3 (c15a0d1f) 加了整体 try/catch 与透传兜底后重新启用 → **仍崩**，
//      栈落在 request_impl::on_response —— 回调 UAF。
//
// 结论：异常安全救不了它。逐请求改写会破坏 nghttp2 内部的请求状态机——改写发生
// 在请求已注册进 session 之后，回调触发时引用的对象已经不是原来那个了。这不是
// 加保护能绕开的，是路线本身与 nghttp2 的生命周期模型冲突。
//
// e752fd67 因此改走端点级（UrlConfig getter），让引擎**自己**以代理为 host 建连，
// 完全不碰 nghttp2 内部。那条路活到了现在。
//
// ⚠ 不要再启用这三个钩子。真要重来，先解决「改写时机早于 session 注册」这个前提。
//
// 另注：下面第一句「setURI 运行时 0 调用(废弃)」是 v2 立论的前提，而那个观测
// 来自一次黑屏会话，可能只是没跑到调用点——见本文件 v1 段落的说明。
//
// ─── 原始分析记录（保留）────────────────────────────────────
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

// 【已停用 · v2/v3】没有 H() 安装它。v3 真机复现 request_impl::on_response 的
// 回调 UAF，异常安全救不了，路线本身与 nghttp2 生命周期冲突。不要再启用。
static void hostServiceHook(void* ec, std::string& host, std::string& service,
                            std::string& path, const std::string& uri) {
    if (g_origHostService) g_origHostService(ec, host, service, path, uri);
    try {
        std::string base;
        std::vector<std::string> domains;
        if (!proxySnapshot(base, domains)) return;
        if (host.empty() || proxyIsSelfHost(host) || !proxyHostMatches(host, domains)) return;
        std::string proxyHost = proxyHostOf(base);
        if (proxyHost.empty()) return;
        LOGI("[proxy] host_service: %s -> %s", host.c_str(), proxyHost.c_str());
        host.assign(proxyHost);   // 连接目标/TLS SNI/证书校验 host 全变代理 host
    } catch (...) {
        // 钩子边界绝不外抛：异常即透传，不挡引擎请求
    }
}

// session::submit(ec, method, path, headers, priority_spec): path 是完整 URL。
// 改「像完整 URL 的」string 参数(不依赖哪个是 path——method 不会是 https://)。
using SubmitFn = void (*)(void* self, void* ec, std::string& a, std::string& b,
                          void* headers, void* prio);
static SubmitFn g_origSubmit = nullptr;

// 【已停用 · v2/v3】没有 H() 安装它。与 hostServiceHook 同一路线，同因停用。
static void submitHook(void* self, void* ec, std::string& a, std::string& b,
                       void* headers, void* prio) {
    try {
        std::string base;
        std::vector<std::string> domains;
        std::string rw;
        if (g_origSubmit && proxySnapshot(base, domains)) {
            if (tryRewriteUrl(a, base, domains, rw)) a = rw;
            else if (tryRewriteUrl(b, base, domains, rw)) b = rw;
        }
    } catch (...) {
        // 钩子边界绝不外抛：异常即透传
    }
    if (g_origSubmit) g_origSubmit(self, ec, a, b, headers, prio);
}

// session::submit(ec, method, path, body, headers, prio) —— 带 body string 的重载(0x1119ef0)。
// 改写「像完整 URL 的」string(path)。POST 等带 body 的请求走这个重载。
using SubmitBodyFn = void (*)(void* self, void* ec, std::string& a, std::string& b,
                              std::string& c, void* headers, void* prio);
static SubmitBodyFn g_origSubmitBody = nullptr;

// 【已停用 · v2/v3】没有 H() 安装它。submitHook 的带 body 重载，同因停用。
static void submitBodyHook(void* self, void* ec, std::string& a, std::string& b,
                           std::string& c, void* headers, void* prio) {
    try {
        std::string base;
        std::vector<std::string> domains;
        std::string rw;
        if (g_origSubmitBody && proxySnapshot(base, domains)) {
            if (tryRewriteUrl(a, base, domains, rw)) a = rw;
            else if (tryRewriteUrl(b, base, domains, rw)) b = rw;
            else if (tryRewriteUrl(c, base, domains, rw)) c = rw;
        }
    } catch (...) {
        // 钩子边界绝不外抛：异常即透传
    }
    if (g_origSubmitBody) g_origSubmitBody(self, ec, a, b, c, headers, prio);
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

// 代理配置**不做任何缓存**，只认本次启动 Java 侧下发的那一份。
//
// 曾经有过一份磁盘缓存（cn_proxy_config.tsv，8f6dba66），让这里在 JNI_OnLoad 就
// 预读到代理配置，赶在引擎首个请求之前生效。但它带来一个更糟的失败模式：
// config.json 拉不到时缓存既不更新也不删除，于是每次启动都把请求重写到一个可能
// 早已不存在的代理——而端点级重写**没有失败回退**（改完就交给引擎去连，这里根本
// 不知道连没连上）。服务器一旦下线，玩家永远连不上，而不是退回直连。
//
// 现在的语义是二值的：Java 侧成功读到 config.json 且其中有 proxy 段，才会调
// nativeSetProxyConfig 把 g_proxyBase 填上；在那之前 proxySnapshot 返回 false，
// 全部透传直连。代价是首轮引擎请求不走代理——这个代价是**故意付的**，
// 因为「慢一个请求」远好过「服务器没了就再也进不去」。
//
// 顺带把历史遗留的缓存文件删掉：老玩家设备上已经有一份，留着只会让人以为它还在用。
static const std::string PROXY_CACHE_LEGACY_PATH =
    "/data/data/io.kamihama.totentanz/files/madomagi/cn_proxy_config.tsv";

static void removeLegacyProxyCache() {
    if (remove(PROXY_CACHE_LEGACY_PATH.c_str()) == 0) {
        LOGI("[proxy] 已删除历史遗留的代理配置缓存（现已改为不缓存）");
    }
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
// 格式：每行 ja<TAB>zhCN，换行/制表/反斜杠写作 \n \t \\；`^` 开头是前缀规则；
// `#` 开头是注释；zhCN 为空表示**删除**该串（拼接式文案的语序调整用）。
// 表在启动时加载，之后每 3 秒节流行检查一次 mtime，热更替换后免重启生效。
//
// ⚠ 上面那个路径是**运行时副本，不是源**。源在补丁仓库：
//     （外部发布渠道）  →  madomagi/engine_i18n.tsv
// 它由该仓库的 sync-and-upload.yml 打进 cn_scenario_update.zip（台词包），
// 客户端热更下来解到 <files>/，正好落在上面这个路径。也就是说**直接改设备上
// 那份只是就地验证，下一次台词包更新会把它整个盖掉**——译文要落地必须提到补丁
// 仓库去。完整链路与操作步骤见本仓库 i18n/README.md。

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

// ─── 未命中记录（调试开关 logI18nMiss / logI18nMissAll）───────────────
//
// 钩子原本只在**命中**时打日志。于是「这句为什么没汉化」是问不出答案的：串不在
// 表里时，它有没有流经钩子，日志长得一模一样。2026-08-08 战斗结束那句
// 「カーテンコールで終いやな」就卡在这里——补表和改前端是两个方向完全不同的
// 修法，而当时没有任何证据能分辨该走哪个。这两个开关就是把那片空白填上。
//
// **只记录，不改任何行为**：关着时 noteI18nMiss 头一行就返回，转发路径逐行不变。
//
// 输出按 tsv 的行格式打，换行/制表/反斜杠按同一套规则转义，所以 logcat 抓下来
// `sed` 掉前缀就能直接当表的骨架用，不必手工誊写：
//
//     adb logcat -d -s MagiaCN_Legacy | sed -n 's/.*\[i18n-miss\]\[[^]]*\] //p' \
//         | sort -u > miss.tsv
//
// ⚠ 行首那个 `#` 是**故意**的，别去掉。这张表里「译文为空」不是「还没翻」，而是
// **删除该串**（拼接式文案调语序用的）。也就是说未填译文的骨架行不是惰性的：不带
// `#` 直接追加进表，这些串会当场从界面上消失，而且是在没人改译文的情况下悄悄发生。
// 加上 `#` 后追加是纯粹的空操作（加载器第一件事就是跳过 `#` 行），翻一条放开一条。
//
// ⚠ 填好的译文**要提到补丁仓库**（（外部发布渠道） 的
// madomagi/engine_i18n.tsv），不是留在设备上——设备上那份是热更下发的运行时副本，
// 下一次台词包更新会把它整个盖掉。就地追加只用于验证。见 i18n/README.md。
//
// 为什么默认只记含**假名**的串：译文是简体中文，和日文汉字在字节上分不开，
// 按「含 CJK」筛会把已经翻好的中文台词全量记一遍——去重集瞬间撑满，真正没翻的
// 反而被埋掉。假名（U+3040–U+30FF）中文里不会出现，是唯一可靠的「这串没翻」标记。
// 代价是漏掉纯汉字的日文短语（如「全体攻撃」）；需要时用 logI18nMissAll 兜。
static std::mutex g_i18nMissMutex;
static std::unordered_set<std::string> g_i18nMissSeen;
static bool g_i18nMissFull = false;
static const size_t I18N_MISS_MAX = 2000;   // 撑满就停，不能让排查工具自己吃爆内存

// U+3040–U+30FF 的 UTF-8 恰好是 E3 81/82/83 xx。
// 第二字节 0x80 是 U+3000–U+303F（「」、。等 CJK 标点），中文里也用，必须排除，
// 否则每一句中文台词都会被当成「没翻」。
static bool containsKana(const char* d, size_t n) {
    for (size_t i = 0; i + 1 < n; i++) {
        if ((unsigned char)d[i] != 0xE3) continue;
        unsigned char b = (unsigned char)d[i + 1];
        if (b >= 0x81 && b <= 0x83) return true;
    }
    return false;
}

// i18nUnescape 的逆：让多行文案在 logcat 里保持**一行**。
// 不转义的话一条带 \n 的文案会被 logcat 拆成好几行，抓下来既没法去重也没法回填。
static std::string i18nEscape(const char* d, size_t n) {
    std::string out;
    out.reserve(n + 8);
    for (size_t i = 0; i < n; i++) {
        char c = d[i];
        if      (c == '\n') out += "\\n";
        else if (c == '\t') out += "\\t";
        else if (c == '\\') out += "\\\\";
        else                out += c;
    }
    return out;
}

static void noteI18nMiss(const char* d, size_t n, const char* from) {
    if (!g_dbgLogI18nMiss && !g_dbgLogI18nMissAll) return;   // 关着时零开销
    if (d == nullptr || n == 0 || n > 512) return;  // 超长的多半是拼好的整段，
                                                    // 当表项用不了，记了只是噪音
    if (!g_dbgLogI18nMissAll && !containsKana(d, n)) return;

    std::string s(d, n);
    {
        std::lock_guard<std::mutex> lk(g_i18nMissMutex);
        if (g_i18nMissFull) return;
        if (g_i18nMissSeen.size() >= I18N_MISS_MAX) {
            g_i18nMissFull = true;
            // 记满是**结论会不完整**，必须显式说，否则会以为「就这么多」。
            LOGE("[i18n-miss] 已记满 %zu 条，后续不再记录——这份清单不完整。"
                 "若是开着 logI18nMissAll，多半是被伤害数字之类的一次性串灌满了，"
                 "改用 logI18nMiss 再跑一局。", I18N_MISS_MAX);
            return;
        }
        if (!g_i18nMissSeen.insert(s).second) return;   // 这串见过了
    }
    // 锁外打日志：__android_log_print 可能阻塞，不该压着别的渲染线程。
    // 形状是 `#原文<TAB>`——`#` 见上面的警告；末尾 TAB 是留给译文的空列。
    // 即使 logcat 把行尾空白吃掉也没关系：`#` 在最前面，那行照样是注释。
    std::string esc = i18nEscape(s.data(), s.size());
    LOGI("[i18n-miss][%s] #%s\t", from, esc.c_str());
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
                                const char* label) {   // label 只在 logI18nMiss 时用
    // ⚠ 这两句的**顺序与相对位置都不能动**：开关关着时本函数必须与加开关之前
    // 逐行等价。第一版把开关塞在两者之间、顺手把它们换了个个儿——那是个即使
    // 开关全关也会生效的改动，正是调试设施最不该干的事。
    maybeReloadEngineI18n();
    maybeReleaseDeferredTop();  // 浮层若在刚才撤掉，这里补推主页跳转/补放 BGM
    if (g_dbgNoI18nSetString) { // 调试开关：原样转交，不做任何替换。
        old(self, text);        // 放在两句之后——它们与翻译无关，关掉翻译不该
        return;                 // 顺带把浮层收尾也关掉。
    }
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
    // 两级查找都没命中 —— 记下来（开关关着时下面这句立刻返回，转发路径不变）
    noteI18nMiss(v.data, v.size, label);
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
//
// ⚠ 这几个替身结构体**必须与 cocos2d 的原型逐字节一致**，否则上面那句话就是空话。
// CNColor4B 曾经只写了 r,g,b 三个字节——而 cocos2d::Color4B 是 {r,g,b,a} 四字节。
// AAPCS64 下 3 字节和 4 字节的小聚合体都占一个通用寄存器，所以**参数位置不会错**，
// 编译器也不会报错；但我们转发时只搬 3 个字节，**alpha 被丢掉**，引擎拿到的透明度
// 是寄存器里的残留值。表现是「文字时有时无/整块 UI 看不见」这种极难归因的毛病，
// 而不是干脆的崩溃——正因为它不崩，才在库里躺了很久。
struct CNVec2    { float x, y; };
struct CNSize    { float w, h; };
struct CNColor4B { unsigned char r, g, b, a; };
using InitLabelFn = void (*)(void*, void*, const char*, float,
                             CNVec2, int, CNSize, CNColor4B, int);
static InitLabelFn initLabelOld = nullptr;
static void initLabelNew(void* node, void* label, const char* text, float f,
                         CNVec2 v2, int i1, CNSize sz, CNColor4B c4b, int i2) {
    if (g_dbgNoI18nLabel) {            // 调试开关：原样转发，不做任何替换
        initLabelOld(node, label, text, f, v2, i1, sz, c4b, i2);
        return;
    }
    maybeReloadEngineI18n();
    const char* use = text;
    bool hit = false;
    static thread_local std::string combined;  // 前缀规则命中时的拼接缓冲
    if (text && g_engineI18nReady.load()) {
        auto it = g_engineI18n.find(text);
        if (it != g_engineI18n.end()) {
            uint64_t n = ++g_engineI18nHits;
            if (n <= 10 || n % 100 == 0)
                LOGI("[i18n] 替换 #%llu: %.40s", (unsigned long long)n, text);
            use = it->second.c_str();
            hit = true;
        } else if (enginePrefixLookup(text, strlen(text), combined)) {
            use = combined.c_str();
            hit = true;
        }
    }
    // 没命中就记下来。表没加载成功时（g_engineI18nReady 为假）也算没命中——
    // 那种情况下这份清单会是「所有流经的串」，与 setString 侧的口径一致。
    if (!hit && text) noteI18nMiss(text, strlen(text), "LbUtility::initLabel");
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

// 引擎里硬编码的字体路径只有三条（两个 ABI 一致，strings 核对过）：
//     fonts/MTF4a5kp.ttf          ← 这里重定向
//     fonts/mbm_20160902.ttf      ← 重定向的目标，本来就是它
//     fonts/witchText-export.fnt  ← 魔女文字位图字体，另一套机制，不动
// 所以「把所有字体引用汇到 mbm」落到实处就是下面这一对常量。
//
// 为什么目标是 mbm 而不是先前的 TTZhiHeiGB3-W4：
//   · 覆盖最好——mbm 是格式 12 cmap、30823 个码位（ZhiHei 是格式 4、28611），
//     CJK 基本区 20945/20992 对 20902，扩展 A 也多。换过去只多字不掉字。
//   · 风格统一——mbm 是游戏自己的 MagiReco CN Medium，剧情文本本来就用它，
//     UI 跟着用之后两处字形一致，且引擎少加载一个 8MB 字体。
//   · 更安全——见下面那段关于长度的说明。
static void fontPathFix(void* strObj, const char* tag) {
    if (g_dbgNoFontHook) return;       // 调试开关：完全不碰字体路径
    static const char kFrom[] = "fonts/MTF4a5kp.ttf";        // 18 字符
    static const char kTo[]   = "fonts/mbm_20160902.ttf";    // 22 字符
    // ⚠ 这 22 不是巧合，改这个常量前先读懂：libc++ 的 std::string 短串上限
    // 正好是 22 字符。kFrom 是 18 字符 → 引擎那个 string 必然是短串（内联），
    // 目标也 ≤22 就能全程写在内联缓冲里，一次堆分配都不做。
    // 先前的 "fonts/TTZhiHeiGB3-W4.ttf" 是 24 字符，超了，于是每次重定向都要走
    // fontPathOverwrite 末尾那条「另分配缓冲交给引擎 string 持有」的路径——
    // 也就是 5df4b46d 修过堆破坏的那一条。现在它基本不会再被走到。
    // 若将来把目标换成超过 22 字符的路径，那条路径会重新变成热路径，
    // 届时请重新审视它的所有权约定。
    NdkStrView v = ndkStrRead(strObj);
    if (v.size == sizeof(kFrom) - 1 && memcmp(v.data, kFrom, sizeof(kFrom) - 1) == 0) {
        fontPathOverwrite(strObj, kTo, sizeof(kTo) - 1);
        LOGI("[font] %s: MTF4a5kp → mbm_20160902", tag);
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

    loadDebugFlags();
    LOGI("========== MagiaLegacy JNI_OnLoad ==========");
    // 这行只写**长期成立的职责**，不写「待接管」「暂未实现」这类进度。
    // 原话是「下载流水线待接管」——接管早就做完了（下面 DLJson / SelectURL /
    // AssetLoadState / DSL 那一串 hook 就是它），可这句在日志里又躺了很久，
    // 而日志恰恰是别人排查时第一眼看的东西：一句过时的状态描述会让人从错的
    // 前提出发。进度属于 README 和提交历史，不属于每次启动都打一遍的横幅。
    LOGI("[VERSION] magia-native v1"
         "（取代 libuwasa 与 libcn_hook：端点重定向 + 下载流水线 + 文案/字体）");

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

        // 浮层关闭后的 deferred top/BGM 必须显式在 GL 线程释放。
        if (gClsDownloaderFix) {
            JNINativeMethod m[] = {
                { (char*)"nativeReleaseDeferredTop", (char*)"()V",
                  (void*)nativeReleaseDeferredTop },
                { (char*)"nativeTutorialRestartFailed", (char*)"()V",
                  (void*)nativeTutorialRestartFailed },
            };
            if (env->RegisterNatives(gClsDownloaderFix, m, 2) != 0) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                LOGE("[JNI] RegisterNatives(CNDownloaderFix) 失败——浮层释放将退回文本 hook 兜底");
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
    // 预读上次下发的代理配置（config.json 拉取晚于引擎首个请求）
    removeLegacyProxyCache();
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

    // Totentanz 代理（v4，端点级改写，替代崩溃的 nghttp2 逐请求钩子）:
    // 钩 UrlConfig 的三个端点 getter——游戏所有 API/Web/Chat 地址都从这里取。
    // 命中白名单就返回 <proxyBase><原host><原路径> 的重写地址，
    // 游戏随后**自己**以代理为 host 建连（TLS/SNI/authority 天然一致），
    // 不碰 nghttp2 内部（v3 证明逐请求改写会让 on_response 回调撞 UAF）。
    H("_ZNK9UrlConfig3apiENS_3Api4TypeE",
      (void*)urlConfigApiNew, (void**)&urlConfigApiOld, "proxy: UrlConfig::api");
    H("_ZNK9UrlConfig4chatENS_4Chat4TypeE",
      (void*)urlConfigChatNew, (void**)&urlConfigChatOld, "proxy: UrlConfig::chat");
    // web 端点**只观测不改写**。改写会让页面加载卡死黑屏（2026-08-06 真机复现），
    // 但它的取值又必须知道：2026-08-07 真机查明，游戏的 API 流量根本不经
    // UrlConfig::api，而是走 WebView 的 shouldInterceptRequest——WebView 从哪个
    // origin 加载，前端就往哪里发请求。所以装一个只读钩子把它记下来。
    H("_ZNK9UrlConfig3webENS_3Web4TypeE",
      (void*)urlConfigWebObserve, (void**)&urlConfigWebOld, "proxy: UrlConfig::web(只读观测)");
    // nghttp2 的 host_service_from_uri / session::submit 钩子维持禁用：
    // 真机复现为请求回调 UAF（栈在 request_impl::on_response），不再启用。
    if (g_dbgNoInitLabelHook) {
        LOGE("[DEBUG] noInitLabelHook 生效：**不安装** LbUtility::initLabel 钩子");
    } else {
        H("_ZN9LbUtility9initLabelEPN7cocos2d4NodeERPNS0_5LabelEPKcfNS0_4Vec2EiNS0_4SizeENS0_7Color4BEi",
          (void*)initLabelNew, (void**)&initLabelOld, "i18n: LbUtility::initLabel");
    }

    // ── 引擎 UI 字体路径重定向（MTF4a5kp → mbm_20160902）──
    if (g_dbgNoTtfHooks) {
        LOGE("[DEBUG] noTtfHooks 生效：**不安装** createWithTTF/setTTFConfig 三个钩子");
    } else {
    H("_ZN7cocos2d5Label13createWithTTFERKNS_10_ttfConfigERKNSt6__ndk112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEENS_14TextHAlignmentEi",
      (void*)createWithTtfCfgNew, (void**)&createWithTtfCfgOld, "font: createWithTTF(cfg)");
    H("_ZN7cocos2d5Label13createWithTTFERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_fRKNS_4SizeENS_14TextHAlignmentENS_14TextVAlignmentE",
      (void*)createWithTtfStrNew, (void**)&createWithTtfStrOld, "font: createWithTTF(str)");
    H("_ZN7cocos2d5Label20setTTFConfigInternalERKNS_10_ttfConfigE",
      (void*)setTtfCfgInternalNew, (void**)&setTtfCfgInternalOld, "font: setTTFConfigInternal");
    }

    // ── 下载浮层期间挂起引擎 BGM（QbUtility::playBgmDirect）──
    H("_ZN9QbUtility13playBgmDirectEPKc",
      (void*)playBgmDirectNew, (void**)&playBgmDirectOld, "Overlay: playBgmDirect 挂起");

    LOGI("[JNI] hooks 安装完成：成功 %d 个，失败 %d 个", hookOk, hookFail);
    return JNI_VERSION_1_6;
}
