#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""远端分支纪律检查器。**开任何分支之前先跑它。**

## 它回答什么

一句话：「我现在能不能开新分支」，以及「远端有没有我该收拾的垃圾」。

规则见 AGENTS.md §0，这里是它的可执行形式：

  1. 白名单只有 main / hotfix/* / surgery/* 三类——之外的分支一律视为
     「该收拾的」：有价值先打 archive/* tag 留锚点，然后删掉；
  2. **2 小时内刚有过分支活动**（且那条分支还没被删），就**不许再开新分支**——
     接着用那条，或者干脆直接提 main；
  3. 一次任务全程只允许有**一条**自己的分支。远端同时存在两条及以上非白名单
     分支，本身就是违规状态；
  4. hotfix/* 超 24 小时、surgery/* 超 3 天即**超期**，一样点名——
     白名单不等于永久居住证。

## 为什么要有它

2026-08-07 一天之内远端被推了六条一次性分支（`build/final-apk-20260807`、
`ci/runtime-fix-driver-*` ×4、`ci/runtime-java-fix-driver-*`、
`ci/runtime-fix-build-*-success`），全是同一条工作的递进快照——它们本该是本地
的几次 `git commit --amend`。人类逐条手工删了它们。

写在文档里没人看，所以做成命令：**跑一下，它直接告诉你行还是不行。**

## 用法

    python3 tools/check-branch-hygiene.py            # 体检：远端现在干净吗
    python3 tools/check-branch-hygiene.py --can-branch   # 我现在能开新分支吗

退出码 0 = 通过，1 = 不通过（`--can-branch` 下即「不许开」）。
"""

import argparse
import re
import subprocess
import sys
import time

# 允许存在于远端的分支。**只有这三类，没有第三种分支**
# （协作方案 §一，2026-08-09 起严格执行）：
#   main      唯一长期分支；
#   hotfix/*  修红灯，寿命以小时计；
#   surgery/* 核心层大手术，寿命 ≤ 3 天。
# 有价值的历史分支打 archive/* tag 留锚点，不作为分支存在。
ALLOW = (
    re.compile(r"^main$"),
    re.compile(r"^hotfix/"),
    re.compile(r"^surgery/"),
    # ── 具名临时例外（2026-08-09 维护者特批）──────────────────────
    # 这三条是别的会话正在跑的活，允许活到合并进 main 为止；
    # 合并删除后把对应行从本表移除，不要往这里加新名字。
    re.compile(r"^agent/fix-mumu-initlabel-hook$"),
    re.compile(r"^feature/battle-engine-i18n-20260808$"),
    re.compile(r"^feature/native-i18n-authority-20260809$"),
)

# 「刚刚才开过分支」的判定窗口
RECENT_HOURS = 2.0

# 例外分支的寿命上限（小时），超期即点名——白名单不等于永久居住证
OVERDUE_HOURS = (
    (re.compile(r"^hotfix/"), 24.0),
    (re.compile(r"^surgery/"), 72.0),
)


def sh(*args):
    return subprocess.run(args, capture_output=True, text=True, timeout=120)


def remote_branches():
    r = sh("git", "ls-remote", "--heads", "origin")
    if r.returncode != 0:
        print("✘ 取不到远端分支列表：" + r.stderr.strip())
        sys.exit(2)
    out = []
    for line in r.stdout.splitlines():
        parts = line.split()
        if len(parts) != 2:
            continue
        out.append((parts[0], parts[1].replace("refs/heads/", "")))
    return out


def allowed(name):
    return any(p.search(name) for p in ALLOW)


def tip_age_hours(sha, name):
    """分支尖端提交距今多久（小时）。取不到返回 None。"""
    # 对象可能不在本地，先按需取一次
    if sh("git", "cat-file", "-e", sha + "^{commit}").returncode != 0:
        sh("git", "fetch", "--quiet", "origin",
           "refs/heads/%s:refs/remotes/hygiene/%s" % (name, name))
    r = sh("git", "log", "-1", "--format=%ct", sha)
    if r.returncode != 0 or not r.stdout.strip():
        return None
    try:
        return (time.time() - int(r.stdout.strip())) / 3600.0
    except ValueError:
        return None


def overdue_limit(name):
    """例外分支的寿命上限（小时）；main 与非例外分支返回 None。"""
    for pat, hours in OVERDUE_HOURS:
        if pat.search(name):
            return hours
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--can-branch", action="store_true",
                    help="判定「现在能不能开新分支」，不能则退出码 1")
    args = ap.parse_args()

    branches = remote_branches()
    strays = [(s, n) for s, n in branches if not allowed(n)]

    print("远端分支共 %d 条：" % len(branches))
    overdue = []
    for sha, name in sorted(branches, key=lambda x: x[1]):
        tag = "允许" if allowed(name) else "⚠ 非白名单"
        age = ""
        limit = overdue_limit(name)
        if limit is not None:
            # 例外分支也要查寿命：白名单不等于永久居住证
            h = tip_age_hours(sha, name)
            if h is not None:
                age = "  （末次提交 %.1f 小时前）" % h
                if h > limit:
                    tag = "⚠ 超期"
                    overdue.append((name, h, limit))
        elif not allowed(name):
            h = tip_age_hours(sha, name)
            age = "  （末次提交 %.1f 小时前）" % h if h is not None else "  （时间未知）"
        print("  %-10s %s%s" % (tag, name, age))

    if not strays and not overdue:
        print("\n✔ 干净：只有 main / hotfix/* / surgery/*，且无超期")
        if args.can_branch:
            print("✔ 可以开分支——但先想清楚：本仓库直接提 main，"
                  "多数情况根本不需要分支（AGENTS.md §0）")
        return 0

    if strays:
        print("\n✘ 有 %d 条非白名单分支：" % len(strays))
        fresh = []
        for sha, name in strays:
            h = tip_age_hours(sha, name)
            if h is not None and h < RECENT_HOURS:
                fresh.append((name, h))
            print("    %s" % name)
        print("\n  不要直接删——先归档成 tag 再删。推荐走 CI：")
        print("    Actions → 🗄️ 归档分支为 tag → 输入分支名")
        print("  （它会先打 archive/<原名> tag、验证推上远端，然后才删分支）")
        for _, name in strays:
            print("    手动等价: git push origin origin/%s^{}:refs/tags/archive/%s"
                  " && git push origin --delete %s" % (name, name, name))

    if overdue:
        print("\n✘ 有 %d 条例外分支超期（白名单不等于永久居住证）：" % len(overdue))
        for name, h, limit in overdue:
            print("    %s —— 已存在 %.1f 小时，上限 %.0f 小时。合入 main 后删除；"
                  "舍不得删的部分打 archive/* tag。" % (name, h, limit))

    if args.can_branch:
        print()
        if strays and fresh:
            print("✘ **不许开新分支**：下面这些是 %.0f 小时内刚动过的，"
                  "接着用它，别再开一条：" % RECENT_HOURS)
            for name, h in fresh:
                print("    %s（%.1f 小时前）" % (name, h))
        elif strays:
            print("✘ **不许开新分支**：远端已经有非白名单分支了。"
                  "一次任务全程只允许一条自己的分支——")
            print("    要么接着用上面某一条，要么先把它们归档成 tag 再删干净。")
        elif overdue:
            print("✘ **不许开新分支**：有超期的例外分支没收拾（见上）。"
                  "先合入删除，或归档成 tag。")
        return 1

    return 1


if __name__ == "__main__":
    sys.exit(main())
