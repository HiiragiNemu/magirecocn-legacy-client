#!/usr/bin/env python3
"""`last-green.yml` 里那段「tag 该不该移」的判断，离线跑四种时序。

## 为什么值得单独测

这段判断只有在 CI 上、且只有在特定时序下才会执行到，出错的表现全都是**沉默的**：

* 该拦没拦 → `last-green` 被往回拖，所有人 `git checkout last-green` 拿到旧代码；
* 该放没放 → 这个 job 每次 push 都红，tag 永远停在原地。

后一种是真撞过的：主线历史整段重写（`filter-repo` 或 rebase 整条主线）之后，
旧 tag 落在一条已经没人要的历史上，与新候选互不为祖先，判断直接走 `exit 1`。
后果是两头都坏——tag 一直钉着那段旧历史不放，而且每次 push 都红、tag 永远
动不了，只能靠人去网页手删 tag 才解开。

## 做法

不复制逻辑，而是**从 workflow 里把那段 shell 抠出来直接跑**：复制一份就会漂移，
而漂移了的测试比没有测试更糟。截到 `git tag -f` 之前，因为后面是真的 push，
判断本身在那之前就已经做完了。

判据不是「输出里有没有某个词」，而是把输出归成**四种判决**之一再比对——
①（前移）和②（不回退）都是 exit 0，只看退出码分不开，而这两者正好是
「tag 动不动」的分界，最不能混。
"""

import os
import shutil
import subprocess
import tempfile
import textwrap
from pathlib import Path

WF = Path(".github/workflows/last-green.yml")

START = "# F-020：last-green 只能向前移动"
END = 'git tag -f last-green "${GITHUB_SHA}"'


def decision_script() -> str:
    """从 workflow 抠出「判断」那一段（fetch 之后、真正改 tag 之前）。

    刻意**不**用 PyYAML：runner 上装没装它不由本仓库决定，而这个测试要是
    因为缺依赖而跳过，等于没有。两个锚点都是那段 shell 里的原文，切出来
    按最短缩进对齐即可。
    """
    text = WF.read_text(encoding="utf-8")
    i = text.find(START)
    j = text.find(END)
    if i < 0 or j < 0 or j < i:
        raise SystemExit(
            "在 last-green.yml 里找不到判断段的边界。改过那一步就把这里的锚点一起改：\n"
            "  起点 %r\n  终点 %r" % (START, END)
        )
    # 从 START 所在行的行首切起，保住它的缩进，dedent 才算得对
    i = text.rfind("\n", 0, i) + 1
    j = text.rfind("\n", 0, j) + 1
    body = textwrap.dedent(text[i:j])
    # `set -euo pipefail` 在原步骤的第一行，抠出来的片段要自己带上——
    # 少了它，中间某条 git 失败会被无声吞掉，测试就测了个寂寞。
    return "set -euo pipefail\n" + body


def git(repo, *args):
    return subprocess.run(
        ["git", "-C", str(repo)] + list(args),
        check=True, capture_output=True, text=True,
    ).stdout.strip()


def commit(repo, msg):
    (Path(repo) / "f").write_text(msg, encoding="utf-8")
    git(repo, "add", "f")
    git(repo, "commit", "-qm", msg)
    return git(repo, "rev-parse", "HEAD")


def new_repo(tmp, name):
    repo = Path(tmp) / name
    repo.mkdir()
    git(repo, "init", "-q", "-b", "main")
    git(repo, "config", "user.name", "t")
    git(repo, "config", "user.email", "t@t")
    return repo


def run_decision(script, repo, github_sha):
    """跑一次判断，返回 (判决, 合并输出)。

    判决只有四种，与 workflow 里那三条出口一一对应：

        拒绝     —— exit 1，tag 不动，job 红
        不回退   —— exit 0 但明说了不动（旧 run 乱序）
        重写放行 —— exit 0，且认定旧 tag 落在被弃的历史上
        前移     —— exit 0 且一声不吭，落到后面的 git tag -f
    """
    p = subprocess.run(
        ["bash", "-c", script],
        cwd=str(repo), text=True, capture_output=True,
        env=dict(os.environ, GITHUB_SHA=github_sha),
    )
    out = (p.stdout or "") + (p.stderr or "")
    if p.returncode != 0:
        verdict = "拒绝"
    elif "不回退" in out:
        verdict = "不回退"
    elif "判定为历史被重写" in out:
        verdict = "重写放行"
    else:
        verdict = "前移"
    return verdict, out


