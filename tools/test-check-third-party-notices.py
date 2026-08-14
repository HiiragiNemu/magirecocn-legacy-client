#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-third-party-notices.py 的自测：把声明改坏，确认它真的拦得住。

许可合规这类检查最容易变成摆设——写完那天跑一次绿灯，之后再没验证过它到底
能不能报错。这里每条用例都构造一份**确定缺东西**的声明文件，要求脚本报出来。
"""

import importlib.util
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location(
    "check_tpn", os.path.join(HERE, "check-third-party-notices.py"))
tpn = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tpn)

REPO = os.path.dirname(HERE)
GOOD = open(os.path.join(REPO, "THIRD-PARTY-NOTICES.md"), encoding="utf-8").read()

fails = []


def check(name, mutate, expect):
    text = mutate(GOOD)
    if text == GOOD:
        fails.append("%s：用例没改动任何东西，说明它在照抄一份过期的声明" % name)
        return
    with tempfile.NamedTemporaryFile("w", suffix=".md", encoding="utf-8", delete=False) as f:
        f.write(text)
        path = f.name
    try:
        code, probs = tpn.run(path, quiet=True)
    finally:
        os.unlink(path)
    if code == 0 or not any(expect in p for p in probs):
        fails.append("%s：期望报出含「%s」的问题，实得 code=%d，%s"
                     % (name, expect, code, probs[:3] or "没有任何问题"))
    else:
        print("  ✓ %s" % name)


print("check-third-party-notices 自测：")

# GPL 第 3 条的义务就是靠这三样履行的，少一样等于没履行
check("aria2 缺书面要约",
      lambda s: s.replace("书面要约", "友情提示"),
      "书面要约")

check("aria2 缺上游源码地址",
      lambda s: s.replace("github.com/aria2/aria2", "example.invalid/aria2"),
      "上游源码地址")

check("aria2 没写 OpenSSL 链接例外",
      lambda s: s.replace("OpenSSL", "TLS 库"),
      "OpenSSL 链接例外")

check("打进包的组件在声明里被删掉",
      lambda s: s.replace("lib/arm64-v8a/libshadowhook.so", "（已删）"),
      "但 THIRD-PARTY-NOTICES.md 里没有它")

check("声明里写着仓库里不存在的文件",
      lambda s: s + "\n\n## 幻觉组件\n\n见 `assets/nowhere/ghost.so`。\n",
      "这个文件不在仓库里")

# 只有 kind=add / replace 的自制件会被扫到；`lib/*/libMagiaLegacy.so` 在清单里是
# kind=generated（CI 编出来的），不走这条路径，所以用 assets/magia/ 那两张图做用例。
check("自制件没在「不在此列的」一节点名",
      lambda s: s.replace("`assets/magia/logo.png`", "（略）"),
      "「不在此列的」一节里没点它的名")

check("整节「不在此列的」被删掉",
      lambda s: s.split("## 不在此列的")[0],
      "无处留痕")

code, probs = tpn.run(quiet=True)
if code != 0:
    fails.append("原样声明被误报：%s" % probs[:5])
else:
    print("  ✓ 原样声明不误报")

if fails:
    print("\n自测未通过：")
    for f in fails:
        print("  ✗ %s" % f)
    sys.exit(1)
print("\ncheck-third-party-notices 自测全部通过")
