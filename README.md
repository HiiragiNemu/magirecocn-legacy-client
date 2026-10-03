# legacy-client

魔法纪录中文化客户端（上一代 / Totentanz 系）的**可重新构建存档**：把一个既有成品
APK 的 Totentanz 客户端为基线，叠一层 Java 补丁，由 CI 重新构建。

基础 APK 来自 `io.kamihama.totentanz`，版权与免责声明见下方「原始署名与免责声明」。

## 当前正式交付（2026-10-03 核验快照）

玩家入口：[最新正式下载页](https://github.com/HiiragiNemu/ProgettoMagius-1/releases/latest)。
当前客户端为 **1.0.204**，配套固定 Scenario **3323**、完整 JS **103** 和累计 delta **26**。
日后版本以正式入口元数据为准；修改本说明不代表 APK 或资源重新构建。

- 后续剧情、脚本、图片和样式更新走累计 delta，完整基线保持原文件、版本与摘要。
  新安装只需固定基线加最新累计层，不要求串联历史 delta。
- 最终 1.0.204 已实测手动重下 delta，以及重下 Scenario／完整 JS 后从网络重下 delta；
  delta 25 的设备落盘校验通过，用户已确认正常进入游戏。
- delta 26 仅追加默认玩家名的显示修订：`TOTENTANZ` 显示为「小丘比」，保留 ID、
  邀请码、存档标识和其他自定义名字。该层已发布并通过回归及包校验，尚未另做设备显示验收；
  不将此描述为新增自定义改名或已验证改名持久化。
- APK 的构建、最终包实测与发布是独立步骤。发布需显式批准已验收的 APK SHA-256
  和对应源码 SHA，匹配后才更新公开客户端入口，不顺带重打资源包。

> **协作**：主干开发，直接提 `main`；分支只有 `hotfix/*`（修红灯）与 `surgery/*`
> （核心层大手术）两类例外。开工前读 [CONTRIBUTING.md](CONTRIBUTING.md)，
> 在 [ACTIVE.md](ACTIVE.md) 登记你在动哪片。

---

## 仓库结构

```
patch/src/main/java/   ← ★ 补丁源码，唯一事实来源
baseline/              ← ★ 基线钉死项 + 可复现的 patchset
magia-native/          ← native hook 源码（libMagiaLegacy.so）
tools/                 ← 测试套件、构建前置检查、汉化与资源工具
assets/ lib/ res/      ← 只剩我们自己的东西：中文字体、aria2c、
                          shadowhook、network_security_config、
                          本地状态覆盖层的前端脚本 localstate.js
config.json            ← 线上配置的快照，仅供本地看字段长什么样，不参与构建
```

仓库里**没有基线工程树**，构建时从整包重建。要看 `smali/` 或完整 `res/`：

```bash
python3 tools/baseline.py fetch
python3 tools/baseline.py apply --out work/tree
```

---

## 基线与补丁（`baseline/`）

```
整包  +  baseline/ 的 125 条操作  =  工程树
```

每条操作带 `pre`/`post` hash 与 `why`，逐字节确定。**分类是人写死在
`baseline.json` 里的，不靠脚本推断**——自动推断会把「我们故意不要的东西」误判成
「我们新增的东西」。

| kind | 条数 | 是什么 |
|---|---:|---|
| `patch` | 14 | 基线里有、我们改了几行 |
| `replace` | 85 | 基本重写或二进制没法 diff：`RestClient.smali` 桩、中文字体、83 个汉化图集（从 overlay 取） |
| `add` | 15 | 基线里没有：`network_security_config.xml`、libaria2c×4（双后端）与 libarchive/shadowhook、浮层用的 logo 与背景、2 个新增图集页、TLS 探针证书、`localstate.js` |
| `remove` | 6 | 要删的：两个未引用的商业字体、被取代的 `libuwasa.so`、`RestClient$1/$2` |
| `generated` | 5 | 构建期产出（Java→dex→smali、native `.so`、BGM 转码），不校验内容 |

基线钉 `1.2.0-r1_r129` 而不是我们这棵树的真实底包 `1.1.1_r125`：打补丁的两个引擎类
两版**逐字节相同**，而换过去白拿 358 个埋点 SDK 类的清除。

### 钉死项

| 项 | 值 | 为什么 |
|---|---|---|
| 整包 | `04dd3f78…`（sha256） | 基线本身 |
| apktool | 2.9.3，`7956eb04…` | 解包结果与它的版本强相关 |
| 重建 JDK | 主版本 ≥ 19 | `Float.toString` 在 JDK 19 换了算法，构建链给 `const` 附的 float 注释因此不同（全 APK 只有 `MurmurHash3.smali` 撞上）。CI 重建步骤单独把 `JAVA_HOME` 切到 JDK 21 |
| 基线树指纹 | `ae69c81a…`（9,468 个文件） | 单文件 hash 只管得住打过补丁的 14 个，剩下的漂移要在 `fetch` 就炸，而不是等到 `verify` 报「某个陌生文件不同」 |

**表里没有任何地址。** 下载地址一律由 secrets 注入——地址不是安全边界，包的身份由
sha256 与树指纹钉死。

| secret | 给谁 |
|---|---|
| `BASELINE_APK_URL` | 基线整包 |
| `OVERLAY_URL` | 汉化图集取件地址（整条 URL，逗号分隔可列多条按序试；需要凭证时按 `https://<user>:<token>@…` 写） |
| `TARGET_REPO` | 发版目标、归档目标（`archive-tags.yml`）与清理工具的回退目标，**是同一个仓库**，形如 `owner/repo`。APK 与版本旁注发到这里（云端版本闸门不由本仓库提升）。读写都走 ``；玩家不受影响（玩家从 CDN 下载，不直连 GitHub） |
| `` | 上面几处的读写凭证 |
| `CLIENT_ROOT_DOMAIN` / `CLIENT_PAGES_HOSTS` | 对外主机名，见 `CNEndpoints` |
| `DOWNLOAD_URLS` | 介绍站上的下载线路（逗号分隔，第一条即「推荐」那条），见 `tools/inject-download-url.py` |

> ⚠ 这里原本是 `ASSET_REPO_UPSTREAM` / `ASSET_REPO_DOWNSTREAM` **两个** secret，
> 名字来自发布目标还是 fork 的年代。那层上下游关系早已不存在、两个目标也
> 合成了同一个仓库，2026-08-24 统一为单个中性的 `TARGET_REPO`（见
> `refactor(ci): ASSET_REPO_UPSTREAM/DOWNSTREAM 统一为 TARGET_REPO`）。
> 新 secret 已在仓库设置里建好。

> 🔒 **约定：提到本仓库之外的东西时，只说它在功能上是什么，不写具体出处。**
> 地址与仓库名一律由 secret 给，源码与历史里都不出现。读代码的人要的信息是
> 「这个值不归我管、改要去那边改」，出处叫什么对他没有增量。
> 完整约定见 [CONTRIBUTING.md](CONTRIBUTING.md) 的「对外表述」一节。

历史遗留还剩一处，这处**没有**跟着改：`build-apk.yml` 通知发布目标的 `repository_dispatch`
事件名是 `upstream-update`，真实语义是「客户端产物就绪」。它是跨仓库的线上
标识符，两边必须同时改才不会静默失联，所以两边都保留原名并各自注释说明。

### 不在仓库里的那些

85 个汉化图集加浮层那张 logo：人手重绘或原样取自国服官方包，**都归原包版权方**，
且无法从本基线重现，只能整份存着——不入工程树，构建时按 `sha256` 取回。
地址与 token 都由 secrets 注入。**内容一律按 hash 认**，所以列多个来源只是防止
某个源不可用时卡住构建，不是「信任其中任何一个」。

还有一类根本不必存：浮层背景就是基线树里的 `web_common0.png`，那条 op 写
`from: baseline` + `src`，构建时现拷，post hash 照常核对。**原包里已经有的字节，
我们不该再存第二份。**

zip 必须由 `tools/make-overlay.py` 打（固定条目顺序、时间戳、权限位，同一批文件
永远同一个 sha256）。改了图集要**三件事一起做**：重打包、重传、同步
`baseline.json` 的 `overlay.sha256`。

### 用法

```bash
python3 tools/baseline.py fetch                # 取整包与 apktool，重建出基线树
python3 tools/baseline.py verify               # 打完补丁与现有工程树逐文件比对
python3 tools/baseline.py regen                # 改完补丁后重生成 diff 与 hash（★ 必跑）
python3 tools/baseline.py apply --out <目录>   # 只重建，不比对
```

基线树落在 `work/baseline/dec`。⚠ 必须由 `fetch` 重建出来——`apktool.yml` 里带着
`apkFileName`，手动换个文件名解一遍，`pre` hash 就对不上。

### 守卫

| 脚本 | 查什么 |
|---|---|
| `tools/check-baseline.py` | 清单自洽：路径不重、hash 对得上、`remove` 的文件确实不在仓库里，**且原包派生路径下没有 patchset 之外的入库文件** |
| `tools/test-check-baseline.py` | 上面那个脚本的自测（11 种坏样本） |
| `tools/baseline.py verify --tree <树>` | 与一棵外部完整树逐文件对账 |

最后那条「不许回流」是这套东西的目的本身：没有检查盯着，移出的原包派生文件会以
各种方式慢慢回来，等发现时已经分不清哪些是故意留的。

---

## 补丁层（`patch/src/main/java/io/kamihama/magianative/`）

| 类 | 职责 |
|---|---|
| `CNCNDownloadUI` | 资源下载浮层。背景图 + 毛玻璃底板 + 左列署名区 + 右列文件槽位/总进度；左上 LOG 胶囊、右上主题切换与 GitHub 胶囊 |
| `CNDownloaderFix` | 资源安装器。**16 个槽位**（13 个基础包 + Scenario、完整 JS、累计 delta，`ARCHIVE_COUNT = 16`）的下载、解压校验、完成标记、重试。两类的校验判据不同：基础包套 `manifest.json` 的分块指纹，热更包按版本 json 的 size/md5——别把 16 个一律叫「基础包」 |
| `CNChunkedDownload` | 多线程分片下载 + 断点续传 |
| `ChunkManifest` | 资源包**固定块哈希清单**（16 MiB 一块）的拉取与解析。它是「这个包是不是官方那一份」的唯一内容判据——基础包没有 md5/size 下发，只有这份清单，所以断点续传、离线导入、下载完工校验三条路都靠它 |
| `CNDownloadConcurrency` | 所有 Java 下载器共享的**连接闸门**：允许多个 ZIP 同时推进，长连接总数始终不超过 8。排队等许可**不算线路停滞**——调用方传 heartbeat，排队期间每秒刷一次，否则另一个文件占满连接时，当前文件会被自己的停滞看门狗误杀 |
| `CNDownloadRestart` | 按文件的**重启代际 + 在跑线程登记表**。重下请求绝不为同一个包起第二个 writer：它只推进代际并打断当前 worker，既有任务观察到代际过期后**只丢这一个文件**的下载状态、从字节 0 重来，别的文件照常跑 |
| `CNManualRedownload` | 任意 ZIP 的**手动强制重下协调器**。不要求其他文件的 marker 齐全、不撤销总完成标记、不为了开始下载而重启。基础包最多三个并行，同一文件去重；Scenario／完整 JS／delta 的手动热更新链另由 `CNHotUpdateCheck` 串行协调，重下前两者会随后从网络重下最新 delta。真正提交由全局提交锁串行，完成标记在整个相关链校验成功后更新 |
| `CNArchiveValidate` | 基础包的**下载完工校验**：ZIP 结构预检 + 按 `ChunkManifest` 的分块指纹逐块比对。与热更那套分开（见 `CNHotUpdateValidate`），因为基础包没有 size/md5 可核 |
| `CNOfflineImport` | **离线包注入**：把玩家自己从网盘下好的官方 zip 拷进私有离线区，按分块清单校验后标记「离线就位」，之后 `installArchive` 直接跳过网络。⚠ 拷 1GB+ 要几十秒，所以逐 4MB 回调进度——不显示的话玩家会以为界面定住而再导一次，两个导入线程会并发写同一个 `.importing` 临时文件互相覆盖；`IMPORTING` 互斥位就是为这个 |
| `CNOfflineImportActivity` | 上面那件事的 trampoline Activity：拉起 `ACTION_GET_CONTENT` 选文件、拷贝、校验、写离线标记，结果经静态回调通知 UI。它是「下载反复失败 / 服务器不可达」时的兜底通道 |
| `CNDownloadMode` | **单线程可靠模式**开关。四处并发（分片工作线程、字节分段、全局连接闸门、并行文件数）共用它一个判据 `cap()`——各写各的判断迟早漏掉一处，而漏掉的表现是「选了单线程但并发没降下来」，不报错不崩，只有翻日志数连接才发现得了。 |
| `CNDiskSpace` | **「装不下」与「网络坏了」的分界**。ENOSPC 抛的是普通 `IOException`，和超时、断流走同一个 catch，于是磁盘满会被当成线路故障：无辜线路被记失败进 60 秒冷却（线上 `switch_after_failures=1`，一次就够）、四次重试逐条线路白烧、玩家对着「重试 / 备用引擎 / 单线程 / 离线包」四个都不解决问题的选项反复点。 |
| `CNZipPlan` | **下载前算出安装峰值**。装一个包的磁盘峰值是 ZIP + 解压后（ZIP 要留到解压成功才删），而这个比例各包差得很远：`cn_base_03.zip` 1.32→2.79 GiB（**2.11x**），其余全在 1.02–1.16x。03 在这些基础包中拥有最高的安装峰值 **4.11 GiB**，而进度条上只写着 1.3 GB——玩家按这个数去清理空间，然后在解压阶段翻车。 |
| `CNEndpoints` | **全部对外主机名的唯一来源**。源码里只留结构（`assets.` 子域 + 主域这样的拼法），真实取值由 `tools/inject-endpoints.py` 在构建期从 Secret 注入，仓库与历史里都不出现。注入缺失时 fail-closed：放行列表不含自有域、线路表为空、热更地址拼不出来——退化成「什么都下不了」，而不是退回某个不受控的默认值。 |
| `CNMirrors` | 线路目录：从 `config.json` 拉取线路表，失败/停滞/过慢时自动换线 |
| `CNAria2` | **进程内 aria2 引擎**（`libaria2c_{ossl,gnutls}.so`，JNI 加载，备用或构建期选作主引擎）：**双 TLS 后端共存 + dead-man's switch**（`Aria2EngineFailover`）——默认 openssl 后端，原生崩溃或加载失败自动换 gnutls 后端，连 4 次死亡才回退主引擎。由 linker 加载共享库、**无 exec**，绕开 SELinux exec 闸与 16KB 页对齐（与 libarchive 同思路）。loopback JSON-RPC 控制，单文件同步下载、多连接 + 断点续传。**日志不落独立文件**：aria2 控制台输出经 native 源码层 AndroidLogFile sink 直进 logcat（`tools/aria2/patches/0001-console-android-log-sink.patch`，不重定向进程 fd），随 CNLog 一起进玩家分享包（2026-08-18 原则，别加回 `--log=`）。构建与许可明细见 THIRD-PARTY-NOTICES.md。 |
| `CNAria2Lib` | 上面那两枚 `.so` 的 JNI 装载层。两组导出**同名 JNI 符号**，因此【硬约束】同一进程只允许加载一个后端——`load(Backend)` 显式选，重复调用返回已加载的那个，绝不双载 |
| `Aria2EngineFailover` | aria2 的 **dead-man's switch**。原生崩溃整体杀进程，Java 层没有任何 catch 机会，所以在启动 aria2 **之前**落盘 armed 标记，只有 RPC 确认干净关停才 disarm；下次启动读到标记仍 armed 就换后端。加载期失败（`UnsatisfiedLinkError`）可捕获，当场换、不等下次。连续 armed-death 达上限则整体停用 aria2（两组 so 是同一份 aria2 代码，负载触发的崩溃换组也会连环炸），并在**客户端版本变更**时自动重置计数 |
| `CNHotUpdate` | 热更新的文件下载，与首次安装共用同一套选线与分片逻辑 |
| `CNHotUpdateCheck` | 热更检查流程：启动时检查 Scenario、完整 JS 和累计 delta，按基线在前、累计层最后的顺序下载并应用。重写自原包的 `RestClient.checkAndApplyHotUpdate`——那版浮层自始至终不出现，无从判断跑没跑 |
| `CNJsDelta` | 累计补充层安装与重放保护：核对固定基线及逐文件 SHA-256，拒绝旧版本、同版本不同身份和错误基线；基线覆盖后重放有效累计层，验证成功后再记版本 |
| `CNHotUpdateTx` | 热更包的**事务化应用**：暂存 → 备份 → 换入，出错整体回滚，崩溃后按 journal 恢复。只用于热更，安装器的大包仍直接解压 |
| `CNHotUpdateValidate` | 热更包的下载完工校验：热更的版本 json 带 size/md5 三元组，逐字节核对才放行。这套是热更专属——基础包没有这三元组，走 `CNArchiveValidate` 那条 |
| `CNWebProxy` | WebView 拦截层代理：把原 `WebViewClient` 包一层，本地文件没命中的 GET 可改走 `/stream/`。默认纯透传，模式由 `config.json` 的 `proxy.web_mode`（`off` / `measure` / `on`）下发，切换不用重打 APK。端点级代理在真机上五次会话零命中（见「网络出口」一节），这是替代路线 |
| `CNWebLocalFiles` | WebView 本地资源拦截的**全部判据**。基线的 `WebViewClientImpl.shouldInterceptRequest` 经补丁改写后只剩一行调它。判据收进 Java 而不是留在 smali，是因为这里每一行都是**安全边界**：旧 smali 版按「URL 任意位置 contains("/magica/")、只剥查询串」拼路径，把 `..` 放进查询串就能穿越出去（F-E-01）。守卫 `check-webview-interceptor.py` 同时钉两侧的形状 |
| `CNSafeLink` | 外链统一出口：只放行 HTTPS 且域名在**写死在客户端**的允许列表内（自有域与那三个站的主机名由 `CNEndpoints` 构建期注入——注入发生在编译前，进包后同样是常量池里的死串，配置改不动它，性质不变）。挡的是「服务端被攻破后靠改配置把玩家导去任意地址」与配置写错，**不是**中间人——那一层已由 DNSSEC + 完整 TLS 验证覆盖 |
| `CNVersionCheck` | 客户端版本检查，跑在热更检查与首次安装**两者之前**（装不上资源的玩家也能收到可选更新提示）。本端版本硬编码在 native（`CLIENT_VERSION`，与 APK 的 versionName/versionCode 无关），云端版本在 `config.json` 的 `client` 段。任何异常一律放行，绝不因网络抖动挡住进游戏 |
| `CNUserAgent` | 补丁侧统一 User-Agent（`magireco-cn-legacy/<ver> (Android …; SDK …)`），CDN/服务端日志据此识别客户端与版本。版本号与 native `CLIENT_VERSION` 同源，CI 直接读取该值，不再用 GitHub Run Number 改写客户端语义版本。补丁发起的请求全覆盖；**WebView 转发的游戏流量不动**，仍透传原始 UA |
| `CNRestart` | 重启本进程。原包的 `RestClient.restartApp()` 是坏的——它开头会重跑旧热更（浮层再现），且新 Activity 起在同进程里，被随后那一刀连带砍掉。**做法换过两版**：先是用 `AlarmManager` 把启动 Intent 排到 ~300ms 后再自杀，但部分机型上仍会退回桌面；现在改走独立进程的可见跳板（见下一行），确认跳板真的到了前台才杀旧进程 |
| `CNRestartActivity` | 重启跳板，跑在独立进程 `:cnrestart` 里的透明 Activity。`onResume` 里确认自己已在前台后写就绪标记，`CNRestart` 轮询到该标记才敢杀旧进程；随后延迟拉起主 Activity，失败还会重试一次并把跳板留在前台，而不是悄悄消失。 |
| `CNBootWatchdog` | **开机看门狗**（兜底）：浮层撤下后前端界面迟迟不出现（判据 = 引擎那个 WebView 既 `VISIBLE` 又有非零尺寸）就自动重载一次页面。救的是「前端把自己藏了再去发请求、请求不回来就没人把它显示回去」造成的黑屏（2026-08-21 玩家日志 0097/0099/0100）。截止时间由前端对 `/magica/api/page/TopPage` 的超时推导，必须排在它之后；序章期间不武装；一个进程只重载一次。逃生开关 `skipBootWatchdog` |
| `CNTutorialPrompt` | 「下次启动去播序章」的标记读写与「自动询问只问一次」的记忆，另含给 native 用的隐藏/恢复前端界面入口。真正的触发在 native 侧（拦 `pushSceneTop` 改调 `pushScenePrologue`） |
| `CNBgm` | 安装浮层的 BGM。不用 `MediaPlayer`——它只能整文件循环，会放出尾部 235 帧 padding 且接缝有空隙；这里自己 `MediaExtractor`+`MediaCodec` 解码喂 `AudioTrack`，按 HCA 循环点做采样级无缝循环。全类绝不外抛。 |
| `CNLog` | 统一日志：logcat + 内存环形缓冲 + 文件，LOG 面板直接渲染同一份缓冲区。**两路 logcat**：主回收带 `--pid=<自己>`；另一路只按 tag 收 `DEBUG`/`libc`/`AndroidRuntime` 且**不带 `--pid`**——原生崩溃的墓碑是 `crash_dump` 用别的 PID 打出来的，主回收看不见它，「闪退」类问题因此一直查不动。指望的是 `-T` 回灌：logcat 环形缓冲跨进程存活，玩家崩完重开一次，上一个进程的墓碑就进了新日志文件。SDK &lt; 24 不起第二路（那些设备没有 `--pid`，主回收本来就整机全收）。24MB 封口只停主回收，不停崩溃流。三个 tag 的优先级不一样是有判据的：`DEBUG` 是 debuggerd 独占的，放宽到 `V`；`libc` 被 bionic 平时也用（`Access denied finding property` 之类），必须收到 `F`，否则会长期灌噪音而它又不受封口约束 |
| `CNLogFormat` | 日志行**解析器**：把一条原始日志拆成来源 / 时刻 / 级别 / 组件 / 正文。LOG 面板原先是把一大坨字符串整个塞进一个 `TextView`，真机上 logcat 一秒几百行，满屏等宽字在飞——而这个面板的用途恰恰是「玩家把现场发给客服」，看不懂等于没有。纯字符串处理、不碰 Android 类型，所以能在 JVM 上直接测 |
| `CNLogBundle` | 「分享日志」的打包：把本次与最近若干次启动的日志拼成一个 txt 落在 `cacheDir/share/`，走 `ACTION_SEND` 交给任意 App 转发。直接复制文本会被 QQ 之类截断，而不是人人都会用 adb 取文件 |
| `CNLogShareProvider` | 上面那个包的只读 `ContentProvider`（`exported=false` + 一次性 URI 授权）。不用 androidx 的 `FileProvider`：编译 classpath 只有 android.jar + OkHttp/Okio，引用了编译期就挂。只服务 `cacheDir/share/` 一个子目录，不开 files/ 与存储卡 |
| `CNCrashHistory` | 开机时记「上几次进程是怎么死的」。走 `ActivityManager.getHistoricalProcessExitReasons()`（API 30+），**与上面两路 logcat 完全独立**——那两路都建立在「墓碑确实进了 logcat 且我们读得到」这个假设上，假设不成立时会一起失明且毫无迹象。光 `reason` 一个字段就把原生崩溃 / Java 崩溃 / ANR / 低内存杀进程分开了。原生崩溃与 ANR 还带 trace；⚠ Android 12 起原生那份是 **protobuf 墓碑不是文本**，本仓库没有 protobuf 运行时也不该为它引依赖，所以按「取可打印片段」处理（protobuf 的字符串字段是长度前缀原文，扫一遍就能拿到信号名、abi、so 路径与 backtrace 符号名）。全异常吞掉 + 后台线程，不占开机关键路径 |
| `CNTlsProbe` | **TLS 探针**（调试开关 `tlsProbe`，默认关）：本机 127.0.0.1 起一个 TLS1.2 自签名服务端（Android 自带 TLS 栈），再由 native 侧 `nativeTlsProbe` 用 `dlsym(RTLD_DEFAULT)` 拿到**引擎自己那份 OpenSSL 1.0.2s** 去连它，逐行复刻 `http2::Http2SessionManager::run` 的调用序列。验的是「自建服务端这条路通不通」——挖出来的结论是引擎**根本不验证服务端证书**（`SSL_CTX_set_verify` 全库 0 次调用、无内置 CA、`OPENSSLDIR` 指向打包机路径），而 336142563 是 OpenSSL 1.0.2s 听不懂现代 TLS 扩展的**代差**。两端都在手机里，不需要电脑或 adb |
| `CNDebugFlags` | 调试开关目录的 Java 侧读取（与 native 共用同一个目录，见「调试开关目录」一节）。首次查询时扫一遍并缓存，之后零 I/O；任何异常一律当作「没开」 |
| `CNDebugBridge` | 调试悬浮窗的**接线层**：native 总闸（三态，`null` = 库还没加载所以现在问不到，与「明确是关」分开——压成一个 false 已经害过一次）、合并 Java 侧与 native 侧的开关全表、落盘 + 重启、HUD 文案排版、停留/重下/日志转接 |
| `CNDebugOverlay` | 调试悬浮窗**本体**：挂 Activity 的 `decorView`（**不要悬浮窗权限**——`SYSTEM_ALERT_WINDOW` 在某些定制 ROM 上给不了或给了不生效，而越是出问题的设备越需要这个自救入口）、可拖动小球、页面树。后挂进 decorView 的东西会盖住它，靠布局监听抬回最前；Activity 重建后从旧树摘下重挂。归总闸管——它提供的是「**改**开关」的能力 |
| `CNDebugHud` | 屏幕上缘那行「调试模式：…」。挂 Activity 的 `decorView`，**不需要悬浮窗权限，也不看总闸**：开关是读文件生效的，不依赖悬浮窗，所以「有开关正在生效就得说出来」不该被悬浮窗的两道闸挡住——某人开了开关又回收了悬浮窗权限，开关照旧生效而提示没了，恰好在最需要它的时候失效。它管的是**监测**，不是操作 |
| `CNDownloadUiAssist` | 下载浮层的显示辅助：字号缩放（含「按分辨率推荐」）、横纵滚动条状态、窄屏弹窗宽度、停留状态（`shouldStayOnPage`，入口在状态行那句话上）、左右两列之间那条可长按拖动的分界线（占比夹在 20%–70% 并落盘）。字号推荐参考 720dp、斜率取半、夹在 85–125：`dp = px/density`，720p 低密度手机报出的 dp 比 1080p 还多，dp 宽度不是屏幕大小的代理 |
| `CNHttp` | 建连的**唯一**加固入口：超时按调用方传（控制面 1.8s/2.2s 要快失败、数据面 15s/30s 要能等，这个差异是有意的），统一做的是 `setUseCaches(false)` + `setInstanceFollowRedirects(true)` + UA + 代理语义（绕开系统代理 / 尊重系统代理）。此前六处样板已漂移——三处缺跟跳转、一处缺禁缓存，靠平台默认恰好等价而已。两条例外：`CNAria2` 的 loopback JSON-RPC 有意不跟跳转，`CNWebProxy` 是透明代理要原样转发头 |
| `CNPaths` | 应用私有目录解析器。补丁层与 native 历史上全硬编码 `/data/data/<pkg>`——那只是指向 `/data/user/0` 的兼容软链，非标准容器/深度定制 ROM 可能没建。真碰上时引擎没事（Cocos 走 `getFilesDir()`），补丁层却会全瘫：完成标记永远读不到 → 每次启动都判「未安装」→ 反复全量重下。改为读 `/proc/self/{cmdline,status}`（**不依赖 Context**，native 最早期触发的 Java 调用也能用）算出用户号后按序探测，取第一个可写的。🔴 用户号 = `uid / 100000`，**不能写死 0**——工作资料 / 系统分身 / 厂商多开不是 0，真机上见过 10 和 999 |
| `CNBuildConfig` | 构建期配置，源码里只留**默认值**，真实取值由 CI 在编译前注入（构建时的「主引擎」选择框 → sed 改写 `MAIN_ENGINE`）。与 `CNEndpoints` 的分工相反：那两个是 fail-closed 的空串，注入失败宁可什么都下不了；这里是「默认值 + 可选注入」，不注入也跑得好。⚠ 别把注入后的值提交进仓库，否则再也分不清「构建选过」与「库里写死」 |
| `CNRestClientActivity` | `RestClient.getCurrentActivity()` 的健壮实现（F-053）。基线那版反射读 `ActivityThread.mActivities` 后无条件取 `valueAt(0)`——`ArrayMap` 第 0 项不是「当前前台 Activity」的合同。进程里同时有主 Activity、离线导入 trampoline、计费代理与重启 trampoline 时，会拿到隐藏/旧实例，把 UI 挂进死视图树、或从错误的 task 发起重启与文件选择。改为遍历全部记录、只收还活着的，且 `paused/stopped` 必须**明确读到 false** 才算可交互——反射失败返回 null，绝不拿 false 冒充 |
| `CNIo` | 流的收尾杂务，目前只有 `closeQuietly`。此前同一个三行方法在五个类里有六份实现，其中 `CNDownloaderFix` 那两个重载**只接 `IOException`**——而 `close()` 也会冒 `RuntimeException`/`NPE`，这个方法又几乎总是从 `finally` 里调的，一往外抛就把原始异常整个盖掉。统一后一律接 `Throwable`。守卫 `check-single-impl-contract.py` 钉住「全仓库只有一份」 |
| `CNAtomicReplace` | 同目录原子替换的**唯一**实现（完成标记、断点元数据、整包换入共用）：候选名逐次唯一——固定成 `<目标>.tmp` 时并发写同一目标会互相截断；换入走 `rename(2)` 且**绝不预删目标**——先删再改名，两步之间被杀就把「上一次可用状态」换成什么都没有；换完 fsync 父目录。清残留一律走 `sweep`（新旧两种候选名一起收） |
| `CNArchiveInstallTx` | ZIP 解压的**唯一**实现（首次安装、aria2 路径、离线包导入共用）：解压前按 zip 目录精确预检空间，逐条目校验，膨胀比两道防护**都在写出去之前**（写完再查等于炸弹已经落地），失败清掉半截产物 |
| `CNZipTool` | 内置 libarchive 的 JNI 封装：进程内解压 zip，替代 exec `bsdtar` 二进制。换掉 exec 的两个理由与 `CNAria2` 同源——Android 10+ 的 SELinux W^X 闸（`app_data_file` 无 execute 权限）与 16KB 页对齐。编成标准 native library 由 linker 从只读的 `nativeLibraryDir` 加载，两个都绕开。资源包里那种「冗余 ZIP64」老设备的 `java.util.zip.ZipFile` 可能打不开，也一并解决 |
| `CNLocalStore` | 客户端本地状态存储：按命名空间存一份 JSON，落盘在 `<privDir>/cn-state/`（**刻意不在 `files/` 下**——那底下 `magica/` 整个子树是 WebView 拿 URL 就能读到的供给区，存档放旁边等于把「那四道闸有没有洞」和「存档会不会被页面读走」绑成一个问题）。命名空间走白名单 `[a-z0-9_-]{1,32}`（它会被拼进文件路径，是安全边界不是命名习惯），内容必须是合法 JSON 对象/数组且单份 ≤512KB，命名空间总数 ≤64。写一律走 `CNAtomicReplace`——它不是缓存，丢了就是玩家编队没了 |
| `CNWebStateBridge` | 上面那个存储对页面 JS 的**唯一**入口，`addJavascriptInterface` 挂成 `CNLocalState`。写限速 40 次/10s（挡死循环狂写把 flash 写坏）。不用 localStorage 的三条理由见类注释，头一条是 `nativeCommand.js` 的 `DATA_CLEAR_WEB_CACHE` 到底清不清站点数据**没有核实过** |
| `CNDeckState` | 本地状态覆盖层的装配方：挂桥 + 注入 `assets/magia/localstate.js`。🔴 **时序是这里最难的一件事**，而且是两件时序要求相反的事：`addJavascriptInterface` 的注入时机是「下一次页面加载」，所以**挂桥要赶在 `loadUrl` 之前、一个 WebView 只需一次**（前端是 hash 路由，错过就是错过一整局）；而脚本挂的是 `XMLHttpRequest.prototype`，活在文档的 JS 环境里，**页面一重载就没了，每个文档都得注一次**。把两者写在同一个分支里会让功能整体失效（挂桥那一刻还停在 `about:blank`，注了白注，此后再不重注）。现在是：100ms 轮询查挂桥（纯 Java），另按 500ms/5s 发一句极小的 `__MAGIACN_LOCAL_STATE__` 探针，只有「这个文档没注过」才注整段。注入时追一句 `!!window.CNLocalState` 的自检回 Java 记进日志，「到底生效了没有」不靠猜。逃生开关 `skipLocalState` |

补丁类的 smali（`smali_classes2/…/CNCNDownloadUI*` 与整个 `smali_classes3/`）
**每次 CI 构建都会用 Java 源码重新生成**，手工改这些 .smali 不会影响产物。

### 🔴 铁律：安装器入口绝不能抛异常

native hook 转调 `RestClient.startCNDownload` 后做 `ExceptionCheck`/`ExceptionClear`。
**Throwable 一旦逃进 JNI，hook 会清掉它并放行引擎自带的下载场景**——玩家看到原生
安装界面，这是必须避免的终态。所以 `runInstaller` / `getEndpoint` 最外层都套了
`catch (Throwable)`：宁可停在我们自己的浮层上报错，也不把控制权交回引擎。

同理 `CNCNDownloadUI.show()` 只在浮层**确实挂上 decorView** 之后才置 `isShowing`；
无条件置位会让一次创建失败之后本进程再也建不起浮层。

### 手工 smali 改动（两处，都有守卫）

- `RestClient.smali` 精简为桩，只剩 `clinit`/`getCurrentActivity`/`startCNDownload`。
- `WebViewImpl$WebViewClientImpl.smali` 手工加入 `CNPaths->filesDir()` 调用，
  由 `tools/check-webview-interceptor.py` 第 7 项守着。

两者都住在 `baseline/` 里。要改就 `baseline.py apply` 出工作树、在那儿改、
再 `baseline.py regen`。

### 右上角胶囊 `right_pill`

默认「GitHub」，`config.json` 下发 `right_pill` 时变成「支持我们」，点击弹窗里的
跳转一律先过 `CNSafeLink`。`enabled` **缺省 true**，置 `false` 时无视其余字段
回落默认胶囊——有这个显式开关才能临时关掉而不用把整段删了再敲回来。

---

## 安全模型：config.json 是半可信输入

信任锚只有三样，都写死在包里：`CNMirrors.MIRRORS_URL`、`CNSafeLink` 的外链允许
列表、APK 签名。其余一切来自 `config.json`。

- **🔴 只收 HTTPS，保留 TLS 证书验证。** `normalizeBase` 拒绝明文地址和内嵌
  控制字符。早期基础包缺少摘要校验的风险是历史背景；当前基础资源已有分块清单校验，
  热更新另核对包身份，累计 delta 还核对基线及逐文件 SHA-256。这些检查不能替代 TLS，
  也不能通过关闭证书验证处理连接错误。
- **`proxy.domains` 有最小粒度。** 它是后缀匹配，填个 `"com"` 就能把所有 `.com`
  流量吸进代理。`isSaneProxyDomain` 要求至少两段、纯 ASCII，并拒掉常见两级公共
  后缀。挡不住多级公共后缀，但最便宜那条路堵死了。
- **解压有膨胀比上限。** md5 管的是压缩后那份。超过 200 倍且已写出 256 MB 就中止；
  正常包最高 2.11x（`cn_base_03.zip`），差近两个数量级，不会误伤。
- **代理响应流要能自己收尾。** `CNWebProxy` 把 `disconnect()` 挂在流的 `close()`
  上，覆盖「读到 EOF」与「被取消」两种收尾。
- **`hide()` 要清干净 static 视图引用。** `CNCNDownloadUI` 的视图引用全是 static，
  漏一个就把 Activity 钉住。加新视图字段记得同步 `HideRunnable` 的清理列表。
- **启动期的东西挂在 `triggerInstaller()` 分支之前。** 版本检查曾只挂在「标记存在」
  那一支，结果首次安装卡住的玩家永远收不到强更提示——而最需要的正是他们。

判据钉在 `tools/ConfigGuardTest.java`（52 项）：

```bash
java -cp .build-test:.cache/deps/android.jar ConfigGuardTest
```

---

## 网络出口：谁走支线、谁直连主线

**支线只分发文件，配置一律直连主线。**

| 请求 | 去向 | 位置 |
|---|---|---|
| `config.json` | 直连主线 | `CNMirrors.MIRRORS_URL` |
| `version_scenario.json` / `version_js.json` / `version_js_delta.json` | 走支线 | `CNHotUpdateCheck.fetchMetaSafe` |
| 13 个基础资源包 | 走支线 | `CNDownloaderFix.fetchArchive` |
| Scenario、完整 JS、累计 delta 三个热更新包 | 走支线 | `CNHotUpdate.download` |
| `/magica/api/snaa`（端点发现） | 有代理配置走 `/stream/`，否则直连 | `CNDownloaderFix.snaaUrl()` |
| **游戏本身的 API / 页面 / 图片** | 不经上述任何一条 | 见下 |
| 同上，`proxy.web_mode=on` 的 GET | 经 `/stream/` 转发，失败回退直连 | `CNWebProxy.fetchViaProxy` |

换线只改「从哪里取字节」。完成标记里记的始终是规范 URL
（`CNMirrors.CANONICAL_BASE` + 文件名），换线不会让既有安装失效。

### 游戏运行时的流量走 WebView

```
WebViewClientImpl.shouldInterceptRequest
    ↓ URL 含 /magica/ → 映射到 <files>/magica/<其后部分>
    ├─ 本地有 → 直接本地供给（不出网）
    └─ 没有   → super()，真的走网络
```

**端点级代理已判定为零命中**，五次真机会话零样本；拦截层与端点级不是同一件事，
黑屏别记到它头上。拿 WebView 实例要读 `WebViewHelper.sWebView`，不要遍历 view 树
找 tag——`WebViewImpl` 构造里那个 `setTag` 随后就被覆盖掉了。`removeWebView()` 会
换出新对象，所以等待线程长期比对实例身份，换了就重新包。

### 本地状态覆盖层：服务端无状态，存档只能在客户端

Totentanz 服务端是**无状态**的——彻底到打完一场战斗、结算界面的星数都不会变。
`/magica/api/userDeck/save` 这类「写」接口，请求发出去、罐头响应回来，
**服务端一个字节都不记**；下次进游戏编队回到默认。

这条事实决定了做法，没得选：

| | 为什么不行 / 行 |
|---|---|
| ❌ 存下 `userDeck/save` 的 payload，下次开机重放一遍 | 对无状态服务端毫无意义，回来的还是那份罐头 |
| ❌ 在 Java 拦截层抓请求体 | `WebResourceRequest` 不提供请求体（任何 Android 版本都没有 `getBody()`），而编队保存恰好是 POST |
| ❌ 存 localStorage | 跟着 WebView 站点数据走；`nativeCommand.js` 的 `DATA_CLEAR_WEB_CACHE` 到底清不清它**没有核实过**（在引擎侧，仓库里没有基线树） |
| ✅ 前端记「存什么」，Java 记「让它活过这次进程」 | 见下 |

```
玩家点保存
  └→ XHR.send 拦到 userDeck/{save,bulkSave} 的请求体（savePrm 形状）
        └→ CNLocalState.set("deck", …) → <privDir>/cn-state/deck.json（原子写）

任何带 userDeckList 的响应到达前端之前
  └→ 用存下来的那份覆盖 / 补齐（savePrm → userDeckList 行，是 savePrmCreate 的逆）
        └→ 前端的 responseSetStorage 从响应读进 storage，它读到什么就信什么
```

覆盖只影响**这一次**的响应，不回写存档：`userCardList` 某次回来不全并不代表玩家
真的失去了那张卡，据此把存档改小就是拿一次响应的抖动去销毁玩家的编队。所以
「账号里没有的魔法少女」是就地摘掉位置，盘上那份原样留着。

三段实现分别是 `CNLocalStore`（落盘）、`CNWebStateBridge`（JS 桥）、
`CNDeckState`（装配与注入），前端脚本在 `assets/magia/localstate.js`。

#### 路由表：这一层能做的不止「改响应」

2026-08-27 的真机日志（`logWebviewRequests` 开着，完整打了一场主线）把整条战斗
链路摊开了，**全部走 WebView，全部是这一层看得见的 XHR**：

```
MainQuest → MainQuestBranch → QuestBattleSelect → SupportSelect → DeckFormation
  → quest/start（敌人配置）→ …46 秒静默，战斗在 native 引擎里跑… → QuestResult（结果）
```

引擎自带的那份 OpenSSL 1.0.2s **整场零流量**（三份日志里 `[proxy] api` 观测都是
0 条）。也就是说「服务端要做的事」在这一层是**可枚举**的，而且不必碰引擎那条
有代差问题的通道。

`localstate.js` 因此从「几个写死的 if」改成一张路由表，每条路由自报拦哪个 path、
以哪种方式介入：

| 钩子 | 能做什么 |
|---|---|
| `request(url, body)` | 看请求体。**这是全客户端唯一看得到 POST body 的地方** |
| `response(json, url)` | 就地改响应，返回是否动过（动过才重新序列化） |
| `answer(url, body)` | 返回字符串即**整份本地应答，不出网** |

内置三条：`deck:capture`（捕获编队）、`sheet:harvest`（攒阵形）、`deck:overlay`
（覆盖 userDeckList）。前两条 `test` 恒真，名字里带「(全站)」标出来。

> ⚠ **目前没有任何一条路由用 `answer`**，这是有意的。本地应答要先有那个端点的
> 真实响应样本（归档里的 `magica/api/<路径>/NNN.json`），照着形状答才有意义；
> 照猜的形状答只会把前端弄崩，而且崩在离原因很远的地方。机制立好了，第一条
> 何时登记是另一件事。`serveLocal` 伪造的范围也有意划得很窄——只有
> `readyState`/`status`/`statusText`/`responseText`/`response` 加两个事件，
> **没有响应头**，登记第一条 `answer` 之前得先确认前端不在乎。

调试口子：`chrome://inspect` 里 `__MAGIACN_STATE__.stats()` 看每条路由命中多少次
（判断某条端点该不该本地答，第一步就是知道它一局里被叫了几次），
`.routes()` 列名单，`.route(def)` 让别处也能登记而不必改这个文件。
覆盖的是**所有** `deckType`，所以主线、竞技场、歼灭战（70+n）、镜之魔女（100+n）
一起生效。逃生开关 `skipLocalState`。

> ⚠ **一个尚未在真机上验证的点**：`addJavascriptInterface` 的注入时机是「下一次
> 页面加载」，而前端是 hash 路由（`location.href="#/TopPage"`），整局不会再触发
> 第二次文档级加载——桥挂晚了就是**整局失效**。`CNDeckState` 为此用 100ms 快轮询
> 抢在 `loadUrl` 之前，并在注入时回传一句自检写进日志：
> `自检通过：页面里 CNLocalState 可见` / `自检未通过：…`。真机第一次跑起来先看这行。
>
> 挂桥与注入是**分开**的两件事，别再合回去：合在一起时那一次注入落在
> `about:blank` 上，同一个 WebView 此后再不重注，于是只剩 `onPageStarted` 一条路，
> 而它在 API &lt; 26、开了 `skipWebProxy`、或页面被 `CNBootWatchdog` 重载之后都不在。

### 为什么 ETag 只能在同一条线路上比对

同一份字节，不同线路给出的 ETag 格式互不相同（转发型给被转发侧的版本号、CDN 给
S3 分段上传的 `<md5>-<段数>`、自建 nginx 给 inode-mtime）。各家实现各自为政，改不了。

> 有哪几条线、权重多少、实测值是什么，**以线上 `config.json` 为准**：那份随时
> 可改，抄进文档的那一刻就开始过期，而过期的线路表比没有更误导人。

由此定下四条：

1. **ETag 只在同一条线路上比对。** 跨线路照比，每次换线都会判「文件变了」并丢弃
   断点，换线与续传互相抵消。断点元数据记录写入时的完整 URL：URL 相同才比 ETag，
   不同则只依赖总长度一致。代价是跨线续传察觉不到两端内容不同，兜底是解压阶段的
   `extractChecked`。
2. **`min_speed_kbps` 按千**比特**每秒解释**（`* 1000 / 8` 换成字节每秒）。
3. **`config.json` 拉不到要带退避重试**：`ensureLoadedAsync` 按 2/15/45/90 秒重试
   四次。第一档特意缩到 2 秒以赶上 3 秒的配置到位窗口。四次都失败会弹框问
   「再试一次 / 用内置线路」，不再静默收场。
4. **拉版本 json 失败不打冷却。** 竞速量的是预热对象的吞吐，版本 json 是冷对象、
   量的是首字节延迟，两件事不是一个维度——照旧逻辑会把自己刚选出来的最快线刷掉。
   > `VER_READ_TIMEOUT_MS`（3.5s）与 6 秒总闸 `VERSION_QUERY_DEADLINE_MS`
   > **只有连着看才成立**：要放宽单条必须同时抬总闸，否则总闸先到期。

### 「过慢」是「换一条」，不是「不给你装」

低于阈值就 abort 并抛 `IOException("线路过慢：…")`，`reportFailure` 打冷却，退避
2/4/8 秒换线重试，最多 4 次。**每次换线前清掉该镜像的 `.part`**——不同镜像的未认证
字节绝不复用，所以四次是每轮从零开始，不是接力续传。

> `switch_after_failures: 1` 不是随手填的保守值：曾有一条最高权重线路对某类客户端
> 整体 403，每次安装第一次尝试必撞。设成 1，代价被限制在一次尝试 + 2 秒退避。

### 网络慢时问玩家，而不是替他决定

`CNCNDownloadUI.askSlowNetwork(...)`：阻塞式，**只能在后台线程调**。两个调用点的
取舍轴不同，所以按钮文案是参数：

| 场景 | 玩家真的在等吗 | 问什么 |
|---|---|---|
| 热更版本查询 | **是**，卡在白屏期 | 继续等待 / 跳过 |
| 线路表 `config.json` | **否**（内置线路始终可用） | 再试一次 / 用内置线路 |

**护栏坏掉时一律退回原行为，绝不卡人**：浮层不在、误在 UI 线程调用、建框抛异常、
选择处理抛异常，四条路径都 `countDown` 并返回 `SLOW_SKIP`，并记日志说明这是
「没条件问」而非「玩家选了跳过」——排查时这两件事完全不同。

---

## 构建

`.github/workflows/build-apk.yml`，**仅手动触发**。push 到 main 只跑两道检查类
workflow（复验 + 全量回归并移动 `last-green`），不产出对外 APK。

```
fetch → apply → work/tree → native .so → BGM 转码 → 覆盖补丁 smali
      → apktool b → zipalign → apksigner → 发布
```

所有读写工程树的步骤都经 `$TREE`；`tools/build-local.sh` 走同一套。

- **d8 分两组出 dex**（`CNCNDownloadUI*` → classes2，其余 → classes3），两次调用都要把
  **整个** `.build/classes` 作为 `--classpath` 传进去。它只供解析类型，不进输出。
  不加会一直报 desugaring 告警——今天无害，但 minSdk 21 下 `default` 方法必须靠
  desugar 才能在 API 21–23 上跑，哪天有人给跨组接口加了 `default`，报的还是同一句，
  然后**静默产出装得上、跑起来炸的类**。所以补 `--classpath` 的同时把 d8 告警变成
  红灯（`run_d8`，workflow 与 `build-local.sh` 各一份，改一处要改两处）。
- **签名用 AOSP testkey**，与上游发行包同一签名身份，可直接覆盖安装。这是一把
  公开测试密钥，**不提供任何真实性保证**。
- **`apktool b` 会重新编码 dex/arsc/manifest**，产物与逐字节替换 dex 的做法不会二进制
  相同，当时验过语义等价。唯一事实来源是 `patch/src/main/java/`。

---

## 前端资源汉化

> 操作手册在 [`i18n/README.md`](i18n/README.md)。本节只讲原理。

前端汉化不走 APK，走热更包 `cn_js_update.zip`：`tools/i18n-extract.py` 抽取 →
`i18n-apply.py` 回填 → `i18n-package.py` 打包。客户端不需要改动。

### 「进游戏后还是英文」分别归谁管

**不能把所有英文都归到 JS 上**，不同来源要动的层完全不同：

| 英文出现在哪 | 归谁管 |
|---|---|
| JS 的按钮/确认框/错误提示、HTML/EJS 模板、数据 JSON | 前端热更包 |
| cocos2d 原生弹窗、下载错误、**战斗中与结束的角色台词** | native 文本 hook（`MagiaLegacy.cpp` 的 i18n 表） |
| 原生中文被渲染成日文字形 | native 字体路径 hook（`fontPathOverwrite`） |
| 资源下载浮层 | Java 补丁（`CNCNDownloadUI`） |
| **烘焙进 PNG／plist 图集的英文** | 只能改图片资源 |
| 服务端直接返回、未经注入器的字段 | 需扩展数据映射或服务端处理 |

判据不靠猜：开 `logI18nMiss` 跑一遍，日志里出现该串就说明它流经 native 标签
（补 `engine_i18n.tsv` 即可，热重载生效），没出现就得往前端或服务端找。
猜错方向整批活白干。

图片是提取器的盲区——它只找日文假名/汉字，文字被画进 PNG 就完全看不见。

### 🔴 CSS 进过热更包就再也拿不出来了

每个页面的 CSS 都走拦截：`index.html` 的 `<link>`，以及 requirejs 用 text 插件读进来
注入 `<style>` 的那些，最后都是请求 `/magica/css/**`。而 `shouldInterceptRequest`
**只按路径匹配、会把 `?<md5>` 丢掉**，再叠上热更**只写不删**——

> **往热更包里放过一次某个 CSS，这个动作不可逆。** 从包里移除它只是以后不再更新
> 它；设备上那份**永远留着、永远赢过服务端**。

已经出过一次事故：某个页面 CSS 的快照缺了一条规则，那个 div 塌成 0 高度，
**历史篇入口就此消失**，而模板、js、图片、控制台全都正常。解毒只有一条路：把服务端
现役内容原样放回包里再发一次。

包里 CSS 分三类，来路完全不同，弄混会出事：

| 类别 | 来源 | 性质 |
|---|---|---|
| `_common/fonts.css` | **重写**，把 `src` 改指包内中文字体 | 完整覆盖——全站只有这一处 `@font-face` |
| `_common/common.css` | **快照 + 追加**：线上原文原封不动，其后追加覆盖规则 | 冻结了线上文件 |
| 其余 11 个 | **原样照抄服务端现役内容** | 纯解毒用，正确状态就是逐字节相同 |

`tools/check-css-freeze.py` 守着第三类。范围刻意收窄（从 188 个收到 13 个）：
放得越多将来要同步的越多，而每一个都是不可逆的。

> ⚠ **这是个会过期的冻结。** 服务端改了 `common.css`，玩家端仍吃我们这份旧的，
> 新增样式会静默消失。改版前先比对线上原文的 md5，不一致就用新原文重做快照，
> 再把覆盖段重新追加上去。

---

## 调试开关目录（排查用）

```
/data/data/io.kamihama.totentanz/
├── log/     ← 日志（CNLog）
├── debug/   ← 调试开关：建同名空文件＝打开，删掉＝关闭，重启生效
└── files/   ← 热更解压根，**不要**把排查工具放这里
```

`log/` 与 `debug/` 与 `files/` **平级**：`files/` 是热更解压根，`CNHotUpdateTx` 会按
前缀算孤儿并删除。今天碰不到调试目录是**巧合而非保证**——前缀哪天放宽，开关就会在
某次热更后集体消失且查不出原因。

native 与 Java 两侧读同一个目录。包里没有 `android:debuggable`，`run-as` 用不了，
这个目录只有能直写应用私有目录的环境碰得到。

### 🔴 边界：只关我们自己加的东西

这些开关一律只做一件事——**把客户端退回更接近原包的行为**。
**绝不设置任何削弱安全判定的开关**：外链白名单、https 强制、配置来源校验、解压
膨胀比上限一概不做成开关。否则这个目录就从排查工具变成攻击面。

> 加新开关前先问：打开之后是「少一个我们加的功能」，还是「少一道防线」？
> 后者一律不做。

**这条不靠自觉**：`tools/check-debug-flag-boundary.py` 在 CI 里断言几个安全判据的
正文里不出现 `CNDebugFlags` / `g_dbg*`。保护区是**方法级**的——`refresh()` 里加开关
合法，`normalizeBase()` 里加就会被拦下。

### 三类开关

| 前缀 | 干什么 | 什么时候用 |
|---|---|---|
| `skipXxx` / `noXxx` | 跳过启动链某一步 / 不装某个 native 改动 | 二分定位「是哪一步把游戏搞挂的」 |
| `failXxx` / `slowXxx` | 故障注入 | 验错误处理路径本身——平时只有网络真烂掉才跑得到 |
| `logXxx` | **只记录，不改行为** | 日志里根本没有能回答这个问题的信息时 |

开关名一律小驼峰，native 与 Java 同一风格。

```
Application.onCreate
 └─ CNDownloaderFix.triggerInstaller()          ← 独立线程
     ├─ CNWebProxy.install()                     skipWebProxy
     ├─ [标记不存在] runInstaller()              skipInstaller
     │    ├─ CNCNDownloadUI.show()               skipOverlay
     │    ├─ 16 个包下载                          failDownload
     │    ├─ 序章询问                             skipTutorialPrompt
     │    └─ noticeAndRestart()                   skipRestart
     └─ [标记存在] CNVersionCheck                skipVersionCheck
          └─ CNHotUpdateCheck.start()             skipHotUpdate
               ├─ CNMirrors                       skipMirrorConfig / failConfigFetch
               ├─ 版本查询（6s 总闸）              failVersionQuery / slowVersionQuery
               ├─ CNHotUpdateTx.apply()            failHotUpdateApply
               └─ 慢网询问框                       skipSlowAsk

（浮层撤下时，首装与热更两条路径共用同一个收尾）
 └─ CNCNDownloadUI.hide() → CNBootWatchdog.arm()  skipBootWatchdog

（native）JNI_OnLoad → 34 个 hook
 ├─ pushSceneTop 浮层闸门                         noOverlayGate
 ├─ 强制序章 / WebView 看门狗                      noTutorialForce / noTutorialGuard
 ├─ UrlConfig api/chat/web 端点                    （只读观测，无开关）
 ├─ initLabel / setString 文案替换                 noI18nLabel / noI18nSetString
 │   └─（只记录）未命中的串                        logI18nMiss / logI18nMissAll
 └─ HTTP2 并发数、ADX2 采样率                      noHttp2Bump / noAdxSampleRate
```

### 「空转」与「根本不装」是两回事

`noI18nLabel` / `noI18nSetString` 只让钩子**空转**——钩子照样装着、照样按我们声明的
原型转发。所以**原型声明本身写错时，这两个开关测不出来**。真机上正是这样定位到
`cocos2d::Size` 的 ABI 偏差的：`noI18nLabel`（仍装着）黑屏，`noInitLabelHook`
（根本不装）可完整战斗。

| 开关 | 关掉的是 |
|---|---|
| `noInitLabelHook` | **不安装** `LbUtility::initLabel` 钩子 |
| `noTtfHooks` | **不安装** `createWithTTF` / `setTTFConfig` 三个钩子 |

排查顺序因此是两级：先用「空转」版看是不是**行为**的锅，再用「不装」版看是不是
**钩子存在本身**（含原型/ABI）的锅。

### 故障注入能验到什么

| 开关 | 验的是哪条错误路径 |
|---|---|
| `failConfigFetch` | 2/15/45/90 秒退避重试，以及跑完那个「再试一次 / 用内置线路」框 |
| `slowVersionQuery` | 慢网询问框（注入 9 秒 > 6 秒总闸必定触发）与选「继续等待」的续期 |
| `failVersionQuery` | 版本查询失败后 fail-open，且「已是最新」不谎报 |
| `failDownload` | 换线、冷却、重试上限 |
| `failHotUpdateApply` | `CNHotUpdateTx` 的整体回滚与 journal 恢复 |

### 用法

```bash
adb shell "run-as io.kamihama.totentanz mkdir -p debug && touch debug/<开关名>"
# 重启游戏，走一遍要查的流程，然后取日志：
adb shell "run-as io.kamihama.totentanz cat log/<最新>.log"
```

---

## 测试

`tools/` 下是补丁层的测试套件，跑在 JVM 上，不需要设备。

```bash
python3 tools/inject-endpoints.py --test        # 先注入占位端点，否则白名单是空的
javac -nowarn -source 8 -target 8 -encoding UTF-8 \
      -cp .cache/deps/android.jar -d .build-test \
      $(find patch/src/main/java -name '*.java') tools/*Test.java
java -cp .build-test:.cache/deps/android.jar <类名>
python3 tools/inject-endpoints.py --reset       # 跑完还原，别把注入结果提交进去
```

⚠ **运行时也要挂 `android.jar`**，不只是编译时。少了它 `BgmLoopTest` /
`ThrottleTest` 会以 `NoClassDefFoundError` 挂掉——看起来像测试失败，其实是
classpath 少了一截。

| 测试 | 覆盖 |
|---|---|
| `HotUpdateTxTest` | 事务化应用：提交、回滚、崩溃在提交前/后的两个恢复方向、恶意包拒收、幂等、清单与孤儿清理 |
| `SafeLinkTest` | 外链白名单：协议、authority 伪装、公共后缀、空白与控制字符、大小写与末尾点归一化 |
| `ConfigGuardTest` | 云端可控字符串的准入：只收 https、CRLF 与控制字符注入、`proxy.domains` 最小粒度 |
| `WebProxyTest` | 代理改写判据：后缀匹配卡在点上、排除自身、只改 https、配置不全一律透传 |
| `ProxyFetchTest` | **需服务器**。真跑 `fetchViaProxy`：gzip、304 不接管、跨协议 301、5xx 进冷却、4xx 不进 |
| `ResumeTest` | **需服务器**。断点复用、同线 ETag 变化拒绝复用、跨线续传、服务端忽略 Range 返回 200 |
| `HotUpdateTest` | **需服务器**。非主线走直连、提前断流不提交残缺文件、承接残片续传 |

> 完整清单见 `tools/*Test.java`（26 个），断言数以当次运行为准。

集成测试要先起 `tools/proxy-test-server.py` / `tools/server.py`。
`ProxyFetchTest` 还要把 `tools/teststubs/android/webkit/WebResourceResponse.java`
加进源文件列表——android.jar 里那个构造函数是 `throw new RuntimeException("Stub!")`，
不盖掉这条路径一步都跑不了。

有一条**桌面上验不到**：Android 的 `HttpURLConnection` 底层是 OkHttp，会自己加
`Accept-Encoding: gzip` 并透明解压；桌面 JDK 不会。测试会打一行 `⏭` 明说这件事，
而不是假装验过了。

---

## 原始署名与免责声明

**原始包**：本仓库的基础 APK 来自游戏 **《魔法纪录 魔法少女小圆外传》**（原作
《魔法少女小圆》系列），一切游戏内容、角色、立绘、语音、音乐与剧情文本的版权归
**Aniplex / f4samurai / 版权方（魔法少女小圆 + 魔法纪录）** 所有。

> ⚠ **免责声明**：本仓库与上述版权方无任何关联，未获其授权或认可。**维护者自己
> 不会将本仓库用于商业用途**；但对于从版权方或上游获得合法授权的人，本声明不
> 构成使用限制。本仓库的**原创代码**（补丁层与工具）按 GPLv3 条款授权；**游戏
> 内容与素材**（角色、立绘、语音、音乐、剧情文本等）归版权方所有，其使用以版权方
> 自己的条款为准，本仓库不代为授权。**版权方若认为本仓库构成侵权，请通过仓库
> 联系方式告知，我们将配合下架相关内容。**

**二次开发**：本仓库在其上的逆向分析与改造（引擎 hook、UI 改造、下载系统、
汉化补丁）由 **Totentanz** 组织完成（GitHub 组织
`Puella-Care`，部分 smali 补丁来源）。

**Totentanz MIT 许可来源**：本仓库的部分 smali 补丁与构建脚手架来自 Totentanz 项目
`Puella-Care/client-apk`（即 Totentanz client），它本身是
`rayshift/magiatranslate`（MagiaTranslate）的 fork，**Copyright (c) 2023 Rayshift，MIT License**。
按 MIT 要求，原版权声明（Copyright (c) 2023 Rayshift）在本仓库
[`LICENSE.additional-terms`](LICENSE.additional-terms) §4 声明保留；MIT 只覆盖
这些派生部分，其余归 GPL v3 管。

**历史汉化贡献**（更早的汉化工作）：MadeInMagius（核心逆向开发，独立完成汉化
引擎、下载系统与日服国服资源合并）、水银h2oag（国服文件之外的翻译和校对）、
CyberNova（下载加速及资源自动化推送）、segfault（国服数据留存）、@PhotonFlow
（国内加速与修复）。

项目官网见客户端「署名」区（地址构建期注入，不写在仓库里）

## 许可证

本仓库的**补丁层与工具**（`patch/`、`tools/`、`docs/` 与仓库内文档）以
**GNU General Public License v3.0** 授权，见 [`LICENSE`](LICENSE)，并受
[`LICENSE.additional-terms`](LICENSE.additional-terms) 的附加条款约束。

**不归 GPLv3 覆盖**的第三方部分（`smali/` 引擎字节码、`assets/`/`lib/`/`res/`
等游戏资源、`original/` 解码参考）归原权利方所有——这条
作为附加条款 §3 写入 `LICENSE.additional-terms`。

**但 `assets/`、`lib/` 里有几个不是原包的东西**，是我们自己塞进去的自由软件，
逐个登记在 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)，附加条款 §5 指向它：

