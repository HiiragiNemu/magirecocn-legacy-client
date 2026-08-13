# -*- coding: utf-8 -*-
"""pre-push 钩子：把 AGENTS.md §0 的分支纪律与 §1 的提交规范变成硬拦截。

装法见 tools/install-hooks.sh。

## 拦什么

两件事，互相独立，各有各的逃生口。

### 一、本次推送新增的提交，信息不合规（§1）

判据与 commit-msg 完全一致（共用 tools/githooks/_msgrules.py）。之所以在推送时
再查一遍：commit-msg 只管得住「提交这个动作发生在这份克隆里」。2026-08-08 进来的
那 12 个英文标题提交是在别处产生、然后作为分支推进来的，commit-msg 从头到尾没有
机会运行——**只有 commit-msg 的话，这套东西挡不住当初催生它的那件事**。

只查**本次推送新增的**提交：已经在远端的历史一律不碰（`remote..local`，新建分支
则是 `local --not --remotes=origin`），另外再加一道时间闸 CUTOFF_EPOCH，
早于它的提交一律不查。所以既不会翻旧账，也不会因为历史里有不合规的老提交就把
第一次推送卡死。合并提交（多个父）跳过——那是 git 自己生成的信息。

逃生口：`SKIP_MSG_HOOK=1 git push ...`

### 二、新建远端分支（§0）

只有**新建**这一个动作。已存在的分支继续推、推 main、删分支，都放行。

新建分支时按 AGENTS.md §0 判定：

  · 白名单（main / hotfix/* / surgery/*——只有这三类，没有第三种分支；
    另有三个具名临时例外，见 ALLOW 表注释）—— 放行；
  · 远端已有非白名单分支 —— **拦**，让你接着用那一条（规则二：全会话一条）；
  · 其中有 2 小时内活动过的 —— **拦**，并指名是哪一条（规则三）；
  · 名字像 CI 触发器（ci/*、build/*、*-driver-*、*-success、带 9 位以上数字的
    run-id）—— **拦**（§2）。日期后缀 -YYYYMMDD 是 8 位，不会误伤。

有价值的历史分支打 `archive/*` tag 留存，不作为分支存在。

## 为什么拦在 pre-push 而不是 checkout

本地开分支无所谓，随便开。**留在远端**才是问题——维护者这几天逐条手工删了
六条一次性分支。所以闸门设在「往远端推一条新分支」这一刻。

### 三、红灯期间推 main（红灯协议，CONTRIBUTING.md §五）

只在**往 main 推**时查。联网问 GitHub API：main 上最近一次**已跑完**的
「🟢 全量回归 + last-green」是什么结论。是 failure（红灯）时，本次推送新增的
提交**必须全是修复类**（标题以 `fix(` / `fix:` / `revert` 开头），否则拦下。
查不到（没网、API 限流、返回变了）一律放行并提示——护栏坏了让路，不锁仓库。

逃生口：`SKIP_REDLIGHT_HOOK=1 git push ...`

## 逃生口

    SKIP_MSG_HOOK=1       git push ...   跳过提交信息检查
    SKIP_BRANCH_HOOK=1    git push ...   跳过分支纪律检查
    SKIP_REDLIGHT_HOOK=1  git push ...   跳过红灯闸门
    SKIP_POLICY_HOOK=1    git push ...   跳过强制约定保全检查（§八）

用它意味着你**明确知道自己在跳过什么**，并且准备好向维护者解释。
"""

import os
import re
import subprocess
import sys
import time

HOOK_NAME = "pre-push"
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    import _msgrules                                          # noqa: E402
except ImportError:
    # 判据模块不在 → **放行**，不是拦下。缺一个文件就让整个仓库提交不了/推不了，
    # 比它想强制的任何规则都糟糕：报错还只有一段 Python traceback，没人看得懂。
    # 常见成因：把钩子单独拷进 .git/hooks/ 时漏了这个不像钩子的文件。
    sys.stderr.write(
        "\n⚠ %s: 找不到同目录下的 _msgrules.py（提交信息判据），本次跳过检查。\n"
        "  它是两个钩子共用的判据模块，必须和它们放在一起。\n"
        "  正确装法是让 core.hooksPath 指向整个目录：bash tools/install-hooks.sh\n"
        "  ——而不是把单个钩子文件拷进 .git/hooks/。\n\n" % HOOK_NAME)
    sys.exit(0)

