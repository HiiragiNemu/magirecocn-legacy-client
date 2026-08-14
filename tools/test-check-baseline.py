#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-baseline.py 的自测：往清单里塞坏样本，确认它真的拦得住。

一个只在「一切正常」时跑过的检查脚本，等于没有检查。这里每条用例都先构造一份
**确定有问题**的 baseline.json，再要求 check-baseline 报出来；最后跑一遍原样清单
确认不误报。
"""

import copy
import json
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import importlib.util

spec = importlib.util.spec_from_file_location("check_baseline", os.path.join(HERE, "check-baseline.py"))
cb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cb)

REPO = os.path.dirname(HERE)
GOOD = json.load(open(os.path.join(REPO, "baseline", "baseline.json"), encoding="utf-8"))

fails = []


def check(name, mutate, expect):
    conf = copy.deepcopy(GOOD)
    mutate(conf)
    with tempfile.NamedTemporaryFile("w", suffix=".json", encoding="utf-8", delete=False) as f:
        json.dump(conf, f, ensure_ascii=False)
        path = f.name
    try:
        code, probs = cb.run(path, quiet=True)
    finally:
        os.unlink(path)
    hit = any(expect in p for p in probs)
    if code == 0 or not hit:
        fails.append("%s：期望报出含「%s」的问题，实得 code=%d，%s"
                     % (name, expect, code, probs[:3] or "没有任何问题"))
    else:
        print("  ✓ %s" % name)


def first(conf, kind):
    for op in conf["ops"]:
        if op["kind"] == kind:
            return op
    raise AssertionError("清单里没有 kind=%s 的 op" % kind)


print("check-baseline 自测：")

# 删除动作只写进清单、没落到树上——重建树会和现有树不一致，而端到端 verify
# 要下 80 MB 才发现。这条是本脚本存在的首要理由。
check("remove 的文件还躺在工程树里",
      lambda c: first(c, "remove").__setitem__("path", "README.md"),
      "工程树里还在")

check("patch 的补丁文件不存在",
      lambda c: first(c, "patch").__setitem__("path", "no/such/file.smali"),
      "补丁文件缺失")

check("add 的内容与 post hash 对不上",
      lambda c: first(c, "add").__setitem__("post", "0" * 64),
      "post hash 对不上")

check("replace 少填 pre（换包时就不会报错了）",
      lambda c: first(c, "replace").__setitem__("pre", ""),
      "pre hash 没填")

check("generated 前缀盖住了别的 op",
      lambda c: c["ops"].append({"kind": "generated", "path": "assets/", "why": "故意写宽"}),
      "会被 verify 无声放过")

check("两条 op 抢同一个路径",
      lambda c: c["ops"].append(dict(first(c, "patch"), kind="remove")),
      "路径重复")

check("分类没写理由",
      lambda c: first(c, "patch").__setitem__("why", ""),
      "没写 why")

check("钉死项的 sha256 形状不对",
      lambda c: c["apk"].__setitem__("sha256", "deadbeef"),
      "sha256 不是 64 位"),

check("预期内差异不写理由",
      lambda c: c["expected_divergence"][0].__setitem__("why", ""),
      "不写理由就是掩盖差异")

code, probs = cb.run(quiet=True)
if code != 0:
    fails.append("原样清单被误报：%s" % probs[:5])
else:
    print("  ✓ 原样清单不误报")

if fails:
    print("\n自测未通过：")
    for f in fails:
        print("  ✗ %s" % f)
    sys.exit(1)
print("\ncheck-baseline 自测全部通过")