| 文件 | 是什么 | 许可 | 义务 |
|---|---|---|---|
| `lib/*/libaria2c_ossl.so` | aria2 1.37.0 进程内共享库（OpenSSL 1.1.1w 后端） | **GPLv2+**（附 OpenSSL 链接例外） | **要提供对应源码**——源码指向与三年书面要约见声明文件 |
| `lib/*/libaria2c_gnutls.so` | aria2 1.37.0 进程内共享库（GnuTLS 3.8.3 后端） | **GPLv2+**，静态链入 **LGPL** 组件（GnuTLS/nettle/GMP） | **要提供对应源码 + LGPL 可重链**——源码指向与书面要约见声明文件 |
| `lib/*/libarchive.so` | libarchive 3.7.4 的 JNI 封装（进程内解压资源包） | **BSD-2-Clause**（附 zlib 静态链接） | 附版权声明与许可全文（见声明文件） |
| `lib/*/libshadowhook.so` | ShadowHook 2.0.1（ByteDance），**CI 从上游源码构建，且我们改过它的源码** | MIT | 附版权声明与许可全文；改动照实写在声明文件里 |
| `assets/fonts/mbm_20160902.ttf` | MagiReco CN Medium（Source Han 派生） | Apache-2.0 | 附许可、注明改动（§4(b)） |

