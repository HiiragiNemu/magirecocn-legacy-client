#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""基线工具 —— 把「原包 + 我们的补丁」两件事分开。

## 这个工具解决什么问题

本仓库长期以来是一整棵 apktool 重建树入库：入库文件里，只有 ~60 个是我们
写的，其余全是构建链重建产物。上游 Puella-Care/client-apk 走的是另一条路——
仓库正文只放补丁，整包放 Release。我们可以做得更干净：**连原包都不自己存**，
构建时直接从Totentanz 公开 Release 取。

这个工具就是那条路上的机械部分：

    fetch   下载并校验发布仓库 Release APK 与 apktool，重建出「基线树」
    regen   拿现有工程树反推出 patchset（开发时用，改完补丁重新生成）
    apply   在基线树上应用 patchset，还原出可构建的工程树
    verify  apply 一遍，逐文件比对现有工程树，报告所有差异

## 为什么分类是手写在 baseline.json 里、而不是自动推断

自动推断会把「我们故意不要的东西」误判成「我们新增的东西」。最典型的是
1.1.1 → 1.2.0 之间上游删掉的 358 个埋点 SDK 类（thinkingdata / backtrace）：
它们在我们的旧树里有、在新基线里没有，任何按存在性推断的脚本都会把它们当成
「我方新增，要加回去」——而实际上删掉它们正是换基线的收益。

所以每一条操作的**性质由人判断并写进 baseline.json**，工具只负责把内容机械化
（算 diff、算 hash、应用、核对）。判断留痕，执行自动。

## 操作类型

| kind | 含义 | 校验 |
|---|---|---|
| `patch` | 基线里有此文件，我们改了几行 | `pre` = 基线文件 hash，`post` = 改完 hash |
| `replace` | 基线里有此文件，但我们基本重写了，存整份更诚实 | 同上 |
| `add` | 基线里没有，我们新增（自带内容在仓库里） | `post` |
| `remove` | 基线里有，我们要删掉 | `pre` |
| `generated` | 构建期由别的步骤产出（Java→dex→smali、native .so） | 不校验内容 |

`pre` 存在的意义：项目换了包、或 apktool 换了版本导致重建结果变化时，
**在打补丁之前**就报错，而不是打出一棵似是而非的树再去构建。

