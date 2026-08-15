#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""清掉还挂在外面的旧构建产物。

## 清的是什么、为什么

跑过的东西会一直堆着：workflow artifact、Actions cache、历次 run 及其日志、
以及 Release 上被新版取代的旧资产。留着有两个实际代价——占配额，以及**旧包仍然
可下载**：有人顺着旧链接装到一个早已不适用的版本，排查时会得到完全对不上的现象。

APK 本身不在 artifact 里——构建完直接发到发布目标，所以「旧包还能下」这一条
落在最后一行（Release 资产），不在第一行。

| 类别 | 说明 |
|---|---|
| workflow artifact | 归档流水线留下的索引、校验和与 bundle，按保留期堆积 |
| Actions cache | 构建缓存，同上 |
| run 与日志 | 历次运行记录 |
| Release 资产 | 被新版取代的旧发行文件 |

**能做到的是「不再由我们主动提供」，不是「世界上不存在」**：已经装在玩家手机上的
包、别人下载过的副本、第三方镜像，都不在射程内。

## 三条安全线（都是有意为之，别拆）

1. **默认 dry-run。** 不加 `--apply` 只列不删。一键删除这种东西，默认必须是「看看」。
2. **Release 资产要额外确认。** 别的类别删了顶多丢历史，Release 资产删错会**当场
   打断玩家的下载链接**。所以它单独开关，且默认保留最新的一份。
3. **保留最近 N 次运行**（`--keep-runs`，默认 3）。全删会让「上一次绿灯长什么样」
   也一起没了，排查回归时无从对照。

## 用法

    export =...                 # 需要 actions:write（自身仓库）
    python3 tools/purge-artifacts.py                     # dry-run，列出会删什么
    python3 tools/purge-artifacts.py --apply             # 真删 artifact/cache/日志
    python3 tools/purge-artifacts.py --apply --release-assets --yes-delete-release-assets

CI 里走 `.github/workflows/purge-artifacts.yml`（手动触发，勾选框对应上面几个开关）。
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.github.com"
SELF = os.environ.get("GITHUB_REPOSITORY", "")
ASSET_REPO = os.environ.get("TARGET_REPO", "").strip().strip("/")
RELEASE_TAG = os.environ.get("UPSTREAM_RELEASE_TAG", "latest")


def self_token():
    """删自身仓库的 artifact / cache / run 用的 token。

    优先内置的 ：workflow 里已经给了 actions:write。PAT 未必对本仓库
    有这个权限，拿它去删会 403——而 403 出现在「一键清理」里很容易被读成
    「没什么可删的」。两个 token 各管各的。
    """
    t = os.environ.get("") or os.environ.get("")
    if not t:
        raise SystemExit("没有  / ")
    return t


def asset_token():
    """删别的仓库上的 Release 资产用的 token —— 只有 PAT 够得着。"""
    t = os.environ.get("")
    if not t:
        raise SystemExit("没有 ：删别的仓库的 Release 资产要一枚能写它的 PAT")
    return t


def api(path, token, method="GET", retries=4):
    """带退避重试。删除是幂等的（已经没了会 404），所以 404 当成功。"""
    delay = 2
    for attempt in range(retries + 1):
        req = urllib.request.Request(API + path, method=method)
        req.add_header("Authorization", "Bearer " + token)
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                body = r.read()
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            if e.code == 404 and method == "DELETE":
                return {}
            if e.code in (401, 403, 404) or attempt == retries:
                raise SystemExit("%s %s → HTTP %d\n  %s"
                                 % (method, path, e.code,
                                    e.read().decode("utf-8", "replace")[:300]))
        except Exception as e:
            if attempt == retries:
                raise SystemExit("%s %s 失败：%s" % (method, path, e))
        time.sleep(delay)
        delay *= 2


def paged(path, token, key):
    """按页取完。GitHub 每页最多 100。"""
    out, page = [], 1
    while True:
        sep = "&" if "?" in path else "?"
        d = api("%s%sper_page=100&page=%d" % (path, sep, page), token)
        items = d.get(key) or []
        out.extend(items)
        if len(items) < 100:
            return out
        page += 1
        if page > 50:                     # 5000 条封顶，防止翻页失控
            return out


def mb(n):
    return "%.1f MB" % (n / 1e6)


def purge_artifacts(token, repo, apply_):
    items = paged("/repos/%s/actions/artifacts" % repo, token, "artifacts")
    live = [a for a in items if not a.get("expired")]
    print("\n== workflow artifact：%d 个（未过期 %d 个，共 %s）=="
          % (len(items), len(live), mb(sum(a.get("size_in_bytes", 0) for a in live))))
    for a in live:
        print("   %-42s %-10s %s" % (a["name"][:42], mb(a.get("size_in_bytes", 0)),
                                     a.get("created_at", "")[:10]))
        if apply_:
            api("/repos/%s/actions/artifacts/%d" % (repo, a["id"]), token, "DELETE")
    return len(live)


def purge_caches(token, repo, apply_):
    items = paged("/repos/%s/actions/caches" % repo, token, "actions_caches")
    print("\n== Actions cache：%d 个（共 %s）=="
          % (len(items), mb(sum(c.get("size_in_bytes", 0) for c in items))))
    for c in items:
        print("   %-42s %-10s" % (c.get("key", "")[:42], mb(c.get("size_in_bytes", 0))))
        if apply_:
            api("/repos/%s/actions/caches/%d" % (repo, c["id"]), token, "DELETE")
    return len(items)


