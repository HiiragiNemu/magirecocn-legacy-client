# 开发者参与

补丁层完全开源，CI 可复现构建。欢迎从 issue 定位一路做到 PR。

## 仓库

[代码仓库](https://github.com/MagirecoCN-Revival-Project/legacy-client)

这不是从零写的客户端：基础 APK（`io.kamihama.totentanz`）作为基线，
CI 可重新构建；我们的改动集中在**补丁层**：

```
patch/src/main/java/io/kamihama/magianative/   ← Java 补丁，唯一事实来源
magia-native/src/MagiaLegacy.cpp               ← native hook（i18n/字体/启动链）
smali*/                                         ← 引擎本体（基本不动）
tools/                                          ← 测试套件与构建前置检查
```

## 本地验证

提交前至少跑过这些（CI 会复验）：

```bash
bash tools/check-native-syntax.sh          # native 语法预检（桩头文件，无需 NDK）
python3 tools/check-native-string-layout.py   # 双 ABI string 布局合同
python3 tools/check-engine-i18n-prefix.py     # i18n 前缀规则合同
python3 tools/check-base-urls.py              # 热更地址一致性
python3 tools/check-debug-flag-boundary.py    # 调试开关边界
```

## 协作流程（主干开发）

- 所有人直接提 `main`；分支只有 `hotfix/*`（修红灯）与 `surgery/*`
  （核心层大手术）两类例外，寿命以小时/天计，归档打 tag 后即删。
- **一个逻辑改动一个 commit**，Commit message 用 Conventional Commits
  （`feat(hook):` / `fix(inject):`），正文说明改动理由，并带一行 `文档: …`
  声明文档影响（commit-msg 钩子强制）。
- 开工前读 [CONTRIBUTING.md](https://github.com/MagirecoCN-Revival-Project/legacy-client/blob/main/CONTRIBUTING.md)，
  并在 [ACTIVE.md](https://github.com/MagirecoCN-Revival-Project/legacy-client/blob/main/ACTIVE.md)
  登记你在动哪片（10 秒成本，消灭对撞）。
- CI 守门：push 复验 → 全量回归 → 通过自动打 `last-green` 锚点；
  红灯期间只允许修复主线的提交。
- 半成品功能用**调试开关目录**隔离，代码进 main、功能默认关。

## 翻译贡献

- 引擎硬编码串（弹窗、战斗台词、下载/网络错误）：改 `engine_i18n.tsv`，
  随 JS 热更包下发，不用重出 APK。那张表不在本仓库，走反馈通道联系维护者。
- 前端（WebView 一半）：本仓库 `i18n/` 下四张 TSV 对照表。
- 判一句日文该归哪一层：[i18n/README.md](https://github.com/MagirecoCN-Revival-Project/legacy-client/blob/main/i18n/README.md) 有判据，别猜。

## 提 PR 前

1. issue 里先说清楚要做什么，避免撞车（或看 ACTIVE.md）。
2. 改动路径决定验证级别：动了核心 hook / 注入时序 / WebView 桥的，
   需要实机测试记录。
3. 一个 PR 只做一件事。
