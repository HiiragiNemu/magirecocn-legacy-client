#!/usr/bin/env python3
"""检查 native 入口方法体内没有落在 try 之外的语句。

为什么需要它：runInstaller 与 getEndpoint 由 native 经 JNI 调用，方法体一旦
让任何 Throwable 逃逸，hook 在 ExceptionCheck 后就会清掉异常并放行引擎自带的
下载场景——玩家看到原生安装界面。这个不变量在开发中已经被无意破坏过两次
（都是"在方法开头加一行初始化"造成的），靠人眼复查不可靠，故做成脚本。

用法：python3 tools/check-entry-guard.py
"""
import re, sys, pathlib

SRC = pathlib.Path("patch/src/main/java/io/kamihama/magianative/CNDownloaderFix.java")
ENTRIES = ["public static void runInstaller()",
           "public static String getEndpoint(int i)"]

def body_of(text, sig):
    i = text.index(sig)
    i = text.index("{", i) + 1
    depth, j = 1, i
    while depth:
        if text[j] == "{": depth += 1
        elif text[j] == "}": depth -= 1
        j += 1
    return text[i:j-1]

def main():
    text = SRC.read_text(encoding="utf-8")
    bad = []
    for sig in ENTRIES:
        if sig not in text:
            bad.append(f"{sig}：找不到该方法（签名变了？）")
            continue
        body = body_of(text, sig)
        # 逐行扫到第一个 try {，之前不允许出现任何语句
        for line in body.splitlines():
            s = line.strip()
            if not s or s.startswith(("//", "*", "/*")):
                continue
            if s.startswith("try"):
                break
            bad.append(f"{sig}：try 之前出现语句 → {s}")
            break
    if bad:
        print("✘ native 入口保护检查未通过：")
        for b in bad: print("   " + b)
        print("\n  这些语句抛出时会漏进 JNI，导致引擎放行原生下载界面。")
        print("  请把它们移到 try 内部。")
        return 1
    print(f"✔ native 入口保护检查通过（{len(ENTRIES)} 个入口，方法体首条语句均为 try）")
    return 0

if __name__ == "__main__":
    sys.exit(main())
