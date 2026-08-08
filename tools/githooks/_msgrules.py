# -*- coding: utf-8 -*-
"""commit-msg 与 pre-push 共用的提交信息判据。

判据只有一份，两个钩子都用它，因为它们查的是同一件事、在两个不同的时刻：

  commit-msg  你在**装了钩子的克隆里**提交时；
  pre-push    你**往远端推**时——这道才是真正兜底的。

为什么需要第二道：commit-msg 只管得住「提交这个动作发生在这份克隆里」。
2026-08-08 进来的那 12 个英文标题提交是在别处产生、然后作为分支推进来的，
commit-msg 从头到尾没有机会运行。只查分支名的 pre-push 同样拦不住。
换句话说，只有 commit-msg 的话，**这套东西挡不住当初催生它的那件事**。
"""

import re

CJK = re.compile(r"[一-鿿]")
SKIP = "[skip-hooks]"
# 顶格独占一行才算：不允许前导空白，因为 `git commit -v` 的 diff 上下文行正是
# 「一个空格 + 原文」，本仓库又确实有一行顶格的 [skip-hooks]（AGENTS.md）。
SKIP_LINE = re.compile(r"^\[skip-hooks\][ \t]*$", re.M)
# git commit -v / commit.cleanup=scissors 的剪刀线，其后是 diff，不参与判定
SCISSORS = re.compile(r"^#?\s*-+\s*>8\s*-+", re.M)
# 由 git 自己生成的信息，不强求
AUTO_PREFIXES = ("Merge ", "Revert ", "fixup!", "squash!")


def normalize(raw, drop_comments):
    """返回 (subject, body)。

    drop_comments 只在 commit-msg 侧为真——那里读的是编辑器缓冲，`#` 开头的行
    是 git 加的说明，会被 git 丢掉。pre-push 侧读的是 `git log %B`，已经是成品，
    此时再删 `#` 开头的行反而会改坏正文（正文里的 Markdown 标题就以 # 开头）。
    """
    m = SCISSORS.search(raw)
    if m:
        raw = raw[:m.start()]
    lines = raw.split("\n")
    if drop_comments:
        lines = [l for l in lines if not l.startswith("#")]
    subject = ""
    for l in lines:
        if l.strip():
            subject = l.strip()
            break
    return subject, "\n".join(lines)


def problems(raw, drop_comments=False):
    """返回不合规之处的列表（每项是一段给人看的说明）。合规则返回空列表。

    自动生成的信息、以及带顶格 [skip-hooks] 的信息，一律返回空列表。
    """
    subject, body = normalize(raw, drop_comments)

    if subject.startswith(AUTO_PREFIXES):
        return []
    if SKIP_LINE.search(body):
        return []

    out = []
    if not subject:
        out.append("提交信息是空的")
    elif not CJK.search(subject):
        out.append("标题必须用**中文**（AGENTS.md §1 一）\n"
                   "      当前标题: " + subject[:72])

    if not re.search(r"^Co-authored-by:\s*\S+", body, re.M):
        out.append("缺 Co-authored-by trailer（CLAUDE.md 提交约定）\n"
                   "      末尾加一行，例如:\n"
                   "        Co-authored-by: Codex <noreply@openai.com>\n"
                   "        Co-authored-by: Claude <noreply@anthropic.com>")

    if not re.search(r"^文档[:：]", body, re.M):
        out.append("缺「文档:」交代（AGENTS.md §1 四）\n"
                   "      写明对应文档改动，或明确写不影响，例如:\n"
                   "        文档: 已更新 README.md 的「网络出口」表\n"
                   "        文档: 纯内部重构，不影响任何文档描述")

    return out
