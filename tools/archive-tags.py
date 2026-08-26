#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把要删掉的存档 tag 打包备份，传到发布仓库 Release，之后由既有链路取走。

## 背景

`archive/*` 那批 tag 钉着已经退役的分支。它们**通常不在 main 的历史上**，删掉
就真没了；而里头相当一部分是研究结论——结论往往写在 commit 信息里，不在最终
那棵树里。

删之前先备份。名单每次从 origin 现取，不写死，所以开完新分支、下一批 tag 攒够
要清时，直接再跑一次就行。

## 为什么默认打 bundle 而不是一堆源码包

2026-08-14 那批 15 个 tag 的实测值：

    每个 tag 各打一个 tar.gz   ≈ 1.6 GB（对象在 15 份里重复了 15 遍）
    一个 git bundle            ≈ 120 MB（git 自带去重）

差 13 倍。而且 bundle 是**无损**的：commit 信息、作者、时间、全部 ref 都在，
`git clone` 它就能得到一份能翻历史的仓库；tar.gz 只有最终那棵树，历史全丢——
恰恰丢掉的是最值钱的部分。

真要一份份的源码包，加 `--tarballs`；两种可以一起打。

## 为什么只传 Release

既有的分发链路本来就是「读目标仓库 `releases/latest` 的资产 → 取走」。传到
Release 就够了，后面顺着那条链路走，不必为备份另写一条上传通道——多一条通道
就多一份要维护、要授权、会失效的东西。

## 怎么跑

**优先走 CI**：`.github/workflows/archive-tags.yml`（手动触发）。理由是那 120 MB
从境内机器传到 GitHub 是整条路上最慢最不稳的一段，而 runner 上传 Release 全程在
GitHub 内网；token 与目标地址也都已经在仓库 Secrets 里，不必落到谁的机器上。

本地跑（调试或 CI 不可用时）：

    export =... TARGET_REPO=owner/repo
    git fetch --force origin 'refs/tags/*:refs/tags/*'
    python3 tools/archive-tags.py pack        # 打包 + 生成索引，落在 work/archive/
    python3 tools/archive-tags.py upload      # 传到发布仓库 Release（同名资产先删后传）
    python3 tools/archive-tags.py verify      # 把资产下回来，逐个校 sha256

`pack` 会顺带写一份 `*-index.md`：每个 tag 的提交、日期、作者、标题与正文首段。
备份要是几年后还看得懂，靠的就是这份索引，而不是一个不知道装了什么的压缩包。
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(REPO, "work", "archive")
BASENAME = "legacy-client-archive-tags"

# 归档资产传到哪：地址不写死在仓库里，由环境变量给（CI 从 secrets 注入）。
# 那边的同步链路会读它的 releases/latest 再往外镜像。
# 形如 owner/repo，与发版步骤用的是同一个 secret（TARGET_REPO）——同一件事
# 只该有一个来源，拆成 owner 与 name 两个变量只会多一处能改错的地方。
# 2026-08-24：UPSTREAM/DOWNSTREAM 已统一为 TARGET_REPO（断上游后单仓库）。
TARGET = os.environ.get("TARGET_REPO", "").strip().strip("/")
RELEASE_TAG = os.environ.get("UPSTREAM_RELEASE_TAG", "latest")


def require_target():
    if TARGET.count("/") != 1 or not all(TARGET.split("/")):
        raise SystemExit(
            "TARGET_REPO 没设或形状不对（要 owner/repo，实得 %r）："
            "目标地址由环境变量给，本仓库里不写死。CI 里由 secrets 注入。" % TARGET)

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"


def sh(*args, **kw):
    return subprocess.check_output(["git", "-C", REPO] + list(args), text=True, **kw).strip()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for blk in iter(lambda: f.read(1 << 20), b""):
            h.update(blk)
    return h.hexdigest()


