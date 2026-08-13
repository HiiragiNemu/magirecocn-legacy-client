# 调试悬浮窗方案

> 状态：**已全部落地**。native 总闸 + 开关表 JNI 导出、`CNDebugBridge` 接线层、
> `CNDebugOverlay` 界面本体、浮层让位逻辑、边界检查、回归测试。
>
> 界面的取舍与文案规则另见 [`DEBUG_OVERLAY_DESIGN_PRINCIPLES.md`](./DEBUG_OVERLAY_DESIGN_PRINCIPLES.md)
> ——那份是界面的事实来源（用户画像、六条设计原则、开关分类与白话文案），
> 本文管的是「为什么这么立项」与接线层的判据。
>
> ⚠ **本文档 v1 有两处硬错，已在下文改正**，列在这里免得有人只读了旧版：
>
> 1. 曾写「权限门槛已经没有了，原包 manifest 里本来就有 `SYSTEM_ALERT_WINDOW`」
>    ——写这句时它已经被 `9688f7e7` 删掉 15 分钟了，并且同一个提交还在
>    `check-download-ui-contract.py` 里立了「不许回来」的断言。该删除**维护者
>    并不知情**，已回退，断言也反了过来（现在断言它必须在）。
> 2. 曾把入口设计成「先用 su 建 `<priv>/debug/enableOverlay` 自举」。
>    **这个方案是错的**，理由见下方「入口」一节——已改成烧在包里的一个布尔。

## 一句话

用一个**真·系统悬浮窗**（`WindowManager` + `TYPE_APPLICATION_OVERLAY`）替代
「`su` 进去手建 34 个空文件」这套操作，把调试开关的门槛从「有 root + 有电脑」
降到「点两下」。

---

## 收益边界：换掉的是 `su`，不是重启

**必须先说清楚它做不到什么**，否则会按错的预期去设计。

开关只在进程启动时读一次，两侧都是：

```java
// CNDebugFlags.ensureLoaded()
if (loaded) return;          // 之后全走缓存
```

```cpp
// MagiaLegacy.cpp
JNI_OnLoad → loadDebugFlags();   // 进程一起就定死
```

而且 `noInitLabelHook` / `noTtfHooks` 这类开关**决定的是「装不装这个钩子」**——
`JNI_OnLoad` 跑完钩子已经装好，运行时改文件毫无意义。

所以悬浮窗给不了「即时生效」，能给的是「**一键写文件 + 一键重启**」。

### 那它到底省了什么

| 现状 | 有了悬浮窗 |
|---|---|
| 要 root（`e2c00727` 收回 debuggable 后 `run-as` 也没了，只能 `su`） | 不要 |
| 要电脑 + adb | 不要 |
| 34 个开关靠记名字手敲 `touch` | 列表勾选 |
| `su` 建的文件属主是 root | app 自己写，属主天然正确 |
| 改完自己去杀进程重开 | 一键走 `CNRestart` |

**第四条是实打实踩过的坑**：`su` 建的 `log/.seq` 属主 root → 应用写不动 → 启动序号
卡在同一个值，排查绕了大半天（见 `CNLog.nextSeq` 的诊断注释）。这一整类问题会
因为「由 app 自己写」而消失。

---

## 为什么是真悬浮窗，不是浮层里再开一页

现有 `CNCNDownloadUI` 挂在游戏 Activity 的 `decorView` 上，代价是：

- 引擎切场景会把 `decorView` 整个换掉，浮层得**反复检测并重挂**
  （`CNCNDownloadUI` 里那段 `if (dv != decorView) { 重新挂 }`）；
- 为了「浮层在的时候别让引擎抢跑」，native 侧配了一整套 `overlayActive` 标记、
  `pushSceneTop` 闸门、BGM 挂起；
- **装完就撤了**——战斗中根本不在，而战斗恰恰是最需要开关的场景。

系统悬浮窗是独立 window，与 `decorView` 无关：上面三条一条都不需要，任何时刻
都够得到。

### 权限

`SYSTEM_ALERT_WINDOW` 是**原包自带**的权限，不是我们加的。

