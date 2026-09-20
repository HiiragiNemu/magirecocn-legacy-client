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

# 删掉原包树之后最容易慢慢退化回去的一条：把清单里某个 keeper 摘掉，
# 它在仓库里的那份就成了「patchset 之外的入库文件」，必须被点名。
# 选择器用 from==repo 而不是写死某个资产名：这些文件由源码仓 commit 钉死，
# 但仍必须登记在 patchset，防止有人把 APK 路径下的入库文件绕过重建清单。
check("原包路径下混进了 patchset 之外的入库文件",
      lambda c: c["ops"].remove(next(o for o in c["ops"]
                                     if o["kind"] == "add"
                                     and o.get("from") == "repo")),
      "patchset 里没有它")

check("store replace 的内容与 post hash 对不上",
      lambda c: next(o for o in c["ops"]
                     if o["kind"] == "replace"
                     and o.get("from", "store") == "store").__setitem__("post", "0" * 64),
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

# 下面两条是 2026-08-14 那次 CI 红灯补上的：同一个 APK 在 JDK 17 和 21 上解出了
# 不一样的树，而单文件 hash 只覆盖打过补丁的 14 个文件，一个都没盖住它。
check("整棵重建树没有指纹",
      lambda c: c["apk"].pop("tree_fingerprint", None),
      "管不住环境漂移")

check("没钉重建用的 JDK 大版本",
      lambda c: c.pop("jdk", None),
      "JDK 大版本也是钉死项")

check("预期内差异不写理由",
      lambda c: c["expected_divergence"][0].__setitem__("why", ""),
      "不写理由就是掩盖差异")

# ── hunk 头行数与实际不符（手改补丁只加行、忘了改 @@ 头）──
_tmp = tempfile.NamedTemporaryFile("w", suffix=".patch", delete=False)
_tmp.write("@@ -1,3 +1,3 @@\n ctx1\n+add1\n+add2\n+add3\n+add4\n")
_tmp.close()
_saved = cb.problems
cb.problems = []
try:
    cb.check_patch_hunks(_tmp.name, "fake.patch")
    if not any("新行数" in p for p in cb.problems):
        fails.append("hunk 头新行数不一致应被拦")
    else:
        print("  ✓ hunk 头新行数不一致被拦")
finally:
    cb.problems = _saved
    os.unlink(_tmp.name)

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
