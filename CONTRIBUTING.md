# CONTRIBUTING.md — 高并发协作方案（主干开发定制版）

> 本文件是「怎么一起干活」的约定；技术档案看 [README.md](README.md)，
> Agent 硬约束看 [AGENTS.md](AGENTS.md) / [CLAUDE.md](CLAUDE.md)。
> 三者有冲突时：CLAUDE.md > AGENTS.md > 本文件。

## 一、分支结构：一根主线 + 两类例外

```
main（唯一长期分支，已保护：禁 force push / 禁删除）
├── 日常开发：所有人直接提交
├── 例外1：hotfix/* —— 修主线红灯，寿命以小时计
└── 例外2：surgery/* —— 核心 hook/注入层大重构，寿命 ≤ 3 天
```

- 禁止第三种分支存在，feature 分支全部用 feature flag 替代（见 §三）；
  （三个具名临时例外见 AGENTS.md §0 表注，合并即删）；
- `surgery/*` 开工前必须在群里吼一声 + 在 [ACTIVE.md](ACTIVE.md) 登记，
  完工立即合入删除；
- `hotfix/*` / `surgery/*` 用完即退役（hotfix 超 24 小时、surgery 超 3 天
  会被 `tools/check-branch-hygiene.py` 点名）；
- **分支退役不许直接删**：先打 `archive/<原分支名>` tag 存档再删——
  用 CI 干这个事：Actions →「🗄️ 归档分支为 tag」（workflow_dispatch，
  它会先打 tag、验证推上远端，然后才删分支）。`archive/*` tag 不可变，
  不存在 `archive/*`、`research/*` 这类长期挂着的**分支**。

## 二、提交规则（给 Agent 的硬性约束）

1. **一个逻辑改动一个 commit**，禁止攒批量提交
   （攒提交 = 把 rebase 冲突攒成炸弹）；
2. 提交前必过：本地静态检查 + 编译（命令见 AGENTS.md §3）；
3. **分层验证**——按改动路径决定验证级别：

   | 改动路径 | 进入 main 的要求 |
   |---|---|
   | 文档、脚本、配置 | 静态检查 + 编译 |
   | 调试选项、UI、外围功能 | + CI 全量检查 |
   | 核心 hook、注入时序、WebView 桥 | + **实机测试** |

   CI（`main-checks.yml` 的 tier-hint 任务）会按本次 diff 的路径自动判定
   级别并写进运行摘要。实机测试 CI 替代不了，级别 3 必须有人在真机上跑过
   才算完。
4. `git config pull.rebase true` + `rebase.autoStash true` +
   `rerere.enabled true`，每个 Agent 开工第一件事就是 rebase 到最新 main；
5. Commit message 用 **Conventional Commits 前缀 + 中文描述**，
   时差交接全靠它：

   ```
   feat(hook): 战斗文本钩子加前缀规则
   fix(inject): 修复注入时序空指针
   refactor(webview): 拦截层路径解析换 CNPaths
   ```

   允许的 type：`feat` `fix` `refactor` `docs` `test` `chore` `ci` `perf`
   `build`。scope 自选（hook / inject / webview / ui / i18n / ci …）。
   中文描述与 `Co-authored-by`、「文档:」交代的要求不变（commit-msg 钩子
   照旧拦）。
6. **署名**：作者固定为本仓库 git config 的维护者身份；实际执笔的 Agent
   必须在末尾加 `Co-authored-by: Name <email>`（完整格式，钩子硬拦）。
   已登记的署名表见 AGENTS.md §1 三，新 Agent 首次执笔时随同一个提交
   登记进表。

## 三、Feature Flag 规范

所有「牵一发动全身」的新功能，**代码进 main、功能默认关**。

本仓库不另造 flag 系统——直接用已有的调试开关机制（`CNDebugFlags` /
`<数据目录>/debug/<开关名>`，native 侧对应 `g_dbg*`），它本来就是干这个的：

- flag 打开 = 功能完成的定义，打开动作本身是一个独立 commit；
- 稳定运行一周以上才允许删除旧代码路径和 flag；
- 🔴 边界不变（`check-debug-flag-boundary.py` 钉死）：flag 只能把客户端
  退回更接近原包的行为，**绝不能关掉任何一道安全判定**。

## 四、CI 流水线

```
push 到 main
 ├── main-checks.yml
 │    ├── 复验：完整构建流水线（verify_only，不上传产物）
 │    ├── 并发预警：改动文件 24h 内有他人改动 → 提交下留言（不阻断）
 │    └── 分层提示：按 diff 路径给出验证级别（1/2/3）
 └── last-green.yml（串行，不取消）
      ├── 全量回归：tools/ 测试套件 + 全部守卫脚本
      ├── 通过 → 自动移动 tag `last-green` 到本次提交
      └── 失败 → 🔴 红灯流程（见 §五）
```

- `last-green` 是「时空锚点」：任何人拉到坏代码，
  `git checkout last-green` 立刻回到稳定点，不用等修复；
- APK 构建/发版仍然**只手动** `workflow_dispatch`（铁律 6 修订后仅豁免
  检查类 workflow 的 push 触发；tag 是内部锚点，不算自动发版）；
- 产物只进 Artifacts，不进仓库。

## 五、红灯协议（main 挂了的处理 SOP）