`9688f7e7`（2026-08-13 00:26）把它连同 `MANAGE_EXTERNAL_STORAGE` 和
`android:requestLegacyExternalStorage` 一起删掉，提交信息写作「移除无用的全盘和
悬浮窗权限」，并在 `check-download-ui-contract.py` 里立了一条「不许回来」的断言。
**维护者对这条改动完全不知情**，且它并不「无用」——悬浮窗正是靠它挂
`WindowManager` 窗口。已回退，并把那条断言**反过来**：现在 CI 断言这个权限必须在，
谁再静默删掉当场红灯。

> `MANAGE_EXTERNAL_STORAGE` 与 `requestLegacyExternalStorage` 尚未恢复，
> 待维护者决定——它们同属那次未知情的改动，但和悬浮窗无关。

窗口类型按版本分叉：

```
API ≥ 26   TYPE_APPLICATION_OVERLAY
API 21–25  TYPE_PHONE            （minSdk 21 全覆盖）
```

API 23+ 仍需用户在系统设置里手动授予（`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`），
用 `Settings.canDrawOverlays()` 检查。**这一步不能省，也不该省**——见下。

---

## 🔴 入口：烧在包里的一个布尔

### 先说被否掉的方案（v1 写的就是它）

v1 定的判据是**自举**：悬浮窗只在 `<priv>/debug/enableOverlay` 存在时启用，
想要它就先用 `su` 建一次这个文件，之后其余开关都不用 `su`。理由是「分界一点没变
——能建第一个文件的人本来就能建全部」。

**这个推理成立，结论却没用。** 它漏掉了一个问题：分界该架在哪儿？

> 能建出 `enableOverlay` 的人 = 会 adb/Termux 的人 = **本来就能直接
> `touch debug/skipHotUpdate` 的人**；
> 真正需要悬浮窗的人 = **建不出那个文件的人**。

门槛正好挡住了要服务的那批，放进来的正好是不需要它的那批。这不是假想——
`android:debuggable` 就是这么白开了两天又收回去的（`2de18e15` → `e2c00727`）：
开它是为了让人免 root 抓日志和改开关，收它是因为**这条路根本送不到人**，
要用它得会 Termux，而实际会用的人几乎没有。同一个错误不该犯第二次。

### 定下的判据：编译期常量

```cpp
// MagiaLegacy.cpp
#ifndef MAGIA_DEBUG_OVERLAY
#define MAGIA_DEBUG_OVERLAY 1     // 公测期：所有人可用
#endif
static const bool DEBUG_OVERLAY_ENABLED = (MAGIA_DEBUG_OVERLAY != 0);
```

经 JNI 交给 `CNDebugBridge.overlayAllowed()`，**没有任何运行时手段能改它**。

- **公测期**：`1`，所有人都够得到——这正是它存在的意义；
- **公测结束**：把 `1` 改成 `0` 出包，一步收回，不依赖任何人在设备上做什么；
- **内部测试包**：编译时传 `-DMAGIA_DEBUG_OVERLAY=1` 覆盖，不受对外收回影响。

取不到（native 没起来 / `RegisterNatives` 失败）一律按**关**处理：这是道分界，
宁可少给功能，不能多给。`CNDebugFlags.writeState` 也查这同一个闸——分界写在代码里，
不是只写在界面上，不然收回之后「功能还在，只是不显示」。

### 那支持成本怎么办

开关的边界规则保证它们「只能把客户端退回更接近原包的行为，绝不关掉任何一道安全
判定」，所以不构成**安全**风险。但 `skipInstaller`、`failDownload`、
`failHotUpdateApply` 足以让玩家把自己的安装搞坏，然后来报「装不上」——那是支持
成本，一样要避免。

**对策不是把入口藏起来，而是把状态摆出来**：生效中的开关以小字**常驻在所有页面
之上**（`CNDebugBridge.hudText()`）。

- 玩家自己看得见「我现在处在调试模式」，不会莫名其妙地以为游戏坏了；
- 他截图报错时**我们**也看得见，省掉「你是不是开了什么开关」这一整轮问答；
- 开关只在启动时读一次，所以小字里还要标出「有改动待重启」——勾了没重启是最容易
  让人误判「开关坏了」的时刻。