def purge_runs(token, repo, apply_, keep):
    """删 run 会连日志一起删。

    保留最近 keep 次：全删会让「上一次绿灯长什么样」也没了，出回归时无从对照。
    """
    runs = paged("/repos/%s/actions/runs" % repo, token, "workflow_runs")
    runs.sort(key=lambda r: r.get("created_at", ""), reverse=True)
    doomed = runs[keep:]
    print("\n== workflow run：共 %d 次，保留最近 %d 次，删 %d 次（含日志）=="
          % (len(runs), min(keep, len(runs)), len(doomed)))
    for r in doomed[:40]:
        print("   #%-6s %-28s %s" % (r.get("run_number"), (r.get("name") or "")[:28],
                                     r.get("created_at", "")[:10]))
    if len(doomed) > 40:
        print("   …还有 %d 次" % (len(doomed) - 40))
    if apply_:
        for r in doomed:
            api("/repos/%s/actions/runs/%d" % (repo, r["id"]), token, "DELETE")
    return len(doomed)


def purge_release_assets(token, repo, tag, apply_, keep_names):
    """删对外 Release 上的旧资产。

    **这一类删错会当场打断玩家的下载链接**，所以：默认只在明确开关下运行，
    且 keep_names 里的（当前对外直链指向的那些）一律不动。
    """
    rel = api("/repos/%s/releases/tags/%s" % (repo, tag), token)
    assets = rel.get("assets", [])
    doomed = [a for a in assets if a["name"] not in keep_names]
    print("\n== Release 资产（%s @ %s）：共 %d 个，保留 %d 个，删 %d 个 =="
          % (repo, tag, len(assets), len(assets) - len(doomed), len(doomed)))
    for a in assets:
        mark = "删" if a in doomed else "留"
        print("   [%s] %-46s %s" % (mark, a["name"][:46], mb(a.get("size", 0))))
    if apply_:
        for a in doomed:
            api("/repos/%s/releases/assets/%d" % (repo, a["id"]), token, "DELETE")
    return len(doomed)


def main():
    ap = argparse.ArgumentParser(description="清掉还挂在外面的旧构建产物",
                                 formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__)
    ap.add_argument("--apply", action="store_true", help="真删；不给就只列不删")
    ap.add_argument("--artifacts", action="store_true")
    ap.add_argument("--caches", action="store_true")
    ap.add_argument("--runs", action="store_true",
                    help="删旧 run（连带日志一起删）")
    ap.add_argument("--keep-runs", type=int, default=3)
    ap.add_argument("--release-assets", action="store_true",
                    help="连对外 Release 上的旧资产一起删（危险，见 --yes-…）")
    ap.add_argument("--yes-delete-release-assets", action="store_true",
                    help="确认删 Release 资产。删错会当场打断玩家的下载链接，"
                         "所以单独要一次确认")
    ap.add_argument("--keep-asset", action="append", default=[],
                    help="Release 上要保留的资产名，可多次给；默认保留当前对外直链那份")
    args = ap.parse_args()

    # ⚠ 一个类别都没选时**直接报错，不要「默认全开」**。
    # 原先写的是「都没给就全开」，而 CI 那边是按勾选框逐个传参的——用户在界面上
    # 把三个框全取消，传进来就是一个都没有，于是「我什么都不想删」会被翻译成
    # 「全删」。删除工具里这种反转是最坏的一类默认值。
    if not (args.artifacts or args.caches or args.runs or args.release_assets):
        raise SystemExit(
            "一个类别都没选。要删什么得说出来：\n"
            "  --artifacts   workflow artifact（旧 APK 在这里）\n"
            "  --caches      Actions cache\n"
            "  --runs        旧 run 及其日志\n"
            "  --release-assets  对外 Release 上的旧资产（另需 --yes-…）")

    token = self_token()
    if not SELF:
        raise SystemExit("拿不到 GITHUB_REPOSITORY（本脚本要在 Actions 里跑，"
                         "或自己 export 一个 owner/repo）")

    if not args.apply:
        print("※ dry-run：只列不删。确认无误后加 --apply。")

    total = 0
    if args.artifacts:
        total += purge_artifacts(token, SELF, args.apply)
    if args.caches:
        total += purge_caches(token, SELF, args.apply)
    if args.runs:
        total += purge_runs(token, SELF, args.apply, args.keep_runs)

    if args.release_assets:
        if not args.yes_delete_release_assets:
            raise SystemExit(
                "\n--release-assets 要连 --yes-delete-release-assets 一起给。\n"
                "  这一类删错会当场打断玩家的下载链接，所以不给一次「顺手就删了」的机会。")
        if not ASSET_REPO:
            raise SystemExit("没有 TARGET_REPO，不知道该动哪个 Release")
        keep = set(args.keep_asset) or {"magireco-latest-legacy-client.apk"}
        print("\n（Release 上保留：%s）" % "、".join(sorted(keep)))
        total += purge_release_assets(asset_token(), ASSET_REPO, RELEASE_TAG,
                                      args.apply, keep)

    print("\n%s %d 项" % ("已删除" if args.apply else "将删除（dry-run）", total))

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("\n### 旧产物清理\n\n%s **%d 项**。\n"
                    % ("已删除" if args.apply else "dry-run，将删除", total))
            if not args.apply:
                f.write("\n> 这次没有真删。确认列表无误后，重新触发并勾上「真的删除」。\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
