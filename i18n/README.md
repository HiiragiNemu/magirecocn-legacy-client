# 汉化对照表

游戏里的一句日文，要改哪里，取决于它是**谁渲染的**。这是三条完全不同的链路，
用错一条，活白干：

| 谁渲染 | 改哪里 | 怎么下发 | 源在哪 |
|---|---|---|---|
| WebView（前端一半） | `frontend-strings.tsv` 等四张表 → 回填进前端代码 | 热更包 `cn_js_update.zip` | 补丁仓库 `i18n/`，见下 |
| cocos2d 原生引擎 | `engine_i18n.tsv`（文本 hook 的翻译表） | 热更包 `cn_js_update.zip` 内的 `madomagi/engine_i18n.tsv` → `<files>/madomagi/` | 补丁仓库，见下 |
| 烘焙进 PNG／plist 图集的文字 | 只能改图片资源 | 资源包 | 无 |

两条链路的译文源都在补丁仓库（`（外部发布渠道）`）：前端四张表在
`i18n/`，引擎表在 `madomagi/engine_i18n.tsv`。本目录不放任何对照表，只记
**判定方法和操作步骤**——客户端这边才有 hook 和调试开关，判据只能在这儿产生。

---

## 一句日文该归哪一层：不要猜，有判据

补 `engine_i18n.tsv` 和改前端热更包是两个方向完全不同的修法，猜错整批活白干。
2026-08-08 之前这件事**没有判据可用**：文本 hook 只在命中时打日志，未命中一声不吭，
所以串不在表里时，它有没有流经 native 标签，日志长得一模一样。

现在有了。`logI18nMiss` 调试开关会把「流经 hook 却没翻到」的串打出来：

```bash
adb shell "run-as io.kamihama.totentanz mkdir -p debug"
adb shell "run-as io.kamihama.totentanz touch debug/logI18nMiss"
# 重启游戏，把要查的流程走一遍，然后：
adb logcat -d -s MagiaCN_Legacy | sed -n 's/.*\[i18n-miss\]\[[^]]*\] //p' | sort -u
```

- **清单里有这句** → 走 native 标签，补 `engine_i18n.tsv` 就行，而且热重载生效。
- **没有** → 不经过 native 标签，去前端热更包或服务端那层找。

开关有两个：`logI18nMiss` 只记**含假名**的串（默认，噪音低）；`logI18nMissAll`
不筛内容，用来确认拉丁字母/纯数字的串走没走 native 标签。详见 README 的
「调试开关目录」一节。

---

## 战斗相关文本怎么汉化

### 结论（2026-08-08 实测）

战斗中与战斗结束的角色台词**走 native 标签**，补 `engine_i18n.tsv` 即可，
不需要重出 APK，也不需要动热更包。

判定过程留档，因为这类结论没有实证就会被反复重新猜一遍：

1. 现象：战斗结束（Battle Clear，WAVE 2/2）时说话人的台词
   「カーテンコールで終いやな」是日文，而**同一场战斗中**的台词
   （焰「（几乎跟魔女之夜一样……）」）是中文。
2. 排除机制故障：日志里 `[i18n] 已加载 295 条 + 2 前缀规则`、6 个 hook 全部
   `✓`、本局实际替换 10 次；台词包 `server=3214 local=3214` 已是最新。
   所以不是「汉化没生效」，是**这句不在任何一张表里**。
3. 判定链路：往设备上的 `engine_i18n.tsv` 追加一行，3 秒内热重载，再打一场
   —— 变成中文了。**证毕：走 native 标签。**

> 顺带一个还没查的：截图里说话人名字显示为拉丁字母 `Livia Medeiros`，
> 既不是日文原文（リヴィア・メデイロス）也不是中文译名。`logI18nMiss` 看不见
> 它（不含假名），需要开 `logI18nMissAll` 跑一局确认它走不走 native 标签。
> 若不走，它多半属于「服务端直接返回、未经注入器的字段」——README 那张分层表里
> 唯一标「未做」的一行。

### 操作步骤