小字**不跟随悬浮窗的显示与否**：窗收起来了，字照旧。它是保障，不是装饰。

#### 它已经从悬浮窗里搬出去了（2026-08-13）

上面那句「不跟随悬浮窗」原先只做到一半：小字虽然独立于「窗收没收起来」，实现上
却仍是悬浮窗的一个 `WindowManager` 小窗，于是同时被两道闸挡着——native 总闸关掉
就不挂，玩家/开发者撤掉「显示在其他应用上层」权限就挂不上。

第二条尤其致命：**某人开了调试开关，之后顺手回收了悬浮窗权限**。开关是读
`CNDebugFlags` 的**文件**生效的，根本不依赖悬浮窗，所以它照旧生效；只有提示没了。
这不是「功能一起没了」，是「功能还在，指示灯灭了」——恰好在最需要提示的时候失效，
而这正是这行字存在的全部理由。开发者也有忘事的时候。

所以它被拆成独立的 `CNDebugHud`，挂在 Activity 的 `decorView` 上：那是应用自己的
窗口，**不需要任何权限**。代价是它只覆盖本应用画面（悬浮窗能盖住整个屏幕），而这
正好够用——要监测的是这个游戏的行为，截图截的也是这个游戏。

与总闸的关系也随之反过来：**`CNDebugHud` 不看总闸**。总闸管的是「能不能**改**
开关、能不能开面板」，而「有开关正在生效就得说出来」跟能不能改无关。公测结束后
总闸关掉，若某台设备上还留着 flag 文件，这行字照样要出现。

判据钉在三处，谁改回悬浮窗都会当场红灯：

- `CNDebugHud.gatedByOverlayForTest()` 恒为 `false`（`DebugOverlayTest` [9a]）；
- `tools/check-download-ui-contract.py`：`CNDebugHud` 代码里不得出现
  `WindowManager` / `overlayGate`，`CNDebugOverlay` 里不得留下 HUD 的残骸；
- 同一份守卫还钉住**挂载顺序**——先无条件挂提示条，再去问总闸。反过来写的话，
  「总闸问不到」那一支会顺带把提示条也吞掉，等于把缺陷原样搬了个家。

摆不下时的取舍也在这一版定了：开关名是英文小驼峰，开几个就能连成横跨整屏的一长条
（真机反馈原话是「大型滚木」）。限宽到屏宽 3/4、最多两行、超出省略——宁可看不全也
不让它糊住游戏画面，要看全的话面板里有完整列表。

---

## 形态

常驻两样东西：**一行小字**（永远在，见上）和**一个可拖动小球**（点开是面板）。

```
   调试模式：skipHotUpdate · noI18nLabel +2（有改动待重启）   ← 常驻小字，所有页面
                                                                
┌─ 调试 ───────────────────────── ✕ ─┐
│  ▸ 资源            重下 / 停留      │   ← 从下载浮层搬过来的
│      1. …zip                [重下]  │
│      …                              │
│      [ 停留本页 / 进入游戏 ]        │
│  ▸ 开关                             │
│      Java 侧 (15)                   │
│        ☐ skipWebProxy               │
│        ☑ skipHotUpdate      (待重启)│
│      native 侧 (19)                 │
│        ☐ noI18nLabel                │
│        ☑ logI18nMissAll             │
├─────────────────────────────────────┤
│  [ 保存并重启 ]   [ 全部关闭 ]      │
│  [ 分享日志 ]     [ 日志尾巴 ]      │
└─────────────────────────────────────┘
```

要点：

- **勾选只改内存，点「保存并重启」才落盘**——避免手滑一勾就写文件；
- 每行要同时显示「磁盘上」与「正在生效」两个状态（`COL_ON_DISK` / `COL_ON_BOOT`）。
  这两列**不能合并**：勾了没重启时它们不一样，而那正是最容易让人误判
  「这个开关坏了」的时刻；
