#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验第三方组件声明没有漏项 —— 每个「我们自己塞进包里的」文件都得有声明。

## 为什么要有它

`assets/` 与 `lib/` 底下绝大多数东西来自基础 APK，归游戏版权方；但有几个是**我们
加进去的自由软件**（aria2、ShadowHook、中文字体）。它们此前被 `LICENSE.additional-terms`
第 3 条一并划给了游戏版权方——既把与人家无关的自由软件记到人家名下，又漏掉了
这些许可证要求的声明。

这类错误的特点是**不会有人发现**：编译过、测试过、装机跑得好好的，只有真出事那天
才知道少了什么。所以把判据变成可执行的检查：

    baseline/baseline.json 里凡是 kind=add 或 kind=replace 的二进制资产
    （即「不是从原包打补丁打出来的」），落在 assets/ 或 lib/ 底下的，
    THIRD-PARTY-NOTICES.md 里必须有它的路径。

反过来也查：声明里写着的路径，文件必须真的在——否则就是删了文件忘了删声明，
留一份描述不存在文件的许可声明同样是错的。

## 判据的边界（故意留的口子）

`assets/magia/`、`lib/*/libMagiaLegacy.so`、`res/xml/network_security_config.xml`
这些是我们自制的，归 GPLv3，不需要第三方声明。它们写在 SELF_MADE 里豁免，
而且**必须在声明文件的「不在此列」一节里出现**——豁免也要留痕，否则这个白名单
迟早变成「往里加一行就不用管了」的后门。
"""

import json
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NOTICES = os.path.join(REPO, "THIRD-PARTY-NOTICES.md")
BASELINE = os.path.join(REPO, "baseline", "baseline.json")

# 打进包里、但归我们自己（GPLv3）的东西：不要求第三方声明，但要求在
# 声明文件的「不在此列」一节里点名，理由同上。
SELF_MADE = (
    "assets/magia/",
    "lib/arm64-v8a/libMagiaLegacy.so",
    "lib/armeabi-v7a/libMagiaLegacy.so",
    "res/xml/network_security_config.xml",
)

# 原包素材的衍生（汉化重绘），归原包版权方一侧，由 LICENSE.additional-terms §3 管。
DERIVED_FROM_BASE = ("assets/fonts/witchText-export.",)

WATCH_ROOTS = ("assets/", "lib/")

problems = []


def bad(msg):
    problems.append(msg)


def run(notices_path=None, quiet=False):
    global problems
    problems = []
    notices_path = notices_path or NOTICES

    if not os.path.isfile(notices_path):
        print("找不到 %s" % notices_path, file=sys.stderr)
        return 1, ["声明文件缺失"]
    if not os.path.isfile(BASELINE):
        print("找不到 %s" % BASELINE, file=sys.stderr)
        return 1, ["baseline.json 缺失"]

    with open(notices_path, "r", encoding="utf-8") as f:
        notices = f.read()
    with open(BASELINE, "r", encoding="utf-8") as f:
        conf = json.load(f)

    # 「不在此列」一节：豁免项必须在这里点名
    tail = notices.split("## 不在此列的", 1)
    exempt_section = tail[1] if len(tail) > 1 else ""
    if not exempt_section:
        bad("声明文件里没有「不在此列的」一节——豁免项无处留痕")

    # ── 一、我们加进包里的东西，都得有声明 ────────────────────────────────
    for op in conf["ops"]:
        rel, kind = op["path"], op["kind"]
        if kind not in ("add", "replace"):
            continue
        if not rel.startswith(WATCH_ROOTS):
            continue
        if rel.startswith(DERIVED_FROM_BASE):
            continue
        if rel.startswith(SELF_MADE):
            if rel.rsplit("/", 1)[-1] not in exempt_section and rel not in exempt_section:
                bad("%s 是自制件，但「不在此列的」一节里没点它的名" % rel)
            continue
        # 汉化图集是原包素材的衍生，走 §3，不是第三方组件
        if op.get("group") == "汉化图集":
            continue
        if rel not in notices:
            bad("%s 打进了包，但 THIRD-PARTY-NOTICES.md 里没有它——"
                "要么补声明，要么说明它为什么不需要" % rel)

    # ── 二、声明里写的文件，必须真的在 ────────────────────────────────────
    for m in re.finditer(r"`((?:assets|lib|res)/[^`]+)`", notices):
        rel = m.group(1)
        if any(ch in rel for ch in "*?"):
            continue
        if not os.path.exists(os.path.join(REPO, rel)):
            bad("声明里写着 %s，但这个文件不在仓库里——删文件时忘了删声明？" % rel)

    # ── 三、GPL 组件必须带源码指向与书面要约 ──────────────────────────────
    # 这两条不是形式主义：GPL 第 3 条的义务就是靠它们履行的，少一条就等于没履行。
    if "aria2" in notices:
        if "github.com/aria2/aria2" not in notices:
            bad("aria2 是 GPL，声明里必须给出上游源码地址（github.com/aria2/aria2）")
        if "书面要约" not in notices:
            bad("aria2 是 GPL，声明里必须有书面要约（三年有效的源码索取途径）")
        if "OpenSSL" not in notices:
            bad("aria2 静态链了 OpenSSL，声明里必须写明它的 OpenSSL 链接例外")

    # Apache-2.0 第 4(b) 条：派生品必须注明改动
    if "Apache" in notices and "mbm_20160902.ttf" in notices:
        if "4(b)" not in notices and "改动" not in notices:
            bad("字体是 Apache-2.0 派生品，声明里必须注明改动（第 4(b) 条）")

    if problems:
        if not quiet:
            print("第三方声明检查未通过：\n")
            for p in problems:
                print("  ✗ %s" % p)
            print("\n判据见 THIRD-PARTY-NOTICES.md 顶部与本脚本的 docstring")
        return 1, list(problems)

    if not quiet:
        n = len(re.findall(r"^## ", notices, re.M)) - 1  # 减掉「不在此列的」那节
        print("✔ 第三方声明检查通过（%d 个组件有声明，声明里的路径全部存在）" % n)
    return 0, []


if __name__ == "__main__":
    sys.exit(run()[0])
