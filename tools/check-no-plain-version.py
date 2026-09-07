#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守卫：产物里不许出现明文的客户端版本号。

    python3 tools/check-no-plain-version.py <apk> <版本号>

## 为什么要有这个检查

版本号防的**不是**逆向工程师——能读懂 smali 与 JNI 的人直接 fork 仓库重打包就
行，本仓库拦不住也不打算拦。防的是拿 APK 管理器照教程改包的人，他们的全部手法
是「全局搜版本号 → 改成一个大的 → 用管理器内置签名重签 → 装」。签名那一环拦不
住（包用公开的 AOSP 测试密钥，谁都能重签成同一指纹），所以唯一有意义的一步是
**让第一步搜不到东西**。

为此做了两件事，两件都容易在后续改动里被无声地退回去：

  · native 侧 `CLIENT_VERSION` 编译期异或，明文不进 .rodata
    （见 magia-native/src/MagiaLegacy.cpp）。有人把它改回普通 `const char*`，
    编译照过、功能照常，只是防线没了。
  · Java 侧不再持有版本号字面量，改为向 native 要
    （见 CNUserAgent）。有人为了省事加回一个 `static final String`，
    javac 会把它**内联到每一个引用处**，等于把明文撒进整个 dex。

两种退回都不会有任何东西报错。所以这里在构建后直接翻产物：**搜得到就是红灯**。

## 只翻我们自己的产物

`classes.dex` 是上游整包自带的游戏代码，`assets/` `res/` 里也全是上游素材，
它们里面出现什么与本仓库无关，扫进来只会制造假阳性。所以只看三样：
补丁类所在的 `classes2.dex` / `classes3.dex`，以及我们自己编的
`lib/*/libMagiaLegacy.so`。
"""

import re
import sys
import zipfile

# 只扫我们自己的产物，理由见文件头。
SCAN_EXACT = ("classes2.dex", "classes3.dex")
SCAN_SUFFIX = ("/libMagiaLegacy.so",)


def targets(z):
    for name in z.namelist():
        if name in SCAN_EXACT or name.endswith(SCAN_SUFFIX):
            yield name


def main():
    if len(sys.argv) != 3:
        print("用法：check-no-plain-version.py <apk> <版本号>")
        return 2
    apk, version = sys.argv[1], sys.argv[2]

    # 版本号形如 1.0.171。空的或形状不对就不查——与其按一个错的模式扫出一堆
    # 假阳性，不如明说「没法查」。
    if not re.match(r"^\d+\.\d+\.\d+$", version):
        print("✘ 版本号形状不对：%r（期望 1.0.<构建号>）" % version)
        return 1

    needle = version.encode("ascii")
    # UTF-16LE：dex 的字符串常量池是 MUTF-8，但 Java 侧若有 char[] 之类的写法
    # 也可能以 UTF-16 出现，一并查掉。
    needle16 = version.encode("utf-16-le")

    hits = []
    with zipfile.ZipFile(apk) as z:
        scanned = list(targets(z))
        if not scanned:
            print("✘ 包里没找到任何要扫的产物（classes2/3.dex 与 libMagiaLegacy.so）")
            return 1
        for name in scanned:
            blob = z.read(name)
            n8 = blob.count(needle)
            n16 = blob.count(needle16)
            if n8 or n16:
                hits.append((name, n8, n16))

    print("明文版本号检查（版本 %s，扫了 %d 个产物）：" % (version, len(scanned)))
    for name in scanned:
        print("  · %s" % name)
    if hits:
        for name, n8, n16 in hits:
            print("  ✘ %s 里出现明文版本号 %d 处（UTF-16 另 %d 处）" % (name, n8, n16))
        print()
        print("  这意味着「全局搜版本号再改掉」这条路又通了。两个常见原因：")
        print("    1. native 的 CLIENT_VERSION 被改回普通 const char*，没走编译期异或；")
        print("    2. Java 侧又加了版本号字面量常量——javac 会把它内联到每个引用处。")
        print("  改法见 magia-native/src/MagiaLegacy.cpp 里 CLIENT_VERSION 的注释。")
        return 1

    print("  ✔ 三样产物里都没有明文版本号")
    return 0


if __name__ == "__main__":
    sys.exit(main())
