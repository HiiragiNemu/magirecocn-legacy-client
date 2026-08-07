#!/usr/bin/env bash
# 安装本仓库的 git 钩子。
#
# **多数情况用不上这个脚本**：.claude/settings.json 与 .codex/config.toml 各注册了
# 一个 PreToolUse(Bash) 钩子指向 tools/agent-guard.py，它会在第一条 Bash 命令执行前
# 自动做完下面这件事。只有「Claude 与 Codex 都没碰过这份克隆」（比如人类直接
# git clone 下来手敲）时才需要手动跑一次。
#
# 钩子放在 tools/githooks/（受版本控制），靠 core.hooksPath 指过去——
# 这样它们跟着仓库走，而不是躺在每个人各自的 .git/hooks 里自生自灭。
#
#   tools/githooks/commit-msg  提交信息规范（中文 / Co-authored-by / 文档:）
#   tools/githooks/pre-push    分支纪律（AGENTS.md §0：别再乱开分支）
#
# 跳过单次检查：
#   提交信息里顶格独占一行写 [skip-hooks]  跳过 commit-msg
#   SKIP_BRANCH_HOOK=1 git push ...  跳过 pre-push
#
# 卸载：git config --unset core.hooksPath

set -euo pipefail
cd "$(dirname "$0")/.."

chmod +x tools/githooks/*
git config core.hooksPath tools/githooks

echo "✔ 已把 core.hooksPath 指向 tools/githooks"
echo
echo "  生效的钩子："
for h in tools/githooks/*; do
  [ -f "$h" ] && printf "    %-12s %s\n" "$(basename "$h")" \
    "$(sed -n '3s|^"""||p;3s|。.*||p' "$h" 2>/dev/null | head -1)"
done
echo
echo "  自检： git config --get core.hooksPath"
