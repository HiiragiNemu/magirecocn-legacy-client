#!/usr/bin/env python3
"""守卫日志热路径的线程安全。

## 这条守卫在防什么

`SimpleDateFormat` 不是线程安全的，而 `CNLog.write()` 会被下载线程、UI 线程、
logcat 回灌线程和各路看门狗并发调用。共享一个实例而不加同一把锁时，轻则时间戳
串行错乱，重则内部 Calendar 被同时改写抛 `ArrayIndexOutOfBoundsException`
——那会从日志代码自己里炸出来，最难查。

原先 `write()` 在 `synchronized (TS)` 里用它（对的），而 `init()` 在
`synchronized (FILE_LOCK)` 里也用它（错的）：**两把不同的锁保护不了同一个对象，
等于没锁**。真机上 init() 之后 logcat 采集线程立刻起来，而「日志继续」的追加模式
会让 init() 再跑一次，并发窗口是真实存在的。

修法是把 init() 那种一个进程只跑一两次的地方改用局部实例，热路径那个共享实例
保持静态（逐行新建 SimpleDateFormat 要解析 pattern、建 Calendar 与
DateFormatSymbols，在每分钟近八百行的解压期是实打实的开销）。

所以这里钉三件事：共享实例只有一个、它的每次使用都在自己的监视器下、包内没有
第二个静态 SimpleDateFormat 冒出来。
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _guardlib import code   # noqa: E402

JAVA = Path("patch/src/main/java/io/kamihama/magianative")
log_src = (JAVA / "CNLog.java").read_text(encoding="utf-8")
log_code = code(log_src)

# 共享实例的每次使用都必须紧跟在 synchronized (TS) 之后。
# 判据取「代码行序列」：TS.format 出现的那一行，往回找最近的 synchronized。
lines = log_code.splitlines()
uses_ok = True
uses = 0
for i, line in enumerate(lines):
    if "TS.format(" not in line:
        continue
    uses += 1
    guarded = any("synchronized (TS)" in lines[j] for j in range(max(0, i - 3), i))
    if not guarded:
        uses_ok = False

# 包内静态 SimpleDateFormat 字段的总数（局部变量不算）
static_fmt = 0
for p in JAVA.glob("*.java"):
    src = code(p.read_text(encoding="utf-8"))
    static_fmt += len(re.findall(r"static\s+(?:final\s+)?SimpleDateFormat\s+\w+", src))

checks = {
    "共享格式化器 TS 仍然是静态的（热路径不逐行新建）":
        bool(re.search(r"private static final SimpleDateFormat TS\b", log_code)),
    "TS 的每一次使用都在 synchronized (TS) 里":
        uses >= 1 and uses_ok,
    "TS 只在热路径那一处使用":
        uses == 1,
    "init() 不再碰共享的 TS（改用局部实例）":
        "SimpleDateFormat(\"yyyyMMdd-HHmmss\", Locale.US).format(" in log_code,
    "包内只有这一个静态 SimpleDateFormat":
        static_fmt == 1,
    "注释里留下了「不是线程安全」的理由":
        "不是线程安全" in log_src,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS " if ok else "FAIL ") + name)
if failed:
    raise SystemExit("log threadsafety contract failed: " + ", ".join(failed))