1. 全量回归失败 → CI 自动在失败提交下留言标记 🔴；配置了邮件通知
   （仓库变量 `NOTIFY_URL` / `NOTIFY_FROM` / `NOTIFY_FROM_NAME` +
   secret `NOTIFY_TO` / `NOTIFY_TOKEN`，宝塔 webhook 格式）时同步发信；
   收件人走 secret 不明文示人；
   `NOTIFY_TO` 支持逗号/分号/空格分隔的多个收件人，逐人各发一封。
   并发预警触发（24h 内同文件有他人改动）时另发 🟡 黄灯邮件——
   **黄灯只发本次 push 的 commit 作者**（取 author 邮箱，不含 Co-Author），
   不惊动全员；**红灯才发 `NOTIFY_TO` 全体收件人**；
   **无预警、全绿都不发邮件**。
   邮件发送统一走 `tools/notify-mail.sh`，发送失败只打 warning 不阻断；
2. 红灯期间：只允许修复主线的提交，新功能开发本地继续但**不许 push**。
   这条由 pre-push 钩子的红灯闸门执行：推 main 前联网查最近一次全量回归
   的结论，红灯且新增提交不全是修复类（`fix(`/`fix:`/`revert` 开头）就
   拦下；查不到状态时放行并提示。客户端钩子只管得住装了钩子的克隆，
   属「机器提醒 + 自觉」级，逃生口 `SKIP_REDLIGHT_HOOK=1`；
3. 引入者负责修，修不了就 `git revert` 先恢复绿灯，问题回炉；
4. 找不到引入者 → `git bisect` 从 `last-green` 二分定位。

## 六、最低限度沟通机制

- [ACTIVE.md](ACTIVE.md)（仓库根目录）：每人/每个 Agent 开工时加一行
  `日期 | 人 | 在搞什么 | 预计动哪片`，完工删行。10 秒成本，消灭最严重
  的对撞；
- 群公告约定：**红灯、surgery 分支开工、flag 打开**，三件事必须发群，
  其余免沟通。

## 七、与既有规则的关系

- AGENTS.md §0 的分支白名单就是 `main` / `hotfix/*` / `surgery/*` 三类
  （另有三个具名临时例外，见该节表注），其余纪律（默认不开分支、
  全会话一条、不用分支触发 CI）不变；
  历史分支已归档为 `archive/*` tag（2026-08-09），退役分支统一走
  「🗄️ 归档分支为 tag」CI；
- CLAUDE.md 铁律 6 修订为：「不做自动发版——APK 构建/Release 只手动；
  检查类 workflow（main-checks / last-green）允许 push 触发」。

## 八、非主线分支发版强制约定（禁止更改）

> 🔒 **本约定是硬规则，禁止修改**。受仓库 branch ruleset 保护（禁 force
> push / 禁删除分支），任何改动必须经维护者批准后提 `main`，再推送全部分支。

### 适用范围

所有**非 `main` 分支**构建、独立对外发版（可安装 APK 分发）的客户端版本，
包括但不限于 `surgery/single-thread-reliable-20260813`（单线程版）等
`hotfix/*` / `surgery/*` 分支产物。`main` 分支构建的版本不受本约定约束。

### 规则一：独立发版必须登记「停止支持开关」

非主线分支版本**只要对外发版**，发布动作里必须同步更新线上
`config.json`（<https://api.example.test/legacy/config.json>，仓库根
`config.json` 是快照，改线上那份），在 `client` 段旁登记该分支版本并
加「停止支持开关」：

```json
"branch_versions": {
  "single-thread": {
    "supported": true,
    "mainline_apk_url": "https://assets.example/magireco-latest-legacy-client.apk"
  }
}
```

- `supported: true` = 该分支版本仍在分发、仍受支持（发版时必须为 true）；
- `mainline_apk_url` = 主线（`main` 分支）最新版 APK 的下载链接；
- 条目缺失或 `supported: false` = 该分支版本已停止支持。

发版顺序：**先登记开关再分发**。忘了登记就发版 = 违反本约定，按红灯协议
处理。

### 规则二：主线跟上后关闭开关，强制推送主线最新版

当 `main` 分支版本的功能**覆盖**该分支版本（主线已包含其改进，玩家无需
再装分支版）后，发布负责人必须：

1. 把 `config.json` 里该分支的 `supported` 改为 `false`（或移除条目）；
2. **强制推送主线最新版本下载链接**——`mainline_apk_url` 指向主线最新
   APK（保持与 `client.apk_url` 一致），分支版客户端因此被引导升级到
   主线版本；
3. 分支版本按 §一 走「归档分支为 tag」流程退役。

开关关闭后，分支版客户端不得再收到该分支的新构建分发；再次分发即违反
本约定。

### 违反的后果

- 非主线分支版本未登记开关即发版 → 视为发布事故，发布者负责回滚/补登记，
  按 §五 红灯协议追责；
- 修改本约定 → 被 ruleset 拒绝（force push / 删分支），且破坏约定本身
  比破坏任何功能代码都严重。

### 禁止一次性 Workflow（2026-08-13 维护者补充）

- **禁止新建任何 `*-once` / 一次性 workflow 文件**（如 `apply-*-once.yml`、
  `generate-*-once.yml`）。workflow 是长期资产，不是脚手架；
- 一次性迁移/生成逻辑写进 `tools/` 下脚本（可带 `--once` 参数），或并入
  现有主 workflow 的步骤，不得以独立 workflow 文件入库；
- 违反视为与 §八 同级的事故，审查会拦。

### 生效范围

本约定随本文件推送到全部活跃分支（含 `main`）。分支内容与该分支无关的
场景（如一次性 research 分支）也须保留本约定章节，不得删除。