def archive_tags():
    """要备份的 tag：**以 origin 上有的为准**，不是本地有什么就打什么。

    要备份的是「即将从远端删掉的那些」。本地可能还躺着别人的临时 tag
    （踩过：这个克隆里有 tmp-agents / tmp-d8 两个本地专用 tag），把它们裹进备份
    既莫名其妙又误导后人。反过来，远端有而本地没取的必须报错——静默少备份一个
    tag，是这类脚本最坏的失败方式。

    不按 archive/* 之类的前缀筛：在 main 历史上的（如 last-green）删掉本来也不丢
    东西，但一并收进来不花额外体积（git bundle 对象全共享），少一层判断少一处出错。
    """
    # ls-remote 对附注 tag 会给两行：tag 对象自身，以及 `^{}` 那行指向的提交。
    # 要比的是**提交**，所以 `^{}` 存在时以它为准。
    remote = {}
    for line in sh("ls-remote", "--tags", "origin").splitlines():
        if "refs/tags/" not in line:
            continue
        sha, _, ref = line.partition("\t")
        name = ref.strip().split("refs/tags/")[1]
        peeled = name.endswith("^{}")
        name = name[:-3] if peeled else name
        if peeled or name not in remote:
            remote[name] = sha.strip()
    if not remote:
        raise SystemExit("origin 上没有 tag")

    local = set(sh("tag", "-l").splitlines())
    missing = [t for t in remote if t not in local]
    if missing:
        raise SystemExit(
            "这些 tag 在 origin 上有、本地没有，先取回来再打包：\n  %s\n"
            "  git fetch --force origin 'refs/tags/*:refs/tags/*'" % "\n  ".join(missing))

    # 本地 tag 与远端指向不同 → 会备份错的那个提交，比少备份一个还糟：
    # 表面上「备份好了」，内容却是另一份历史。踩过：CI 的 last-green.yml 每次
    # push 都 force 移动 last-green，而 `git fetch` **默认不强制更新已存在的 tag**，
    # 本地那个于是一直停在旧提交上。
    stale = [(tag, sh("rev-parse", tag + "^{commit}")[:8], want[:8])
             for tag, want in sorted(remote.items())
             if sh("rev-parse", tag + "^{commit}") != want]
    if stale:
        raise SystemExit(
            "这些 tag 本地与 origin 指向不同，备份下去会存错提交：\n"
            + "\n".join("  %-52s 本地 %s ≠ 远端 %s" % s for s in stale)
            + "\n  git fetch --force origin 'refs/tags/*:refs/tags/*'")

    extra = sorted(local - set(remote))
    if extra:
        print("（本地另有 %d 个不在 origin 上的 tag，不收进备份：%s）"
              % (len(extra), "、".join(extra)))
    return sorted(remote)


def require_main():
    """索引里「在 main 上」那一列要拿 origin/main 比，没有它整列会写成「否」。

    浅克隆正好是这种情况（CI 里 actions/checkout 默认只取一个 ref）：不报错、
    不缺文件，只是那一列全错——而那列恰恰是「删掉会不会丢东西」的判据。
    """
    if subprocess.call(["git", "-C", REPO, "rev-parse", "--verify", "-q", "origin/main"],
                       stdout=subprocess.DEVNULL) != 0:
        raise SystemExit(
            "拿不到 origin/main，索引里「在 main 上」那一列会整列写错。\n"
            "  浅克隆要先补全：git fetch --unshallow origin，"
            "或 actions/checkout 时给 fetch-depth: 0")