用法见 `--help`；细节见 README「基线与补丁」。
"""

import argparse
import difflib
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASELINE_DIR = os.path.join(REPO, "baseline")
BASELINE_JSON = os.path.join(BASELINE_DIR, "baseline.json")
PATCH_DIR = os.path.join(BASELINE_DIR, "patches")
REPLACE_DIR = os.path.join(BASELINE_DIR, "replace")
WORK = os.path.join(REPO, "work", "baseline")


# ---------------------------------------------------------------- 基础工具

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for blk in iter(lambda: f.read(1 << 20), b""):
            h.update(blk)
    return h.hexdigest()


def read_lines(path):
    """按行读文本，保留行尾。返回 (行列表, 原文末尾是否缺换行)。

    缺末尾换行的文件（AndroidManifest.xml 就是）会让 unified diff 需要
    `\\ No newline at end of file` 标记。这里的做法是补一个换行再做 diff，
    缺不缺换行单独记在 op 里，写回时按记录还原——补丁正文里就不用出现那个标记，
    应用逻辑也少一种分支。
    """
    with open(path, "rb") as f:
        text = f.read().decode("utf-8")
    lines = text.splitlines(keepends=True)
    nonl = bool(lines) and not lines[-1].endswith("\n")
    if nonl:
        lines[-1] += "\n"
    return lines, nonl


def write_lines(path, lines, nonl):
    text = "".join(lines)
    if nonl and text.endswith("\n"):
        text = text[:-1]
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def load_conf():
    with open(BASELINE_JSON, "r", encoding="utf-8") as f:
        return json.load(f)


def save_conf(conf):
    with open(BASELINE_JSON, "w", encoding="utf-8") as f:
        json.dump(conf, f, ensure_ascii=False, indent=2)
        f.write("\n")


def op_paths(conf, kind):
    return [op["path"] for op in conf["ops"] if op["kind"] == kind]


# ---------------------------------------------------------------- 补丁应用

def parse_hunks(patch_text):
    """把 unified diff 拆成 hunk 列表：(旧起始行, 旧行数, 行内容)。"""
    hunks = []
    cur = None
    for line in patch_text.splitlines(keepends=True):
        if line.startswith("@@"):
            head = line.split("@@")[1].strip()          # 形如 -12,7 +12,9
            old = head.split()[0]                       # -12,7
            start, _, count = old[1:].partition(",")
            cur = (int(start), int(count) if count else 1, [])
            hunks.append(cur)
        elif cur is not None and line[:1] in (" ", "-", "+", "\\"):
            cur[2].append(line)
        # 其余（---/+++ 头、空行）忽略
    return hunks


def apply_unified(orig, patch_text, where):
    """严格应用 unified diff：上下文与删除行必须逐字相符，否则报错。

    自己实现而不调 `patch(1)` / `git apply`：CI 与开发机上不保证有 patch，
    而 `git apply` 在非 git 目录里行为不一致——基线树恰恰不是 git 仓库。
    """
    out = []
    pos = 0                                             # 已消费到原文第几行（0 基）
    for start, count, lines in parse_hunks(patch_text):
        idx = start - 1 if start > 0 else 0
        if idx < pos:
            raise SystemExit("%s：hunk 起点倒退，补丁本身有问题" % where)
        out.extend(orig[pos:idx])
        pos = idx
        for line in lines:
            tag, body = line[0], line[1:]
            if tag == "\\":
                continue
            if tag in (" ", "-"):
                if pos >= len(orig):
                    raise SystemExit("%s：补丁要求的第 %d 行超出原文长度" % (where, pos + 1))
                if orig[pos] != body:
                    raise SystemExit(
                        "%s：第 %d 行对不上——基线不是补丁写作时的那份。\n"
                        "  基线：%s  补丁：%s" % (where, pos + 1, orig[pos].rstrip("\n"), body.rstrip("\n")))
                if tag == " ":
                    out.append(orig[pos])
                pos += 1
            elif tag == "+":
                out.append(body)
    out.extend(orig[pos:])
    return out


# ---------------------------------------------------------------- 子命令

def cmd_fetch(args):
    conf = load_conf()
    os.makedirs(WORK, exist_ok=True)

    def grab(spec, name):
        dst = os.path.join(WORK, name)
        if os.path.isfile(dst) and sha256_file(dst) == spec["sha256"]:
            print("已有且校验通过：%s" % name)
            return dst
        print("下载 %s\n  ← %s" % (name, spec["url"]))
        tmp = dst + ".part"
        with urllib.request.urlopen(spec["url"]) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f)
        got = sha256_file(tmp)
        if got != spec["sha256"]:
            os.remove(tmp)
            raise SystemExit("sha256 不符：期望 %s，实得 %s" % (spec["sha256"], got))
        os.replace(tmp, dst)
        return dst

    apk = grab(conf["apk"], "upstream.apk")
    jar = grab(conf["apktool"], "apktool.jar")

    java = args.java or "java"
    check_jdk(conf, java)

    dec = os.path.join(WORK, "dec")
    if args.force and os.path.isdir(dec):
        shutil.rmtree(dec)
    if os.path.isdir(dec):
        print("基线树已存在，跳过重建（要重来加 --force）：%s" % dec)
    else:
        print("重建（apktool %s）…" % conf["apktool"]["version"])
        subprocess.check_call([java, "-jar", jar, "d", "-f", "-o", dec, apk])
    check_fingerprint(conf, dec)
    print("基线树：%s" % dec)
    return 0


def java_major(java):
    """取 java 的主版本号。`java -version` 走 stderr，格式历来是 "21.0.10" 或 "1.8.0"。"""
    out = subprocess.run([java, "-version"], capture_output=True, text=True).stderr
    m = re.search(r'version "(\d+)(?:\.(\d+))?', out)
    if not m:
        raise SystemExit("认不出 java 版本，`%s -version` 输出：\n%s" % (java, out))
    major = int(m.group(1))
    return int(m.group(2) or 0) if major == 1 else major


def check_jdk(conf, java):
    """JDK 主版本也是钉死项之一 —— 理由见 baseline.json 的 jdk.why。"""
    spec = conf.get("jdk")
    if not spec:
        return
    got = java_major(java)
    lo, hi = spec.get("min_major"), spec.get("max_major")
    if (lo and got < lo) or (hi and got > hi):
        raise SystemExit(
            "JDK 版本不在钉死区间：需要 %s，实得 %d（%s）\n  %s\n"
            "  换一个合规的 JDK，或用 --java 指定它的 java 可执行文件。"
            % ("%s–%s" % (lo or "*", hi or "*"), got, java, spec.get("why", "")))
    print("JDK 主版本 %d（钉死区间 %s–%s）" % (got, lo or "*", hi or "*"))


def tree_fingerprint(root):
    """整棵树的指纹：把每个文件的「相对路径 + sha256」排序后再哈希一次。

    单个文件的 hash 只能发现「这个文件变了」；指纹能发现「这棵树和当初那棵不是
    同一棵」——包括多了文件、少了文件。重建环境一旦漂移（apktool 换版本、JDK 换
    大版本），这里立刻炸，而不是等到某个没打补丁的文件在 verify 里冒出来。
    """
    h = hashlib.sha256()
    items = []
    for d, dirs, files in os.walk(root):
        if ".git" in dirs:
            dirs.remove(".git")
        for f in files:
            p = os.path.join(d, f)
            rel = os.path.relpath(p, root).replace(os.sep, "/")
            if rel == "apktool.yml":
                continue          # 带 apkFileName，不是 APK 内容的纯函数
            items.append((rel, sha256_file(p)))
    for rel, digest in sorted(items):
        h.update(("%s %s\n" % (rel, digest)).encode("utf-8"))
    return h.hexdigest(), len(items)


def check_fingerprint(conf, dec):
    want = (conf.get("apk") or {}).get("tree_fingerprint")
    got, n = tree_fingerprint(dec)
    if not want:
        print("基线树指纹（%d 个文件）：%s\n  ← baseline.json 里还没记，"
              "确认无误后填进 apk.tree_fingerprint" % (n, got))
        return
    if got != want:
        raise SystemExit(
            "基线树指纹不符（%d 个文件）\n  期望 %s\n  实得 %s\n"
            "  同一个 APK 解出了不一样的树——查重建工具链：apktool 版本、JDK 大版本。\n"
            "  （踩过一次：构建链给 const 指令加的 float 注释来自 Float.toString，\n"
            "    JDK 19 换了最短表示算法，同一常量在 17 上是 -8.2930312E7f、\n"
            "    在 21 上是 -8.293031E7f。注释不影响 dex，但树就不是同一棵了。）"
            % (n, want, got))
    print("基线树指纹相符（%d 个文件）" % n)


def cmd_regen(args):
    """拿现有工程树反推 patchset：算 diff、算 hash、写文件。分类不动。"""
    conf = load_conf()
    base, tree = args.baseline, args.tree
    changed = 0
    for op in conf["ops"]:
        kind, rel = op["kind"], op["path"]
        b, t = os.path.join(base, rel), os.path.join(tree, rel)
        if kind == "generated":
            continue
        if kind == "remove":
            if not os.path.isfile(b):
                raise SystemExit("要删的文件不在基线里：%s" % rel)
            op["pre"] = sha256_file(b)
        elif kind == "add":
            if os.path.isfile(b):
                raise SystemExit("标为新增的文件基线里已有：%s" % rel)
            op["post"] = sha256_file(t)
        elif kind == "replace":
            op["pre"] = sha256_file(b)
            op["post"] = sha256_file(t)
            if op.get("from", "store") == "store":
                dst = os.path.join(REPLACE_DIR, rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copyfile(t, dst)
        elif kind == "patch":
            a, _ = read_lines(b)
            c, nonl = read_lines(t)
            diff = list(difflib.unified_diff(a, c, fromfile="a/" + rel, tofile="b/" + rel, n=3))
            if not diff:
                raise SystemExit("标为 patch 的文件与基线一模一样：%s" % rel)
            dst = os.path.join(PATCH_DIR, rel + ".patch")
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "w", encoding="utf-8", newline="") as f:
                f.writelines(diff)
            op["pre"] = sha256_file(b)
            op["post"] = sha256_file(t)
            if nonl:
                op["no_final_newline"] = True
            else:
                op.pop("no_final_newline", None)
        else:
            raise SystemExit("未知 kind：%s（%s）" % (kind, rel))
        changed += 1
    save_conf(conf)
    print("已刷新 %d 条操作的内容与 hash" % changed)
    return 0


def cmd_apply(args):
    conf = load_conf()
    base, out = args.baseline, args.out
    if os.path.isdir(out):
        shutil.rmtree(out)
    print("复制基线树 → %s" % out)
    shutil.copytree(base, out)

    for op in conf["ops"]:
        kind, rel = op["kind"], op["path"]
        dst = os.path.join(out, rel)
        if kind == "generated":
            continue
        if kind == "remove":
            if not os.path.isfile(dst):
                raise SystemExit("要删的文件不在基线里：%s" % rel)
            check_pre(dst, op, rel)
            os.remove(dst)
            continue
        if kind == "add":
            src = os.path.join(REPO, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)
        elif kind == "replace":
            check_pre(dst, op, rel)
            src = (os.path.join(REPO, rel) if op.get("from") == "repo"
                   else os.path.join(REPLACE_DIR, rel))
            shutil.copyfile(src, dst)
        elif kind == "patch":
            check_pre(dst, op, rel)
            with open(os.path.join(PATCH_DIR, rel + ".patch"), "r", encoding="utf-8") as f:
                patch_text = f.read()
            orig, _ = read_lines(dst)
            result = apply_unified(orig, patch_text, rel)
            write_lines(dst, result, op.get("no_final_newline", False))
        got = sha256_file(dst)
        if op.get("post") and got != op["post"]:
            raise SystemExit("%s：打完补丁 hash 不符\n  期望 %s\n  实得 %s" % (rel, op["post"], got))
    print("patchset 已应用：%d 条操作" % len(conf["ops"]))
    return 0


def check_pre(path, op, rel):
    want = op.get("pre")
    if not want:
        return
    got = sha256_file(path)
    if got != want:
        raise SystemExit(
            "%s：基线文件与 patchset 写作时的不是同一份\n  期望 %s\n  实得 %s\n"
            "  多半是项目换了包或 apktool 换了版本——先核对 baseline.json 的钉死项。"
            % (rel, want, got))


def cmd_verify(args):
    """apply 一遍，再和现有工程树逐文件比。所有差异都要在 baseline.json 里有交代。"""
    conf = load_conf()
    out = args.out or os.path.join(WORK, "applied")
    ns = argparse.Namespace(baseline=args.baseline, out=out)
    cmd_apply(ns)

    tree = args.tree
    roots = tuple(conf["compare_roots"])
    exact, prefixes = set(), []
    for grp in conf["expected_divergence"]:
        exact.update(grp.get("paths", []))
        prefixes.extend(grp.get("prefixes", []))
    # generated 的 path 一律按前缀理解：它描述的是一族构建期产物
    # （`smali_classes3/` 是整个目录，`assets/magia/bgm` 是 bgm*.ogg + bgm.json，
    #   写全名的单个 .so 也照样匹配自己）
    prefixes.extend(op_paths(conf, "generated"))
    prefixes = tuple(prefixes)

    def walk(root):
        seen = set()
        for d, dirs, files in os.walk(root):
            if ".git" in dirs:
                dirs.remove(".git")
            for f in files:
                rel = os.path.relpath(os.path.join(d, f), root).replace(os.sep, "/")
                if rel.startswith(roots):
                    seen.add(rel)
        return seen

    a, b = walk(out), walk(tree)

    def excused(rel):
        return rel in exact or (bool(prefixes) and rel.startswith(prefixes))

    only_tree = sorted(p for p in b - a if not excused(p))
    only_out = sorted(p for p in a - b if not excused(p))
    differ = sorted(p for p in a & b
                    if not excused(p)
                    and sha256_file(os.path.join(out, p)) != sha256_file(os.path.join(tree, p)))

    for title, items in (("现有树里多出来", only_tree), ("重建树里多出来", only_out)):
        print("\n=== %s：%d ===" % (title, len(items)))
        for p in items[:40]:
            print("   ", p)
        if len(items) > 40:
            print("    …还有 %d 个" % (len(items) - 40))

    # 「内容不同：1」这种报法在 CI 里毫无用处——看不到差在哪就没法判断是我们改漏了
    # 还是重建环境漂移了。所以直接把差异打出来（限量，别刷屏）。
    print("\n=== 内容不同：%d ===" % len(differ))
    for p in differ[:10]:
        print("\n--- %s" % p)
        show_diff(os.path.join(out, p), os.path.join(tree, p))
    if len(differ) > 10:
        print("\n…还有 %d 个不同的文件（未展开）" % (len(differ) - 10))

    bad = len(only_tree) + len(only_out) + len(differ)
    print("\n未交代的差异共 %d 个" % bad)
    return 1 if bad else 0


def show_diff(a, b, max_lines=40):
    """打出两份文件的差异。文本走 unified diff，二进制只报大小与首个不同的字节位置。"""
    try:
        ta = open(a, "rb").read().decode("utf-8").splitlines(keepends=True)
        tb = open(b, "rb").read().decode("utf-8").splitlines(keepends=True)
    except UnicodeDecodeError:
        sa, sb = os.path.getsize(a), os.path.getsize(b)
        da, db = open(a, "rb").read(), open(b, "rb").read()
        at = next((i for i in range(min(len(da), len(db))) if da[i] != db[i]), min(len(da), len(db)))
        print("    二进制：重建 %d 字节 / 现有 %d 字节，首个不同字节在偏移 %d" % (sa, sb, at))
        return
    shown = 0
    for line in difflib.unified_diff(ta, tb, fromfile="重建", tofile="现有", n=1):
        sys.stdout.write("    " + line if line.endswith("\n") else "    " + line + "\n")
        shown += 1
        if shown >= max_lines:
            print("    …（差异过长，已截断）")
            break


def main():
    ap = argparse.ArgumentParser(description="基线与补丁工具", formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("fetch", help="下载并校验上游 APK 与 apktool，重建出基线树")
    p.add_argument("--force", action="store_true", help="已有基线树时也重新重建")
    p.add_argument("--java", default=None, help="重建用的 java 可执行文件（默认 PATH 上的 java）")
    p.set_defaults(func=cmd_fetch)

    p = sub.add_parser("regen", help="从现有工程树反推 patchset 内容与 hash")
    p.add_argument("--baseline", default=os.path.join(WORK, "dec"))
    p.add_argument("--tree", default=REPO)
    p.set_defaults(func=cmd_regen)

    p = sub.add_parser("apply", help="在基线树上应用 patchset")
    p.add_argument("--baseline", default=os.path.join(WORK, "dec"))
    p.add_argument("--out", default=os.path.join(WORK, "applied"))
    p.set_defaults(func=cmd_apply)

    p = sub.add_parser("verify", help="重建后与现有工程树逐文件比对")
    p.add_argument("--baseline", default=os.path.join(WORK, "dec"))
    p.add_argument("--tree", default=REPO)
    p.add_argument("--out", default=None)
    p.set_defaults(func=cmd_verify)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