```bash
# 1. 收集这一局所有该翻没翻的串（骨架已经是 tsv 行格式）
adb shell "run-as io.kamihama.totentanz touch debug/logI18nMiss"
# 重启，打一场，然后：
adb logcat -d -s MagiaCN_Legacy | sed -n 's/.*\[i18n-miss\]\[[^]]*\] //p' | sort -u \
  > /tmp/miss.tsv

# 2. 填译文。每行形如  #原文<TAB>   —— 翻一条，去掉行首的 #，把译文补在 TAB 后

# 3. 推回设备验证（表每 3 秒查一次文件身份指纹，不用重启游戏）
adb push /tmp/miss.tsv /sdcard/miss.tsv
adb shell "run-as io.kamihama.totentanz sh -c \
  'cat /sdcard/miss.tsv >> files/madomagi/engine_i18n.tsv'"
```

> 🔴 **行首那个 `#` 不是装饰。** 这张表里「译文为空」的语义是**删除该串**，
> 不是「还没翻」。所以未填译文的骨架行不是惰性的——不带 `#` 直接追加，这些串会
> 当场从界面上消失，而且是在没人改过译文的情况下悄悄发生。所以日志输出默认带
> `#`，翻一条放开一条。

这不是理论上的脚坑，表里正用着。补丁仓库那份的第 41–42 行：

| 行 | 原文 | 译文 |
|---|---|---|
| 41 | `敵から受けるダメージが ` | `受到敌方的伤害提升 ` |
| 42 | `上昇する` | *（空）* |

日文把一句话拆成「受到的伤害」+ 句尾动词「上昇する」两段拼接；中文的「提升」
已经并进第一段，第二段**必须删掉**，否则界面上会多出一个「上昇する」。这就是
`loadEngineI18n()` 里 `if (!ja.empty()) fresh[ja] = zh;`——`zh` 是空串也照存，
两条查找路径随后都会拿它当译文塞回引擎。**每次加载都在执行这个语义**，
所以一个手滑的空译文和一次有意的删除，在加载器眼里没有任何区别。

> 上面这套是**在设备上就地验证**，改的是设备上那份副本，下次热更会被覆盖。
> 验证通过之后，把同样的行提交到补丁仓库 `（外部发布渠道）` 的
> `madomagi/engine_i18n.tsv`（去掉 `#`），推上去就会自动重打 JS 热更包并下发——
> 见下一节。

---

## `engine_i18n.tsv` 的源与下发链路

**源不在本仓库**，在补丁仓库：

```
（外部发布渠道）  →  madomagi/engine_i18n.tsv     ← 译文改这里
```

`（外部发布渠道）` 是它的组织下游，跑
`.github/workflows/sync-and-upload.yml`：同步上游 → 打包 → 传 S3。整条链路：

```
上游改 madomagi/engine_i18n.tsv
  └─ 下游同步，detect 步骤把该路径归入 JS，而不是 scenario
      └─ HAS_JS=1 → 重打 cn_js_update.zip
          （ZIP 根目录同时包含 magica/ 与 madomagi/engine_i18n.tsv）
          └─ version_js.json 版本号自 configures/ 递增 → 上传 S3
              └─ 客户端 CNHotUpdateCheck 比对版本 → 下载 → 解到 <files>/
                  └─ madomagi/engine_i18n.tsv 就位，native hook 3 秒内热重载
```

也就是说**改一句译文只要在补丁仓库改一行**，剩下的全自动，客户端这边一行代码
都不用动，也不用重出 APK。

> 🔴 **不要在本仓库建 `i18n/engine_i18n.tsv`。** 那会造出第二个源，两边一分叉，
> 谁也说不清哪份是真的——而这张表「译文为空 = 删除该串」的语义会让分叉直接表现为
> 界面上的文字消失。要改译文就去补丁仓库改。

几条对得上的旁证（2026-08-09 核对）：

- 补丁仓库迁移前基线是 299 个逻辑行 = 1 行注释 + 296 条精确条目
  + 2 条前缀规则；设备在现场验证前的备份与它按换行归一化后逐字一致。
  现场追加的官方文案仅用于验证，下次热更可被覆盖，不能据设备行数反推权威源。
- 解压根是 `<应用数据目录>/files/`（`CNHotUpdateCheck.FILES_DIR`，经 `CNPaths`
  动态解析，正规设备上即 `/data/data/io.kamihama.totentanz/files/`），
  所以包内路径 `madomagi/engine_i18n.tsv` 正好落到 `MagiaLegacy.cpp` 的
  `ENGINE_I18N_PATH`。