原先 §3 把这些一并划给了游戏版权方，两头都错：既把与人家无关的自由软件记到人家
名下，又漏掉了这些许可证要求的声明。`tools/check-third-party-notices.py` 在 CI
里守着——打进包的组件少一条声明就红灯。

> libaria2c_{ossl,gnutls}.so 以**进程内 JNI** 链接进应用（不再是独立子进程的
> 「单纯聚合」）。本项目整体按 GPLv3 分发；aria2 是 GPLv2-or-later（「或任何
> 更新版本」条款使其可与 GPLv3 结合），openssl 组的 OpenSSL 由 aria2 源码头附带
> 的链接例外覆盖，gnutls 组静态链入的 LGPL 组件（GnuTLS/nettle/GMP）需提供
> 可重链源码——详见 THIRD-PARTY-NOTICES.md。

**另有 MIT 来源**（部分 smali 补丁/脚手架，来自
Totentanz 项目 → `rayshift/magiatranslate`，Copyright Rayshift）：版权声明在 §4 声明保留，MIT 只覆盖那些派生部分。

---

---

## 提交与分支纪律（有钩子在管，不是靠自觉）

**完整纪律见 [`CLAUDE.md`](CLAUDE.md)（提交约定 / 钩子机制）与
[`AGENTS.md`](AGENTS.md)（§0 分支纪律、§1 提交规范）。** 本仓库的特色是钩子由
`tools/agent-guard.py` 在 Agent 跑第一条命令时自动接电；没跑过 Agent 的克隆手动补一次
`bash tools/install-hooks.sh`（Windows: `tools\install-hooks.cmd`）。

