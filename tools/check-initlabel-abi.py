#!/usr/bin/env python3
"""钉住 LbUtility::initLabel 替身原型里容易被源码外观掩盖的 C++ ABI。

目标函数的 C++ 签名把 ``cocos2d::Size`` 写成按值参数，但该类有用户定义的
copy ctor，按 Itanium C++ ABI 属于 non-trivial-for-calls：真正调用时传的是指向
调用方临时副本的隐藏指针。把它替换成同为 8 字节的 ``{float w, h;}`` 并不等价；
AArch64 会把后者分类成 HFA，放进浮点寄存器，随后参数便整体错位。

本检查不尝试重新实现 C++ ABI 分类，只钉住已经从两份引擎二进制反汇编确认的
结论，并要求 trampoline 类型直接从 replacement 推导，避免两份声明以后再漂移。
"""

from pathlib import Path
import re
import sys


SRC = Path("magia-native/src/MagiaLegacy.cpp")


def require(text, pattern, message, problems):
    if re.search(pattern, text, re.M | re.S) is None:
        problems.append(message)


def main():
    try:
        text = SRC.read_text(encoding="utf-8")
    except OSError as exc:
        print("读不到 %s: %s" % (SRC, exc), file=sys.stderr)
        return 2

    begin = text.find("// LbUtility::initLabel(Node*")
    # 字体路径改写已删除；ABI 检查不应依赖那个无关 helper 的注释。
    end = text.find("using CreateWithTtfCfgFn", begin)
    if begin < 0 or end < 0:
        print("✘ 找不到 initLabel ABI 段落边界", file=sys.stderr)
        return 1
    block = text[begin:end]

    problems = []
    if re.search(r"\bstruct\s+CNSize\b", block):
        problems.append("CNSize 又被写成按值 POD；真实 cocos2d::Size 必须按隐藏指针透传")

    require(block, r"using\s+CNSizeAbiArg\s*=\s*void\s*\*\s*;",
            "缺少 `using CNSizeAbiArg = void*`", problems)
    require(block,
            r"static_assert\s*\(\s*sizeof\s*\(\s*CNVec2\s*\)\s*==\s*8\s*&&\s*"
            r"alignof\s*\(\s*CNVec2\s*\)\s*==\s*4",
            "缺少 CNVec2 的 size/alignment 编译期断言", problems)
    require(block,
            r"static_assert\s*\(\s*sizeof\s*\(\s*CNColor4B\s*\)\s*==\s*4\s*&&\s*"
            r"alignof\s*\(\s*CNColor4B\s*\)\s*==\s*1",
            "缺少 CNColor4B 的 size/alignment 编译期断言", problems)
    require(block, r"CNVec2\s+v2\s*,\s*int\s+i1\s*,\s*CNSizeAbiArg\s+sizeArg\s*,\s*"
                   r"CNColor4B\s+c4b\s*,\s*int\s+i2",
            "initLabelNew 的 Size/Color4B/末尾 int 参数顺序偏离已确认 ABI", problems)
    require(block, r"using\s+InitLabelFn\s*=\s*decltype\s*\(\s*&initLabelNew\s*\)\s*;",
            "trampoline 类型必须用 decltype(&initLabelNew) 推导", problems)

    forwarded = len(re.findall(
        r"initLabelOld\s*\([^;]*?v2\s*,\s*i1\s*,\s*sizeArg\s*,\s*c4b\s*,\s*i2\s*\)\s*;",
        block, re.S))
    if forwarded != 2:
        problems.append("initLabelOld 应有两条完整透传路径，实得 %d 条" % forwarded)

    symbol = (
        "_ZN9LbUtility9initLabelEPN7cocos2d4NodeERPNS0_5LabelEPKcf"
        "NS0_4Vec2EiNS0_4SizeENS0_7Color4BEi"
    )
    if symbol not in text:
        problems.append("initLabel 的目标 mangled symbol 被改动或移除")

    if problems:
        print("✘ initLabel ABI 守卫未通过：", file=sys.stderr)
        for problem in problems:
            print("  · " + problem, file=sys.stderr)
        return 1

    print("✔ initLabel ABI 守卫通过")
    print("    Size 按隐藏指针透传；Vec2/Color4B 布局已钉住；trampoline 与 replacement 同型")
    return 0


if __name__ == "__main__":
    sys.exit(main())
