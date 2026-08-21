#!/usr/bin/env python3
"""守卫 F-088：同一热更槽位的提交必须单调。

## 故障链

启动检查取到 v2 并下载完，continuation 还没拿到资源提交锁；玩家这时对同一槽位
点「重下」，手动链取到 v3、先提交并写下 localVersion=3。旧的启动 continuation
后到，而它只靠全局 `extractCommitLock()` 串行化——那把锁只保证两笔事务不同时改
活动树，**不保证同一槽位版本单调**。于是 v2 覆盖回去，玩家的手动更新被静默降级。

## 这里锁住什么

单调性靠四件事同时成立，缺一条就能被绕回去：

1. 每槽位有独立的 lifecycleLock（全局锁不够）；
2. 进入真正的修改窗口**之后**要再读一次当前版本（只在窗口外读等于没读）；
3. 候选更旧时只丢弃，绝不触碰活动树、绝不回写低版本；
4. `CNHotUpdateTx.apply()` 在本文件里只有一个调用点——只要还有第二个直接
   apply，前三条都能被绕过。

另外锁死锁序 lifecycleLock → extractCommitLock。反过来拿会死锁，而那种写法
编译得过、单线程测也过，只有真机并发才炸。
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _guardlib import body, code   # noqa: E402

JAVA = Path("patch/src/main/java/io/kamihama/magianative")
src = (JAVA / "CNHotUpdateCheck.java").read_text(encoding="utf-8")
src_code = code(src)

helper = body(
    src,
    "private static int commitVerifiedPackage(Pkg pkg, File tmp,",
)

# 助手体内两次 readLocalVersion：一次在 lifecycleLock 内、extractCommitLock 外，
# 一次在 extractCommitLock 内。
reads = helper.count("readLocalVersion(pkg.versionKey)")

# 锁序：lifecycleLock 必须先于 extractCommitLock 出现
life_at = helper.find("synchronized (pkg.lifecycleLock)")
glob_at = helper.find("synchronized (CNDownloaderFix.extractCommitLock())")

# apply 的真实调用点（去注释后）
apply_calls = src_code.count("CNHotUpdateTx.apply(")
# 版本回写的真实调用点：只应出现在助手里
saves = src_code.count("saveLocalVersion(pkg.versionKey")

checks = {
    "每槽位有独立的 lifecycleLock":
        "final Object lifecycleLock = new Object();" in src_code,
    "提交助手存在且被两条链共用":
        bool(helper)
        and src_code.count('commitVerifiedPackage(pkg, tmp, meta, "startup")') == 1
        and src_code.count('commitVerifiedPackage(pkg, tmp, meta, "manual")') == 1,
    "助手内读了两次当前版本（窗口外一次、窗口内一次）":
        reads == 2,
    "锁序固定为 lifecycleLock → extractCommitLock":
        life_at >= 0 and glob_at >= 0 and life_at < glob_at,
    "候选更旧时提前返回，不进入修改窗口":
        helper.count("return HOT_COMMIT_STALE;") == 2,
    "陈旧分支不触碰活动树也不回写版本":
        # STALE 的两个 return 都排在 apply 之前
        helper.index("CNHotUpdateTx.apply(") > helper.rindex("return HOT_COMMIT_STALE;"),
    "CNHotUpdateTx.apply 在本文件只有一个调用点":
        apply_calls == 1,
    "版本回写也只有助手里那一处":
        saves == 1,
    "F-067 的「内容已应用但版本没落盘」仍是显式部分失败":
        "HOT_COMMIT_STATE_FAILED" in helper
        and src_code.count("commitResult == HOT_COMMIT_STATE_FAILED") == 2,
    "两条链都处理了陈旧结果，没有默默当成功":
        src_code.count("commitResult == HOT_COMMIT_STALE") == 2,
    "下载阶段不持有 lifecycleLock（锁只出现在助手里）":
        src_code.count("synchronized (pkg.lifecycleLock)") == 1,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS " if ok else "FAIL ") + name)
if failed:
    raise SystemExit("hotupdate monotonic contract failed: " + ", ".join(failed))