关键一条：`commit-msg` 只管得住「提交发生在装有钩子的克隆里」，`pre-push` 才是
兜底——2026-08-08 那 12 个英文标题提交就是在别处产生、作为分支推进来的。
逃生口：`[skip-hooks]`（信息内顶格独占一行）、`SKIP_MSG_HOOK=1`、`SKIP_BRANCH_HOOK=1`。

## 远端分支现状（动态，以脚本为准）

远端分支**只应**有 `main` / `hotfix/*` / `surgery/*`（另有具名临时例外，见
[`AGENTS.md`](AGENTS.md) §0），退役一律走「🗄️ 归档分支为 tag」CI（先打
`archive/<原名>` tag 再删分支）。实时复核：`python3 tools/check-branch-hygiene.py`。
历史研究/功能分支均已归档为 `archive/*` tag（只读、不可变），不再以分支存在。

---

## 状态提醒

本仓库不做自动发版：产出对外 APK 的那条腿只有手动触发，push 到 main 只跑检查。
游戏后端不由我们掌控，自动产出对外包只会让玩家装到连不通的版本。

## 独立发布传输迁移

配置入口与两条下载线路可在构建期独立注入；未指定时保留原线路。安装标记使用的规范资源前缀和其哈希保持不变，所以替换下载服务不会让既有基础资源失效。源码中的五个注入位均保持空串，产物验证同时核对实际配置入口与传输端点。

