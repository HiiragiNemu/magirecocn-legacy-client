#!/usr/bin/env python3
"""校验 APK 里的产物确实是「刚编译出来的那一份」，而不是上一版的残留。

踩过的坑（两次，都靠真机日志才发现，各费掉一整轮往返）：

1. `CNBgm` 编译出了 .class，却不在任何一组 d8 的输入里 —— 类根本不在 APK 里，
   浮层建到一半抛 NoClassDefFoundError。
2. `libMagiaLegacy.so` 编译好了，却忘了从 `magia-native/build/` 拷进 `lib/` ——
   发出去的包带的是上一版库。新加的 JNI 调用一行日志都不打，现场看起来像
   「功能没生效」，实际是**根本没装上**。

这两类错误的共同点：apktool 能打包、能签名、能安装，所有既有静态检查都过，
只有装到机器上才暴露，而且暴露出来的现象会把人引向错误的方向（去查功能逻辑，
而不是去查产物有没有进包）。

本脚本把这一类挪到构建期，判据有两条：

  A. 若 `magia-native/build/<abi>/libMagiaLegacy.so` 存在，则 APK 里同名 .so
     必须与它**逐字节相同**。不同即为「忘了拷」或「拷完又被覆盖」。
  B. `patch/src/main/java` 下每个顶层类，在 APK 的某个 dex 里都必须找得到
     其类名字符串。找不到即为「编译了但没进任何一组 dex」。

用法：python3 tools/check-apk-freshness.py <apk>
"""

import os
import re
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NATIVE_BUILD = os.path.join(REPO, "magia-native", "build")
PATCH_SRC = os.path.join(REPO, "patch", "src", "main", "java")

# 只作为编译期桩存在，真实实现在 smali_classes2 里，不该出现在补丁 dex 中
STUB_ONLY = {"RestClient"}


def rodata_sections(blob):
    """取出 .rodata 系只读数据段的内容。

    为什么不直接比整文件的字节或字符串：CI 在拷进 lib/ 之后会跑
    llvm-strip --strip-unneeded，产物与构建目录里那份必然不逐字节相同，
    连字符串集合都会差一大截——`.symtab` 里那些本地符号名（`$d.104`、
    `_ZL16setGameUiVisibleb`）会被删掉。

    但 strip **不动 `.rodata`**，而我们真正要判断的「这是不是上一版的库」，
    看只读数据就够：字符串字面量、类名、方法签名、日志文案全在那儿，
    少一条立刻能看出来。
    """
    import struct
    out = []
    if blob[:4] != b"\x7fELF":
        return out
    is64 = blob[4] == 2
    en = "<" if blob[5] == 1 else ">"
    if is64:
        e_shoff, = struct.unpack_from(en + "Q", blob, 0x28)
        e_shentsize, e_shnum, e_shstrndx = struct.unpack_from(en + "HHH", blob, 0x3A)
    else:
        e_shoff, = struct.unpack_from(en + "I", blob, 0x20)
        e_shentsize, e_shnum, e_shstrndx = struct.unpack_from(en + "HHH", blob, 0x2E)
    if not e_shoff or not e_shnum:
        return out

    def sh(i):
        o = e_shoff + i * e_shentsize
        name, = struct.unpack_from(en + "I", blob, o)
        if is64:
            off, = struct.unpack_from(en + "Q", blob, o + 0x18)
            size, = struct.unpack_from(en + "Q", blob, o + 0x20)
        else:
            off, = struct.unpack_from(en + "I", blob, o + 0x10)
            size, = struct.unpack_from(en + "I", blob, o + 0x14)
        return name, off, size

    _, stroff, _strsize = sh(e_shstrndx)
    for i in range(e_shnum):
        nameoff, off, size = sh(i)
        end = blob.index(b"\0", stroff + nameoff)
        name = blob[stroff + nameoff:end].decode("ascii", "replace")
        if name.startswith(".rodata"):
            out.append(blob[off:off + size])
    return out


def strings_of(blob, minlen=6):
    """抽 .rodata 里的可打印 ASCII 串集合。取不到 .rodata 就退回整文件。"""
    chunks = rodata_sections(blob) or [blob]
    s = set()
    for c in chunks:
        s |= set(re.findall(rb"[\x20-\x7e]{%d,}" % minlen, c))
    return s


def check_native(apk, problems):
    """判据 A：包内 .so 的内容必须来自最近一次 native 构建。"""
    if not os.path.isdir(NATIVE_BUILD):
        return 0
    checked = 0
    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        for abi in sorted(os.listdir(NATIVE_BUILD)):
            built_path = os.path.join(NATIVE_BUILD, abi, "libMagiaLegacy.so")
            if not os.path.isfile(built_path):
                continue
            entry = "lib/%s/libMagiaLegacy.so" % abi
            if entry not in names:
                problems.append("%s 不在 APK 里" % entry)
                continue
            in_apk = z.read(entry)
            built = open(built_path, "rb").read()
            if in_apk != built:
                # 逐字节不同——可能只是 strip 过，也可能真是旧库。看字符串集合。
                missing = strings_of(built) - strings_of(in_apk)
                if missing:
                    sample = sorted(s.decode("ascii", "replace") for s in missing)[:5]
                    problems.append(
                        "%s 缺少构建产物里的 %d 条字符串（例：%s）—— "
                        "很可能是编译完忘了拷进 lib/，包里带的是上一版库"
                        % (entry, len(missing), "、".join(sample)))
            checked += 1
    return checked


def patch_classes():
    """列出 patch/ 下的顶层类名（按文件名，够用且不需要解析 Java）。"""
    out = []
    for root, _dirs, files in os.walk(PATCH_SRC):
        for f in files:
            if f.endswith(".java"):
                name = f[:-5]
                if name not in STUB_ONLY:
                    out.append(name)
    return sorted(out)


def check_dex(apk, problems):
    """判据 B：每个补丁类都得能在某个 dex 里找到类名字符串。"""
    blobs = []
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if n.startswith("classes") and n.endswith(".dex"):
                blobs.append(z.read(n))
    if not blobs:
        problems.append("APK 里没有 dex")
        return 0
    names = patch_classes()
    for name in names:
        needle = name.encode()
        if not any(needle in b for b in blobs):
            problems.append(
                "补丁类 %s 编译了却不在任何 dex 里 —— 检查 d8 的分组" % name)
    return len(names)


def main():
    if len(sys.argv) < 2:
        print("用法: check-apk-freshness.py <apk>", file=sys.stderr)
        return 2
    apk = sys.argv[1]
    problems = []
    n_so = check_native(apk, problems)
    n_cls = check_dex(apk, problems)
    if problems:
        print("✘ APK 内容与构建产物对不上：")
        for p in problems:
            print("   " + p)
        return 1
    print("✔ 产物新鲜度检查通过（%d 个 .so 的 .rodata 与构建产物相符，"
          "%d 个补丁类均已进 dex）" % (n_so, n_cls))
    return 0


if __name__ == "__main__":
    sys.exit(main())
