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
// 所以做法是：把 libuwasa 里**唯一值得留的两个性能 hook** 逆向移植进本文件，
// 然后整个停用 libuwasa 的加载。
//
// 资源下载流水线保留，并补回 libcn_hook 特有的「叫起 Java 安装器」触发。
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
#include <new>        // ::operator new（NDK 字符串缓冲分配）
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>   // logI18nMiss 的去重集
#include <vector>

#include <dirent.h>
#include <errno.h>    // loadDebugFlags 报「目录读不进去」时带上 errno
#include <sys/types.h>
#include <sys/stat.h>
#include <pthread.h>
#include <dlfcn.h>
#include "StoryNameLayout.h"
#include <stdlib.h>   // strtol（安装完成标记的正文解析）
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <stdarg.h>       // TLS 探针的 append(fmt, ...)
#include <sys/socket.h>   // 以下三个都是 TLS 探针建 TCP 用
#include <netdb.h>
#include <arpa/inet.h>

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
// 这个坑第一版照着踩了：真机日志里是
//     E/MagiaCN_Legacy: [UrlConfig] 找不到 CNDownloaderFix
static jclass gClsDownloaderFix   = nullptr; // io.kamihama.magianative.CNDownloaderFix
static jclass gClsRestClient      = nullptr; // io.kamihama.magianative.RestClient
static jclass gClsTutorialPrompt  = nullptr; // io.kamihama.magianative.CNTutorialPrompt
static jclass gClsVersionCheck    = nullptr; // io.kamihama.magianative.CNVersionCheck
static jclass gClsCNMirrors       = nullptr; // io.kamihama.magianative.CNMirrors
static jclass gClsDebugBridge     = nullptr; // io.kamihama.magianative.CNDebugBridge
static jclass gClsTlsProbe        = nullptr; // io.kamihama.magianative.CNTlsProbe

namespace cocos2d {
    struct Data { unsigned char* _bytes; ssize_t _size; };
}

// ═══ 调试开关目录 ════════════════════════════════════════════════════
//
//     <应用数据目录>/debug/<开关名>
//     （数据目录经下方 resolvePrivDir() 解析，正规设备上即
//      /data/data/io.kamihama.totentanz/debug/<开关名>）
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
// 现状：包里没有 android:debuggable，`run-as` 用不了，这个目录
// 只有能直写应用私有目录的环境（root/su、模拟器）碰得到。公测期曾短暂打开过
// debuggable（f38ffea2），两天后又收了回去——不是因为收紧，而是这条路根本送不到
// 人：要用它得会 adb 或 Termux，实际会用的人几乎没有。
//
// 安全边界从来不靠目录的隐蔽性，而靠下面这条：开关只退功能，绝不退防线。
// debuggable 开着还是关着，这条都不变——变的只是有多少人够得到。
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
//     adb shell "run-as io.kamihama.totentanz touch debug/noI18nLabel"
//     # 重启游戏；logcat 里 [DEBUG] 会把当前生效的开关列出来
//
// 启动时无论开没开都会打印全表，所以「有哪些开关」看一眼日志就知道，
// 不必回来翻源码。写错名字也会被单独列出来——否则你会以为开关没用。
// 落点在私有目录根下，与 CNLog 的 log/ **平级**（<priv>/log 与 <priv>/debug）。
// 不放 files/ 里：那是热更的解压根，CNHotUpdateTx 会按前缀算孤儿并删除。
// 现在 cleanupPrefixes("scenario") 只清 madomagi/resource/scenario/json/，
// 碰不到这里——但那是巧合不是保证，前缀哪天放宽到 madomagi/，开关就会在某次
// 热更后集体消失且查不出原因。挪出来就不存在这个问题。
// ─── 应用私有目录解析 ────────────────────────────────────────────
// 历史上这里全部硬编码 /data/data/<pkg>。那是 Android 4.2+ 设备上指向
// /data/user/0 的兼容软链——正规设备都有，但非标准容器/深度定制 ROM 可能
// 没建这个软链。真碰到时引擎没事（走 Context.getFilesDir()），补丁层全瘫：
// FINAL_FLAG 永远读不到 → 每次启动都判定「未安装」→ 反复全量重下。
// 统一改成：读 /proc/self/cmdline 拿包名（不依赖 JNI 就绪时机）、getuid()
// 拿用户号，按 /data/user/<用户号> → /data/data → /data/user/0 的顺序探测，
// **取第一个可写的**。与 Java 侧 CNPaths 同一套算法——两边共享一批 flag 文件
// （安装标记、引擎闸门、序章标记），必须解析出同一个目录，改算法时两边一起改。
// Java 那边 uid 读 /proc/self/status 的 Uid: 行，与 getuid() 是同一个数。
static std::string resolvePrivDir() {
    std::string pkg = "io.kamihama.totentanz";
    FILE* f = ::fopen("/proc/self/cmdline", "rb");
    if (f) {
        char buf[128];
        size_t n = ::fread(buf, 1, sizeof(buf) - 1, f);
        ::fclose(f);
        if (n > 0) {
            buf[n] = 0;
            std::string s(buf);
            // F-038：次级进程名是 <package>:<suffix>（如 io.kamihama.totentanz:
            // cnrestart），应用数据目录仍以基础 package 命名。与 Java CNPaths
            // 同一规则——后缀不得拼进 /data/user/<id>/... 路径。
            const size_t colon = s.find(':');
            if (colon != std::string::npos) s.resize(colon);
            if (!s.empty() && s.find('/') == std::string::npos) pkg = s;
        }
    }
    // 🔴 用户号不能写死 0。用户号 = uid / 100000（UserHandle.PER_USER_RANGE）。
    // 主用户是 0，但工作资料 / 系统分身 / 厂商应用多开不是——真机上见过 10 和 999。
    //
    // 写死 0 的坑比「路径不存在」更隐蔽：分身进程去 stat /data/user/0/<pkg> 时，
    // /data、/data/user、/data/user/0 一路都是 711，**stat 会成功**——于是这里
    // 高高兴兴返回了**主用户**的目录，而本进程（另一个 uid）对它没有任何读写
    // 权限。表现是补丁层「写了没生效 / 读不到自己刚写的东西」，哪一层都不报错。
    //
    // 判据也因此改成**可写**（access W_OK）而不是「是个目录」：别人的目录照样
    // 是目录，但那对我们没用——要的是能落 flag 的地方，不是能看见的地方。
    char userBuf[32];
    ::snprintf(userBuf, sizeof(userBuf), "%lu",
               (unsigned long)::getuid() / 100000UL);
    const std::string candidates[] = {
        // 本进程所属用户的目录。主用户时它就是 /data/user/0/<pkg>。
        std::string("/data/user/") + userBuf + "/" + pkg,
        // 每个应用的挂载命名空间里 /data/data 指向自己那一份；
        // 老设备上它是指向 /data/user/0 的兼容软链。
        "/data/data/" + pkg,
        // 历史路径兜底。放最后：写死 0 在分身进程里会指到别人家。
        "/data/user/0/" + pkg,
    };
    struct stat st;
    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); i++) {
        const char* c = candidates[i].c_str();
        if (::stat(c, &st) == 0 && S_ISDIR(st.st_mode) && ::access(c, W_OK) == 0) {
            return candidates[i];
        }
    }
    // 一个可写的都没有：退回按本进程用户号拼出来的那个。它是「本该正确」的
    // 路径，让错误暴露在原位，而不是换去一个更隐蔽的地方。
    return candidates[0];
}

// 解析一次缓存：进程内路径不会变，每次 flag 读写都重新 stat 太浪费。
static const std::string& privDir() {
    static const std::string dir = resolvePrivDir();
    return dir;
}
static std::string filesDir() { return privDir() + "/files"; }

static const std::string DEBUG_DIR = privDir() + "/debug";

// 开关名用**小驼峰**，与 Java 侧保持一致（同一个目录，两边名字风格不该分裂）。
static bool g_dbgNoI18nLabel     = false;
static bool g_dbgNoI18nSetString = false;
static bool g_dbgNoTutorialGuard = false;
static bool g_dbgNoTutorialForce = false;
static bool g_dbgNoOverlayGate   = false;
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
// 序章按战斗跳段（与其他开关一样的空文件开关）。战斗与 OP 段的对应关系
// 出自引擎调试页 backdoorList.html：OP030=任务①、OP050=任务②、
// OP070=任务③（CONNECT 教学战），每场战斗后紧跟一段 ADV（OP040/060/080）。
// 全部不放时强制序章的一切行为与原来逐行等价（起始段 OP020）。
static bool g_dbgTutSkipToB1   = false;
static bool g_dbgTutAfterB1    = false;
static bool g_dbgTutSkipToB2   = false;
static bool g_dbgTutAfterB2    = false;
static bool g_dbgTutSkipToB3   = false;
static bool g_dbgTutAfterB3    = false;
// 命中的跳段开关折成的起始段与拼好的 pushScenePrologue 入参 JSON；
// 两个都为空 = 不跳转。在 loadDebugFlags 里一并填好，调用点零解析。
static std::string g_dbgTutorialStartSection;
static std::string g_dbgTutorialStartArg;

struct DebugFlagDef { const char* name; bool* slot; const char* desc; };
static const DebugFlagDef kDebugFlags[] = {
    // ── 关掉我们加的引擎改动（按启动链顺序）──
    { "noOverlayGate",   &g_dbgNoOverlayGate,   "浮层期间不闸住 pushSceneTop/BGM（引擎照常推进）" },
    { "noTutorialForce", &g_dbgNoTutorialForce, "不强制序章（即使标记在，也照常进主页）" },
    { "noTutorialGuard", &g_dbgNoTutorialGuard, "序章期间不起 WebView 看门狗" },
    // ── 关掉渲染/文案改动 ──
    { "noI18nLabel",     &g_dbgNoI18nLabel,     "initLabel 不替换文案（引擎侧标签回日文）" },
    { "noI18nSetString", &g_dbgNoI18nSetString, "setString 系不替换文案" },
    // ── 关掉从 libuwasa 移植的两条性能/音频改动 ──
    { "noHttp2Bump",     &g_dbgNoHttp2Bump,     "HTTP/2 并发数保持引擎原本的 4，不提到 10" },
    { "noAdxSampleRate", &g_dbgNoAdxSampleRate, "不锁 ADX2 采样率 48000，用设备实际值" },
    // ── 「根本不装」，用来排除「钩子存在本身」（含原型声明错）──
    { "noInitLabelHook", &g_dbgNoInitLabelHook, "**不安装** LbUtility::initLabel 钩子（排除原型/ABI 问题）" },
    { "noTtfHooks",      &g_dbgNoTtfHooks,      "不安装两个 TTF 构造文本翻译钩子（字体加载仍由原引擎执行）" },
    // ── 只记录，不改行为：把「流经钩子但没翻到」的串打出来 ──
    { "logI18nMiss",     &g_dbgLogI18nMiss,     "记录未命中翻译表的**含假名**串（tsv 行格式，去重）" },
    { "logI18nMissAll",  &g_dbgLogI18nMissAll,  "同上但不筛内容（含英文/数字，噪音大，用于确认某串走没走 native 标签）" },
    // ── 序章按战斗跳段（互斥；同时放多个以跳得最远的为准）──
    { "tutorialSkipToBattle1",   &g_dbgTutSkipToB1,   "序章跳到第 1 场战斗前" },
    { "tutorialSkipAfterBattle1",&g_dbgTutAfterB1,    "序章跳到第 1 场战斗后" },
    { "tutorialSkipToBattle2",   &g_dbgTutSkipToB2,   "序章跳到第 2 场战斗前" },
    { "tutorialSkipAfterBattle2",&g_dbgTutAfterB2,    "序章跳到第 2 场战斗后" },
    { "tutorialSkipToBattle3",   &g_dbgTutSkipToB3,   "序章跳到 CONNECT 教学战前" },
    { "tutorialSkipAfterBattle3",&g_dbgTutAfterB3,    "序章跳到结尾剧情（测序章收尾最快）" },
};

