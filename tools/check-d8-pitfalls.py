#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""扫编译产物,找出会让 d8 崩掉的两种类形状(CLAUDE.md 铁律 4)。

## 为什么要有这个脚本

撞上这两种形状时,d8 给出的是这么一句话:

    Error in .../CNRestartActivity$2.class
    java.lang.NullPointerException: Cannot invoke "String.length()"
        because "<parameter1>" is null

没有源码行号,没有说是哪一行代码的问题,只有一个 R8 内部的 NPE。本仓库为它栽过
三次。这个脚本在 javac 之后、d8 之前跑,把它换成一句能照着改的话。

## 两条判据(2026-08-08 在 build-tools 34.0.0 / R8 8.2.2-dev 上逐条实测)

  一、类带合成字段 `this$0` —— 也就是**非静态**的内部类 / 匿名类 / 局部类。
      崩:  非静态内部类 `class N {}`
      崩:  **实例**方法里的匿名类(javac 会给它塞 this$0)
      不崩:静态嵌套类 `static final class N {}`
      不崩:**静态**方法里的匿名类,哪怕捕获局部变量(只有 val$xxx,没有 this$0)

      → 所以旧的说法「不要写嵌套类的方法内的匿名类」两头都不准:它漏掉了非静态
        内部类,又误伤了静态方法里的匿名类——本仓库现有 32 个匿名类全在静态方法
        里,一个都不该改。真正的判据是**有没有 this$0**。

  二、`implements java.util.Comparator<T>` —— **带类型实参的 Comparator**。
      崩:  `implements Comparator<String>`
      不崩:`implements Comparator`(裸类型 —— 本仓库 CNMirrors 用的就是这招)
      不崩:`implements Callable<Boolean>`、`implements Iterable<String>`
      不崩:`class T<E>`、字段/方法参数/局部变量上的泛型

      → 旧说法「不要让类实现带泛型参数的接口」同样过宽:泛型父型本身没事,
        本仓库就有 5 个类 `implements Callable<...>` 一直构建正常。逐个接口试下来
        只有 Comparator 会崩(它是 d8 为 min-api 21 做脱糖时特殊处理的那一批)。
        所以这里只认 Comparator——**宁可漏报也不要误报**:一个会把正常代码拦下来
        的检查,比没有检查更糟。其余形状交给 d8 自己去拦。

用法:
    python3 tools/check-d8-pitfalls.py [classes 目录]     默认 .build/classes
"""

import os
import re
import subprocess
import sys

THIS0 = re.compile(rb"\bthis\$0\b")
# javap -v 输出里,类声明行顶格,形如
#   class G extends java.lang.Object implements java.util.Comparator<java.lang.String>
# 直接读它就够了,不必去解析 Signature 属性——那条在成员块里也有同名的
# (字段和方法各自的泛型签名),按行去匹配会把 `List<Entry> x;` 这种字段全部误报。
# 第一版就是这么写的,在真实产物上报了 11 个假阳性。
CLASS_DECL = re.compile(r"^(?:public |protected |private |final |abstract |static |strictfp )*"
                        r"(?:class|interface|enum) (\S+)(.*)$")


# 只认带类型实参的 Comparator。见文件头「二」:其余泛型父型实测都不崩,
# 写宽了会把仓库里 5 个正常的 Callable<...> 拦下来。
GENERIC_COMPARATOR = re.compile(r"implements\s+(?:[\w.]*\.)?Comparator<")


def supertypes_have_typeargs(tail):
    """类声明里是否 `implements Comparator<具体类型>`。"""
    return bool(GENERIC_COMPARATOR.search(tail))


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else ".build/classes"
    if not os.path.isdir(root):
        print("跳过：找不到 %s（还没编译？）" % root)
        return 0

    classes = []
    for dirpath, _, names in os.walk(root):
        for n in names:
            if n.endswith(".class"):
                classes.append(os.path.join(dirpath, n))
    if not classes:
        print("跳过：%s 里没有 .class" % root)
        return 0

    inner = []
    for p in classes:
        with open(p, "rb") as f:
            if THIS0.search(f.read()):
                inner.append(p)

    generic = []
    try:
        out = subprocess.run(["javap", "-v", "-p"] + sorted(classes),
                             capture_output=True, text=True, timeout=300).stdout
    except Exception as e:                  # javap 不在就只做第一项检查
        out = ""
        print("提示：javap 跑不起来（%s），跳过泛型父型检查" % e)
    for line in out.split("\n"):
        m = CLASS_DECL.match(line)
        if m and supertypes_have_typeargs(m.group(2)):
            generic.append((m.group(1), (m.group(1) + m.group(2)).strip()))

    if not inner and not generic:
        print("✔ d8 陷阱检查通过（%d 个类）" % len(classes))
        return 0

    print("\n✘ 发现会让 d8 崩掉的类形状（CLAUDE.md 铁律 4）：\n")
    for p in inner:
        print("  · %s" % os.path.relpath(p, root))
        print("      带合成字段 this$0 —— 非静态的内部类/匿名类/局部类。")
        print("      改法：写成 static 嵌套类，把原先靠 this$0 拿到的外部实例")
        print("            改成构造参数传进去。参考 CNRestartActivity.LaunchTask。\n")
    for name, sig in generic:
        print("  · %s" % name)
        print("      implements 了带类型实参的 Comparator：%s" % sig)
        print("      改法：改用裸类型（implements Comparator，不写 <T>），")
        print("            调用处按需强转。参考 CNMirrors 的 ByWeightDesc。\n")
    print("  d8 撞上这两种形状只会报一句 R8 内部的")
    print("  NullPointerException: Cannot invoke \"String.length()\"，")
    print("  既没有行号也没有类名以外的线索——所以在这里拦下来。\n")
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        import traceback
        traceback.print_exc()
        print("\n检查脚本自身出错，放行（d8 仍会拦下真正的问题）")
        sys.exit(0)