- 列表**从两侧的开关表生成**，不硬编码——`CNDebugBridge.flagTable()` 已经把
  Java 的 15 个与 native 的 19 个合好了。硬编码一份副本一定会过期，而两边不一致时
  人只会得出「开关坏了」这个错结论；
- **「重下」「停留」从下载浮层搬进来，不留冗余按钮**。浮层那边由
  `CNDownloadUiAssist.overlayTookOver()` 自动让位，判据是「悬浮窗真的挂上了」；
- 「分享日志」「日志尾巴」复用现成的 `CNLogBundle` / `CNLogShareProvider` / `CNLog`
  环形缓冲，接线已在 `CNDebugBridge.shareLog()`；
- 小球默认贴边、半透明；窗口带 `FLAG_NOT_FOCUSABLE`，不抢游戏输入。

### 两种「pending」不是一回事（2026-08-13 补）

上面第一条说「勾选只改内存」，第二条说「勾了没重启要标出来」——这是**两种不同的
未完成态**，压成一个就会同时误导两边的人：

| | 判据 | 出口 | 面板措辞 |
|---|---|---|---|
| **未应用** | 内存 ≠ 磁盘 | 点「保存并重启」才落盘 | `countUnapplied()` |
| **待重启** | 磁盘 ≠ 启动值 | 已经落盘了，重启就生效 | `countPending()` |

由此定下的三条行为：

- **未应用的改动跨面板重开保留**。原先重开一次面板就 `loadFlags()` 覆盖内存，
  改了半天关一次全没了。现在先看 `dirty()`：有未应用的改动就不覆盖，并记一行日志
  说明保留了几项；
- **总览页同时提示两种 pending**，不是只提示待重启——「我明明勾了」和
  「我明明保存了」是两种不同的困惑；
- **总览页自带「应用并重启」和「丢弃」**。原先要进分组才有重启按钮，在最外层改完
  开关的人找不到出口。同一个 `ApplyClick`，不是第二套逻辑。

### 日志预览：一页只留一个滚动容器

`setTextIsSelectable(true)` 会顺带给 TextView 装上 `ArrowKeyMovementMethod`——那本身
就是一个**可滚动且吃触摸**的实现。于是手指落在预览框上时它先把竖直手势消费掉，
外层页面 `ScrollView` 抢不到：真机反馈的「内外两层滑动条打架、吸底不管用」，吸的是
内层、看到的是外层没动。

现在预览框既不可选中也不带 `MovementMethod`，整页只有 `pagescroll` 一个滚动容器，
吸底也吸它。要复制日志有「打包并分享日志」和下载浮层 LOG 面板里的「复制全部」，
不必为此在这里留一个会抢手势的选中态。守卫 `check-download-ui-contract.py` 钉了
「`CNDebugOverlay` 的**代码**里不得出现 `setTextIsSelectable(true)`」——注释里为了
讲清楚原样引用了这个写法，所以那条判据先剥注释再比对。

---

## 明确不做

**不做「运行时即时生效」。**

那要拆掉 `ensureLoaded` 的缓存、把 native 侧改成可重读、还得处理「钩子装了又要卸」。
投入远超收益，而且会把一个排查工具变成新的崩溃来源——我们刚被 i18n 表热重载的
竞态咬过一次（`engineI18nSnapshot` 那次 use-after-free），没有理由在钩子安装这种
更危险的地方重演。

**不做「悬浮窗里改配置」**（线路表、代理、超时等）。那些走 `config.json`，
是云端控制面，不该有第二条本地入口。

---

## 落地情况