/**
 * <b>Java 侧</b>才读的开关名。native 一个都不读，但必须认得。
 *
 * <h3>为什么 native 要背一份它根本不用的名单</h3>
 *
 * 下面那圈「目录里有不认识的文件」只拿 {@code kDebugFlags} 比对，于是玩家每开一个
 * Java 侧开关，日志里就多一条 ERROR 说他名字打错了。2026-08-27 的真机日志是这样的：
 *
 *   ⚠ 目录里有不认识的文件 countWebSocket —— 名字打错了？      ← 假的，开关正常
 *   ⚠ 目录里有不认识的文件 useAria2 —— 名字打错了？            ← 假的
 *   ⚠ 目录里有不认识的文件 logWebviewRequests —— 名字打错了？   ← 假的
 *   ⚠ 目录里有不认识的文件 tlaProbe —— 名字打错了？             ← **真的**（tlsProbe 打错）
 *
 * 四条长得一模一样，唯一那条真的埋在里面。维护者据此以为探针坏了，回头查了半天
 * 调用链——而实际上警告早就把答案喊出来了，只是喊得跟三次狼来了没有区别。
 *
 * 这正是本仓库自己写下的那条：会喊狼来了的告警比没有告警更糟。
 *
 * <h3>为什么是抄一份而不是共享一份</h3>
 *
 * native 与 Java 之间没有共用的开关表，跨 JNI 现取一份只为打日志不划算。抄一份的
 * 代价是会漂移——所以 {@code tools/check-debug-flag-catalog.py} 钉住
 * 「本表 == Java 的 KNOWN 减去 kDebugFlags」，两边任何一侧加减开关都会红。
 */
static const char* const kJavaSideFlags[] = {
    "skipWebProxy",
    "skipInstaller",
    "skipOverlay",
    "skipVersionCheck",
    "skipMirrorConfig",
    "skipHotUpdate",
    "skipTutorialPrompt",
    "skipRestart",
    "skipSlowAsk",
    "skipBootWatchdog",
    "skipLocalState",
    "useWebviewDebug",
    "logWebviewRequests",
    "countWebSocket",
    "failConfigFetch",
    "failVersionQuery",
    "slowVersionQuery",
    "failDownload",
    "failHotUpdateApply",
    "useAria2",
    "useSingleThread",
    "tlsProbe",
};

// 跳段开关 → 起始段的映射表。按段号升序排，loadDebugFlags 里后者覆盖前者，
// 于是多个同时放时以跳得最远的为准。
struct TutorialSkipDef { const bool* on; const char* section; };
static const TutorialSkipDef kTutorialSkips[] = {
    { &g_dbgTutSkipToB1,   "OP030" },
    { &g_dbgTutAfterB1,    "OP040" },
    { &g_dbgTutSkipToB2,   "OP050" },
    { &g_dbgTutAfterB2,    "OP060" },
    { &g_dbgTutSkipToB3,   "OP070" },
    { &g_dbgTutAfterB3,    "OP080" },
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
            // Java 侧的开关也算「认识」：它们由 CNDebugFlags 读，native 不读，
            // 但报成打错名字会把真正打错的那一个埋掉（见 kJavaSideFlags 的注释）。
            for (size_t i = 0; !known
                    && i < sizeof(kJavaSideFlags) / sizeof(kJavaSideFlags[0]); i++) {
                if (::strcmp(e->d_name, kJavaSideFlags[i]) == 0) { known = true; break; }
            }
            if (!known) LOGE("[DEBUG] ⚠ 目录里有不认识的文件 %s —— 名字打错了？", e->d_name);
        }
        ::closedir(d);
    }
    // 序章跳段：把命中的开关折成一个起始段。空文件开关没有参数可读，
    // 同时放多个时以跳得最远（段号最大）的为准并警告——映射表按段号
    // 升序，循环里后者覆盖前者即可。
    g_dbgTutorialStartSection.clear();
    g_dbgTutorialStartArg.clear();
    {
        int n = 0;
        for (const auto& sk : kTutorialSkips) {
            if (!*sk.on) continue;
            n++;
            g_dbgTutorialStartSection = sk.section;
        }
        if (n > 1) {
            LOGE("[DEBUG] ⚠ 序章跳段开关同时放了 %d 个，以跳得最远的 %s 为准",
                 n, g_dbgTutorialStartSection.c_str());
        }
        if (n > 0) {
            g_dbgTutorialStartArg =
                "{\"beginningId\":\"" + g_dbgTutorialStartSection +
                "\",\"callback\":\"nativeCallback\"}";
            LOGE("[DEBUG]   [ON ] 序章跳段 → 从 %s 开始",
                 g_dbgTutorialStartSection.c_str());
        }
    }
    if (on > 0) {
        LOGE("[DEBUG] ⚠ 共 %d 个开关生效——这是排查用的降级模式，不是正常配置", on);
    }
}

// 安装完成标记。与 Java 侧 CNDownloaderFix.FINAL_FLAG 解析的是同一个文件
// （两边同一套 CNPaths/resolvePrivDir 算法）。
static const std::string FLAG_PATH =
    filesDir() + "/madomagi/magica/cn_base_done.flag";

// 强制序章标记。由 Java 侧 CNTutorialPrompt 在玩家选「是」时写出，
// 我们在引擎首个「进主页」命令上消费它。放在与安装标记同一个目录，
// 那个目录在资源装完时必定存在，不必额外 mkdir。
// ⚠ 与 CNTutorialPrompt.FORCE_TUTORIAL_FLAG 指向同一个文件（两边同一套解析算法）。
static const std::string FORCE_TUTORIAL_FLAG_PATH =
    filesDir() + "/madomagi/magica/cn_force_tutorial.flag";

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

using GetSceneLayerManagerFn = void* (*)();
using PopSceneLayerFn = void (*)(void*, int);
static GetSceneLayerManagerFn getSceneLayerManagerFn = nullptr;
static PopSceneLayerFn popSceneLayerFn = nullptr;

// pushSceneDownload 先 push Loading(33)，再 push Download(27)。原下载状态机
// 会先 pop33；被快速路径直接调用的 onDownloaded 只 pop27，因此这里补齐配对。
// 仅在下载快速完成分支调用，沿用 onEnter 的 GL 线程，不碰其他场景的 loading。
static bool completeReadyDownload(const std::function<void()>& callback) {
    if (!getSceneLayerManagerFn || !popSceneLayerFn) return false;
    void* manager = getSceneLayerManagerFn();
    if (!manager) return false;
    popSceneLayerFn(manager, 33);
    callback();
    return true;
}

static void resolveDownloadLoadingExit(const char* lib) {
    void* handle = ::dlopen(lib, RTLD_NOW | RTLD_NOLOAD);
    if (!handle) handle = ::dlopen(lib, RTLD_NOW);
    if (!handle) return;
    getSceneLayerManagerFn = reinterpret_cast<GetSceneLayerManagerFn>(
        ::dlsym(handle, "_ZN17SceneLayerManager11getInstanceEv"));
    popSceneLayerFn = reinterpret_cast<PopSceneLayerFn>(
        ::dlsym(handle, "_ZN17SceneLayerManager13popSceneLayerE15ESceneLayerType"));
    ::dlclose(handle);
}

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

// ─── 安装完成判据（F-074） ───────────────────────────────
//
// 原先这里是 `return fileExists(FLAG_PATH);` ——**只问文件在不在**。于是一个
// 0 字节、或写到一半掉电的标记，与一份完整标记完全等价。而这个布尔值直接控制
// 八处引擎控制流：跳不跳过原版下载场景、要不要叫起 Java 安装器、下载回调静默组
// 与放行组的极性。Java 侧已经改成按正文判（CNDownloaderFix.parseFinalFlag），
// native 若还停在存在性上，两边就不是同一个状态机——Java 认为「没装完、去装」，
// native 却认为「装好了」，把引擎放进一棵缺资源的树里。
//
// ⚠ 下面三个常量与解析规则**必须**与 Java 侧 CNDownloaderFix 的
//   FINAL_FLAG_BODY / FINAL_FLAG_MAX_BYTES / parseFinalFlag 逐条一致。
//   tools/check-download-ui-contract.py 把两边钉在一起，改一边会红。
//
// 分工：**Java 修，native 只读**。坏标记的自愈（查 13 个基础包 marker、齐全就
// 原子补写）留在 Java 侧一处——那需要 RESOURCE_BASE_URL 的逐字符串比对，复刻到
// native 就是第二份会漂的实现。native 读到不合格的标记只报「没装好」，方向是
// 安全的：引擎放行原版下载场景（我们的浮层盖在上面），安装器随之被叫起。
static const size_t FINAL_FLAG_MAX_BYTES = 16384;
static const long   FINAL_FLAG_ARCHIVES  = 16;

// 纯函数：正文里必须同时出现 schema=<正整数> 与 archives=15。
// schema 只要求「解析得出且 >= 1」——将来格式升级时，新版写下的标记不该被这一版
// 判成损坏；archives 必须严格相等，它就是「这张标记为几个包背书」。
static bool parseFinalFlag(const std::string& body) {
    if (body.empty()) return false;
    bool schemaOk = false, archivesOk = false;
    size_t pos = 0;
    while (pos <= body.size()) {
        size_t nl = body.find('\n', pos);
        std::string line = body.substr(pos, nl == std::string::npos
                                            ? std::string::npos : nl - pos);
        pos = (nl == std::string::npos) ? body.size() + 1 : nl + 1;
        size_t eq = line.find('=');
        if (eq == std::string::npos || eq == 0) continue;
        std::string key = line.substr(0, eq);
        std::string val = line.substr(eq + 1);
        // 两侧空白（含 \r，Java 那边 trim 掉的东西）
        const char* ws = " \t\r\f\v";
        size_t a = key.find_first_not_of(ws), b = key.find_last_not_of(ws);
        key = (a == std::string::npos) ? std::string() : key.substr(a, b - a + 1);
        a = val.find_first_not_of(ws); b = val.find_last_not_of(ws);
        val = (a == std::string::npos) ? std::string() : val.substr(a, b - a + 1);
        if (key != "schema" && key != "archives") continue;
        // 只认纯十进制整数，与 Java 的 Integer.parseInt 同口径
        if (val.empty()) return false;
        size_t i = (val[0] == '+' || val[0] == '-') ? 1 : 0;
        if (i >= val.size()) return false;
        for (size_t j = i; j < val.size(); j++) {
            if (val[j] < '0' || val[j] > '9') return false;
        }
        long n = ::strtol(val.c_str(), nullptr, 10);
        if (key == "schema") {
            if (n < 1) return false;
            schemaOk = true;
        } else {
            if (n != FINAL_FLAG_ARCHIVES && n != 15) return false;
            archivesOk = true;
        }
    }
    return schemaOk && archivesOk;
}

static bool finalFlagWellFormed() {
    struct stat st;
    if (::stat(FLAG_PATH.c_str(), &st) != 0) return false;
    if (!S_ISREG(st.st_mode)) return false;
    if (st.st_size <= 0 || (size_t)st.st_size > FINAL_FLAG_MAX_BYTES) return false;
    FILE* f = ::fopen(FLAG_PATH.c_str(), "rb");
    if (!f) return false;
    std::string body;
    body.resize((size_t)st.st_size);
    size_t got = ::fread(&body[0], 1, body.size(), f);
    ::fclose(f);
    body.resize(got);
    return parseFinalFlag(body);
}

// 一旦判定为「已装好」就缓存住：本进程内标记不会再变回不合格（重下单个包不删
// 它，「全部重下」走的是重启）。判 false 时不缓存——安装器正在跑，装完这一刻
// 起后续调用必须立刻看到 true。
static std::atomic<bool> g_resourcesReadyCache{false};

