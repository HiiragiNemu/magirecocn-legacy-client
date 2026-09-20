#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 baseline/ 这套 patchset 自洽 —— 不需要下载上游 APK。

`tools/baseline.py verify` 才是真正的端到端校验（取 Totentanz 包、重建、打补丁、
逐文件比对），但它要下 80 MB、跑一次 apktool。**这个脚本只做离线自洽检查**，
几毫秒跑完，适合每次构建都过一遍：

    ▸ baseline.json 能解析，钉死项（URL / sha256 / 版本 / JDK / 树指纹）形状正确
    ▸ 每条 op 的 path 唯一，都有 why——分类是人做的判断，判断必须留痕
    ▸ patch  → 补丁文件在、pre/post 都填了，且目标**不在**仓库里
    ▸ replace/add → 内容文件在，且 hash 与 post 相符
    ▸ remove → 填了 pre，且该文件确实不在仓库里
    ▸ generated → 不与其他 op 的路径重叠
    ▸ **原包派生路径下不许有 patchset 之外的入库文件**

最后一条是这套东西的目的本身。原包派生文件从仓库移除之后，
如果没有一道检查盯着，它们会以各种方式慢慢回来：调试时拷一份忘了删、某次
「顺手补个文件」、从旧检出 cherry-pick 带进来。等到有人发现时已经分不清
哪些是故意留的。
"""

import hashlib
import json
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONF = os.path.join(REPO, "baseline", "baseline.json")
PATCH_DIR = os.path.join(REPO, "baseline", "patches")
REPLACE_DIR = os.path.join(REPO, "baseline", "replace")
HEX64 = re.compile(r"^[0-9a-f]{64}$")

problems = []


def bad(msg):
    problems.append(msg)


def content_src(op, rel):
    """一条 op 的内容在本地哪儿。

    返回 None 表示**内容不在本仓库**，有两种：
      overlay  —— 在外部来源的 Release 里；
      baseline —— 在基线树里的另一个路径（op 的 `src`），构建时现拷。
    两种都要求「仓库里不能同时还躺着一份」，那是迁移做了一半。
    """
    where = op.get("from", "store")
    if where == "repo":
        return os.path.join(REPO, rel)
    if where in ("overlay", "baseline"):
        return None
    return os.path.join(REPLACE_DIR, rel)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for blk in iter(lambda: f.read(1 << 20), b""):
            h.update(blk)
    return h.hexdigest()


def check_patch_hunks(pf, rel):
    """补丁 hunk 头的行数必须与实际行数一致。

    手改补丁时容易只加/删行、忘了改 `@@ -a,b +c,d @@` 里的 b/d。baseline.py
    apply_unified 不用这两个数（按实际行应用），所以不会当场炸——但 regen 会
    算出正确的行数，提交的头与之不一致就成了漂移的定时炸弹（换基线时被 verify
    抓到，又得回头翻是哪个补丁改岔了）。
    """
    try:
        lines = open(pf, encoding="utf-8").read().splitlines()
    except OSError as e:
        bad("%s：读补丁失败 %s" % (rel, e))
        return
    for i, line in enumerate(lines):
        if not line.startswith("@@"):
            continue
        m = re.match(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", line)
        if not m:
            bad("%s：第 %d 行 hunk 头形状不对：%s" % (rel, i + 1, line))
            continue
        old_s, old_c, new_s, new_c = (int(g) if g else 1 for g in m.groups())
        j = i + 1
        added = removed = context = 0
        while j < len(lines) and not lines[j].startswith("@@"):
            tag = lines[j][:1]
            if tag == "+":
                added += 1
            elif tag == "-":
                removed += 1
            elif tag == " ":
                context += 1
            j += 1
        if context + removed != old_c:
            bad("%s：hunk 头旧行数 %d ≠ 实际 %d（上下文 %d + 删除 %d）"
                % (rel, old_c, context + removed, context, removed))
        if context + added != new_c:
            bad("%s：hunk 头新行数 %d ≠ 实际 %d（上下文 %d + 新增 %d）"
                % (rel, new_c, context + added, context, added))


def run(conf_path=None, quiet=False):
    """返回 (退出码, 问题列表)。conf_path 可换，供 test-check-baseline.py 注入坏样本。"""
    global problems
    problems = []
    conf_path = conf_path or CONF
    if not os.path.isfile(conf_path):
        print("找不到 %s" % conf_path, file=sys.stderr)
        return 1, ["清单缺失"]
    with open(conf_path, "r", encoding="utf-8") as f:
        conf = json.load(f)

    # ── 钉死项 ────────────────────────────────────────────────────────────
    for key in ("apk", "apktool"):
        spec = conf.get(key) or {}
        # 地址要么直接写 https，要么声明由哪个环境变量给（CI 从 secrets 注入）。
        # 两者都没有就是拿不到东西；两者都有反而含糊，也拦下。
        has_url = str(spec.get("url", "")).startswith("https://")
        has_env = bool(spec.get("url_env"))
        if has_url == has_env:
            bad("%s：url（https）与 url_env 必须二选一，现在是%s"
                % (key, "两个都有" if has_url else "两个都没有"))
        if not HEX64.match(str(spec.get("sha256", ""))):
            bad("%s.sha256 不是 64 位小写十六进制" % key)
        if not spec.get("version"):
            bad("%s.version 空着" % key)
        if not spec.get("why"):
            bad("%s.why 空着——钉死一个版本总得说清为什么" % key)

    # 重建树的指纹：单文件 hash 只管得住打过补丁的那 14 个，指纹管得住整棵树。
    # 少了它，「同一个 APK 解出了不一样的树」这类环境漂移只会在某个没打补丁的
    # 文件上偶发地冒出来，而现象指不向原因（踩过：JDK 19 换了 Float.toString 的
    # 最短表示算法，MurmurHash3.smali 的一句注释就变了）。
    if not HEX64.match(str((conf.get("apk") or {}).get("tree_fingerprint", ""))):
        bad("apk.tree_fingerprint 没填或形状不对——整棵重建树没有指纹就管不住环境漂移")

    jdk = conf.get("jdk") or {}
    if not isinstance(jdk.get("min_major"), int):
        bad("jdk.min_major 没填——重建用的 JDK 大版本也是钉死项，理由见 jdk.why")
    if not jdk.get("why"):
        bad("jdk.why 空着——钉死 JDK 版本这种反直觉的约束尤其要写清为什么")

    ops = conf.get("ops") or []
    if not ops:
        bad("ops 是空的")

    seen = {}
    generated = []
    for op in ops:
        kind, rel = op.get("kind"), op.get("path")
        if not rel:
            bad("有一条 op 没有 path")
            continue
        if rel in seen:
            bad("路径重复：%s（%s 与 %s）" % (rel, seen[rel], kind))
        seen[rel] = kind
        if not op.get("why"):
            bad("%s 没写 why" % rel)

        in_tree = os.path.join(REPO, rel)

        if kind == "patch":
            pf = os.path.join(PATCH_DIR, rel + ".patch")
            if not os.path.isfile(pf):
                bad("%s：补丁文件缺失 %s" % (rel, os.path.relpath(pf, REPO)))
            for k in ("pre", "post"):
                if not HEX64.match(str(op.get(k, ""))):
                    bad("%s：%s hash 没填或形状不对" % (rel, k))
            # 2026-08-14 起仓库里不再存原包派生文件：patch 的目标只存在于重建树里。
            # 它要是又出现在仓库里，多半是谁把一棵完整树的一部分提交了回来。
            if os.path.isfile(in_tree):
                bad("%s：标为 patch，但仓库里又出现了这个原包文件——patchset 的目标"
                    "只该存在于重建树里" % rel)
            check_patch_hunks(pf, rel)

        elif kind in ("replace", "add"):
            if kind == "replace" and not HEX64.match(str(op.get("pre", ""))):
                bad("%s：pre hash 没填（replace 也要认基线，否则换包时不报错）" % rel)
            # from=repo 的内容已经由本次构建显式钉住的 Git commit 逐字节确定。
            # baseline.json 再存一份 post hash 会制造第二事实来源：正常改源码却忘了
            # 同步 JSON，就会在真正构建前产生假失败。外部/store 来源仍必须有 post。
            where = op.get("from", "store")
            if where != "repo" and not HEX64.match(str(op.get("post", ""))):
                bad("%s：post hash 没填或形状不对" % rel)
            src = content_src(op, rel)
            if src is None:
                # 内容不在本仓库（overlay / baseline）。overlay 那份由
                # baseline.py fetch 按 overlay.sha256 认；baseline 那份由 apply
                # 按 post hash 认。这里只要求它别同时还躺在本仓库里——那说明
                # 迁移做了一半。
                where = op.get("from")
                if os.path.isfile(in_tree):
                    bad("%s：已改从 %s 取，但仓库里那份还没删——迁移做了一半" % (rel, where))
                if where == "baseline" and not op.get("src"):
                    bad("%s：from=baseline 必须给 src（基线树里的源路径），"
                        "否则构建时不知道去哪拷" % rel)
            elif not os.path.isfile(src):
                bad("%s：内容文件缺失 %s" % (rel, os.path.relpath(src, REPO)))
            elif where != "repo" and sha256_file(src) != op["post"]:
                bad("%s：内容与 post hash 对不上——改了文件没跑 baseline.py regen？" % rel)

        elif kind == "remove":
            if not HEX64.match(str(op.get("pre", ""))):
                bad("%s：remove 必须填 pre，否则删错了也不知道" % rel)
            if os.path.isfile(in_tree):
                bad("%s：标为要删，但工程树里还在——清单和树不一致" % rel)

        elif kind == "generated":
            generated.append(rel)

        else:
            bad("%s：未知 kind %r" % (rel, kind))

    # generated 是「构建期产出」的前缀声明，不该盖住任何一条具体 op：
    # 真盖住了，那条 op 会被 verify 无声放过。
    for rel, kind in seen.items():
        if kind == "generated":
            continue
        for g in generated:
            if rel.startswith(g):
                bad("%s（%s）落在 generated 前缀 %s 之下，会被 verify 无声放过" % (rel, kind, g))

    # ── 原包派生文件不许回流 ──────────────────────────────────────────────
    # 让原包派生文件留在仓库之外是这套东西的目的本身。如果没有一道检查盯着，
    # 它们会以各种方式慢慢回来：调试时拷一份进来忘了删、某次「顺手补个文件」、
    # 从旧检出 cherry-pick 带进来。等到有人发现时已经分不清哪些是故意留的。
    # 判据：APK 相关目录下入库的文件，只能是 patchset 里 add / replace(from=repo)
    # 点名的那些。
    allowed = {op["path"] for op in ops
               if op["kind"] in ("add", "replace") and content_src(op, op["path"]) is not None
               and op.get("from") == "repo"}
    roots = ("smali/", "smali_classes2/", "smali_classes3/", "res/", "assets/",
             "lib/", "kotlin/", "unknown/", "original/", "META-INF/",
             "AndroidManifest.xml", "apktool.yml")
    try:
        tracked = subprocess.check_output(
            ["git", "-C", REPO, "ls-files"], text=True).split("\n")
    except Exception:
        tracked = []                      # 不是 git 检出（打包下载等）就跳过这一项
    stray = sorted(p for p in tracked
                   if p and p.startswith(roots) and p not in allowed)
    for p in stray[:20]:
        bad("%s：原包派生路径下多出了入库文件，patchset 里没有它" % p)
    if len(stray) > 20:
        bad("……另有 %d 个同类文件（已截断）" % (len(stray) - 20))

    for grp in conf.get("expected_divergence") or []:
        if not grp.get("why"):
            bad("expected_divergence 有一组没写 why——「预期内的差异」不写理由就是掩盖差异")
        if not (grp.get("paths") or grp.get("prefixes")):
            bad("expected_divergence 有一组既没 paths 也没 prefixes")

    if problems:
        if not quiet:
            print("baseline patchset 自洽检查未通过：\n")
            for p in problems:
                print("  ✗ %s" % p)
            print("\n改完补丁记得跑：python3 tools/baseline.py regen")
        return 1, list(problems)

    kinds = {}
    for op in ops:
        kinds[op["kind"]] = kinds.get(op["kind"], 0) + 1
    if not quiet:
        print("baseline patchset 自洽：%d 条操作（%s）" % (
            len(ops), "，".join("%s %d" % (k, v) for k, v in sorted(kinds.items()))))
    return 0, []


if __name__ == "__main__":
    sys.exit(run()[0])