# 提交信息检查只查这个时刻之后的提交（2026-08-08 00:10 UTC，本检查上线时）。
# 「不限制历史提交，从现在开始检查」——历史里的老提交不翻旧账，否则谁 rebase
# 一下旧分支都会被一堆与自己无关的提交卡死。
CUTOFF_EPOCH = 1786147800
MAX_COMMITS = 200                 # 单次推送最多查这么多，超出的不查（护栏不是审计）

ALLOW = (re.compile(r"^main$"), re.compile(r"^hotfix/"), re.compile(r"^surgery/"),
         # ── 具名临时例外（2026-08-09 维护者特批）──────────────────
         # 这两条是别的会话正在跑的活，允许活到合并进 main 为止；
         # 合并删除后把对应行从本表移除，不要往这里加新名字。
         re.compile(r"^feature/battle-engine-i18n-20260808$"),
         re.compile(r"^feature/native-i18n-authority-20260809$"))
CI_SHAPED = (
    re.compile(r"^ci/"), re.compile(r"^build/"),
    re.compile(r"-driver-"), re.compile(r"-success$"),
    # 分支名里带 run-id 这种长数字。**9 位起**，不是 8 位：本仓库自己的命名习惯
    # 就带 -YYYYMMDD 日期后缀（archive/legacy-client-runtime-i18n-20260806），
    # 8 位会把每一条按规矩起名的分支都误判成 CI 触发器，还给出完全不着边的理由。
    # GitHub 的 run-id 是 11 位（31212457531），两者不会撞。
    re.compile(r"\d{9,}"),
)
RECENT_HOURS = 2.0

# 红灯闸门：标题以这些开头的算「修复类」提交（红灯期间唯一允许推 main 的）
FIXISH = re.compile(r"^\s*(fix[(：:]|revert)", re.IGNORECASE)
REDLIGHT_API = ("https://api.github.com/repos/{slug}/actions/workflows/"
                "last-green.yml/runs?branch=main&status=completed&per_page=1")
REDLIGHT_TIMEOUT = 15


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True, timeout=120)


def allowed(name):
    return any(p.search(name) for p in ALLOW)


def parse_stdin():
    """stdin: <local ref> <local sha> <remote ref> <remote sha>，逐行。

    返回 [(local_sha, branch, remote_sha)]，只含 refs/heads/ 且非删除的条目。
    全零 OID = 「不存在」；长度随仓库哈希算法变（SHA-1 40 / SHA-256 64），
    所以按「全是 0」判，别写死 40。
    """
    out = []
    for line in sys.stdin.read().split("\n"):
        parts = line.split()
        if len(parts) != 4:
            continue
        _, local_sha, remote_ref, remote_sha = parts
        if not remote_ref.startswith("refs/heads/"):
            continue
        if set(local_sha) == {"0"}:                  # 删除分支，放行
            continue
        out.append((local_sha, remote_ref[len("refs/heads/"):], remote_sha))
    return out


def remote_name():
    """git 把远端名作为第一个参数传给 pre-push。取不到就退回 origin。"""
    return sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] else "origin"


def new_commits(local_sha, remote_sha):
    """本次推送**新增**的提交 SHA 列表（不含合并提交）。

    已存在的分支用 remote..local，精确且不需要任何远端信息；
    新建分支用 local --not --remotes=<远端>，把已经在那个远端的历史全部排除掉。
    远端名取自 git 传进来的参数,不写死 origin——推到 fork 之类的第二个远端时,
    写死会拿错排除集,把对方早就有的提交当成新增的重查一遍。
    """
    if set(remote_sha) != {"0"}:
        rng = ["%s..%s" % (remote_sha, local_sha)]
    else:
        rng = [local_sha, "--not", "--remotes=%s" % remote_name()]
    r = sh("git", "rev-list", "--no-merges",
           "--max-count=%d" % MAX_COMMITS, *rng)
    if r.returncode != 0:
        return []                                    # 算不出来就不查（fail-open）
    return [x for x in r.stdout.split() if x]


def check_messages(refs):
    """检查本次推送新增提交的信息。返回给用户看的问题列表。"""
    bad, seen = [], set()
    for local_sha, branch, remote_sha in refs:
        for sha in new_commits(local_sha, remote_sha):
            if sha in seen:          # 同一个提交出现在两条被推的分支上，只报一次
                continue
            seen.add(sha)
            r = sh("git", "log", "-1", "--format=%ct%x1f%B", sha)
            if r.returncode != 0:
                continue
            ts, _, raw = r.stdout.partition("\x1f")
            try:
                if int(ts.strip()) < CUTOFF_EPOCH:   # 历史提交不翻旧账
                    continue
            except ValueError:
                continue
            probs = _msgrules.problems(raw)
            if probs:
                subj = (raw.strip().split("\n") or [""])[0][:60]
                bad.append((sha[:8], branch, subj, probs))
    return bad