static bool resourcesReady() {
    if (g_resourcesReadyCache.load(std::memory_order_relaxed)) return true;
    if (!finalFlagWellFormed()) return false;
    g_resourcesReadyCache.store(true, std::memory_order_relaxed);
    return true;
}

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
    if (!completeReadyDownload(cb)) {
        LOGE("[DSL::onEnter] loading 清理接口未就绪，保留原下载状态机");
        downloadSceneLayerOnEnterOld(_this);
        return;
    }
    LOGI("[DSL::onEnter] ★ 已清理下载前置 loading，并调用完成回调 info=%p", info);
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
        // 与下方改写循环同一判据（带引号的键名）：预判裸子串会把「其它字段值里
        // 恰好含 asset_optimize 字样」的清单误带进补丁分支，白走一遍无操作复制。
        if (sv.find("\"asset_optimize\"") != std::string_view::npos) {
            LOGI("[checkParseJson] 修正 asset_optimize");
            std::string patched(sv);          // 栈上副本，避免静态存储的竞态
            // 精准改写（F-F-05）：只翻 "asset_optimize" 键名下的 :1——旧版把
            // 全串 ":1" 一律改 ":0"，清单里任何其它字段（计数值、嵌套对象、
            // 字符串内容里的 ":1"）都会被误翻。规则：命中键名 → 跳过 JSON
            // 空白 → 期望 ':' → 再跳空白 → 当前字符是 '1' 且后继不是数字
            // （"1.0"→"0.0" 合法小数照旧翻；"10"~"19" 等整数不动）才翻。
            size_t pos = 0;
            while ((pos = patched.find("\"asset_optimize\"", pos)) != std::string::npos) {
                size_t p = pos + 16;          // 键名长度
                while (p < patched.size() && (patched[p] == ' ' || patched[p] == '\t'
                        || patched[p] == '\r' || patched[p] == '\n')) p++;
                if (p >= patched.size() || patched[p] != ':') { pos += 16; continue; }
                p++;
                while (p < patched.size() && (patched[p] == ' ' || patched[p] == '\t'
                        || patched[p] == '\r' || patched[p] == '\n')) p++;
                if (p < patched.size() && patched[p] == '1'
                        && (p + 1 >= patched.size() || patched[p + 1] < '0' || patched[p + 1] > '9')) {
                    patched[p] = '0';
                }
                pos = p;
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
//
// ⚠ 本段钩子的极性分两组，**不要顺手「统一」**（F-F-04）：
//   · 静默组（selectURL/dlJson/mainSceneOnErr 等）：resourcesReady() 为真时
//     吞掉回调——资源已装，引擎自身的下载/错误流程不必再跑；
//   · 放行组（qbScene/questData，见下）：resourcesReady() 为**假**时吞掉——
//     这两个回调驱动的是玩法场景数据，资源没装好前放行会让引擎拿着空资源
//     进场景；装好之后才该原样透传。
// 两组极性相反都是有意为之。注意：本结论由调用点行为反推（libcn_hook 无
// 源码，引擎内部语义无从直接证实），若日后拿到引擎符号级证据显示某钩子
// 极性反了，单独修那一个，不要整段翻转。
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
    // 放行组（极性与上面静默组相反，见段首 F-F-04 注释）：资源未装时吞掉，
    // 装好才透传——不要顺手翻转成「ready 时静默」。
    if (!resourcesReady()) return;
    qbSceneOnRespOld(a, b, c);
}
static void questDataOnRespNew(void* a, void* b, void* c) {
    // 放行组：同上，极性有意相反，勿翻转。
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
// 另一条路是逐字段复刻调试菜单「播放序章」的构造：
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
    filesDir() + "/madomagi/cn_overlay_active.flag";

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


// v6: 序章全程压住前端界面的看门狗，回到 v4 语义（250ms 无条件按回隐藏）。
// v5 曾给每次 notifyJs / 内部 Top 跳转各发 2500ms「宽限期」，担心 v4 的强压
// 与 ADV->战斗 撞车。但宽限期生效的时刻与 WebView 自己复出的时刻**恰好重合**
// ——段通知一到，前端就在段边界复出（见上文 v3 的教训），于是一连串 notifyJs
// 把宽限期不断续满，看门狗全程待机，主页（含 Live2D）整场压在战斗画面上，
// 直到段通知停了、最后一个宽限期耗尽才被按回去。玩家看到的就是「第一场战斗
// 时主页 L2D 小人压在战斗前面，过一段时间又自己消失」（公测真机报告）。
// 而且压 WebView 只是 setVisibility，挡不住也不影响 native 场景切换——
// ADV->战斗 若真被卡，嫌疑在 Top 闸门（v5 已改为只吞精确匹配的主页 Top，
// 本版保留），不在 WebView 的显隐。
static std::atomic<bool> g_uiWatchdogOn{false};
static void* uiWatchdogMain(void*) {
    LOGI("[Tutorial] WebView guard started mode=always-hide interval=250ms");
    while (g_tutorialActive.load()) {
        setGameUiVisible(false);
        usleep(250 * 1000);
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
        // 按战斗跳段（debug/tutorialSkip{To,After}Battle{1,2,3}）：
        // loadDebugFlags 已把命中的开关折成同样的 JSON 放在
        // g_dbgTutorialStartArg；它为空（一个开关都没放）时 prologueArg
        // 就是 kPrologueArg 本身，本分支的每条语句与没有这个功能时一致。
        const std::string& prologueArg = g_dbgTutorialStartArg.empty()
                                         ? kPrologueArg : g_dbgTutorialStartArg;
        const char* startSection = g_dbgTutorialStartSection.empty()
                                   ? "OP020" : g_dbgTutorialStartSection.c_str();
        LOGI("[Tutorial] 命中强制教程标记 → 改走 pushScenePrologue(%s)"
             "（原 pushSceneTop arg=%s）", startSection, arg.c_str());
        saveTop(self, arg);
        {
            std::lock_guard<std::mutex> lk(g_savedTopMutex);
            g_tutorialHomeTopSelf = self;
            g_tutorialHomeTopArg = arg;
        }
        LOGI("[Tutorial] homepage Top identity captured arg=%s", arg.c_str());
        g_tutorialForced.store(true);
        g_tutorialActive.store(true);
        // v6 guard: 序章全程无条件按回复出的 WebView（理由见 uiWatchdogMain 注释）。
        if (!g_dbgNoTutorialGuard && !g_uiWatchdogOn.exchange(true)) {
            pthread_t t;
            if (pthread_create(&t, nullptr, uiWatchdogMain, nullptr) == 0) {
                pthread_detach(t);
            } else {
                g_uiWatchdogOn.store(false);
                LOGE("[Tutorial] WebView guard thread failed; ctor one-shot hide remains");
            }
        }
        pushScenePrologueFn(self, prologueArg);
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

// 序章结束的统一收尾。前端此刻状态不可知（主页加载到一半、被我们藏了整场、
// 还收了一堆段通知），就地收拾不如干脆重启——与「安装完成」同一套
// Toast + 3 秒 + 重启，回来是干净的主页。标记在触发时已删，
// 重启后不会再进序章。
//
// 前端界面**保持隐藏**直到进程退出：恢复出来也只会把加载到一半的
// 主页亮给玩家看 3 秒，不如不亮。Toast 是系统级窗口，不受影响。
//
// 用 exchange 保证只有第一个到达的结束信号（dtor 或最终 notifyJs）真正收尾。
static bool finishPrologueOnce(const char* source) {
    bool wasActive = g_tutorialActive.exchange(false);
    bool forced = g_tutorialForced.load();
    if (!wasActive || !forced) return false;
    if (requestPrologueRestart()) {
        LOGI("[Tutorial] 序章结束（%s），已叫起 Toast + 3 秒 + 重启", source);
    } else {
        // JNI 不通时的兜底：恢复前端界面 + 补放吞掉的 pushSceneTop，
        // 至少别把玩家留在黑屏上。
        LOGE("[Tutorial] 重启通道不通（%s），退兜底：恢复界面 + 补放 pushSceneTop", source);
        setGameUiVisible(true);
        replaySavedTop();
    }
    return true;
}

// 序章图层的构造/析构。
static void prologueCtorNew(void* _this, void* info) {
    prologueCtorOld(_this, info);
    bool forced = g_tutorialForced.load();
    LOGI("[Tutorial] PrologueSceneLayer 已构造 _this=%p（forced=%d）", _this, (int)forced);
    if (forced) setGameUiVisible(false);
}

static void prologueDtorNew(void* _this) {
    LOGI("[Tutorial] PrologueSceneLayer 析构 _this=%p（active=%d forced=%d）",
         _this, (int)g_tutorialActive.load(), (int)g_tutorialForced.load());
    finishPrologueOnce("dtor");
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
    notifyJsOld(_this, arg);
    LOGI("[Tutorial::notifyJs] after callback arg=%s", arg.c_str());
    // 「prologue」是 OP020…OP080 全部播完后的最终完成信号。实测引擎此刻
    // 并不析构 PrologueSceneLayer（它等前端驱动下一步，而前端被我们压了
    // 整场、状态不可知），dtor 闸门可能永远等不到——把完成信号也作为
    // 结束触发点；finishPrologueOnce 保证两个信号只收尾一次。
    if (arg == "prologue") finishPrologueOnce("notifyJs");
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
// 包的身份，动了会影响覆盖安装）。**CI 构建时会把下面那个字面量改写成
// 1.0.<run_number>**（见 build-apk.yml 的「注入客户端版本号」步骤，每个构建
// 单调递增）——这里的字面量只是本地构建（tools/build-local.sh）的兜底，
// 发版不需要手改本文件。云端 config.json 的 client.version 抬过某个构建号，
// 低于它的包启动时就弹强制更新框（Java 侧 CNVersionCheck）。
//
// ## 为什么要编译期混淆它
//
// 要防的**不是**逆向工程师：能读懂 smali 与 JNI 的人，直接 fork 仓库自己重打包
// 就行，本仓库拦不住、也不打算拦。要防的是拿 APK 管理器照着教程改包的人，
// 他们的全部手法就是「全局搜版本号 → 改成一个大的 → 用管理器内置签名重签 →
// 装」。签名这一环拦不住——包用的是公开的 AOSP 测试密钥，谁都能重签成同一
// 指纹——所以唯一有意义的一步是**让第一步就搜不到东西**。
//
// 做法：字面量只在编译期存在，逐字节异或之后才进 .rodata；异或密钥随下标变化，
// 免得整串同一偏移、扫一眼就看出规律。运行时在栈上还原，用完即清。
// 注意 strip 不动 .rodata（tools/check-apk-freshness.py 正是靠这一点做新鲜度
// 比对），所以指望 strip 把明文带走是不成立的，必须在源码层面就不留。
//
// Java 侧同理：`CNUserAgent` 不再持有版本号字面量，改为向本函数要——
// `static final String` 会被 javac 内联到每一个引用处，等于把明文撒进整个 dex。
namespace verobf {

// 密钥随下标变化。写成 constexpr 函数而不是宏，保证在编译期求值。
constexpr uint8_t key_at(size_t i) {
    return static_cast<uint8_t>(0x5Au + i * 0x1Fu);
}

template <size_t N>
struct Hidden {
    char bytes[N];
    constexpr explicit Hidden(const char (&s)[N]) : bytes{} {
        for (size_t i = 0; i < N; ++i)
            bytes[i] = static_cast<char>(static_cast<uint8_t>(s[i]) ^ key_at(i));
    }
};

}  // namespace verobf

// ⚠ CLIENT_VERSION 是客户端语义版本的唯一事实来源。CI 只读取它写入构建环境与
//   版本旁注，不得用 GITHUB_RUN_NUMBER 等构建编号覆盖；本地构建也直接使用该值。
//   它是 constexpr、从不取地址，只在编译期喂给下面的 Hidden，因此不会有一份
//   明文留在产物里。
static constexpr char CLIENT_VERSION[] = "1.0.203";

// 真正进二进制的是这一份：异或之后的字节。
static constexpr auto kVersionHidden =
        verobf::Hidden<sizeof(CLIENT_VERSION)>(CLIENT_VERSION);

// 经 RegisterNatives 绑给 CNVersionCheck.nativeClientVersion()。
static jstring nativeClientVersion(JNIEnv* env, jclass) {
    // 保留运行期读取：否则优化器会把 constexpr 解码再折叠成明文字面量。
    const volatile char* encoded = kVersionHidden.bytes;
    char plain[sizeof(CLIENT_VERSION)];
    for (size_t i = 0; i < sizeof(plain); ++i)
        plain[i] = static_cast<char>(
                static_cast<uint8_t>(encoded[i]) ^ verobf::key_at(i));
    jstring s = env->NewStringUTF(plain);
    // 别把明文留在栈上。volatile 防止优化器把这次清零当成死代码删掉。
    volatile char* wipe = plain;
    for (size_t i = 0; i < sizeof(plain); ++i) wipe[i] = 0;
    return s;
}

// ═══ 调试悬浮窗的总闸：烧在包里的一个布尔 ═══════════════════════════
//
// 决定 CNDebugBridge 允不允许挂调试悬浮窗（以及允不允许由应用自己写调试开关
// 文件）。**不是**运行时开关，没有任何文件/配置能改它——收回调试权限就是把
// 下面这个 1 改成 0 再出包，一步，不依赖任何人在设备上做什么。
//
// ## 为什么门槛必须烧在包里，而不是「先建一个文件自举」
//
// 自举方案（先用 su 建 <priv>/debug/enableOverlay，之后其余开关免 su）看起来
// 守住了分界，实际上把门槛正好架在了目标受众面前：
//
//   能建出那个文件的人 = 会 adb/Termux 的人 = 本来就能直接 touch 开关的人；
//   真正需要悬浮窗的人 = 建不出那个文件的人。
//
// 挡住的正好是要服务的那批，放进来的正好是不需要它的那批。这不是假想——
// android:debuggable 就是这么白开了两天又收回去的（f38ffea2 打开，两天后收回）：
// 那条路要求会用 Termux，而实际会用的人几乎没有。同一个错误不该犯第二次。
//
// ## 公测结束怎么收
//
// 把 MAGIA_DEBUG_OVERLAY 的默认值改成 0 出包即可。内部测试包不受影响：
// 编译时传 -DMAGIA_DEBUG_OVERLAY=1 覆盖（tools/build-local.sh 可加）。
// 这样「对外收回」与「内部保留」是两条独立的开关，不用维护两份源码。
#ifndef MAGIA_DEBUG_OVERLAY
#define MAGIA_DEBUG_OVERLAY 1     // 公测期：所有人可用
#endif
static const bool DEBUG_OVERLAY_ENABLED = (MAGIA_DEBUG_OVERLAY != 0);

// 经 RegisterNatives 绑给 CNDebugBridge.nativeDebugOverlayEnabled()。
static jboolean nativeDebugOverlayEnabled(JNIEnv*, jclass) {
    return DEBUG_OVERLAY_ENABLED ? JNI_TRUE : JNI_FALSE;
}

// 经 RegisterNatives 绑给 CNDebugBridge.nativeDebugFlagTable()。
//
// 把 kDebugFlags 交给 Java 侧，让调试悬浮窗的列表能**从表生成**，而不是在界面
// 里硬编码一份副本。副本一定会过期：本文件加一个开关，界面上不会有；界面上删
// 一行，native 侧照跑不误——而两边不一致时，人只会得出「这个开关坏了」这个错
// 结论，恰恰是这套开关最不该造成的效果。
//
// 返回**扁平三元组** String[3N]：{名字, 说明, 当前是否生效("1"/"0")}。
// 不用 String[][]：二维数组要先 FindClass("[Ljava/lang/String;") 再逐行建，
// 多一层出错点，而这里的结构简单到不值得。Java 侧按 3 取模拆开。
//
// 第三列是**本进程启动时**读到的值，也就是当前真正在生效的那份，直接取 slot
// 指针。磁盘上「下次启动会生效」的那份由 Java 侧扫同一个目录得到——两者可以
// 不同（勾了还没重启），这个区别 Java 侧要留着，别在这里合并掉。
//
// 任何一步失败一律返回 nullptr：Java 侧据此只列自己那 16 个开关，不崩。
static jobjectArray nativeDebugFlagTable(JNIEnv* env, jclass) {
    const size_t n = sizeof(kDebugFlags) / sizeof(kDebugFlags[0]);
    jclass strCls = env->FindClass("java/lang/String");
    if (!strCls) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jobjectArray out = env->NewObjectArray((jsize)(n * 3), strCls, nullptr);
    if (!out) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(strCls);
        return nullptr;
    }
    for (size_t i = 0; i < n; i++) {
        const DebugFlagDef& f = kDebugFlags[i];
        const char* cells[3] = {
            f.name ? f.name : "",
            f.desc ? f.desc : "",
            (f.slot && *f.slot) ? "1" : "0",
        };
        for (size_t c = 0; c < 3; c++) {
            jstring s = env->NewStringUTF(cells[c]);
            if (!s) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->DeleteLocalRef(out);
                env->DeleteLocalRef(strCls);
                return nullptr;
            }
            env->SetObjectArrayElement(out, (jsize)(i * 3 + c), s);
            // 局部引用有上限（默认 512 个）。19 个开关 × 3 列虽然还没到顶，
            // 但这里是循环建引用，逐个放掉是这类代码唯一不用算数的写法。
            env->DeleteLocalRef(s);
        }
    }
    env->DeleteLocalRef(strCls);
    return out;
}

// ═══ 【已停用 · v1】setURI 改写 —— 当前没有任何 H() 安装 setURI_hook ═══
//
// 停用于 1ef3401f（真机黑屏卡死）。下面的实现完整保留，作为反向工程记录。
//
// ⚠ 关于「setURI 运行时 0 调用，是废弃 API」这个说法，证据没有看上去那么硬：
//   那次观测（47c02f72 加了无条件日志、1ef3401f 记录结果）的现场是**游戏黑屏
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
//     <proxyBase><host><path>   (proxyBase 如 https://<api 子域>/stream/)
// 代理入口与域名白名单由 CNMirrors 从 config.json 的 "proxy" 字段解析后
// 经 nativeSetProxyConfig 注入——不在本文件硬编码, 换节点只改 config.json。
// 配置缺失(未下发)时原样直连, 兼容旧版。
//
// 安全要点(同 fontPathOverwrite dd06a6b6 的教训):
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

// 排除自身: 自有主域及其子域是 config/线路表/资源所在, 重写它会死循环。
//
// 主域由构建期注入(MAGIA_ROOT_DOMAIN), 源码里不留真实域名——与 Java 侧的
// CNEndpoints.ROOT_DOMAIN 是同一个值, 由 build-apk.yml 一处给出。
// 注入缺失时宏是空串: 此时**一律不认作自身**, 于是不会有任何 host 被误判成
// 自家域而跳过重写。方向是安全的那一边(宁可多重写一次, 不可漏掉死循环判断
// 以外的东西), 而真正防呆的是构建期的预检——没注入根本出不了包。
#ifndef MAGIA_ROOT_DOMAIN
#define MAGIA_ROOT_DOMAIN ""
#endif
static bool proxyIsSelfHost(const std::string& host) {
    static const std::string root = MAGIA_ROOT_DOMAIN;
    // 带点的后缀只拼一次: proxyEndsWith 收的是 const char*, 每次现拼会造一个
    // 临时 std::string, 而这个判断在每条被改写的请求上都要走一遍。
    static const std::string dotRoot = "." + root;
    if (root.empty()) return false;
    return host == root || proxyEndsWith(host, dotRoot.c_str());
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

// 【已停用 · v1】没有 H() 安装它。停用于 1ef3401f（真机黑屏卡死）。
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
// 与 setURI 一起停用于 1ef3401f。它是「黑屏嫌疑人」之一，但**从未被单独验证过**
// ——那次两个钩子是一起装、一起撤的，谁的责任分不开。
//
// 后来 67ad9664 从另一条路（端点级 web 重写）复现了黑屏，并查明原因：
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

// 【已停用】没有 H() 安装它。停用于 1ef3401f；黑屏责任未单独验证过，
// 但 67ad9664 已从另一条路查明根因：WebView 本地文件拦截只认原始域名。
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

// 【已停用】没有 H() 安装它。与 webViewLoadURL_hook 同批停用于 1ef3401f。
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

// [api/web/chat][type] 改写结果缓存。
//
// 为什么不能是裸 std::string 数组：这些 getter 由引擎的网络线程**并发**
// 调用（本文件 1543 行自己也是这么论证 g_endpointSeen 的）。裸 string 的
// 「比较 + 赋值 + 把引用交出去」在无锁并发下有两种炸法：
//   1. 两个线程同时给同一槽位赋值 → std::string 数据竞争（UB）；
//   2. 引擎经返回的引用长期持有对象，下一次 `= rw` 重赋值触发重新分配，
//      引擎手里的引用悬空（UAF）。
// 所以槽位里放的是**原子指针**，指向的对象一经创建永不修改、永不释放
// （有意泄漏，换「返回引用的终身有效」）：端点取值在一局游戏里极少变化，
// 每次变化只泄漏一个几十字节的对象，代价可忽略；而引擎任何时候解引用
// 拿到的都是完整对象。这正是「写一次、永不改」语义。
static std::atomic<const std::string*> g_endpointCache[3][URLCFG_MAX_SLOTS];

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
        std::string base;
        std::vector<std::string> domains;
        if (!proxySnapshot(base, domains)) return orig;
        std::string rw;
        if (!tryRewriteUrl(*orig, base, domains, rw)) return orig;
        // 无锁读改写：命中既有缓存直接复用；未命中或取值变了就**新建**
        // 一个 string 并原子替换指针。旧对象故意不 delete——引擎可能正
        // 持有它的引用，释放即 UAF；泄漏一个对象换引用终身有效。
        const std::string* cur =
                g_endpointCache[slot][type].load(std::memory_order_acquire);
        if (cur && *cur == rw) return cur;
        const std::string* nxt = new std::string(rw);
        LOGI("[proxy] %s[%d]: %s -> %s", tag, type, orig->c_str(), rw.c_str());
        g_endpointCache[slot][type].store(nxt, std::memory_order_release);
        return nxt;
    } catch (...) {
        return orig;   // 钩子边界绝不外抛
    }
}

/**
 * 端点 getter 的只读观测包装：记录原值后原样返回，结构上没有改写路径。
 *
 * <p>2026-08-21 真机 A/B 已确认：api/chat 被重写到代理后，旧版 Cocos/OpenSSL
 * 在服务端握手扩展处报 0x140920E3（界面错误码 336142563），所有战斗均无法进入；
 * 同一进程改为 native 端点直连后战斗立即成功。WebView 代理属于另一条链，仍由
 * Java 拦截器处理。因此 api/chat 永久只读观测，不再靠调试文件临时绕过。</p>
 *
 * <h3>⚠ 未解：上面这段与另外两处记载对不上</h3>
 *
 * 三份材料放在一起是矛盾的，谁翻到这里都该先知道，别拿其中一条当定论：
 *
 * <ol>
 *   <li><b>本段</b>（2026-08-21 A/B）说「重写之后战斗全挂」——前提是那次<b>重写
 *       真的发生了</b>。</li>
 *   <li><b>CNWebProxy 的类注释</b>说端点级代理「一次都没生效过」：0103/0104/
 *       0105/0107/0112 五份日志里，表示改写成功的 {@code [proxy] api[n]: 原址
 *       -> 新址} 一行都没有；成因是 api[0] 的取值是个<b>裸主机名</b>（没有
 *       scheme），tryRewriteUrl 第一道 "https://" 判断就返回 false。</li>
 *   <li><b>2026-08-27 的三份日志</b>（0131/0132/0134，其中 0134 完整打了一场
 *       主线战斗）：{@code [proxy] api} 观测 <b>0 条</b>，而钩子确实装着
 *       （34 成功 0 失败）；同一场战斗里 quest/start（敌人配置）与 QuestResult
 *       （结果回传）<b>全部走 WebView</b>，战斗本身那 46 秒一条请求都没有。</li>
 * </ol>
 *
 * ①与②不可能同时为真：要么那次 A/B 用的不是这条改写路径，要么 336142563 来自
 * 别的连接。③只说明「引擎这条通道没在传战斗数据」，它不能替①或②作证。
 *
 * <p><b>本决定不受影响</b>：只读观测是两种可能下都安全的那一边——真会挂就必须
 * 只读，不会挂也只是少一条没人用的改写路径。但谁要重新启用改写，<b>先把①②哪
 * 条不成立查清楚</b>，别照着其中一条往下推。查法：开 tlsProbe 拿到握手结论，
 * 再看下面 probeEndpointSlots 那行汇总（它会说清 getter 到底读没读到东西）。
 */
static const std::string* endpointObserveOnly(UrlGetterFn old, void* self, int type,
                                              int slot, const char* tag) {
    const std::string* orig = old(self, type);
    try {
        if (orig) endpointObserve(slot, type, *orig, tag);
    } catch (...) {}      // 钩子边界绝不外抛
    return orig;          // 原样返回，绝不改写
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

    // ⚠ 下面这一行汇总不是可有可无的调试残留，它是**这段探测唯一的存在证明**。
    //
    // 2026-08-27 的三份真机日志（0131/0132/0134，其中 0134 还完整打了一场主线
    // 战斗）里，[proxy] 只有 web 那两行，api / chat 一条都没有——而钩子明明装上了
    // （日志里 `[Hook] ✓ proxy: UrlConfig::api(只读观测)`，34 成功 0 失败）。
    // 「没有输出」当时对应三种完全不同的事实，而日志里长得一模一样：
    //
    //   ① probeEndpointSlots 压根没跑到（节流/CAS 抢名额失败/根本没被调用）；
    //   ② 跑了，但 getter 返回 nullptr，一个槽位都没读到；
    //   ③ 读到了，但 endpointObserve 因为去重把每一条都咽了。
    //
    // 三者指向的下一步完全相反，而当时无从分辨——这正是 endpointObserve 自己
    // 那段注释里记着的同一个教训（「getter 没被调用」与「调用了但没命中」压成
    // 同一种沉默），只是这次沉默发生在更外面一层。所以补一行：跑过就留痕，
    // 读到几个、其中几个非空，一行说清。每轮最多一行，八轮封顶，不吵。
    int apiRead = 0, apiNonEmpty = 0, chatRead = 0, chatNonEmpty = 0;
    for (int t = 0; t < URLCFG_API_SLOTS; t++) {
        const std::string* v = urlConfigApiOld ? urlConfigApiOld(self, t) : nullptr;
        if (v) {
            apiRead++;
            if (!v->empty()) apiNonEmpty++;
            endpointObserve(0, t, *v, "api");
        }
    }
    for (int t = 0; t < URLCFG_CHAT_SLOTS; t++) {
        const std::string* v = urlConfigChatOld ? urlConfigChatOld(self, t) : nullptr;
        if (v) {
            chatRead++;
            if (!v->empty()) chatNonEmpty++;
            endpointObserve(2, t, *v, "chat");
        }
    }
    LOGI("[proxy] 主动探测第 %d 轮：api 读到 %d/%d（非空 %d），chat 读到 %d/%d（非空 %d）"
         "%s",
         done + 1, apiRead, URLCFG_API_SLOTS, apiNonEmpty,
         chatRead, URLCFG_CHAT_SLOTS, chatNonEmpty,
         (urlConfigApiOld && urlConfigChatOld) ? "" : "  ⚠ 有 getter 指针是空的（钩子没装上）");
}

static const std::string* urlConfigApiNew(void* self, int type) {
    try { probeEndpointSlots(self); } catch (...) {}   // 钩子边界绝不外抛
    return endpointObserveOnly(urlConfigApiOld, self, type, 0, "api(只读)");
}
// 【已停用】web 端点的**改写**没有 H() 安装它（67ad9664）。
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
    return endpointObserveOnly(urlConfigChatOld, self, type, 2, "chat(只读)");
}

/**
 * web 端点的**观测专用**钩子：只记日志，一个字节都不改。
 *
 * <p>为什么单独写一个而不复用 endpointRewrite：那个函数会改写。web 端点一改写
 * 就黑屏（67ad9664 真机复现），但我们又确实需要知道它的取值——2026-08-07 那次
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
//   v2 (8407bdc5) → 直接闪退，022eb399 禁用
//   v3 (91038e89) 加了整体 try/catch 与透传兜底后重新启用 → **仍崩**，
//      栈落在 request_impl::on_response —— 回调 UAF。
//
// 结论：异常安全救不了它。逐请求改写会破坏 nghttp2 内部的请求状态机——改写发生
// 在请求已注册进 session 之后，回调触发时引用的对象已经不是原来那个了。这不是
// 加保护能绕开的，是路线本身与 nghttp2 的生命周期模型冲突。
//
// 8291dc6c 因此改走端点级（UrlConfig getter），让引擎**自己**以代理为 host 建连，
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
//     host(api 子域的真实证书, 免证书 hook)。
//   · client::session::submit(ec, method, path, headers, prio): path 参数是完整
//     URL(Http2Request+0x10), 直接改写为 base+host+path → :authority/:path 走
//     /stream。两个 hook 缺一不可(:path 只来自 submit, host 只来自 host_service)。
// 仍由 nativeSetProxyConfig 下发配置; 未下发即全部透传直连。

// 从 base("https://<api 子域>/stream/") 提取代理 host("<api 子域>")
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
// 曾经有过一份磁盘缓存（cn_proxy_config.tsv，7d108b10），让这里在 JNI_OnLoad 就
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
    filesDir() + "/madomagi/cn_proxy_config.tsv";

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
//   <files>/madomagi/engine_i18n.tsv（files 目录经 resolvePrivDir() 解析）
// 格式：每行 ja<TAB>zhCN，换行/制表/反斜杠写作 \n \t \\；`^` 开头是前缀规则；
// `#` 开头是注释；zhCN 为空表示**删除**该串（拼接式文案的语序调整用）。
// 表在启动时加载，之后每 3 秒节流检查一次文件身份指纹，热更替换后免重启生效。
//
// ⚠ 上面那个路径是**运行时副本，不是源**。源在外部发布渠道：
//     外部发布渠道  →  madomagi/engine_i18n.tsv
// 它由该仓库的 资产同步流水线 打进 cn_js_update.zip（JS 包，与 magica/
// 同包下发），客户端热更下来解到 <files>/，正好落在上面这个路径。也就是说
// **直接改设备上那份只是就地验证，下一次 JS 包更新会把它整个盖掉**——译文要
// 落地必须提到外部发布渠道去。完整链路与操作步骤见本仓库 i18n/README.md。

static const std::string ENGINE_I18N_PATH =
    filesDir() + "/madomagi/engine_i18n.tsv";

// ─── 表的持有方式：整体快照，不可变，引用计数 ───────────────────────
//
// 🔴 原先是两个裸全局容器 + 重载时 swap()，那是**两个并发缺陷叠在一起**：
//
//   一、`find()` 与 `swap()` 并发本身就是 UB。maybeReloadEngineI18n() 在
//       setStringTrampoline（任意线程）和 initLabelNew（GL 线程）里都会调，
//       任何一次重载都可能撞上另一线程正在查表。
//   二、更要命的是查完之后：engineLookup 返回 `&it->second`、initLabelNew 取
//       `it->second.c_str()`，都是**指向容器内部的指针**，然后跨函数调用继续
//       用（fakeNdkStr → old()、initLabelOld()）。swap 一发生，旧表连同这些
//       字符串一起析构，手里的指针立刻悬空 —— 典型 use-after-free。
//
// 旧实现的触发条件是 mtime 变化，而这张表**当时随台词包下发**，也就是每次热更之后
// 都会打开一次窗口。2026-08-09 那次「进战斗就崩、隔天自己好了」正卡在这个形状
// 上（相关性确凿：唯一崩过的那场也是唯一重载过的那场；因果未证——27 次重载 +
// 6 场战斗的复现实验没崩，内容不变时释放的块多半又被同样的字符串填回去了）。
//
// 注：上面说的「随台词包下发」是 2026-08-09 当时的事实，属于事故经过的一部分，
// 别照它推断现状。**现在这张表随 cn_js_update.zip 下发**（几 KB 的译表不该让玩家
// 重下几百 MB 的台词包），合同见 i18n/README.md。下面的快照改法与它归哪个包无关。
//
// 因果没证死不影响这里该改：上面两条是代码事实，不是推测。
//
// 现在的形状：表做成 shared_ptr<const …> 的不可变快照。读者一次性取走快照，
// 在整个使用期间持有它；重载只是让全局指针指向新快照，旧快照等最后一个读者
// 撒手才析构。读者之间零竞争，指针也不可能悬空。
struct EngineI18nTable {
    std::unordered_map<std::string, std::string>        exact;
    std::vector<std::pair<std::string, std::string>>    prefix;   // '^' 前缀规则
};
using EngineI18nPtr = std::shared_ptr<const EngineI18nTable>;

static std::mutex    g_engineI18nMutex;      // 保护下面的快照指针与文件指纹
static EngineI18nPtr g_engineI18nTable;      // 可能为空（表还没加载）

// 不能只盯秒级 st_mtime：热更是「临时文件 + 原子换名」，同一秒内替换时秒值
// 可以完全相同。更隐蔽的竞态是 fopen 取得旧 inode 后，热更换入新 inode，旧实现
// 却在读完后 stat(path) 并把**新文件 mtime**记在旧内容上；此后就永久看不见新表。
//
// 指纹绑定到真正被读的 fd：设备/inode 识别原子换名，size 与纳秒 mtime 覆盖原地
// 更新。加载前后各 fstat 一次；若读取期间 fd 自身发生变化，就保留上一份好快照，
// 等下一轮重试。
struct EngineI18nFileStamp {
    dev_t  device = 0;
    ino_t  inode = 0;
    off_t  size = 0;
    time_t mtimeSeconds = 0;
    long   mtimeNanoseconds = 0;
    bool   valid = false;
};

static EngineI18nFileStamp engineI18nStampFromStat(const struct stat& st) {
    EngineI18nFileStamp stamp;
    stamp.device = st.st_dev;
    stamp.inode = st.st_ino;
    stamp.size = st.st_size;
    stamp.mtimeSeconds = st.st_mtime;
#if defined(__APPLE__)
    stamp.mtimeNanoseconds = st.st_mtimespec.tv_nsec;
#else
    // Android/Bionic 与 Linux 均提供 POSIX.1-2008 的 st_mtim。
    stamp.mtimeNanoseconds = st.st_mtim.tv_nsec;
#endif
    stamp.valid = true;
    return stamp;
}

static bool engineI18nSameStamp(const EngineI18nFileStamp& a,
                                const EngineI18nFileStamp& b) {
    return a.valid == b.valid
        && (!a.valid || (a.device == b.device
                      && a.inode == b.inode
                      && a.size == b.size
                      && a.mtimeSeconds == b.mtimeSeconds
                      && a.mtimeNanoseconds == b.mtimeNanoseconds));
}

static EngineI18nFileStamp g_engineI18nStamp;

/** 取一份当前快照。返回的对象在调用方手里一直有效，与重载完全解耦。 */
static EngineI18nPtr engineI18nSnapshot() {
    std::lock_guard<std::mutex> lk(g_engineI18nMutex);
    return g_engineI18nTable;
}

static EngineI18nFileStamp engineI18nLoadedStamp() {
    std::lock_guard<std::mutex> lk(g_engineI18nMutex);
    return g_engineI18nStamp;
}

static std::atomic<bool>     g_engineI18nReady{false};
static std::atomic<time_t>   g_engineI18nLastCheck{0};
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
        if (g_engineI18nReady.load() || engineI18nLoadedStamp().valid)
            LOGI("[i18n] 表文件暂缺，保持现状: %s", ENGINE_I18N_PATH.c_str());
        return;
    }
    struct stat openedBefore;
    if (::fstat(::fileno(f), &openedBefore) != 0) {
        LOGE("[i18n] 无法读取已打开表的文件指纹，保持现状: errno=%d", errno);
        fclose(f);
        return;
    }
    std::shared_ptr<EngineI18nTable> fresh = std::make_shared<EngineI18nTable>();
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
            if (!ja.empty()) fresh->prefix.emplace_back(ja, zh);
            continue;
        }
        std::string ja = i18nUnescape(line.substr(0, tab));
        std::string zh = i18nUnescape(line.substr(tab + 1));
        if (!ja.empty()) fresh->exact[ja] = zh;
    }
    bool readFailed = ferror(f) != 0;
    int readErrno = readFailed ? errno : 0;
    struct stat openedAfter;
    bool statFailed = ::fstat(::fileno(f), &openedAfter) != 0;
    int statErrno = statFailed ? errno : 0;
    fclose(f);
    if (readFailed || statFailed) {
        LOGE("[i18n] 读取表或复核文件指纹失败，保持现状: read=%d(errno=%d) "
             "stat=%d(errno=%d)",
             (int)readFailed, readErrno, (int)statFailed, statErrno);
        return;
    }
    EngineI18nFileStamp beforeStamp = engineI18nStampFromStat(openedBefore);
    EngineI18nFileStamp afterStamp = engineI18nStampFromStat(openedAfter);
    if (!engineI18nSameStamp(beforeStamp, afterStamp)) {
        LOGI("[i18n] 表在读取期间发生变化，保持现状并等待下一轮重载");
        return;
    }
    size_t nExact = fresh->exact.size(), nPrefix = fresh->prefix.size();
    {
        // 指针与它对应的 fd 指纹必须在同一临界区发布。旧快照的析构发生在锁外、
        // 且要等最后一个读者撒手——绝不会在别人正拿着它查表时被拆掉。
        std::lock_guard<std::mutex> lk(g_engineI18nMutex);
        g_engineI18nTable = fresh;
        g_engineI18nStamp = afterStamp;
        g_engineI18nReady.store(nExact != 0 || nPrefix != 0);
    }
    LOGI("[i18n] 已加载 %zu 条 + %zu 前缀规则（第 %zu 行止，坏行 %zu）",
         nExact, nPrefix, lineno, bad);
}

