#!/usr/bin/env python3
"""守卫「README 的补丁层类表 ↔ 实际类文件」两边一一对应。

## 这条守卫在防什么

2026-08-27 横扫文档时发现：`patch/src/main/java/io/kamihama/magianative/` 下
54 个类里，**18 个在 README 的类表里根本没有**——`CNPaths`、`CNZipTool`、
`CNManualRedownload`、`CNWebLocalFiles`、`Aria2EngineFailover` 这些都不是边角料，
其中好几个是安全边界或跨进程判据。

漏掉的原因不是谁偷懒，是**没有任何东西会因此报红**：新增一个类，编译过、测试过、
全套守卫绿，README 不动也没人拦。而这张表恰恰是 CLAUDE.md 的代码→文档对照表里
点名要求同步的那一张（「新增类 / 三层架构变化 / 类职责」那一行）。

漏一个类的后果不是「文档不全」这么轻：下一个人读 README 会以为补丁层就这些东西，
于是在别处重新实现一遍已经有的判据。`CNIo`、`CNHttp`、`CNAtomicReplace` 这几个类
本身就是「同一件事被写了五六份」之后合并出来的——那正是这张表该防的事。

## 两个方向都查

* **有类没行** → 新增类忘了登记（本次那 18 个）；
* **有行没类** → 类被删掉或改名，而表里那一行留着，读的人会去找一个不存在的东西。

后者同样真实：改名比新增更容易漏，因为改名的人心里认为「我没有加东西」。

## 判据只取「补丁层」那一张表

README 里带 `| \\`X\\` |` 形状的表有好几张（secret 表、守卫表、baseline 分类表），
它们的第一列不是类名。所以这里只截 `## 补丁层（…）` 标题之后的那一张，
避免把 `BASELINE_APK_URL` 之类当成「不存在的类」误报。
"""

import re
import sys
from pathlib import Path

PKG = Path("patch/src/main/java/io/kamihama/magianative")
README = Path("README.md")

# 类表所在小节的标题。改标题就要改这里——改不动会当场报「找不到类表」而不是静默放行。
HEADING = "## 补丁层（`patch/src/main/java/io/kamihama/magianative/`）"

# 有意不进类表的类。**默认应为空**：这张表的意义就是「补丁层有什么」，
# 除非某个类真的只是别处的实现细节，否则不该往这里加。加之前先问一句
# 「读 README 的人不需要知道它存在吗」。
INTENTIONALLY_ABSENT = set()


def class_table(text: str):
    """截出补丁层那一张表的第一列。找不到标题就抛，不静默返回空。"""
    i = text.find(HEADING)
    if i < 0:
        raise SystemExit(
            "在 README.md 里找不到类表的标题，判据无从谈起。\n"
            "改过标题就把 check-class-table.py 的 HEADING 一起改：\n  %r" % HEADING
        )
    names, started = [], False
    for line in text[i:].splitlines()[1:]:
        if line.startswith("|"):
            started = True
            m = re.match(r"\|\s*`([A-Za-z][A-Za-z0-9_]*)`\s*\|", line)
            if m:
                names.append(m.group(1))
        elif started and line.strip() == "":
            break                     # 表结束（后面还有别的表，不能继续吃）
    return names


def main():
    text = README.read_text(encoding="utf-8")
    rows = class_table(text)
    classes = {p.stem for p in PKG.glob("*.java")}

    problems = []

    dup = sorted({n for n in rows if rows.count(n) > 1})
    if dup:
        problems.append("类表里有重复行：" + "、".join(dup))

    missing = sorted(classes - set(rows) - INTENTIONALLY_ABSENT)
    if missing:
        problems.append(
            "这些类没有登记进 README 的类表（新增类要连表一起改，见 CLAUDE.md 的"
            "代码→文档对照表）：\n    " + "\n    ".join(missing))

    ghost = sorted(set(rows) - classes)
    if ghost:
        problems.append(
            "类表里这些行找不到对应的类文件（类被删了或改名了，行忘了跟）：\n    "
            + "\n    ".join(ghost))

    stale_allow = sorted(INTENTIONALLY_ABSENT - classes)
    if stale_allow:
        problems.append(
            "INTENTIONALLY_ABSENT 里这些类已经不存在，白名单该收：\n    "
            + "\n    ".join(stale_allow))

    if problems:
        print("✘ 补丁层类表与实际类对不上：", file=sys.stderr)
        for p in problems:
            print("  · " + p, file=sys.stderr)
        raise SystemExit(1)

    print("✔ 补丁层类表核对通过（%d 个类，一一对应）" % len(classes))


if __name__ == "__main__":
    main()
