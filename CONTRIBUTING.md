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
   （仓库变量 `NOTIFY_URL` / `NOTIFY_FROM` / `NOTIFY_FROM_NAME` /
   `NOTIFY_TO` + secret `NOTIFY_TOKEN`，宝塔 webhook 格式）时同步发信；
2. 红灯期间：只允许修复主线的提交，新功能开发本地继续但**不许 push**；
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