// 节流重载检查：热更可能在我们启动后才把表放进来/换掉
static void maybeReloadEngineI18n() {
    time_t now = ::time(nullptr);
    time_t last = g_engineI18nLastCheck.load();
    if (now - last < 3) return;
    if (!g_engineI18nLastCheck.compare_exchange_strong(last, now)) return;
    struct stat st;
    if (::stat(ENGINE_I18N_PATH.c_str(), &st) != 0) return;
    EngineI18nFileStamp pathStamp = engineI18nStampFromStat(st);
    EngineI18nFileStamp loadedStamp = engineI18nLoadedStamp();
    if (!engineI18nSameStamp(pathStamp, loadedStamp)) {
        LOGI("[i18n] 检测到表变更，重新加载");
        loadEngineI18n();
    }
}

// NDK libc++ classic std::string 只读视图。对象固定由 3 个机器字组成：
// __short: 首字节 = size<<1（LSB=0），数据在 +1；容量 = 3*word-2；
// __long : 首 size_t 的 LSB=1 作标记，随后依次是 size、数据指针。
//
// 因此 ARM64 是 24B / short cap 22 / size@+8 / data@+16；
// ARMv7 是 12B / short cap 10 / size@+4 / data@+8。这里必须从机器字宽度
// 派生，绝不能把 ARM64 偏移写死——同一个源码会构建进两个 ABI。
static constexpr size_t kNdkStringWordBytes      = sizeof(size_t);
static constexpr size_t kNdkStringObjectBytes    = 3 * kNdkStringWordBytes;
static constexpr size_t kNdkStringLongSizeOffset = kNdkStringWordBytes;
static constexpr size_t kNdkStringLongDataOffset = 2 * kNdkStringWordBytes;
static constexpr size_t kNdkStringShortCapacity  = kNdkStringObjectBytes - 2;
static_assert(sizeof(size_t) == sizeof(void*), "NDK string word/pointer width mismatch");
static_assert(kNdkStringShortCapacity == (sizeof(void*) == 8 ? 22u : 10u),
              "unexpected libc++ classic string layout");