def tag_info(tag):
    fmt = "%H%x00%ad%x00%an%x00%s%x00%b"
    raw = sh("log", "-1", "--date=short", "--format=" + fmt, tag)
    parts = raw.split("\0")
    while len(parts) < 5:
        parts.append("")
    commit, date, author, subject, body = parts[:5]
    on_main = subprocess.call(
        ["git", "-C", REPO, "merge-base", "--is-ancestor", commit, "origin/main"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) == 0
    files = len(sh("ls-tree", "-r", "--name-only", commit).splitlines())
    return {"tag": tag, "commit": commit, "date": date, "author": author,
            "subject": subject, "body": body.strip(), "files": files, "on_main": on_main}


def cmd_pack(args):
    os.makedirs(OUT_DIR, exist_ok=True)
    require_main()
    tags = archive_tags()
    print("要备份的 tag：%d 个" % len(tags))

    infos = [tag_info(t) for t in tags]
    produced = []

    # ── bundle：一个文件装下全部 ref 及其历史 ────────────────────────────
    bundle = os.path.join(OUT_DIR, BASENAME + ".bundle")
    if os.path.exists(bundle):
        os.remove(bundle)
    # 连 main 一起打：对象与 tag 大量共享，几乎不增体积，但恢复出来的克隆有
    # HEAD 可检出（只打 tag 的话 git clone 会报「remote HEAD refers to
    # nonexistent ref」并留下一个空工作区，几年后拿到的人多半会以为包坏了）。
    subprocess.check_call(["git", "-C", REPO, "bundle", "create", bundle,
                           "HEAD", "refs/heads/main"] + ["refs/tags/" + t for t in tags])
    # 自校验：bundle 坏了要在这里发现，不是等到几年后想恢复时
    subprocess.check_call(["git", "-C", REPO, "bundle", "verify", bundle],
                          stdout=subprocess.DEVNULL)
    produced.append(bundle)
    print("bundle：%.0f MB（已通过 git bundle verify）" % (os.path.getsize(bundle) / 1e6))

    # ── 可选：一份份的源码快照 ───────────────────────────────────────────
    if args.tarballs:
        for t in tags:
            name = t.replace("/", "__") + ".tar.gz"
            dst = os.path.join(OUT_DIR, name)
            with open(dst, "wb") as f:
                p1 = subprocess.Popen(["git", "-C", REPO, "archive", "--format=tar",
                                       "--prefix=%s/" % t.replace("/", "__"), t],
                                      stdout=subprocess.PIPE)
                p2 = subprocess.Popen(["gzip", "-9"], stdin=p1.stdout, stdout=f)
                p1.stdout.close()
                if p2.wait() != 0 or p1.wait() != 0:
                    raise SystemExit("打包失败：%s" % t)
            produced.append(dst)
            print("  %s  %.0f MB" % (name, os.path.getsize(dst) / 1e6))

    # ── 索引：几年后还看得懂，靠的是这个 ─────────────────────────────────
    index = os.path.join(OUT_DIR, BASENAME + "-index.md")
    with open(index, "w", encoding="utf-8") as f:
        f.write("# legacy-client 存档 tag 备份\n\n")
        f.write("这些 tag 钉着已经退役的分支，**都不在 main 的历史上**——删掉就真没了。\n")
        f.write("删除前的备份。恢复方式：\n\n")
        f.write("```bash\ngit clone %s.bundle 恢复出来的仓库\ncd 恢复出来的仓库 && git tag -l\n```\n\n"
                % BASENAME)
        f.write("包里另有备份当天的 `main`（对象与这些 tag 大量共享，几乎不占额外体积），"
                "所以克隆出来直接就有一个可检出的 HEAD。\n\n")
        f.write("| tag | 提交 | 日期 | 文件数 | 在 main 上 |\n|---|---|---|---:|:---:|\n")
        for i in infos:
            f.write("| `%s` | `%s` | %s | %d | %s |\n"
                    % (i["tag"], i["commit"][:8], i["date"], i["files"],
                       "是" if i["on_main"] else "**否**"))
        f.write("\n---\n\n")
        for i in infos:
            f.write("## `%s`\n\n" % i["tag"])
            f.write("`%s` · %s · %s\n\n" % (i["commit"][:8], i["date"], i["author"]))
            f.write("**%s**\n\n" % i["subject"])
            if i["body"]:
                f.write("```\n%s\n```\n\n" % i["body"][:4000])
    produced.append(index)

    sums = os.path.join(OUT_DIR, BASENAME + ".sha256")
    with open(sums, "w", encoding="utf-8") as f:
        for p in produced:
            if p != sums:
                f.write("%s  %s\n" % (sha256_file(p), os.path.basename(p)))
    produced.append(sums)

    print("\n产物在 %s：" % OUT_DIR)
    for p in produced:
        print("  %8.1f MB  %s" % (os.path.getsize(p) / 1e6, os.path.basename(p)))
    return 0


# ---------------------------------------------------------------- 上传

def api(path, token, method="GET", data=None, headers=None, base=API, retries=4):
    """带退避重试的 GitHub API 调用。网络不稳是这条路上的常态，别一次失败就整批重来。"""
    url = base + path
    delay = 2
    for attempt in range(retries + 1):
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", "Bearer " + token)
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=900) as r:
                body = r.read()
                return json.loads(body) if body else None
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")[:300]
            if e.code in (404, 422) or attempt == retries:
                raise SystemExit("%s %s → HTTP %d\n  %s" % (method, url, e.code, detail))
            print("  HTTP %d，%d 秒后重试（%d/%d）" % (e.code, delay, attempt + 1, retries))
        except Exception as e:
            if attempt == retries:
                raise SystemExit("%s %s 失败：%s" % (method, url, e))
            print("  %s，%d 秒后重试（%d/%d）" % (e, delay, attempt + 1, retries))
        time.sleep(delay)
        delay *= 2


