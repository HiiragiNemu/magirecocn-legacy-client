#!/usr/bin/env python3
"""守卫「原生崩溃的墓碑一定收得到」。

## 这条守卫在防什么

`CNLog` 的主 logcat 回收用的是 `logcat --pid=<自己>`。而原生崩溃的堆栈**不是
崩溃进程打的**——内核把信号交给 debuggerd，由 `crash_dump` 子进程以 `DEBUG`
tag 写出来，PID 不是我们。于是 `--pid=` 把整段墓碑连同信号、faulting address、
backtrace 一并滤掉：玩家发来的日志包里只剩崩之前最后几行业务日志。

2026-08-22 查「进战斗就闪退」时撞上的正是这一堵墙——不是线索不够，是**这套
日志系统在结构上就不可能带出崩溃现场**。所以补了第二路不带 `--pid`、只按 tag
收的回收流。

它真正起作用的时刻不是崩溃当下（那会儿我们自己也在死，多半来不及落盘），而是
**下一次启动**：logcat 的环形缓冲跨进程存活，`-T` 回灌会把上一个进程的墓碑捞进
新日志文件。「崩溃 → 重开 → 发日志」这条玩家本来就会走的路，因此能带出现场。

## 为什么这些点值得钉

每一条都是「看着像能顺手简化、一简化就恢复失明」的形状：

* 给崩溃流补上 `--pid`「保持一致」→ 墓碑立刻又没了，而日志文件看上去一切正常；
* 把两路合成一路 → 要么丢墓碑（带 --pid），要么主回收整机全收把缓冲冲垮；
* 24MB 封口顺手把崩溃流一起停 → 玩到 24MB 才崩的那次恰恰最需要现场；
* 去掉 `-T` 回灌 → 只剩「崩溃当下」这一个几乎抓不到的时机。
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _guardlib import code, body   # noqa: E402

JAVA = Path("patch/src/main/java/io/kamihama/magianative/CNLog.java")
raw = JAVA.read_text(encoding="utf-8")
src = code(raw)

crash_reader = body(src, "class CrashLogReader")
main_reader = body(src, "class LogcatReader")
stop_main = body(src, "void stopLogcatCapture()")
start_crash = body(src, "void startCrashLogCapture()")
init_early = body(src, "void initEarlyInner()")

# 过滤器四件套：三个要的 tag + 把其余静音的 *:S。少了 *:S 就等于整机全收。
filterspec = re.search(r"CRASH_FILTERSPEC\s*=\s*\{([^}]*)\}", src)
spec = filterspec.group(1) if filterspec else ""

# 「命令行里有没有 --pid」只能看**真正拼进命令的那几行**，不能拿整个方法体判。
# 第一版就是那么写的，当场自己红灯：崩溃流那句 write() 的提示文案里原样写着
# 「不带 --pid」，而 _guardlib.code() 只剥注释、不剥字符串字面量，于是这条解释
# 危险写法的日志把危险写法本身给顶了罪。判据取 cmd.add(...) 的实参。
crash_cmd = "\n".join(re.findall(r"cmd\.add\(([^)]*)\)", crash_reader))

checks = {
    "崩溃流存在（CrashLogReader + startCrashLogCapture）":
        bool(crash_reader) and bool(start_crash),
    "崩溃流**不带** --pid（带上就再也收不到 crash_dump 的墓碑）":
        bool(crash_cmd) and "--pid" not in crash_cmd,
    "崩溃流收 DEBUG（墓碑本体：信号 / faulting address / backtrace）":
        '"DEBUG:V"' in spec,
    # 只收 F：libc 这个 tag 不是崩溃路径独占的，bionic 平时也拿它打非致命的
    # 东西（Access denied finding property 之类，某些 ROM 上每次属性查询一条）。
    # 放宽到 V 就等于开了一条长期噪音流，而崩溃流又有意不受 24MB 封口约束。
    # 要的那句 Fatal signal 本身是 FATAL 级，F 够用。
    "崩溃流只收 FATAL 级的 libc（V 会把非致命的 libc 噪音长期灌进来）":
        '"libc:F"' in spec and '"libc:V"' not in spec,
    "崩溃流收 AndroidRuntime（崩在 CNLog 起来之前时唯一的一份）":
        '"AndroidRuntime:E"' in spec,
    "崩溃流用 *:S 把其余静音（否则退化成整机全收）":
        '"*:S"' in spec,
    "崩溃流有 -T 回灌（指望的是下一次启动，不是崩溃当下）":
        '"-T"' in crash_cmd,
    "崩溃流由 initEarlyInner 起（和主回收同一个最早时机）":
        "startCrashLogCapture()" in init_early,
    "主回收仍带 --pid（崩溃流存在的前提就是它；两路各管各的）":
        "--pid" in main_reader,
    "24MB 封口只停主回收，不停崩溃流":
        bool(stop_main)
        and "crashLogProc" not in stop_main
        and "crashLogThread" not in stop_main,
    "SDK < 24 不起第二路（那些设备没有 --pid，主回收本来就全收）":
        bool(re.search(r"SDK_INT\s*<\s*24", start_crash)),
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS " if ok else "FAIL ") + name)
if failed:
    raise SystemExit("crash capture contract failed: " + ", ".join(failed))