struct NdkStrView { const char* data; size_t size; };
static NdkStrView ndkStrRead(const void* strObj) {
    const unsigned char* s = (const unsigned char*)strObj;
    if (s[0] & 1) {
        return { *(const char* const*)(s + kNdkStringLongDataOffset),
                 *(const size_t*)(s + kNdkStringLongSizeOffset) };
    }
    return { (const char*)(s + 1), (size_t)(s[0] >> 1) };
}

/**
 * 精确查表。命中则把译文**拷进** out 并返回 true。
 *
 * ⚠ 刻意不返回 `&it->second`。原先那么写，调用方拿着指向表内部的指针跨函数调用
 * 继续用（fakeNdkStr → old()），一旦另一线程重载把旧表拆掉，指针立刻悬空。
 * 拷一份的代价是一次短字符串复制，换掉的是一整类 use-after-free。
 */
// ⚠ 快照由**调用方**传进来，这里不再自己取。
//
// 原先精确查找和前缀查找各自 engineI18nSnapshot() 一次，于是「一次翻译」会跨
// 两个快照。除了白白多锁一次 g_engineI18nMutex（那把锁被 GL 线程、网络线程和
// 重载路径共用），更要紧的是它**违反了这张表整个设计的前提**——558efd5 把表做成
// 不可变快照，靠的就是「读者一次性取走快照，在整个使用期间持有它」。两次取的
// 中间要是落进一次热重载，同一句文案的精确规则和前缀规则就来自两个不同版本的表。
// 那不会崩，但会得出一个两边都没写过的结果，而且完全无法复现。
static bool engineLookup(const EngineI18nPtr& t, const void* strObj, std::string& out) {
    if (!t) return false;
    NdkStrView v = ndkStrRead(strObj);
    if (v.size == 0 || v.size > 8192) return false;
    auto it = t->exact.find(std::string(v.data, v.size));
    if (it == t->exact.end()) return false;
    out = it->second;
    uint64_t n = ++g_engineI18nHits;
    if (n <= 10 || n % 100 == 0)
        LOGI("[i18n] 替换 #%llu: %.40s", (unsigned long long)n, v.data);
    return true;
}

