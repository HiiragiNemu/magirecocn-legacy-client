#!/usr/bin/env python3
"""minSdk 21 API 门槛：登记的高 API 符号出现处必须有 SDK_INT 守卫。

CLAUDE.md 铁律 3：minSdk 21，API 24+ 的便捷方法要用 Build.VERSION.SDK_INT
守卫。这条纯靠约定维持——javac 对 API 33 的 android.jar 编译（API 22+
的 android.* 符号编译期零拦截），d8 的 API model 只覆盖 java.*/jdk.* 脱糖库。

本脚本钉住一张**登记表**：每个高 API 符号在补丁源码里出现时，其所在文件
必须同时满足
  · 含 Build.VERSION.SDK_INT（文件是 API 感知的）；
  · 有一个 SDK_INT 守卫比较的数值 >= 该符号所需 API
    （数值可为字面量，或文件内定义的整数常量，如 MIN_SDK_FOR_WRAP=26）。

不是 lint：只认登记表里的符号，新符号要人工补登记。但足以拦住最常见的回归
——新文件引了 NotificationChannel / getDataDir 等却一个守卫都没有，或守卫
级别不够。

用法：python3 tools/check-api-levels.py        # 扫 patch/src/main/java
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "patch" / "src" / "main" / "java"

# 符号 -> 所需 API 级别。只登记「patch 里允许出现、但必须守卫」的高 API 成员
# （API > 21）。加新成员前先确认它真需要守卫，别把 API 21 的也塞进来。
HIGH_API = {
    "ApplicationExitInfo": 30,
    "getHistoricalProcessExitReasons": 30,
    "getProcessName": 28,
    "getWebViewClient": 26,
    "canDrawOverlays": 23,
    "ACTION_MANAGE_OVERLAY_PERMISSION": 23,
    "setApplicationProtocols": 29,
    "setHorizontalScrollbarThumbDrawable": 29,
    "setVerticalScrollbarThumbDrawable": 29,
    "setHorizontalScrollbarTrackDrawable": 29,
    "setVerticalScrollbarTrackDrawable": 29,
    "createDeviceProtectedStorageContext": 24,
    "getDataDir": 24,
    "NotificationChannel": 26,
    "TYPE_APPLICATION_OVERLAY": 26,
    "startForegroundService": 26,
}

# 运算符 -> 有效守卫 API：`>=` 就是 N；`<` 守卫（`if (SDK_INT < N) return`）让
# 后面代码在 API >= N 跑，也是 N；`>` 与 `<=` 是 N+1。
_OP_EFFECT = {"<": 0, ">=": 0, "<=": 1, ">": 1}
_GUARD_RE = re.compile(
    r"Build\.VERSION\.SDK_INT\s*(>=|<=|>|<)\s*(\d+|[A-Za-z_][A-Za-z0-9_]*)")
_CONST_RE = re.compile(r"\b%s\s*=\s*(\d+)")

# 需要 core-library desugaring 才能在 minSdk 21 上跑的 Java 库。构建未配置
# desugar_jdk_libs（d8 只有 --min-api，没有 --desugared_lib），直接用会在
# API 21-25 上 NoSuchMethodError。出现即拦，提醒先补 desugaring 再说。
DESUGAR_RE = re.compile(
    r"\bjava\.time\.|"
    r"\bjava\.util\.stream\.|"
    r"\bjava\.util\.function\.|"
    r"\bjava\.util\.(Optional|Base64)\b|"
    r"\bjava\.util\.concurrent\.CompletableFuture\b")


def strip_comments_and_strings(text, keep_imports=False):
    """去掉字符串/字符字面量与注释；默认连 import 一起去掉。

    import 行是声明不是运行时引用：类真被用到时，符号会在方法/字段体里
    再次出现（那里才是判断点）；只 import 不用是死代码，不构成运行时风险。
    keep_imports=True 时保留 import，用于 desugar 检查（import 声明本身就是
    「可能用到」的信号）——但必须建立在已去掉注释的文本上，否则注释里写
    `import java.util.Optional` 会误捕。
    """
    text = re.sub(r'"(?:[^"\\]|\\.)*"', '""', text)
    text = re.sub(r"'(?:[^'\\]|\\.)*'", "''", text)
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    if not keep_imports:
        text = re.sub(r"\bimport\s+[^;]+;", "", text)
    return text


def collect_guards(stripped):
    """文件里所有 SDK_INT 守卫的有效 API 级别（字面量或已定义常量）。"""
    guards = []
    for m in _GUARD_RE.finditer(stripped):
        op, token = m.group(1), m.group(2)
        if token.isdigit():
            value = int(token)
        else:
            dm = re.search(_CONST_RE.pattern % re.escape(token), stripped)
            if not dm:
                continue
            value = int(dm.group(1))
        guards.append(value + _OP_EFFECT[op])
    return guards


def relpath(path):
    try:
        return path.relative_to(ROOT)
    except ValueError:
        return path


def check_file(path):
    text = path.read_text(encoding="utf-8")
    # 去注释/字符串但留 import：desugar 检查用它（import 声明也是信号）。
    no_comments = strip_comments_and_strings(text, keep_imports=True)
    # 全去掉：符号守卫检查只用真代码。
    stripped = strip_comments_and_strings(text)
    problems = []
    if DESUGAR_RE.search(no_comments):
        problems.append(
            f"{relpath(path)}: 用到需要 core-library desugaring 的库（java.time / "
            "java.util.stream / java.util.function / Optional / Base64 / "
            "CompletableFuture）——minSdk 21 构建未配置 desugar_jdk_libs，"
            "API 21-25 上会 NoSuchMethodError。先补 desugaring 再说"
        )
    if "Build.VERSION.SDK_INT" not in stripped:
        # 文件连 SDK_INT 都没有：任何高 API 符号都是未守卫。
        for sym in HIGH_API:
            if re.search(r"\b" + re.escape(sym) + r"\b", stripped):
                problems.append(
                    f"{relpath(path)}: {sym}（API {HIGH_API[sym]}）出现在不含 "
                    "Build.VERSION.SDK_INT 的文件——几乎确定没守卫"
                )
        return problems
    guards = collect_guards(stripped)
    guard_max = max(guards) if guards else 0
    for sym, need in HIGH_API.items():
        if not re.search(r"\b" + re.escape(sym) + r"\b", stripped):
            continue
        if not any(g >= need for g in guards):
            problems.append(
                f"{relpath(path)}: {sym}（API {need}）的 SDK_INT 守卫只到 "
                f"{guard_max}，低于所需 {need}"
            )
    return problems


def main():
    bad = 0
    for path in sorted(SRC.rglob("*.java")):
        for problem in check_file(path):
            print(f"::error::{problem}")
            bad += 1
    if bad:
        print(f"minSdk 21 API 门槛：{bad} 处守卫缺失")
        return 1
    print("minSdk 21 API 门槛检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
