#!/usr/bin/env python3
"""守卫目录 fsync 的实现与契约。

## 这条守卫在防什么

`CNArchiveInstallTx.syncDir` 曾经用 `new RandomAccessFile(dir, "r")` 打开目录。
那种写法在 Android 上对目录**必定**抛 `FileNotFoundException: … EISDIR`——
2026-08-21 的玩家日志里 1386 次调用无一例外全部落进「该文件系统可能不支持」的
降级分支，也就是说：

* 目录级持久化**从来没有生效过**，而热更事务的崩溃恢复语义（journal 的持久化
  严格早于任何 rename）正是建立在它之上的；
* 附带地，那行降级警告占了某份玩家日志全部 1721 行里的 1072 行（62%），把真正
  要查的东西冲得干干净净。

正确写法（`Os.open(O_RDONLY)` + `Os.fsync`）本来就在仓库里，在
`CNOfflineImport` 那边——错的那份没复用它。所以这里同时锁住三件事：实现只有
一份、不许退回 RandomAccessFile、失败日志必须限流。
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _guardlib import body, code   # noqa: E402

JAVA = Path("patch/src/main/java/io/kamihama/magianative")

tx = (JAVA / "CNArchiveInstallTx.java").read_text(encoding="utf-8")
offline = (JAVA / "CNOfflineImport.java").read_text(encoding="utf-8")
all_java = {p.name: p.read_text(encoding="utf-8") for p in JAVA.glob("*.java")}


or_throw = body(tx, "static void syncDirOrThrow(File dir) throws Exception")
swallow = body(tx, "static void syncDir(File dir)")

# 整个补丁包里 fsync 的系统调用出现几次——必须只有一次
fsync_sites = sum(src.count("Os.fsync(") for src in all_java.values())
# 禁用写法：拿 RandomAccessFile 开目录
# 只看去掉注释后的代码：本文件自己那句「不要改回 RandomAccessFile」的
# 警告注释，否则会替代码顶罪（这正是 _guardlib 抽出来的原因）。
rafs = sum(code(src).count("RandomAccessFile(dir") for src in all_java.values())

checks = {
    "目录 fsync 用 Os.open(O_RDONLY)+Os.fsync":
        "Os.open(" in or_throw
        and "O_RDONLY" in or_throw
        and "Os.fsync(" in or_throw,
    "拿到的 fd 一定会被关掉":
        "finally" in or_throw and "Os.close(" in or_throw,
    "fsync 系统调用全仓库只有一处实现":
        fsync_sites == 1,
    "没有任何地方用 RandomAccessFile 打开目录（在 Android 上必定 EISDIR）":
        rafs == 0,
    "CNOfflineImport 复用同一份实现，不再自带一份":
        "CNArchiveInstallTx.syncDirOrThrow(" in offline
        and "private static void syncDirectory" not in offline,
    "两种契约都保留：syncDirOrThrow 抛、syncDir 吞":
        "throws Exception" in tx.split("static void syncDirOrThrow")[1][:80]
        and "catch (Throwable t)" in swallow,
    "严格契约的调用方仍然把失败当作「不报告成功」":
        "本次不报告成功" in offline,
    "失败日志按目录限流（不支持的挂载上会每次都失败）":
        "SYNC_DIR_WARNED" in tx and "SYNC_DIR_WARN_MAX" in tx,
    "限流有总量封顶，不只是按目录去重":
        "SYNC_DIR_WARNED.size() >= SYNC_DIR_WARN_MAX" in tx,
    "记日志本身不会把事务搞挂":
        "catch (Throwable ignore)" in body(tx, "private static void warnSyncDirFailed"),
    "注释里留下了「不要改回 RandomAccessFile」的原因":
        "EISDIR" in tx and "RandomAccessFile" in tx,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS " if ok else "FAIL ") + name)
if failed:
    raise SystemExit("dir fsync contract failed: " + ", ".join(failed))