def token_or_die():
    t = os.environ.get("") or os.environ.get("")
    if not t:
        raise SystemExit("没有 。需要一个能写目标仓库的 token。")
    return t


def cmd_upload(args):
    require_target()
    token = token_or_die()
    files = sorted(os.path.join(OUT_DIR, f) for f in os.listdir(OUT_DIR)
                   if f.startswith(BASENAME))
    if not files:
        raise SystemExit("%s 里没有产物，先跑 pack" % OUT_DIR)

    rel = api("/repos/%s/releases/tags/%s" % (TARGET, RELEASE_TAG), token)
    rid = rel["id"]
    print("目标 Release：%s @ %s（id %s）" % (TARGET, RELEASE_TAG, rid))

    existing = {a["name"]: a["id"] for a in rel.get("assets", [])}
    for path in files:
        name = os.path.basename(path)
        size = os.path.getsize(path)
        if name in existing:
            print("同名资产已存在，先删：%s" % name)
            api("/repos/%s/releases/assets/%d" % (TARGET, existing[name]),
                token, method="DELETE")
        print("上传 %s（%.1f MB）…" % (name, size / 1e6))
        with open(path, "rb") as f:
            data = f.read()
        api("/repos/%s/releases/%d/assets?name=%s"
            % (TARGET, rid, urllib.parse.quote(name)),
            token, method="POST", data=data,
            headers={"Content-Type": "application/octet-stream",
                     "Content-Length": str(size)},
            base=UPLOADS)
        print("  ✔ %s" % name)

    print("\n资产已就位。既有链路读的是发布仓库 releases/latest，"
          "等它跑一次即可（或手动触发）。")
    return 0


def cmd_verify(args):
    """把资产下回来逐个校 sha256 —— 「传上去了」和「传完整了」是两回事。"""
    require_target()
    token = token_or_die()
    sums = os.path.join(OUT_DIR, BASENAME + ".sha256")
    if not os.path.isfile(sums):
        raise SystemExit("没有 %s，先跑 pack" % sums)
    want = {}
    for line in open(sums, encoding="utf-8"):
        digest, _, name = line.strip().partition("  ")
        if name:
            want[name] = digest

    rel = api("/repos/%s/releases/tags/%s" % (TARGET, RELEASE_TAG), token)
    assets = {a["name"]: a for a in rel.get("assets", [])}
    bad = 0
    tmp = os.path.join(OUT_DIR, ".verify.tmp")
    for name, digest in want.items():
        a = assets.get(name)
        if not a:
            print("✘ %s：Release 上没有" % name)
            bad += 1
            continue
        req = urllib.request.Request(a["url"])
        req.add_header("Authorization", "Bearer " + token)
        req.add_header("Accept", "application/octet-stream")
        with urllib.request.urlopen(req, timeout=900) as r, open(tmp, "wb") as f:
            while True:
                blk = r.read(1 << 20)
                if not blk:
                    break
                f.write(blk)
        got = sha256_file(tmp)
        print(("✔ " if got == digest else "✘ ") + name +
              ("" if got == digest else "  期望 %s 实得 %s" % (digest, got)))
        bad += got != digest
    if os.path.exists(tmp):
        os.remove(tmp)
    print("\n%d 个资产，%d 个不符" % (len(want), bad))
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser(description="存档 tag 备份到发布仓库 Release",
                                 formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("pack", help="打 bundle + 索引 + sha256 清单")
    p.add_argument("--tarballs", action="store_true",
                   help="额外为每个 tag 打一个 tar.gz（体积约 13 倍，历史会丢）")
    p.set_defaults(func=cmd_pack)

    p = sub.add_parser("upload", help="传到发布仓库 Release（需要 ）")
    p.set_defaults(func=cmd_upload)

    p = sub.add_parser("verify", help="下回来逐个校 sha256")
    p.set_defaults(func=cmd_verify)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