本版同时合入已验收的启动路由保护及初始字体文案翻译：战斗、剧情和活动路由不再被启动看门狗中途重载，初始 TTF 文案使用与后续更新一致的单快照译表。保留既有 WebView 主线程调试设置和进程级启动恢复。

WebView 调试回调仍由主线程执行，编译形状使用静态回调类，避免生成携带外层线程实例的匿名类。

客户端版本的编码字节经 volatile 运行期读取，防止优化器将解码折叠回明文；正式产物继续执行既有版本字面量检查。


## 多来源更新检查

客户端同时检查内置发布来源的 APK 旁注与在线配置；JS 和 Scenario 分别检查各发布来源的版本文件。首个来源返回旧版本不再结束查询。只选择版本号、大小、摘要完整且下载链接符合既有准入规则的最高候选；同一最高版本出现不同文件身份时保留已安装内容。

内置备用来源在远程线路表较旧时仍参与更新检查，查询并行且受总时限约束。下载优先使用最高候选的来源，备用下载仍必须满足同一大小、摘要与 ZIP 结构，不回退覆盖较新本地版本。基础资源安装标记的规范身份保持不变。

测试顺序：先安装候选客户端，再发布更高版本的真实 APK 与对应元数据；保留旧入口的旧版本，验证其他发布来源仍触发更新。候选构建不自行提升线上版本闸门。