def report_messages(bad):
    sys.stderr.write("\n✘ push 被 pre-push 钩子拦下：%d 个提交的信息不合规\n\n"
                     % len(bad))
    for sha, branch, subj, probs in bad:
        sys.stderr.write("  %s (%s)  %s\n" % (sha, branch, subj))
        for p in probs:
            sys.stderr.write("      · %s\n" % p.replace("\n      ", "\n        "))
        sys.stderr.write("\n")
    sys.stderr.write("  这一道查的是**本次推送新增的**提交——commit-msg 只管得住在\n"
                     "  这份克隆里做的提交，在别处提交再推进来的它看不见。\n\n")
    sys.stderr.write("  改法: git rebase -i --exec 'git commit --amend' 或重写这几条的信息\n")
    sys.stderr.write("  规则出处: CLAUDE.md「提交约定」/ AGENTS.md §1\n")
    sys.stderr.write("  确需跳过: SKIP_MSG_HOOK=1 git push ...\n\n")


def repo_slug():
    """从远端 URL 解出 owner/repo。解不出来返回 None（本检查 fail-open）。"""
    r = sh("git", "remote", "get-url", remote_name())
    if r.returncode != 0:
        return None
    url = r.stdout.strip()
    m = re.search(r"github\.com[:/]([^/]+/[^/]+?)(?:\.git)?$", url)
    return m.group(1) if m else None


def remote_main_is_red():
    """main 最近一次已跑完的全量回归是不是红灯。查不到返回 None。"""
    slug = repo_slug()
    if not slug:
        return None
    import json
    import urllib.request
    req = urllib.request.Request(
        REDLIGHT_API.format(slug=slug),
        headers={"Accept": "application/vnd.github+json",
                 "User-Agent": "magireco-pre-push-hook"})
    try:
        with urllib.request.urlopen(req, timeout=REDLIGHT_TIMEOUT) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        runs = data.get("workflow_runs") or []
        if not runs:
            return None
        return runs[0].get("conclusion") == "failure"
    except Exception:
        return None


def check_redlight(refs):
    """红灯期间推 main：新增提交必须全是修复类。返回非修复类提交的 (sha, subj) 列表；
    不在推 main、不是红灯、或查不到状态，都返回空（fail-open）。"""
    main_refs = [(l, b, r) for l, b, r in refs if b == "main"]
    if not main_refs:
        return []
    red = remote_main_is_red()
    if red is None:
        sys.stderr.write("pre-push: 红灯状态查询失败（网络/API），本次跳过红灯闸门\n")
        return []
    if not red:
        return []
    bad, seen = [], set()
    for local_sha, _, remote_sha in main_refs:
        for sha in new_commits(local_sha, remote_sha):
            if sha in seen:
                continue
            seen.add(sha)
            r = sh("git", "log", "-1", "--format=%s", sha)
            if r.returncode != 0:
                continue
            subj = r.stdout.strip()
            if not FIXISH.search(subj):
                bad.append((sha[:8], subj))
    return bad


def report_redlight(bad):
    sys.stderr.write("\n✘ push 被 pre-push 钩子拦下：main 现在是 🔴 红灯\n\n"
                     "  最近一次全量回归失败。红灯期间只许修复主线的提交\n"
                     "  （标题以 fix( / fix: / revert 开头），本次推送里有\n"
                     "  %d 个提交不属于修复类：\n\n" % len(bad))
    for sha, subj in bad:
        sys.stderr.write("      %s  %s\n" % (sha, subj[:60]))
    sys.stderr.write("\n  规则出处: CONTRIBUTING.md「红灯协议」\n"
                     "  稳定锚点: git checkout last-green\n"
                     "  确需跳过: SKIP_REDLIGHT_HOOK=1 git push ...\n\n")


# ────────────────────────────────────────────────────────────────
# 四、强制约定保全（CONTRIBUTING §八 非主线分支发版强制约定）
#
# 约定本身「禁止更改」：删掉 §八 / 规则四 / 铁律 7，等于把发版纪律从仓库里
# 抹掉。ruleset 挡的是 force push / 删分支——那是「推翻整个分支」；这一道挡
# 「改约定文件内容」：凡本次推送新增的提交改了三个约定文件之一，改动后必须
# 仍含各自的强制约定标记，否则拦。
#
# 判据是「标记必须还在」，不是「内容不许动」——§八 的措辞修正、错别字、
# 补细节都放行，只有把整段约定删掉/改成非强制才会被拦。
#
# 逃生口：SKIP_POLICY_HOOK=1 git push ...（改了约定还硬要推，向维护者交代）
# ────────────────────────────────────────────────────────────────

