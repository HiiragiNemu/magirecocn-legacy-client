#!/usr/bin/env python3
"""守卫「同一件事只有一份实现」。

仓库里已经吃过这个亏：目录 fsync 有两份实现，对的那份在 `CNOfflineImport`、
错的那份在 `CNArchiveInstallTx`，而错的那份 1386 次调用一次都没成功过——没人发现，
因为它自己看起来很完整。重复实现的危险不在于多写几行，在于**它们会各自漂移，
然后其中一份悄悄变成错的**。

这里钉住两个已经统一过的：

* `closeQuietly` —— 原先五个类里六份实现，其中 `CNDownloaderFix` 那两个重载
  只 `catch (IOException)`，其余四份 `catch (Throwable)`。差别不是风格：`close()`
  抛 `RuntimeException` 时前者会往外传，而它几乎总是从 `finally` 里调的，
  一传就把原始异常整个盖掉。现统一到 `CNIo.closeQuietly`。
* `appContext()` —— 原先在 `CNHotUpdateCheck` 与 `CNTutorialPrompt` 里逐字重复，
  现统一到 `CNRestClientActivity`（它本来就是「反射进 ActivityThread 取进程级
  句柄」的归属地）。
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _guardlib import code   # noqa: E402

JAVA = Path("patch/src/main/java/io/kamihama/magianative")
srcs = {p.name: code(p.read_text(encoding="utf-8")) for p in JAVA.glob("*.java")}

# 「实现」= 带方法体的声明；「调用」不算。
def impls(pattern: str):
    hits = []
    for name, src in srcs.items():
        for m in re.finditer(pattern, src):
            hits.append(name)
    return hits


close_impls = impls(r"(?:static\s+)?void\s+closeQuietly\s*\([^)]*\)\s*\{")
ctx_impls = impls(r"(?:static\s+)?Context\s+appContext\s*\(\s*\)\s*\{")

# 只 catch IOException 的「安静关闭」——正是被统一掉的那种弱契约。
#
# 判据必须严格取**本方法体**，两种省事写法都会失效，我都踩过：
#   · `[^}]*` 够不到 catch —— 一行式 `try { c.close(); } catch (...)` 的第一个 `}`
#     属于 try 块，正则在那儿就停了，于是永远匹配不到要抓的形状；
#   · 定长字符窗口会溢出到下一个方法 —— 隔壁 deleteQuietly 里的 `catch (Throwable)`
#     会把判据抵消掉，同样漏报。
# 所以按花括号配平截出方法体，一个字符都不多看。
def _method_body(src: str, start: int) -> str:
    depth = 0
    for i in range(start, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
    return src[start:]


def _weak(src: str) -> bool:
    for m in re.finditer(r"void\s+closeQuietly\s*\([^)]*\)\s*\{", src):
        body_ = _method_body(src, m.end() - 1)
        if re.search(r"catch\s*\(\s*IOException", body_) \
                and not re.search(r"catch\s*\(\s*Throwable", body_):
            return True
    return False


weak_close = [name for name, src in srcs.items() if _weak(src)]

checks = {
    "closeQuietly 全仓库只有一份实现":
        len(close_impls) == 1,
    "那份实现在 CNIo 里":
        close_impls == ["CNIo.java"],
    "没有只接 IOException 的弱版安静关闭":
        not weak_close,
    "CNIo 的实现接的是 Throwable":
        bool(re.search(r"void\s+closeQuietly\s*\([^)]*\)\s*\{.*?catch\s*\(\s*Throwable",
                       srcs.get("CNIo.java", ""), re.S)),
    "appContext 全仓库只有一份实现":
        len(ctx_impls) == 1,
    "那份实现在 CNRestClientActivity 里":
        ctx_impls == ["CNRestClientActivity.java"],
    "刷新节流的时间戳是 volatile（32 位 ABI 上 long 读写才原子）":
        "public static volatile long lastUpdateTime;" in srcs.get("CNCNDownloadUI.java", ""),
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS " if ok else "FAIL ") + name)
if close_impls and close_impls != ["CNIo.java"]:
    print("     closeQuietly 实现出现在: " + ", ".join(sorted(set(close_impls))))
if ctx_impls and ctx_impls != ["CNRestClientActivity.java"]:
    print("     appContext 实现出现在: " + ", ".join(sorted(set(ctx_impls))))
if failed:
    raise SystemExit("single impl contract failed: " + ", ".join(failed))
