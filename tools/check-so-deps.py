#!/usr/bin/env python3
"""校验 APK 内每个 .so 的 DT_NEEDED 都能在包内或系统里找到。

踩过的坑：libMagiaLegacy.so 动态链接 shadowhook，但 CI 只把 libMagiaLegacy.so
拷进了 lib/<abi>/，libshadowhook.so 落在构建目录里没带上。装到真机上报的是

    dlopen failed: library "libshadowhook.so" not found: needed by libMagiaLegacy.so

——而这只有在**真机启动那一刻**才会暴露：APK 能打包、能签名、能安装，所有静态
自检都过。这个脚本把这一类问题挪到构建期。

判据：对包内每个 lib/<abi>/*.so，逐条看 DT_NEEDED；不在同 abi 目录里、也不在
系统库白名单里的，即为缺失。

用法：python3 tools/check-so-deps.py <apk>
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

# Android 平台自带、无需随包分发的库。
SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so",
    "libandroid.so", "libjnigraphics.so", "libGLESv1_CM.so", "libGLESv2.so",
    "libGLESv3.so", "libEGL.so", "libOpenSLES.so", "libOpenMAXAL.so",
    "libstdc++.so", "libc++_shared.so", "libvulkan.so", "libnativewindow.so",
    "libmediandk.so", "libcamera2ndk.so", "libneuralnetworks.so",
    "libaaudio.so", "libsync.so", "libnativehelper.so",
}


def needed_of(path):
    """读 .so 的 DT_NEEDED 列表。优先 readelf，退回纯 Python 解析。"""
    exe = shutil.which("readelf") or shutil.which("llvm-readelf")
    if exe:
        r = subprocess.run([exe, "-d", "-W", path], capture_output=True, text=True)
        if r.returncode == 0:
            return re.findall(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", r.stdout)
    # 没有 readelf 时的兜底：直接扫 .dynamic
    return needed_fallback(path)


def needed_fallback(path):
    import struct
    d = open(path, "rb").read()
    if d[:4] != b"\x7fELF":
        return []
    is64 = d[4] == 2
    endian = "<" if d[5] == 1 else ">"
    if is64:
        e_shoff, = struct.unpack_from(endian + "Q", d, 0x28)
        e_shentsize, e_shnum = struct.unpack_from(endian + "HH", d, 0x3A)
    else:
        e_shoff, = struct.unpack_from(endian + "I", d, 0x20)
        e_shentsize, e_shnum = struct.unpack_from(endian + "HH", d, 0x2E)
    dyn = strtab = None
    for i in range(e_shnum):
        o = e_shoff + i * e_shentsize
        sh_type = struct.unpack_from(endian + "I", d, o + 4)[0]
        if is64:
            sh_off, = struct.unpack_from(endian + "Q", d, o + 0x18)
            sh_size, = struct.unpack_from(endian + "Q", d, o + 0x20)
            sh_link, = struct.unpack_from(endian + "I", d, o + 0x28)
        else:
            sh_off, = struct.unpack_from(endian + "I", d, o + 0x10)
            sh_size, = struct.unpack_from(endian + "I", d, o + 0x14)
            sh_link, = struct.unpack_from(endian + "I", d, o + 0x18)
        if sh_type == 6:                       # SHT_DYNAMIC
            dyn = (sh_off, sh_size, sh_link)
    if not dyn:
        return []
    sh_off, sh_size, sh_link = dyn
    o = e_shoff + sh_link * e_shentsize
    if is64:
        strtab, = struct.unpack_from(endian + "Q", d, o + 0x18)
    else:
        strtab, = struct.unpack_from(endian + "I", d, o + 0x10)
    out, step = [], (16 if is64 else 8)
    fmt = endian + ("QQ" if is64 else "II")
    for p in range(sh_off, sh_off + sh_size, step):
        tag, val = struct.unpack_from(fmt, d, p)
        if tag == 0:
            break
        if tag == 1:                           # DT_NEEDED
            e = d.index(b"\0", strtab + val)
            out.append(d[strtab + val:e].decode())
    return out


# 只应由 DT_NEEDED 自动加载、不得被 System.loadLibrary 显式加载的库
NEVER_LOAD_EXPLICITLY = {"shadowhook"}


def check_explicit_loads(apk):
    """在 dex 里找 System.loadLibrary("<禁止项>") 的痕迹。

    dex 的字符串池是明文的，禁止项作为库名会原样出现；而它作为 DT_NEEDED
    只出现在 .so 里、不会进 dex。所以「dex 里出现该字符串」即可判定为显式加载。
    """
    out = []
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if not (n.startswith("classes") and n.endswith(".dex")):
                continue
            blob = z.read(n)
            for lib in NEVER_LOAD_EXPLICITLY:
                if lib.encode() in blob:
                    out.append((n, f'dex 里出现库名 "{lib}"，疑似被 System.loadLibrary 显式加载'))
    return out


def main():
    if len(sys.argv) < 2:
        print("用法: check-so-deps.py <apk>", file=sys.stderr)
        return 2
    apk = sys.argv[1]
    problems, checked = [], 0

    with zipfile.ZipFile(apk) as z:
        names = [n for n in z.namelist() if n.startswith("lib/") and n.endswith(".so")]
        by_abi = {}
        for n in names:
            by_abi.setdefault(n.split("/")[1], set()).add(os.path.basename(n))

        with tempfile.TemporaryDirectory() as td:
            for n in names:
                abi = n.split("/")[1]
                p = os.path.join(td, os.path.basename(n))
                with z.open(n) as src, open(p, "wb") as dst:
                    shutil.copyfileobj(src, dst)
                checked += 1
                for dep in needed_of(p):
                    if dep in SYSTEM_LIBS or dep in by_abi[abi]:
                        continue
                    problems.append((n, dep))

    # 额外一条：带 JNI_OnLoad 的依赖库不能被 smali 显式 loadLibrary。
    # 按 DT_NEEDED 自动加载不会调 JNI_OnLoad，显式加载会——而 libshadowhook
    # 的 JNI_OnLoad 要注册 com/bytedance/shadowhook/ShadowHook 的 native 方法，
    # 我们包里没这个类，于是 JNI_ERR。这个坑踩过一次。
    bad_load = check_explicit_loads(apk)
    problems += bad_load

    if checked == 0:
        print("::warning::APK 里没有 .so")
        return 0
    if problems:
        print("✘ 以下依赖在包内和系统库里都找不到，真机 dlopen 必然失败：")
        for so, dep in problems:
            print(f"   {so}  需要  {dep}")
        return 1
    print(f"✔ .so 依赖检查通过（{checked} 个库，DT_NEEDED 全部可解析）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