### 应用内客户端更新

发现较新 APK 时提供「立即更新」和「继续用旧版」。点击框外或返回键也视为继续；后续首次资源安装或热更检查只接力一次。打开更新页面后取消下载或安装，可返回选择继续，不清理资源或存档。关闭提示只跳过本次 APK 更新，下一次启动仍可提醒。

客户端分别查询各有效发布来源的 APK、脚本与剧情版本，采用最高有效版本。
点击「立即更新」后在应用内显示下载进度与速度；只在同版本、同大小、同 SHA-256 的来源间换线。
下载完成先验证大小、SHA-256、包名、签名与 Android 版本码，再把只读缓存文件临时授权给系统安装器。
首次安装来源权限及覆盖安装确认由 Android 展示；取消权限或安装后可重试，无需重新下载已校验的 APK。
应用升级不执行卸载、清除数据或重装资源。安装成功后重新打开游戏。
下载缓存限定在应用私有 cache/apk-update，不开放游戏存档。断线可换同版本线路或重试。
纯 Java 下载回归：`python tools/test-apk-update.py`。

### 固定基线与累计补充层（2026-10-03）

完整 JS 103、Scenario 3323 按固定 SHA-256 校验。新剧情及资源只更新累计 delta。
所有安装入口共用提交锁：基础包写入前检查最新缓存可重放，写入后立即重放并逐文件核验，最后才保存版本和完成标记。缺失、过期或损坏的缓存会中止基础包覆盖，保留错误供重试。
已安装清单和保留缓存共同拒绝旧 delta；同版本不同内容或压缩包身份拒绝。新的已核验下载可修复损坏缓存。
已退役的旧 CDN 不再作为内置线路自动补回；保留构建配置主备线路及独立公开 GitHub。TLS 证书验证不放宽。规范资源身份不变，不触发旧资源全量重下。
回归：`tools/test-js-delta-install.py` 包含 31 项生产安装器断言；主机测试不替代最终 APK 设备测试。


### 手动重下的完成条件（1.0.204）

点击累计 delta 的重下按钮，会重置对应行并显示排队、下载和安装进度。
点击 Scenario 或完整 JS 的重下按钮，也会重置 delta 行：完整基线安装受缓存保护，
写入后先重放有效缓存，再发起最新累计 delta 的网络下载、校验和应用。
这不是只重放缓存，也不是只显示“正在重下”的提示；相关链成功后才标为完成。
热更新链串行执行，基础资源的有限并行下载不改变实际安装提交的串行保护。

源码仓可见性不应影响玩家下载：正式安装依赖公开下载入口，维护端凭据不进入 APK。
当前文档不把“未来私有化后复验”记成已完成的实际切换。
