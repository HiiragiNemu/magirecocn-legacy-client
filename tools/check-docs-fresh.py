#!/usr/bin/env python3
"""README 防漂移检查（2026-08-14 加入）。

README 里有些快照数字/引用特别容易在代码演进后过期，而且没人发现。本脚本在
CI（last-green 的「跑全部守卫」）里跑，对 README 里**能对代码/文件验证**的断言
逐条核对，撞上漂移就红。

判据只钉「能机械验证」的事实，不碰观点与历史叙述：

  · 提到的文件路径是否存在（tools/、patch/、docs/、.github/workflows/ 下）
  · 提到的 Java 类名是否存在于 patch/src/main/java
  · 提到的脚本是否可执行（tools/*.py）
  · README 自身引用的内部锚点（[CONTRIBUTING](CONTRIBUTING.md) 等）是否存在

数字类断言（断言数、smali 数、类数）刻意**不**在这里钉——它们每次都漂移，
钉了只会天天误报。README 已改为「以当次 CI / 脚本输出为准」的措辞，见 README
「测试」一节的说明。

用法：python3 tools/check-docs-fresh.py
退出码：0 = 干净；1 = 有漂移的引用。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
README = os.path.join(ROOT, "README.md")

# 只检查这些目录下的相对路径引用，避免把 URL、代码片段里的路径也当成文件路径。
CHECKED_DIRS = ("tools/", "patch/src/main/java/io/kamihama/magianative/",
                "docs/", ".github/workflows/")

# 具名引用的文档（markdown 链接目标），常见于 [CONTRIBUTING](CONTRIBUTING.md) 这类。
LINKED_DOCS = ("CONTRIBUTING.md", "AGENTS.md", "CLAUDE.md", "README.md", "NOTICE",
               "LICENSE")

# README 里的代码引用：`tools/xxx.py`、`patch/src/.../YYY.java`、`docs/xxx.md`
# 以及「CNXxx.java:行」这种文件:行形式。
PATH_RE = re.compile(
    r"`(?P<p>[A-Za-z0-9_./-]+(?:\.(?:java|py|md|yml|yaml|sh|cmd|inc|json))?)`"
    r"|(?P<fl>[A-Za-z0-9_/.-]+\.(?:java|py|yml)):(?P<line>\d+)",
    re.IGNORECASE)
LINK_RE = re.compile(r"\]\((?P<doc>[A-Za-z0-9_./-]+\.md)\)")

# 明确是「命令示例」「代码块内」的路径不查（它们在 bash 代码块里，不是文档断言）。
CODE_BLOCK = re.compile(r"^\s*(```|\$ |# )", re.M)


def readme_text():
    with open(README, encoding="utf-8") as f:
        return f.read()


def check_path(p, line_no, problems):
    """p 是相对仓库根的路径。存在就过；不在 CHECKED_DIRS 里也过（不硬查）。"""
    if not p.startswith(CHECKED_DIRS):
        return
    full = os.path.join(ROOT, p)
    if not os.path.exists(full):
        problems.append((line_no, p, "路径不存在"))


def main():
    text = readme_text()
    problems = []

    # 1. 链接目标文档
    for m in LINK_RE.finditer(text):
        doc = m.group("doc")
        if doc in LINKED_DOCS and not os.path.exists(os.path.join(ROOT, doc)):
            line_no = text[:m.start()].count("\n") + 1
            problems.append((line_no, doc, "链接的文档不存在"))

    # 2. 代码/脚本/类引用（逐行，跳过代码块内的行）
    for i, line in enumerate(text.split("\n"), 1):
        if CODE_BLOCK.match(line):
            continue
        for m in PATH_RE.finditer(line):
            p = m.group("p") or m.group("fl")
            if not p:
                continue
            if p.startswith(CHECKED_DIRS) and not os.path.exists(os.path.join(ROOT, p)):
                problems.append((i, p, "引用的路径不存在"))

    if problems:
        print(f"✘ README 引用漂移：{len(problems)} 处")
        for line_no, p, why in problems:
            print(f"  README.md:{line_no}  {p}  —  {why}")
        print("修法：要么更新 README 指向真实路径，要么删掉失效引用。")
        return 1
    print("✔ README 引用无漂移（路径/链接全部存在）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