# 三个约定文件各自的「强制约定存在」标记：改了文件但标记不在了 = 约定被删。
# 用「章节标题 + 紧跟其后的禁止措辞」双条件，防只留标题、正文被掏空的写法。
POLICY_MARKERS = {
    "CONTRIBUTING.md": (
        "## 八、非主线分支发版强制约定",
        "本约定**禁止修改**",
    ),
    "AGENTS.md": (
        "### 规则四：非主线分支发版必须登记「停止支持开关」",
        "本约定**禁止修改**",
    ),
    "CLAUDE.md": (
        "7. **非主线分支发版强制约定（禁止更改）**",
        "本约定**禁止修改**",
    ),
}
POLICY_FILES = tuple(POLICY_MARKERS.keys())


def file_at(sha, path):
    """读某提交里一个文件的内容；没有这个文件返回 None。"""
    r = sh("git", "show", "%s:%s" % (sha, path))
    if r.returncode != 0:
        return None
    return r.stdout


def check_policy(refs):
    """检查本次推送新增提交有没有删掉/弱化强制约定标记。返回问题列表。"""
    bad, seen = [], set()
    for local_sha, branch, remote_sha in refs:
        for sha in new_commits(local_sha, remote_sha):
            if sha in seen:
                continue
            seen.add(sha)
            # 该提交动了哪个约定文件？
            r = sh("git", "diff-tree", "--no-commit-id", "--name-only",
                   "-r", sha, "--", *POLICY_FILES)
            if r.returncode != 0:
                continue
            touched = r.stdout.split()
            if not touched:
                continue
            # 改动前（父提交）与改动后都要有强制约定标记；父提交里本来就没有
            # 的（老分支首次补约定），只要求改后必须有。
            parent = sh("git", "rev-parse", "%s^" % sha)
            parent = parent.stdout.strip() if parent.returncode == 0 else ""
            for path in touched:
                after = file_at(sha, path)
                if after is None:
                    continue                        # 把整个文件删了也拦
                if not markers_present(path, after):
                    before = file_at(parent, path) if parent else None
                    # 文件是本次新建且标记齐全 → 放行；否则报
                    if before is not None and markers_present(path, before):
                        bad.append((sha[:8], branch, path))
    return bad


def markers_present(path, content):
    markers = POLICY_MARKERS[path]
    return all(m in content for m in markers)


def report_policy(bad):
    sys.stderr.write("\n✘ push 被 pre-push 钩子拦下：%d 个提交删除了强制约定\n\n"
                     % len(bad))
    for sha, branch, path in bad:
        sys.stderr.write("  %s (%s)  动了 %s，改动后强制约定标记不在了\n"
                         % (sha, branch, path))
    sys.stderr.write("\n  被删的约定：CONTRIBUTING.md §八「非主线分支发版强制约定」\n"
                     "  （AGENTS.md 规则四 / CLAUDE.md 铁律 7 同源）——\n"
                     "  非主线分支版本独立发版必须先登记 config.json 停止支持开关，\n"
                     "  主线跟上后关开关并强制推送主线最新版下载链接。\n"
                     "  本约定**禁止更改**，受 branch ruleset 保护。\n\n")
    sys.stderr.write("  规则出处: CONTRIBUTING.md §八\n"
                     "  确需跳过: SKIP_POLICY_HOOK=1 git push ...\n\n")