// 前缀规则查找：命中返回「zh前缀 + 原串剩余部分」（写入 out，调用期内有效）。
// 用于尾部带变量的文案，如 「ネットワーク接続に失敗しました。再接続しますか？\nエラーコード：1」。
//
// 规则是译表显式声明的，所以这里不能用「原串必须含假名」作预筛选。
// 服务端已经会为同一 UI 下发英文，而未来也可能需要纯汉字前缀；旧的
// 0xE3/0xE4 字节门槛会让这些规则永远不可达。表通常只有少量前缀规则，
// 直接按顺序比对既是正确语义，开销也可忽略。
// 快照同样由调用方传进来，理由见 engineLookup 上方那段。
static bool enginePrefixLookup(const EngineI18nPtr& t, const char* data, size_t size,
                               std::string& out) {
    if (!t || t->prefix.empty()) return false;
    for (const auto& rule : t->prefix) {
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
// ⚠ 填好的译文**要提到外部发布渠道**（外部发布渠道 的
// madomagi/engine_i18n.tsv），不是留在设备上——设备上那份是热更下发的运行时副本，
// 下一次 JS 包更新会把它整个盖掉。就地追加只用于验证。见 i18n/README.md。
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
static_assert(sizeof(FakeNdkStr) == kNdkStringObjectBytes,
              "fake NDK string must match the target ABI object size");
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
    // 🔴 **必须是局部变量，不能再退回 static thread_local**。
    //
    // fakeNdkStr 交给引擎的是这块缓冲的指针，要求它在 old() 返回前一直有效——
    // 局部变量同样满足（它活到函数结束，而 old() 在函数内部调用），所以
    // static 从来就不是正确性需要的，只是想省掉每次的构造。
    //
    // 而 static 会**自我别名**，代价远大于省下的那点开销。本函数是可重入的：
    // MenuItemLabel::setString(0x12a9bd0) 会把收到的 string 指针**原样**转给内层
    // Label 的虚 setString（0x12a9c14 的 blr），而那个地址正是我们钩着的。于是
    //
    //     外层：engineLookup(text, zh) → zh = 译文；fk.data = zh.c_str()
    //           → old(self,&fk) → 引擎转发 &fk 给内层 Label::setString
    //     内层：本函数再次进入，text 就是 &fk（指向 zh 内部），
    //           而 engineLookup 的 out 又是**同一个** zh
    //
    // 一旦译文本身也是表里的 key，内层那句 `out = it->second` 就在改写外层
    // fk 正指着的缓冲——长度一变就重新分配，外层的指针当场悬空。今天没炸的唯一
    // 理由是「译文又是 key」这种自指条目大概不存在，那是运气不是设计；批次三
    // 刚加了 746 条高频短词条，正是最容易撞上的一类。
    //
    // 代价：多一次 std::string 构造。译文多在 22 字节以内走 SSO，不进堆；超出的
    // 才多一次 malloc/free。同函数里前缀那条路的 combined 本来就是局部的，
    // 统一成局部也让两条路的生命周期口径一致。
    std::string zh;
    // 整次翻译只取**一份**快照，精确与前缀两级共用：既少锁一次那把被 GL 线程、
    // 网络线程与重载路径共用的互斥量，更重要的是让「一句文案对一个版本的表」
    // 成立。理由见 engineLookup 上方那段。
    EngineI18nPtr t = g_engineI18nReady.load() ? engineI18nSnapshot() : EngineI18nPtr();
    if (engineLookup(t, text, zh)) {
        FakeNdkStr fk;
        fakeNdkStr(fk, zh);
        old(self, &fk);
        return;
    }
    // 精确未命中 → 前缀规则（尾部带变量的文案）
    NdkStrView v = ndkStrRead(text);
    if (v.size && t) {
        std::string combined;
        if (enginePrefixLookup(t, v.data, v.size, combined)) {
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

// 字体由引擎原有的每个文本调用点选择，不再依据类名推断整段调用的字体。
// 195：MTF4a5kp / mbm 两个原生文件别名均承载已补字寒蝉全圆 Bold；Cocos 原样加载。
// WebView 另由已修复的 CSS 选择智黑，不使用这里的原生资源别名。

// LbUtility::initLabel(Node*, Label*&, const char* text, float, Vec2, int, Size, Color4B, int)
// const char* 直传，命中就换指针。这里的替身原型必须复刻**编译器降级后的
// 调用 ABI**，而不只是把每个类换成“尺寸一样”的 POD。
//
// arm64 原函数 @0x8c51d0 的入口实际读取：
//   s0 = float, s1/s2 = Vec2, w3 = int, x4 = Size*, w5 = Color4B, w6 = int
// `cocos2d::Size` 按值写在 C++ 签名里，但它对调用 ABI 是非平凡类型，所以由调用方
// 制作副本并以隐式指针传入。若误写成 `{float w, h;}`，AAPCS64 会把它当 HFA
// 放进 s3/s4；后面的 Color4B/int 便从 x4/x5 整体错位，x6 中的末尾 int 甚至不会
// 被转发。真机的 32 位路径可能恰好保留了原栈槽，但 arm64 转译器会稳定暴露错位。
//
// 因此 Size 故意用不透明指针原样透传；不解引、不复制，由原函数按它自己的
// `cocos2d::Size` 类型处理。其余两个按值聚合体仍必须与引擎类型逐字节一致。
// CNColor4B 曾经只写了 r,g,b 三个字节——而 cocos2d::Color4B 是 {r,g,b,a} 四字节。
// AAPCS64 下 3 字节和 4 字节的小聚合体都占一个通用寄存器，所以**参数位置不会错**，
// 编译器也不会报错；但我们转发时只搬 3 个字节，**alpha 被丢掉**，引擎拿到的透明度
// 是寄存器里的残留值。表现是「文字时有时无/整块 UI 看不见」这种极难归因的毛病，
// 而不是干脆的崩溃——正因为它不崩，才在库里躺了很久。
struct CNVec2    { float x, y; };
struct CNColor4B { unsigned char r, g, b, a; };
using CNSizeAbiArg = void*;
static_assert(sizeof(CNVec2) == 8 && alignof(CNVec2) == 4,
              "cocos2d::Vec2 ABI layout changed");
static_assert(sizeof(CNColor4B) == 4 && alignof(CNColor4B) == 1,
              "cocos2d::Color4B ABI layout changed");
static_assert(sizeof(CNSizeAbiArg) == sizeof(void*),
              "cocos2d::Size ABI argument must stay indirect");
static void initLabelNew(void* node, void* label, const char* text, float f,
                         CNVec2 v2, int i1, CNSizeAbiArg sizeArg,
                         CNColor4B c4b, int i2);
// 直接从 replacement 声明推导 trampoline 类型，杜绝两处原型各自漂移。
using InitLabelFn = decltype(&initLabelNew);
static InitLabelFn initLabelOld = nullptr;
static void initLabelNew(void* node, void* label, const char* text, float f,
                         CNVec2 v2, int i1, CNSizeAbiArg sizeArg,
                         CNColor4B c4b, int i2) {
    if (g_dbgNoI18nLabel) {            // 调试开关：原样转发，不做任何替换
        initLabelOld(node, label, text, f, v2, i1, sizeArg, c4b, i2);
        return;
    }
    maybeReloadEngineI18n();
    const char* use = text;
    bool hit = false;
    // use 会被交给引擎（initLabelOld 期间要一直有效）。
    // ⚠ 绝不能再写成 `use = it->second.c_str()`——那是指向表内部的指针，
    // 另一线程一重载就悬空。拷进这块缓冲，生命周期由我们自己保证。
    //
    // 局部而非 static thread_local：局部同样活到函数结束（initLabelOld 在函数内
    // 调用），生命周期够用；而 static 会在本函数万一重入时自我别名，理由与
    // setStringTrampoline 里那段一样。两处口径保持一致，免得下次有人只看一处。
    std::string combined;
    EngineI18nPtr t = (text && g_engineI18nReady.load()) ? engineI18nSnapshot()
                                                        : EngineI18nPtr();
    if (t) {
        auto it = t->exact.find(text);
        if (it != t->exact.end()) {
            uint64_t n = ++g_engineI18nHits;
            if (n <= 10 || n % 100 == 0)
                LOGI("[i18n] 替换 #%llu: %.40s", (unsigned long long)n, text);
            combined = it->second;
            use = combined.c_str();
            hit = true;
        } else if (enginePrefixLookup(t, text, strlen(text), combined)) {
            use = combined.c_str();
            hit = true;
        }
    }
    // 没命中就记下来。表没加载成功时（g_engineI18nReady 为假）也算没命中——
    // 那种情况下这份清单会是「所有流经的串」，与 setString 侧的口径一致。
    if (!hit && text) noteI18nMiss(text, strlen(text), "LbUtility::initLabel");
    initLabelOld(node, label, use, f, v2, i1, sizeArg, c4b, i2);
}



// 只保留构造时的文本汉化；字体参数及布局参数原样交回引擎。
using CreateWithTtfCfgFn = void* (*)(void*, const void*, int, int);
using CreateWithTtfStrFn = void* (*)(void*, const void*, float, void*, int, int);
static CreateWithTtfCfgFn createWithTtfCfgOld = nullptr;
static CreateWithTtfStrFn createWithTtfStrOld = nullptr;

// 纯国服姓名局部 y=63；现归档引擎为57。仅在原有姓名创建调用范围内
// 修正已核实的名字节点，不改字体、正文、锚点或安全区/比例适配。
using StoryMessageAreaFn = void (*)(void*, int);
static StoryMessageAreaFn storyMessageAreaOld = nullptr;
static thread_local magia_story_name::Capture* storyNameCapture = nullptr;
static magia_story_name::ReadPoint storyNodePosition = nullptr;
static magia_story_name::ReadPoint storyNodeAnchor = nullptr;
static magia_story_name::WritePoint storyNodeSetPosition = nullptr;
static magia_story_name::ReadFloat storyNameFontSize = nullptr;
static magia_story_name::ReadFloat storyNameLineHeight = nullptr;
static magia_story_name::WriteFloat storyNameSetLineHeight = nullptr;
using StoryTtfGet = const void* (*)(void*);
using StoryTtfSet = bool (*)(void*, const void*);
static StoryTtfGet storyNameGetConfig = nullptr;
static StoryTtfSet storyNameSetConfig = nullptr;
static std::atomic<unsigned> storyNameLayoutLogged{0};
using StoryFontCreate = void* (*)(const void*, float, int, const char*, bool, float);
using StoryFontAscender = int (*)(void*);
using StoryFontFamily = const char* (*)(void*);
static StoryFontCreate storyNameCreateFont = nullptr;
static StoryFontAscender storyNameAscender = nullptr;
static StoryFontFamily storyNameFamily = nullptr;
static StoryTtfGet storyNameGetString = nullptr;
static magia_story_name::ReadPoint storyNameContentSize = nullptr;

static float storyNameBaselineY(void* label) {
    constexpr float retainedY = 63.0f;
    if (!storyNameCreateFont || !storyNameAscender || !storyNameGetString
        || !storyNameContentSize || !storyNameGetConfig) return retainedY;
    try {
        const auto text = ndkStrRead(storyNameGetString(label));
        if (!text.data || !text.size || text.size > 4096) return retainedY;
        const std::string name(text.data, text.size);
        if (name.find_first_of("\r\n") != std::string::npos
            || name.find("\xe2\x80\xa8") != std::string::npos
            || name.find("\xe2\x80\xa9") != std::string::npos) return retainedY;
        // Public Label::getContentSize forces text layout. For the admitted
        // dimensionless, single-line TTF name its height is lineHeight / CSF.
        // No private Director/Label offsets or window pixel ratios are used.
        const auto extent = storyNameContentSize(label);
        if (!std::isfinite(extent.y) || extent.y <= 0.0f) return retainedY;
        const float scale = 25.0f / extent.y;
        if (!std::isfinite(scale) || scale < 0.25f || scale > 8.0f) return retainedY;
        const void* config = storyNameGetConfig(label);
        if (!config) return retainedY;
        const auto view = ndkStrRead(config);
        if (!view.data || !view.size || view.size > 4096) return retainedY;
        const std::string fontPath(view.data, view.size);
        static thread_local std::string cachedPath;
        static thread_local float cachedScale = 0.0f, cachedY = retainedY;
        if (fontPath == cachedPath && scale == cachedScale) return cachedY;
        // This pinned legacy FontFreeType::create autoreleases its result.
        // Retain no font object and never release it manually. Only numeric
        // metrics are cached; existing shared font atlases remain untouched.
        void* font = storyNameCreateFont(config, 20.0f * scale, 0, nullptr, false, 0.0f);
        if (!font) return retainedY;
        const int ascender = storyNameAscender(font);
        const char* family = storyNameFamily ? storyNameFamily(font) : nullptr;
        const bool bodyProfile = magia_story_name::retainedNameBodyProfile(
            fontPath.c_str(), family, static_cast<float>(ascender), scale);
        const float y = magia_story_name::cnNameBodyY(
            static_cast<float>(ascender), scale, bodyProfile);
        cachedPath = fontPath; cachedScale = scale; cachedY = y;
        LOGI("[StoryNameBaseline] size=20 line=25 rasterScale=%.4f ascender=%d mappedY=%.4f emBodyProfile=%d; font/dialogue untouched", scale, ascender, y, bodyProfile ? 1 : 0);
        return y;
    } catch (...) {
        return retainedY;
    }
}

static bool setStoryNameFontSize(void* label, float size) {
    if (!storyNameGetConfig || !storyNameSetConfig) return false;
    const void* original = storyNameGetConfig(label);
    alignas(void*) magia_story_name::TtfConfigCopy changed, retained;
    if (!magia_story_name::copyNameTtfConfig(original, 16.0f, size, changed)
        || !magia_story_name::copyNameTtfConfig(original, 16.0f, 16.0f, retained)) return false;
    // setTTFConfigInternal can reset the Label on failure. Keep the original
    // font path alive independently, including for restoration, rather than
    // borrowing a heap string which that reset is allowed to release.
    try {
        const auto view = ndkStrRead(original);
        if (!view.data || !view.size || view.size > 4096) return false;
        const std::string fontPath(view.data, view.size);
        FakeNdkStr path;
        fakeNdkStr(path, fontPath);
        std::memcpy(changed.data(), &path, sizeof(path));
        std::memcpy(retained.data(), &path, sizeof(path));
        if (storyNameSetConfig(label, changed.data())) return true;
        storyNameSetConfig(label, retained.data());
    } catch (...) {
        // Allocation failure before the setter must leave the original node.
        return false;
    }
    return false;
}

static void* captureStoryNameLabel(void* label, float size) {
    if (storyNameCapture) storyNameCapture->record(label, size);
    return label;
}
static void storyMessageAreaNew(void* self, int position) {
    magia_story_name::Capture capture(position);
    magia_story_name::Scope scope(storyNameCapture, capture);
    storyMessageAreaOld(self, position);
    if (capture.apply(storyNodePosition, storyNodeAnchor, storyNodeSetPosition,
                      storyNameFontSize, storyNameLineHeight, setStoryNameFontSize,
                      storyNameSetLineHeight, storyNameBaselineY)) {
        const unsigned bit = 1u << static_cast<unsigned>(position);
        if (!(storyNameLayoutLogged.fetch_or(bit, std::memory_order_relaxed) & bit)) {
            LOGI("[StoryNameLayout] slot=%d localY=57->%.4f fontSize=16->20 lineHeight=25; CN baseline mapped, body/font-file/parent/anchor retained", position, storyNodePosition(capture.name).y);
        }
    }
}
static bool resolveStoryNameLayout(const char* lib) {
    void* h = ::dlopen(lib, RTLD_NOW | RTLD_NOLOAD);
    if (!h) return false;
    storyNodePosition = reinterpret_cast<magia_story_name::ReadPoint>(
        ::dlsym(h, "_ZNK7cocos2d4Node11getPositionEv"));
    storyNodeAnchor = reinterpret_cast<magia_story_name::ReadPoint>(
        ::dlsym(h, "_ZNK7cocos2d4Node14getAnchorPointEv"));
    storyNodeSetPosition = reinterpret_cast<magia_story_name::WritePoint>(
        ::dlsym(h, "_ZN7cocos2d4Node11setPositionERKNS_4Vec2E"));
    storyNameGetConfig = reinterpret_cast<StoryTtfGet>(::dlsym(h, "_ZNK7cocos2d5Label12getTTFConfigEv"));
    storyNameSetConfig = reinterpret_cast<StoryTtfSet>(::dlsym(h, "_ZN7cocos2d5Label12setTTFConfigERKNS_10_ttfConfigE"));
    storyNameFontSize = reinterpret_cast<magia_story_name::ReadFloat>(::dlsym(h, "_ZNK7cocos2d5Label20getRenderingFontSizeEv"));
    storyNameLineHeight = reinterpret_cast<magia_story_name::ReadFloat>(::dlsym(h, "_ZNK7cocos2d5Label13getLineHeightEv"));
    storyNameSetLineHeight = reinterpret_cast<magia_story_name::WriteFloat>(::dlsym(h, "_ZN7cocos2d5Label13setLineHeightEf"));
    // Optional metric APIs: their absence preserves the existing size/line/Y
    // correction rather than disabling the entire 1.0.200 name hook.
    storyNameCreateFont = reinterpret_cast<StoryFontCreate>(::dlsym(h,
        "_ZN7cocos2d12FontFreeType6createERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEfNS_15GlyphCollectionEPKcbf"));
    storyNameAscender = reinterpret_cast<StoryFontAscender>(::dlsym(h, "_ZNK7cocos2d12FontFreeType15getFontAscenderEv"));
    storyNameFamily = reinterpret_cast<StoryFontFamily>(::dlsym(h, "_ZNK7cocos2d12FontFreeType13getFontFamilyEv"));
    storyNameGetString = reinterpret_cast<StoryTtfGet>(::dlsym(h, "_ZNK7cocos2d5Label9getStringEv"));
    storyNameContentSize = reinterpret_cast<magia_story_name::ReadPoint>(::dlsym(h, "_ZNK7cocos2d5Label14getContentSizeEv"));
    ::dlclose(h);
    return storyNodePosition && storyNodeAnchor && storyNodeSetPosition && storyNameGetConfig
        && storyNameSetConfig && storyNameFontSize && storyNameLineHeight && storyNameSetLineHeight;
}


// 战斗中的技能浮字有一部分在 createWithTTF() 构造时一次性传入，之后不会再走
// Label::setString。这两个构造入口仍复用 engine 表，但不再改变任何字体参数，
// 同时仍由 noI18nSetString 统一关闭这类动态 Label 文案替换。
static bool translateTtfInitialText(const void* text, const char* label,
                                    std::string& translated) {
    maybeReloadEngineI18n();
    if (g_dbgNoI18nSetString || !text) return false;

    EngineI18nPtr t = g_engineI18nReady.load() ? engineI18nSnapshot() : EngineI18nPtr();
    if (engineLookup(t, text, translated)) return true;

    NdkStrView v = ndkStrRead(text);
    if (v.size && t && enginePrefixLookup(t, v.data, v.size, translated)) return true;
    noteI18nMiss(v.data, v.size, label);
    return false;
}

// createWithTTF(const _ttfConfig& cfg, ...)：fontFilePath 在 cfg 偏移 0
static void* createWithTtfCfgNew(void* cfg, const void* text, int h, int i) {
    std::string translated;
    if (translateTtfInitialText(text, "Label::createWithTTF(cfg)", translated)) {
        FakeNdkStr fk;
        fakeNdkStr(fk, translated);
        return createWithTtfCfgOld(cfg, &fk, h, i);
    }
    return createWithTtfCfgOld(cfg, text, h, i);
}
// createWithTTF(const std::string& text, const std::string& fontFile, float, ...)
static void* createWithTtfStrNew(void* text, const void* font, float size,
                                 void* dims, int h, int v) {
    std::string translated;
    if (translateTtfInitialText(text, "Label::createWithTTF(str)", translated)) {
        FakeNdkStr fk;
        fakeNdkStr(fk, translated);
        return captureStoryNameLabel(createWithTtfStrOld(&fk, font, size, dims, h, v), size);
    }
    return captureStoryNameLabel(createWithTtfStrOld(text, font, size, dims, h, v), size);
}

// BEGIN_TYPED_BATTLE_SKILL_NAMES
// 原生 parseArtUnit -> setParam -> 技能标题。只处理已核对的 ID + 类型 + 完整原文；
// 两个国服正式译名按 MEMORIA/EMOTION 保留，不污染无上下文的 engine_i18n.tsv。
// APK 3.1.9 双 ABI：Type::MEMORIA=3, MemoriaType::ABILITY=1,
// MemoriaDisplay::MEMORIA=1, ::EMOTION=3；详见 docs/battle-skill-name-context.md。
static const char* typedBattleSkillName(int type, int id, int memoriaType,
                                        int displayType, const char* name) {
    if (type != 3 || memoriaType != 1 || !name ||
        strcmp(name, "ファスト・マナアップ") != 0) return name;
    if (id == 115201 && displayType == 1) return "快速魔法提升";
    if (id == 1144110 && displayType == 3) return "魔力骤升";
    return name;
}
using ArtUnitSetParamFn = void (*)(void*, int, int, int, int, int, int,
                                   const char*, const char*, int, int);
static ArtUnitSetParamFn artUnitSetParamOld = nullptr;
static void artUnitSetParamNew(void* self, int type, int id, int icon, int level,
                               int cost, int voice, const char* name,
                               const char* description, int memoriaType, int displayType) {
    const char* translated = g_dbgNoI18nLabel ? name :
        typedBattleSkillName(type, id, memoriaType, displayType, name);
    artUnitSetParamOld(self, type, id, icon, level, cost, voice,
                       translated, description, memoriaType, displayType);
}
// END_TYPED_BATTLE_SKILL_NAMES

// ─── JNI_OnLoad ──────────────────────────────────────────
// ═══ TLS 探针：用**引擎自带的那份 OpenSSL** 去连一个端点 ═══════════
//
// 「native 引擎能不能跟我们自建的服务端说话」是自建服务端路线唯一的技术死穴。
// 静态结论（2026-08-22 从 libmadomagi_native.so 挖出来的）是：**引擎压根不验证
// 服务端证书**——SSL_CTX_set_verify 全库 0 次调用、没有内置 CA、OPENSSLDIR 指向
// 打包机上不存在的路径；而 0x140920E3（=十进制 336142563，
// SSL3_GET_SERVER_HELLO / PARSE_TLSEXT）是 OpenSSL 1.0.2s 听不懂现代 TLS 栈的
// 扩展，属于代差不是信任。
//
// 静态论证再密也是论证。这里把它变成一次真实握手。
//
// ⚠ 关键点：**不 dlopen**。引擎 so 早就在本进程里了（本文件的 hook 就装在它
// 身上），所以 dlsym(RTLD_DEFAULT, …) 直接就能拿到它导出的 OpenSSL API——用的
// 就是引擎运行时用的那份 1.0.2s，不是另开一份。
//
// 调用序列逐行复刻 http2::Http2SessionManager::run（arm64 0xa00434）：
//     ctx = SSL_CTX_new(TLSv1_2_method())        // boost tlsv12 = method 0xf
//     SSL_CTX_set_default_verify_paths(ctx)      // 挂一个不存在的目录
//     SSL_CTX_set_alpn_protos(ctx, "\x02h2", 3)  // configure_tls_context 只干这个
//     ← 故意**不调** SSL_CTX_set_verify：引擎就是不调，这正是被测的那一点
//
// 判据一条：SSL_connect 对着一张自签名证书返回不返回 1。
namespace tlsprobe {

#define TP_SSL_CTRL_SET_TLSEXT_HOSTNAME 55
#define TP_TLSEXT_NAMETYPE_host_name     0

template <typename T> static T sym(const char* n, bool& ok) {
    void* p = dlsym(RTLD_DEFAULT, n);
    if (!p) { ok = false; }
    return reinterpret_cast<T>(p);
}

static void append(std::string& out, const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    LOGI("[tls-probe] %s", buf);
    out += buf;
    out += '\n';
}

static std::string run(const char* host, int port) {
    std::string r;
    bool ok = true;
    auto f_init    = sym<int (*)(void)>("SSL_library_init", ok);
    auto f_method  = sym<void* (*)(void)>("TLSv1_2_method", ok);
    auto f_ctxnew  = sym<void* (*)(void*)>("SSL_CTX_new", ok);
    auto f_paths   = sym<int (*)(void*)>("SSL_CTX_set_default_verify_paths", ok);
    auto f_alpn    = sym<int (*)(void*, const unsigned char*, unsigned int)>(
                         "SSL_CTX_set_alpn_protos", ok);
    auto f_sslnew  = sym<void* (*)(void*)>("SSL_new", ok);
    auto f_setfd   = sym<int (*)(void*, int)>("SSL_set_fd", ok);
    auto f_ctrl    = sym<long (*)(void*, int, long, void*)>("SSL_ctrl", ok);
    auto f_conn    = sym<int (*)(void*)>("SSL_connect", ok);
    auto f_geterr  = sym<int (*)(const void*, int)>("SSL_get_error", ok);
    auto f_ver     = sym<const char* (*)(const void*)>("SSL_get_version", ok);
    auto f_cur     = sym<void* (*)(const void*)>("SSL_get_current_cipher", ok);
    auto f_cname   = sym<const char* (*)(const void*)>("SSL_CIPHER_get_name", ok);
    auto f_vres    = sym<long (*)(const void*)>("SSL_get_verify_result", ok);
    auto f_alpnsel = sym<void (*)(const void*, const unsigned char**, unsigned int*)>(
                         "SSL_get0_alpn_selected", ok);
    auto f_errget  = sym<unsigned long (*)(void)>("ERR_get_error", ok);
    auto f_errstr  = sym<void (*)(unsigned long, char*, size_t)>("ERR_error_string_n", ok);
    if (!ok || !f_init || !f_method || !f_ctxnew || !f_sslnew || !f_conn) {
        append(r, "✘ 拿不到引擎的 OpenSSL 符号——基线换过？");
        return r;
    }
    append(r, "用引擎自带的 OpenSSL（进程内，dlsym RTLD_DEFAULT）");

    f_init();
    void* ctx = f_ctxnew(f_method());
    if (!ctx) { append(r, "✘ SSL_CTX_new 失败"); return r; }
    if (f_paths) f_paths(ctx);
    if (f_alpn) f_alpn(ctx, (const unsigned char*)"\x02h2", 3);
    // 不调 SSL_CTX_set_verify —— 与引擎一致

    char portstr[16];
    snprintf(portstr, sizeof(portstr), "%d", port);
    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo* res = nullptr;
    if (getaddrinfo(host, portstr, &hints, &res) != 0 || !res) {
        append(r, "✘ 解析 %s:%d 失败", host, port);
        return r;
    }
    int fd = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (fd < 0 || ::connect(fd, res->ai_addr, res->ai_addrlen) != 0) {
        append(r, "✘ TCP 连不上 %s:%d", host, port);
        if (fd >= 0) ::close(fd);
        freeaddrinfo(res);
        return r;
    }
    freeaddrinfo(res);

    void* ssl = f_sslnew(ctx);
    f_setfd(ssl, fd);
    if (f_ctrl) f_ctrl(ssl, TP_SSL_CTRL_SET_TLSEXT_HOSTNAME,
                       TP_TLSEXT_NAMETYPE_host_name, (void*)host);

    int rc = f_conn(ssl);
    if (rc == 1) {
        const char* ver = f_ver ? f_ver(ssl) : "?";
        const char* cn = "?";
        if (f_cur && f_cname) { void* c = f_cur(ssl); if (c) cn = f_cname(c); }
        const unsigned char* ap = nullptr; unsigned int al = 0;
        if (f_alpnsel) f_alpnsel(ssl, &ap, &al);
        long vr = f_vres ? f_vres(ssl) : -1;
        append(r, "✔✔ 握手成功：引擎这份 OpenSSL 接受了自签名端点");
        append(r, "   协议=%s cipher=%s ALPN=%.*s", ver, cn,
               (int)al, ap ? (const char*)ap : "");
        append(r, "   SSL_get_verify_result=%ld %s", vr,
               vr == 0 ? "(0=ok)"
                       : "(非 0 却仍握手成功 = SSL_VERIFY_NONE 的运行时证据)");
        append(r, "结论：自签名可用，自建服务端的死穴解除。");
    } else {
        int e = f_geterr ? f_geterr(ssl, rc) : -1;
        append(r, "✘ 握手失败 SSL_connect=%d SSL_get_error=%d", rc, e);
        if (f_errget && f_errstr) {
            unsigned long code;
            while ((code = f_errget()) != 0) {
                char buf[256];
                f_errstr(code, buf, sizeof(buf));
                // 十进制也打：游戏界面报的就是十进制（336142563）
                append(r, "   err 0x%08lx (%lu): %s", code, code, buf);
            }
        }
        append(r, "对照 0x140920E3=336142563（SERVER_HELLO/PARSE_TLSEXT）；");
        append(r, "若是同一个码，说明服务端仍在发 1.0.2 看不懂的扩展。");
    }
    ::close(fd);
    return r;
}

}  // namespace tlsprobe

static jstring nativeTlsProbe(JNIEnv* env, jclass, jstring jhost, jint port) {
    std::string report;
    try {
        const char* host = jhost ? env->GetStringUTFChars(jhost, nullptr) : nullptr;
        report = tlsprobe::run(host ? host : "127.0.0.1", (int)port);
        if (host) env->ReleaseStringUTFChars(jhost, host);
    } catch (...) {
        report = "✘ 探针自身抛异常";
    }
    return env->NewStringUTF(report.c_str());
}

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
            { "io/kamihama/magianative/CNDebugBridge",     &gClsDebugBridge    },
            { "io/kamihama/magianative/CNTlsProbe",        &gClsTlsProbe       },
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

        // TLS 探针：调试开关打开时才会被 Java 侧调到，平时一次都不执行。
        if (gClsTlsProbe) {
            JNINativeMethod m[] = {
                { (char*)"nativeTlsProbe", (char*)"(Ljava/lang/String;I)Ljava/lang/String;",
                  (void*)nativeTlsProbe },
            };
            if (env->RegisterNatives(gClsTlsProbe, m, 1) != 0) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                LOGE("[JNI] RegisterNatives(CNTlsProbe) 失败——TLS 探针不可用");
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
        // 调试悬浮窗要按表列出 native 侧的开关。绑不上不影响任何功能：
        // Java 侧收到 UnsatisfiedLinkError 后只列自己那份表（见 CNDebugBridge）。
        if (gClsDebugBridge) {
            JNINativeMethod m[] = {
                { (char*)"nativeDebugOverlayEnabled", (char*)"()Z",
                  (void*)nativeDebugOverlayEnabled },
                { (char*)"nativeDebugFlagTable", (char*)"()[Ljava/lang/String;",
                  (void*)nativeDebugFlagTable },
            };
            if (env->RegisterNatives(gClsDebugBridge, m, 2) != 0) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                // Java 侧收到 UnsatisfiedLinkError 一律按「不允许」处理（见
                // CNDebugBridge.overlayAllowed），所以这里失败=悬浮窗不出现。
                LOGE("[JNI] RegisterNatives(CNDebugBridge) 失败——调试悬浮窗将不可用");
            }
        }
        LOGI("[DEBUG] 调试悬浮窗总闸: %s（烧在包里，运行时改不了）",
             DEBUG_OVERLAY_ENABLED ? "开" : "关");
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
    resolveDownloadLoadingExit(LIB);
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

    // UrlConfig 三端点全部只读观测。2026-08-21 真机 A/B 证明 api/chat 端点改写会让
    // 旧版 Cocos/OpenSSL 在代理握手阶段报 0x140920E3（界面错误码 336142563），
    // 造成所有战斗通信失败；原样直连则同一进程立即成功。WebView 代理是 Java 侧
    // 独立链路，不依赖这里改写。check-proxy-hooks.py 会阻止 api/chat 回流到改写。
    H("_ZNK9UrlConfig3apiENS_3Api4TypeE",
      (void*)urlConfigApiNew, (void**)&urlConfigApiOld, "proxy: UrlConfig::api(只读观测)");
    H("_ZNK9UrlConfig4chatENS_4Chat4TypeE",
      (void*)urlConfigChatNew, (void**)&urlConfigChatOld, "proxy: UrlConfig::chat(只读观测)");
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

    // 原生战斗同名技能需要 ID 与类型；对象构造时翻译名称，保留其余参数。
    H("_ZN9QbArtUnit8setParamEN5QbArt4TypeEiiiiiPKcS3_NS0_11MemoriaTypeENS0_14MemoriaDisplayE",
      (void*)artUnitSetParamNew, (void**)&artUnitSetParamOld, "i18n: QbArtUnit::setParam typed name");

    // ── TTF 构造文本汉化；字体仍由原调用点及资源决定 ──
    // 兼容已有 noTtfHooks 调试开关；这里只挂构造文本翻译，不改字体/字号/位置。
    if (g_dbgNoTtfHooks) {
        LOGE("[DEBUG] noTtfHooks: skip initial TTF label text translation");
    } else {
    H("_ZN7cocos2d5Label13createWithTTFERKNS_10_ttfConfigERKNSt6__ndk112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEENS_14TextHAlignmentEi",
      (void*)createWithTtfCfgNew, (void**)&createWithTtfCfgOld, "i18n: createWithTTF(cfg)");
    const bool stringLabelHook = H("_ZN7cocos2d5Label13createWithTTFERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_fRKNS_4SizeENS_14TextHAlignmentENS_14TextVAlignmentE",
      (void*)createWithTtfStrNew, (void**)&createWithTtfStrOld, "i18n: createWithTTF(str)");
    // 创建器与公共坐标 API 都可用才启用，未知引擎仍原样运行。
    if (stringLabelHook && resolveStoryNameLayout(LIB)) {
        H("_ZN16StoryMessageUnit17createMessageAreaENS_11TextPosType13TextPosType__E",
          (void*)storyMessageAreaNew, (void**)&storyMessageAreaOld,
          "StoryMessageUnit: CN name local layout");
    }
    }

    // ── 下载浮层期间挂起引擎 BGM（QbUtility::playBgmDirect）──
    H("_ZN9QbUtility13playBgmDirectEPKc",
      (void*)playBgmDirectNew, (void**)&playBgmDirectOld, "Overlay: playBgmDirect 挂起");

    LOGI("[JNI] hooks 安装完成：成功 %d 个，失败 %d 个", hookOk, hookFail);
    return JNI_VERSION_1_6;
}