# ── 四种时序 ──────────────────────────────────────────────────────────
#
# 每种都真的建一个 git 仓库摆出那个形状，而不是塞假的 rev-parse 输出：
# 这段逻辑全靠 merge-base 的真实行为，假输出测不出祖先关系算错。

def case_forward(tmp):
    """① 正常前移：旧 tag 是候选的祖先。"""
    r = new_repo(tmp, "forward")
    old = commit(r, "a")
    new = commit(r, "b")
    git(r, "update-ref", "refs/remotes/origin/main", new)
    git(r, "update-ref", "refs/tags/last-green", old)
    return r, new, "前移"


def case_stale_run(tmp):
    """② 旧 run 乱序完成：候选是旧 tag 的祖先，必须原地不动。"""
    r = new_repo(tmp, "stale")
    old_sha = commit(r, "a")
    newer = commit(r, "b")
    git(r, "update-ref", "refs/remotes/origin/main", newer)
    git(r, "update-ref", "refs/tags/last-green", newer)
    return r, old_sha, "不回退"


def case_rewritten(tmp):
    """③ 历史被重写：旧 tag 的提交已不在 origin/main 上，应放行强推。"""
    r = new_repo(tmp, "rewritten")
    stale = commit(r, "old-history")
    git(r, "update-ref", "refs/tags/last-green", stale)
    # 另起一条与它无关的历史当作重写后的主线
    git(r, "checkout", "-q", "--orphan", "rewritten")
    (Path(r) / "f").write_text("new-history", encoding="utf-8")
    git(r, "add", "f")
    git(r, "commit", "-qm", "new-history")
    new = git(r, "rev-parse", "HEAD")
    git(r, "update-ref", "refs/remotes/origin/main", new)
    return r, new, "重写放行"


def case_real_fork(tmp):
    """④ 真分叉：旧 tag 仍在 origin/main 上却与候选互不为祖先，必须拦。

    线性历史里造不出这个形状（两个都在 main 上就必有祖先关系），得靠一个
    合并提交把两条支线都收进 main。
    """
    r = new_repo(tmp, "fork")
    base = commit(r, "base")
    left = commit(r, "left")
    git(r, "checkout", "-q", base)
    (Path(r) / "g").write_text("right", encoding="utf-8")
    git(r, "add", "g")
    git(r, "commit", "-qm", "right")
    right = git(r, "rev-parse", "HEAD")
    git(r, "checkout", "-q", "main")
    git(r, "merge", "-q", "--no-ff", "-m", "merge", right)
    merged = git(r, "rev-parse", "HEAD")
    git(r, "update-ref", "refs/remotes/origin/main", merged)
    git(r, "update-ref", "refs/tags/last-green", right)
    # 候选 = left：在 origin/main 上，但与 right 互不为祖先
    return r, left, "拒绝"


CASES = [
    ("① 旧 tag 是候选祖先 → 前移", case_forward),
    ("② 旧 run 乱序完成 → 不回退", case_stale_run),
    ("③ 历史被重写 → 重写放行", case_rewritten),
    ("④ 真分叉 → 拒绝", case_real_fork),
]


def main():
    script = decision_script()
    tmp = tempfile.mkdtemp(prefix="last-green-test-")
    failures = []
    try:
        for name, build in CASES:
            repo, sha, want = build(tmp)
            got, out = run_decision(script, repo, sha)
            ok = got == want
            print(("PASS " if ok else "FAIL ") + name)
            if not ok:
                failures.append(
                    "%s：期望判决「%s」，实得「%s」\n---- 输出 ----\n%s"
                    % (name, want, got, out.strip() or "(无输出)")
                )
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    if failures:
        print()
        for f in failures:
            print(f)
        raise SystemExit("last-green tag 移动判断自测未通过")
    print("\nlast-green tag 移动判断：全部通过")


if __name__ == "__main__":
    main()