def main():
    refs = parse_stdin()
    if not refs:
        return 0

    rc = 0

    # ── 一、提交信息（§1）────────────────────────────────────
    if os.environ.get("SKIP_MSG_HOOK"):
        sys.stderr.write("pre-push: SKIP_MSG_HOOK=1，跳过提交信息检查\n")
    else:
        try:
            bad = check_messages(refs)
        except Exception:
            bad = []                                 # fail-open
        if bad:
            report_messages(bad)
            rc = 1

    # ── 三、红灯闸门（红灯协议）──────────────────────────────
    if os.environ.get("SKIP_REDLIGHT_HOOK"):
        sys.stderr.write("pre-push: SKIP_REDLIGHT_HOOK=1，跳过红灯闸门\n")
    else:
        try:
            red_bad = check_redlight(refs)
        except Exception:
            red_bad = []                             # fail-open
        if red_bad:
            report_redlight(red_bad)
            rc = 1

    # ── 四、强制约定保全（CONTRIBUTING §八 非主线分支发版约定）──
    if os.environ.get("SKIP_POLICY_HOOK"):
        sys.stderr.write("pre-push: SKIP_POLICY_HOOK=1，跳过强制约定检查\n")
    else:
        try:
            pol_bad = check_policy(refs)
        except Exception:
            pol_bad = []                             # fail-open
        if pol_bad:
            report_policy(pol_bad)
            rc = 1

    # ── 二、分支纪律（§0）────────────────────────────────────
    if os.environ.get("SKIP_BRANCH_HOOK"):
        sys.stderr.write("pre-push: SKIP_BRANCH_HOOK=1，跳过分支纪律检查\n")
        return rc

    new_branches = [b for local_sha, b, remote_sha in refs
                    if set(remote_sha) == {"0"}]
    if not new_branches:
        return rc

    problems = []
    for name in new_branches:
        if allowed(name):
            continue
        if any(p.search(name) for p in CI_SHAPED):
            problems.append(
                "`%s` 的名字像 CI 触发器。\n"
                "      AGENTS.md §2：**永远不要用分支触发 CI**。\n"
                "      构建由人类在网页上手动 workflow_dispatch；\n"
                "      想验能不能过 CI，按 AGENTS.md §3 在本地跑。" % name)
            continue
        problems.append("`%s` 是一条新的非白名单分支。" % name)

    if not problems:
        return rc

    # 远端现状：有没有可接续的 / 2 小时内动过的
    r = sh("git", "ls-remote", "--heads", remote_name())
    existing = []
    if r.returncode == 0:
        for line in r.stdout.splitlines():
            p = line.split()
            if len(p) == 2:
                n = p[1].replace("refs/heads/", "")
                if not allowed(n):
                    existing.append((p[0], n))

    fresh = []
    for sha, n in existing:
        t = sh("git", "log", "-1", "--format=%ct", sha)
        if t.returncode != 0:
            # 本地没有这个对象，取回来再问一次。**只写 FETCH_HEAD，不建 ref**——
            # 早先是 fetch 进 refs/remotes/hookcheck/<n>，那些 ref 没人清理，
            # 越攒越多，还会让被删分支的对象一直可达。
            if sh("git", "fetch", "--quiet", remote_name(), n).returncode != 0:
                continue
            t = sh("git", "log", "-1", "--format=%ct", "FETCH_HEAD")
        if t.returncode == 0 and t.stdout.strip():
            try:
                h = (time.time() - int(t.stdout.strip())) / 3600.0
                if h < RECENT_HOURS:
                    fresh.append((n, h))
            except ValueError:
                pass

    sys.stderr.write("\n✘ push 被 pre-push 钩子拦下：正在新建分支\n\n")
    for i, p in enumerate(problems, 1):
        sys.stderr.write("  %d. %s\n\n" % (i, p))

    if fresh:
        sys.stderr.write("  远端有 %.0f 小时内刚动过的分支——"
                         "AGENTS.md §0 规则三：接着用它，不许再开：\n" % RECENT_HOURS)
        for n, h in fresh:
            sys.stderr.write("      %s（%.1f 小时前）\n" % (n, h))
        sys.stderr.write("\n")
    elif existing:
        sys.stderr.write("  远端已有非白名单分支——AGENTS.md §0 规则二："
                         "全会话只许一条：\n")
        for _, n in existing:
            sys.stderr.write("      %s\n" % n)
        sys.stderr.write("\n")
    else:
        sys.stderr.write("  本仓库**直接提 main，没有 PR 流程**"
                         "（AGENTS.md §0 规则一）。\n"
                         "  多数情况根本不需要分支：\n"
                         "      git push -u origin main\n\n")

    sys.stderr.write("  详情: python3 tools/check-branch-hygiene.py --can-branch\n")
    sys.stderr.write("  确需跳过: SKIP_BRANCH_HOOK=1 git push ...\n\n")
    return 1


if __name__ == "__main__":
    # 顶层 fail-open：检查自身出了任何意外，都**放行**并把 traceback 打出来。
    # 不这么做的话，一个 FileNotFoundError 或 IndexError 会以退出码 1 冒出去，
    # git 当成「检查没过」——于是钩子自己的 bug 就把整个仓库锁死了，
    # 而屏幕上只有一段谁也看不懂的 Python traceback。
    # （SystemExit 继承自 BaseException，不会被 except Exception 吞掉，
    #   所以 main() 正常返回的 0/1 照常生效。）
    try:
        sys.exit(main())
    except Exception:
        import traceback
        sys.stderr.write(
            "\n\u26a0 " + HOOK_NAME + ": 检查自身出错，本次放行。"
            "请把下面这段贴给维护者：\n")
        traceback.print_exc()
        sys.exit(0)
