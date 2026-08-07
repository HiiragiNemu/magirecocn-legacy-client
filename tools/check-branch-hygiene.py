#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""远端分支纪律检查器。**开任何分支之前先跑它。**

## 它回答什么

一句话：「我现在能不能开新分支」，以及「远端有没有我该收拾的垃圾」。

规则见 AGENTS.md §0，这里是它的可执行形式：

  1. 白名单之外的分支一律视为「本次任务留下的」，必须在收尾前删掉；
  2. **2 小时内刚有过分支活动**（且那条分支还没被删），就**不许再开新分支**——
     接着用那条，或者干脆直接提 main；
  3. 一次任务全程只允许有**一条**自己的分支。远端同时存在两条及以上非白名单
     分支，本身就是违规状态。

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

# 允许长期存在于远端的 ref。其余一律算「谁留下的谁收拾」。
ALLOW = (
    re.compile(r"^main$"),
    re.compile(r"^archive/"),    # 归档；只读，不要往上推
    re.compile(r"^research/"),   # 长期研究分支，不是你的，别动
)

# 「刚刚才开过分支」的判定窗口
RECENT_HOURS = 2.0


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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--can-branch", action="store_true",
                    help="判定「现在能不能开新分支」，不能则退出码 1")
    args = ap.parse_args()

    branches = remote_branches()
    strays = [(s, n) for s, n in branches if not allowed(n)]

    print("远端分支共 %d 条：" % len(branches))
    for sha, name in sorted(branches, key=lambda x: x[1]):
        tag = "允许" if allowed(name) else "⚠ 非白名单"
        age = "" if allowed(name) else ""
        if not allowed(name):
            h = tip_age_hours(sha, name)
            age = "  （末次提交 %.1f 小时前）" % h if h is not None else "  （时间未知）"
        print("  %-10s %s%s" % (tag, name, age))

    if not strays:
        print("\n✔ 干净：只有 main / archive/* / research/*")
        if args.can_branch:
            print("✔ 可以开分支——但先想清楚：本仓库直接提 main，"
                  "多数情况根本不需要分支（AGENTS.md §0）")
        return 0

    print("\n✘ 有 %d 条非白名单分支：" % len(strays))
    fresh = []
    for sha, name in strays:
        h = tip_age_hours(sha, name)
        if h is not None and h < RECENT_HOURS:
            fresh.append((name, h))
        print("    %s" % name)
    print("\n  收尾前请删掉自己留下的那些：")
    for _, name in strays:
        print("    git push origin --delete %s" % name)

    if args.can_branch:
        print()
        if fresh:
            print("✘ **不许开新分支**：下面这些是 %.0f 小时内刚动过的，"
                  "接着用它，别再开一条：" % RECENT_HOURS)
            for name, h in fresh:
                print("    %s（%.1f 小时前）" % (name, h))
        else:
            print("✘ **不许开新分支**：远端已经有非白名单分支了。"
                  "一次任务全程只允许一条自己的分支——")
            print("    要么接着用上面某一条，要么先把它们删干净。")
        return 1

    return 1


if __name__ == "__main__":
    sys.exit(main())
