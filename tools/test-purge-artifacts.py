#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""purge-artifacts.py 的离线自测 —— 把 api() 换成假的，不碰真的 GitHub。

删除工具尤其不能「写完那天跑一次绿灯就算数」：它平时不跑，真跑的那次又正好是
你最不想让它出错的时候。这里钉住五条：

    ▸ 一个类别都没选 → 报错，**不会翻成全删**（原先的默认值就是这么反的）
    ▸ 不给 --apply → 只列不删
    ▸ --apply → 删 artifact/cache/run，且保留最近 N 次 run
    ▸ Release 资产缺二次确认 → 拒绝
    ▸ Release 只删旧资产，当前对外直链那份一律不动
"""

import importlib.util, io, os, sys, contextlib
os.environ["GITHUB_REPOSITORY"]="o/r"; os.environ[""]="x"
os.environ["TARGET_REPO"]="a/b"; os.environ[""]="pat"
HERE=os.path.dirname(os.path.abspath(__file__))
spec=importlib.util.spec_from_file_location("pa", os.path.join(HERE,"purge-artifacts.py"))
pa=importlib.util.module_from_spec(spec); spec.loader.exec_module(pa)
DELETED=[]
def fake(path, token, method="GET", retries=4):
    if method=="DELETE": DELETED.append(path); return {}
    if "/artifacts" in path:
        return {"artifacts":[{"id":1,"name":"apk","size_in_bytes":75_000_000,"created_at":"2026-08-10","expired":False},
                             {"id":2,"name":"old","size_in_bytes":1,"created_at":"2026-08-01","expired":True}]}
    if "/caches" in path: return {"actions_caches":[{"id":9,"key":"deps","size_in_bytes":5_000_000}]}
    if "/runs" in path:
        return {"workflow_runs":[{"id":i,"run_number":i,"name":"构建","created_at":"2026-08-%02d"%i} for i in range(10,4,-1)]}
    if "/releases/tags/" in path:
        return {"assets":[{"id":7,"name":"magireco-latest-legacy-client.apk","size":75_000_000},
                          {"id":8,"name":"legacy-client-archive-tags.bundle","size":120_000_000}]}
    return {}
pa.api=fake
def run(argv):
    DELETED.clear(); sys.argv=["x"]+argv; buf=io.StringIO()
    try:
        with contextlib.redirect_stdout(buf): pa.main()
        return 0, buf.getvalue()
    except SystemExit as e:
        return (e.code if isinstance(e.code,int) else str(e.code)), buf.getvalue()+str(e.code)
fails=[]
c,out=run([])
if "一个类别都没选" not in out: fails.append("全不选应报错，实得: %r" % out[:80])
else: print("  ✓ 一个类别都没选 → 报错，不会翻成全删")
c,out=run(["--artifacts","--caches","--runs"])
if DELETED: fails.append("dry-run 不该删，实删 %s" % DELETED)
else: print("  ✓ dry-run 只列不删")
c,out=run(["--artifacts","--caches","--runs","--apply"])
n=len(DELETED)
if n!=1+1+3: fails.append("应删 1 artifact + 1 cache + 3 run（保留最近 3 次，共 6 次），实得 %d：%s"%(n,DELETED))
else: print("  ✓ --apply 删 artifact/cache/run，且保留最近 3 次 run")
c,out=run(["--release-assets","--apply"])
if "yes-delete-release-assets" not in out: fails.append("Release 资产缺二次确认时应报错")
else: print("  ✓ Release 资产没给二次确认 → 拒绝")
c,out=run(["--release-assets","--yes-delete-release-assets","--apply"])
if any("/releases/assets/7" in d for d in DELETED): fails.append("当前对外直链那份不该被删")
elif not any("/releases/assets/8" in d for d in DELETED): fails.append("旧资产没被删")
else: print("  ✓ Release 只删旧资产，保留当前对外直链那份")
print("\n" + ("自测未通过：\n  "+"\n  ".join(fails) if fails else "purge-artifacts 自测全部通过"))
sys.exit(1 if fails else 0)
