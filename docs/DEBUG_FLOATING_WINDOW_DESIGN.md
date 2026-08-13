# 调试悬浮窗方案

> 状态：**方案，未实现**。写在动手之前，因为它碰到一条既有边界，值得先把判据定死。

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
| 要 root（`run-as` 对正式包无效，只能 `su`） | 不要 |
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

### 权限门槛已经没有了

```xml
<!-- 原包 AndroidManifest.xml 里本来就有 -->
android.permission.SYSTEM_ALERT_WINDOW
```

不用改 manifest、不用新增权限。窗口类型按版本分叉：

```
API ≥ 26   TYPE_APPLICATION_OVERLAY
API 21–25  TYPE_PHONE            （minSdk 21 全覆盖）
```

API 23+ 仍需用户在系统设置里手动授予（`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`），
用 `Settings.canDrawOverlays()` 检查。**这一步不能省，也不该省**——见下。

---

## 🔴 边界：入口必须自举，不能对所有玩家可见

`CNDebugFlags` 的类注释写着现有分界：

> 这里在非 root 的正式包上**玩家碰不到**（`run-as` 只对 debuggable 包有效），
> 所以不构成面向普通玩家的风险面；而有能力自查的人拿 root 或 debuggable 包就能用。
> 这正是想要的分界。

**悬浮窗会把这条分界拆掉。** 虽然开关的边界规则保证了它们「只能把客户端退回更接近
原包的行为，绝不能关掉任何一道安全判定」，所以不构成**安全**风险——但
`skipInstaller`、`failDownload`、`failHotUpdateApply` 足以让玩家把自己的安装搞坏，
然后来报「装不上」。那是支持成本，不是安全问题，但一样要避免。

### 定下的判据：用现有机制自举

**悬浮窗只在 `<priv>/debug/enableOverlay` 存在时才启用。**

想要悬浮窗，先用 `su` 建一次这个文件；建完之后，其余 34 个开关就都不用 `su` 了。

- 分界**一点没变**：能建第一个文件的人，本来就能建全部 34 个；
- 痛点**解决了**：那 34 次重复操作降到 1 次；
- 判据与现有机制**同构**：还是「debug 目录里有没有这个文件」，不引入第二套概念。

> 不选「长按 LOG 胶囊 5 秒 + 连点版本号 7 次」这类彩蛋式入口：它把分界从
> 「有没有 root」换成了「知不知道咒语」，而咒语一定会传出去。

---

## 形态

一个可拖动小球，点开是一页列表：

```
┌─ 调试开关 ────────────────── ✕ ─┐
│  Java 侧 (15)                    │
│    ☐ skipWebProxy                │
│    ☑ skipHotUpdate               │
│    …                             │
│  native 侧 (19)                  │
│    ☐ noI18nLabel                 │
│    ☑ logI18nMissAll              │
│    …                             │
├──────────────────────────────────┤
│  [ 保存并重启 ]   [ 全部关闭 ]    │
│  [ 分享日志 ]     [ 日志尾巴 ]    │
└──────────────────────────────────┘
```

要点：

- **勾选只改内存，点「保存并重启」才落盘**——避免手滑一勾就写文件；
- 列表**从两侧的开关表生成**，不硬编码。Java 侧读 `CNDebugFlags.KNOWN`；
  native 侧的 19 个需要一份可读的表（见下「要动的地方」）；
- 「分享日志」直接复用现成的 `CNLogBundle` / `CNLogShareProvider`；
- 「日志尾巴」复用 `CNLog` 的环形缓冲，只读不写；
- 小球默认贴边、半透明；窗口带 `FLAG_NOT_FOCUSABLE`，不抢游戏输入。

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

## 要动的地方

| 位置 | 改什么 |
|---|---|
| 新增 `CNDebugOverlay.java` | 悬浮窗本体：`WindowManager` 挂载、拖动、列表、落盘、重启 |
| `CNDebugFlags` | 开放一个只读的「全表 + 当前状态」查询；新增 `ENABLE_OVERLAY` 常量 |
| `MagiaLegacy.cpp` | 把 `kDebugFlags` 的名字与描述经 JNI 暴露给 Java（现在只打日志） |
| `CNDownloaderFix.triggerInstaller` | 启动时若 `enableOverlay` 存在，挂上小球 |
| `tools/check-debug-flag-boundary.py` | 保护区列表加上 `CNDebugOverlay`：**悬浮窗自身不得出现在任何安全判据里** |

### 与边界检查的对齐

`check-debug-flag-boundary.py` 现在断言「7 个保护区内不出现 `CNDebugFlags` /
`g_dbg*`」。悬浮窗只是这些开关的**另一个写入口**，不改变开关本身能做什么，所以
现有 7 个保护区的判据不用动。

但要**新增一条**：`CNDebugOverlay` 不得出现在保护区内——否则等于开了一条
「界面上点一下就能碰安全判据」的路。加进同一张表即可，形状与现有条目一致。

---

## 验收

- 无 `enableOverlay` 时：小球不出现，行为与现在**逐行一致**；
- 有 `enableOverlay` 但未授予悬浮窗权限：引导到系统设置，**不崩、不卡**；
- 勾选后不点保存就退出：磁盘无变化；
- 保存并重启：34 个开关文件的存在与否与勾选状态一致，且**属主是应用**；
- 战斗中能拖出小球，不抢游戏触摸；
- `check-debug-flag-boundary.py` 通过（含新增的那条）。