| 位置 | 改什么 | 状态 |
|---|---|---|
| `MagiaLegacy.cpp` | `DEBUG_OVERLAY_ENABLED` 总闸；`nativeDebugOverlayEnabled` / `nativeDebugFlagTable` 两个 JNI 导出（`kDebugFlags` 原先只打日志） | ✅ |
| 新增 `CNDebugBridge.java` | 接线层：总闸、合并全表、落盘+重启、HUD 文案、停留/重下/日志转接 | ✅ |
| `CNDebugFlags` | `knownTable()` / `onDisk()` / `writeState()`；写入口查总闸 + 名字白名单 | ✅ |
| `CNDownloadUiAssist` | 悬浮窗**真的挂上**时撤掉浮层里的「停留」「重下」，否则原样留着 | ✅ |
| `CNDownloaderFix.triggerInstaller` | 总闸开着才起线程等 Activity，然后在 UI 线程挂载 | ✅ |
| `AndroidManifest.xml` | 恢复被静默删掉的 `SYSTEM_ALERT_WINDOW` | ✅ |
| `check-debug-flag-boundary.py` | 保护区禁止 `CNDebugBridge` / `CNDebugOverlay` | ✅ |
| `check-download-ui-contract.py` | 断言 `SYSTEM_ALERT_WINDOW` **必须在**（原断言是反的） | ✅ |
| `tools/DebugBridgeTest.java` | 27 条：总闸 fail-closed、接管判据、HUD 排版、名字白名单 | ✅ |
| 新增 `CNDebugOverlay.java` | **悬浮窗本体**：`WindowManager` 挂载、拖动、页面树、权限引导（常驻小字已迁出，见下一行） | ✅ |
| 新增 `CNDebugHud.java` | **常驻小字**：挂 `decorView`、零权限、**不看总闸**；从悬浮窗里拆出来的理由见「那支持成本怎么办」一节 | ✅ |
| `CNDownloaderFix` | 提示条**无条件**挂一次（`hudMounted`），且在问总闸**之前**——顺序反了就等于把缺陷搬了个家 | ✅ |
| `tools/DebugOverlayTest.java` | [9a–9c]：提示条不受权限/总闸约束、悬浮窗本体仍归总闸管、无开关时整行隐藏 | ✅ |

### 本体接进来时要做的两件事

1. 提供 `public static boolean mount(Activity)`——`CNDebugBridge.mount()` 用反射找它
   （接线先于本体落地，直接引用编译不过；反射还顺带挡住「类漏进 dex 分组」那种
   静默缺席，见 `CNBgm` 那次）。
2. 挂上/摘掉时调 `CNDebugBridge.setActive(true/false)`。浮层靠这个判据让位——
   判据是「**真的挂上了**」而不是「允许挂」：本体没实现、权限没授予、挂载抛异常，
   任何一种情况下浮层里的「停留」「重下」都必须原样留着，否则玩家卡住时会连自救
   手段一起失去。

### 与边界检查的对齐

`check-debug-flag-boundary.py` 原先断言「7 个保护区内不出现 `CNDebugFlags` /
`g_dbg*`」。悬浮窗只是这些开关的**另一个写入口**，不改变开关本身能做什么，所以
现有 7 个保护区的判据没动，只是把 `CNDebugBridge` / `CNDebugOverlay` 加进同一条
禁止名单——否则等于开了一条「界面上点一下就能碰安全判据」的路，而且是比建文件更
好点的那种。（已用反面用例验过：往 `CNSafeLink` 里塞一行 `CNDebugBridge` 会被拦下。）

---

## 验收

接线部分（已可验）：

- 总闸关（`-DMAGIA_DEBUG_OVERLAY=0`）时：不起线程、不挂窗、`writeState` 拒绝写盘，
  行为与现在**逐行一致**；
- native 库没起来 / `RegisterNatives` 失败时：按「关」处理，不崩；
- `DebugBridgeTest` / `PathsTest` / `check-debug-flag-boundary.py` /
  `check-download-ui-contract.py` 全过。

本体接进来后要验（需真机）：

- 未授予悬浮窗权限：引导到系统设置，**不崩、不卡**；
- 勾选后不点保存就退出：磁盘无变化；
- 保存并重启：开关文件的存在与否与勾选状态一致，且**属主是应用**（不是 root）；
- 战斗中能拖出小球，不抢游戏触摸；
- 悬浮窗挂上后，下载浮层里不再有重复的「停留」「重下」；摘掉后它们回来；
- 有开关生效时，小字在**所有页面**（含战斗）都在；勾了没重启时标出「有改动待重启」。