- 它**不会被孤儿清理误删**：`CNHotUpdateTx.cleanupPrefixes("scenario")` 只清
  `madomagi/resource/scenario/json/`，`cleanupPrefixes("js")` 只清 `magica/` 下四个
  白名单前缀；该表（`madomagi/engine_i18n.tsv`）在两者之外。
  该表随 **JS** 热更通道下发（2026-08-10 起由 scenario 通道迁入：
  detect 把它归入 `^(magica/|madomagi/engine_i18n\.tsv)`，JS 打包经
  `_pack_js` 暂存把 `madomagi/engine_i18n.tsv` 一并打进 `cn_js_update.zip`）。
  scenario 包里仍带一份兜底副本，防「只发台词包、JS 包未重打」时全新安装
  拿不到表；客户端解压顺序是 scenario 先、JS 后，JS 包内新副本永远最后落地。

---

## `engine_i18n.tsv` 格式

每行 `原文<TAB>译文`，UTF-8，被 `MagiaLegacy.cpp` 的 `loadEngineI18n()` 读取。

| 写法 | 含义 |
|---|---|
| `日文<TAB>中文` | 精确替换 |
| `^日文前缀<TAB>中文前缀` | **前缀规则**：命中后换掉前缀、保留后缀。用于尾部带变量的文案，如「ネットワーク接続に失敗しました。\nエラーコード：1」 |
| `日文<TAB>`（译文为空） | **删除该串**。拼接式文案调语序时用，**不是**「还没翻」 |
| `#` 开头 | 注释，整行跳过 |
| `\n` `\t` `\\` | 换行／制表／反斜杠的转义（原文和译文两侧都适用） |

行为要点：

- **热重载**：启动时加载一次，之后每 3 秒节流检查一次文件身份指纹
  （device/inode/size/mtime 纳秒）。同一秒内原子换名或原地更新也会被识别，改完免重启；
  读取期间文件若变化，会保留上一份完整快照并在下一轮重试。
- **没有 TAB 的行**算坏行，会计入启动日志的「坏行 N」，但不影响其余条目。
- **前缀命中不限字符集**：表里显式声明的日文、纯英文或纯汉字前缀都必须
  扫描。只有「默认缺译日志」会用假名减少噪音；该日志筛选不得进入替换路径。
- native 收到的 `std::__ndk1::string` 是三个机器字：ARM64 为 24 字节、短串上限
  22，ARMv7 为 12 字节、短串上限 10；读取偏移必须由机器字宽度派生，不能把
  ARM64 的 `+8/+16/22` 写死到双 ABI 源码中。CI 的
  `check-native-string-layout.py` 与单元测试钉住这条合同。
- 命中的 hook 入口共 6 个：`cocos2d::Label::setString`、`LabelAtlas::setString`、
  `MenuItemLabel::setString`、`LoadingSceneLayerInfo::setText` / `setTitle`、
  `LbUtility::initLabel`。前五个收 `std::string`，最后一个收 `const char*`。

---

## 前端四张表

**源已迁入补丁仓库** `（外部发布渠道）` 的 `i18n/`（2026-08-10，
本仓库不再存放副本，避免双源分叉）。由 `tools/i18n-*.py` 消费，链路是
`i18n-extract.py`（抽串）→ 人工／`i18n-glossary.py` 填译文 → `i18n-apply.py`
（回填进前端代码）→ `i18n-fragments.py`（片段改写）→ `i18n-package.py`
（打成 `cn_js_update.zip`）。

| 文件 | 列 | 干什么 |
|---|---|---|
| `frontend-strings.tsv` | 原文／译文／风险／出现次数／出现于 | 主表。前端所有日文字面量，1686 行 |
| `glossary.tsv` | 日文／中文 | 术语表，从中文 Wiki 的术语模板提取，953 行 |
| `overrides.tsv` | 文件前缀／原文／译文 | 同一原文在不同界面含义不同时按文件点名覆盖（如「サポート」= 辅助／支援） |
| `fragments.tsv` | 文件前缀／原始片段／替换片段 | 跨节点整段改写，解决整串替换够不到的语序问题（日文宾语前置、「数+动」） |

每张表的表头注释里写了它自己的判据和存在理由，改之前先读那几行。

回填铁律是**只换整条字面量**（见 `i18n-apply.py`）：不做子串替换，否则汉化会
渗进变量名和 URL 里。语序问题一律走 `fragments.tsv`，不要手改压缩后的 JS
——那些改动会被流水线重跑冲掉。
