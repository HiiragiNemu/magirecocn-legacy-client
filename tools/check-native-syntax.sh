#!/usr/bin/env bash
#
# 用桩头文件对 magia-native 做一次**纯语法检查**，不需要 NDK。
#
# ## 为什么需要它
#
# 本仓库的 CI 只能手动触发，一次完整构建要好几分钟（下 apktool/baksmali/SDK、
# 编译两个 ABI 的 native、apktool b、签名）。而 native 改动里最常见的失败恰恰
# 是最廉价的那类——打错一个符号名、少一个分号、类型对不上。为这种错误占一次
# 手动 CI，代价与收获完全不成比例。
#
# 这个脚本用 tools/native-stubs/ 里的 jni.h / android/log.h / shadowhook.h
# 三个桩，让宿主 g++ 能把 MagiaLegacy.cpp 从头到尾解析一遍。5 秒出结果。
#
# ## ⚠ 它保证什么、不保证什么
#
# 保证：语法正确、名字都能解析、类型能自洽、模板能实例化。
#
# **不保证 NDK 构建一定成功。** 桩不是真的 NDK 头：
#
#   · 宿主 libstdc++ 与 NDK 的 libc++ 不是一回事。std::string 的内存布局差异
#     恰恰是本文件很在意的东西（fontPathOverwrite 直接按 libc++ 的短串布局
#     写内存），这里检查不出来。
#   · 桩里的 JNIEnv 只有本文件用到的那几个方法，签名也做了简化。
#   · 没有 arm64/armeabi-v7a 的目标特定问题（对齐、ABI、内建函数）。
#
# 所以它的定位是**过一遍就能排掉大部分低级错误的前置闸门**，不是构建的替身。
# 真正的验证仍然是 CI 里那两个 ABI 的 cmake 构建。
#
# 用法：tools/check-native-syntax.sh
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$REPO/magia-native/src/MagiaLegacy.cpp"
STUBS="$REPO/tools/native-stubs"

if [ ! -f "$SRC" ]; then
    echo "✘ 找不到 $SRC" >&2
    exit 2
fi

CXX="${CXX:-}"
if [ -z "$CXX" ]; then
    for c in g++ clang++; do
        if command -v "$c" > /dev/null 2>&1; then CXX="$c"; break; fi
    done
fi
if [ -z "$CXX" ]; then
    echo "⚠ 找不到 g++ / clang++，跳过 native 语法检查（不视为失败）" >&2
    exit 0
fi

echo "用 $CXX + 桩头文件检查 $(basename "$SRC") …"
if "$CXX" -std=c++17 -fsyntax-only -I"$STUBS" "$SRC"; then
    echo "✔ native 语法检查通过（$CXX，桩头文件）"
    echo "  注意：这不等于 NDK 构建通过——桩的 libc++ 布局与 NDK 不同，"
    echo "  且没有目标 ABI 相关的检查。真正的验证仍是 CI 的 cmake 构建。"
else
    echo "✘ native 语法检查未通过，见上方错误" >&2
    exit 1
fi
