#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-so-alignment.py —— 校验 lib/arm64-v8a/*.so 的 LOAD 段 16KB 页对齐（F-F-07）。

背景：Android 15+ 的 16KB 页设备要求 64 位 native 库的每个 PT_LOAD 段
p_align >= 0x4000 且 p_vaddr 与 p_offset 对 p_align 同余；不满足的库在
16KB 页内核上直接拒绝加载（dlopen 失败）。armeabi-v7a（32 位）不在此要求
范围内（16KB 页配置只针对 64 位内核），故本脚本只查 arm64-v8a。

libarchive.so（libarchive 3.7.4 重建版）已按
-Wl,-z,max-page-size=16384 重链；本脚本钉死这条红线：任何入库的
arm64 库都必须满足对齐，违例一票否决。

用法：python3 tools/check-so-alignment.py [路径 …]
  每个路径可以是目录（递归扫 *.so）或单个 .so 文件；可给多个；
  无参时默认扫 lib/arm64-v8a。只想钉某几枚库（例如 CI 新编的库，
  不扫游戏自带的历史预编译库）时，把文件逐个列出来即可。
退出码：0 = 全部合格；1 = 存在违例。
"""
import os
import struct
import sys

PAGE_16K = 0x4000


def is_elf64(path):
    """是不是 64 位 ELF（32 位库按设计跳过，不计入「已检查」）。"""
    try:
        with open(path, 'rb') as fp:
            h = fp.read(64)
        return len(h) >= 6 and h[:4] == b'\x7fELF' and h[4] == 2
    except OSError:
        return False


def check_so(path):
    """返回违例描述列表（空 = 合格）。纯 Python 解析 ELF64 程序头。"""
    with open(path, 'rb') as fp:
        data = fp.read()
    errs = []
    if len(data) < 64 or data[:4] != b'\x7fELF':
        return [f"{path}: 不是 ELF 文件"]
    if data[4] != 2:  # ELFCLASS64
        return []     # 32 位库不受 16KB 页要求约束，跳过
    if data[5] != 1:  # 小端
        return [f"{path}: 非小端 ELF，未支持"]
    # ELF64 header：e_phoff@0x20(Q) e_phentsize@0x36(H) e_phnum@0x38(H)
    e_phoff = struct.unpack_from('<Q', data, 0x20)[0]
    e_phentsize, e_phnum = struct.unpack_from('<HH', data, 0x36)
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        # Elf64_Phdr: p_type(I) p_flags(I) p_offset(Q) p_vaddr(Q) p_paddr(Q)
        #             p_filesz(Q) p_memsz(Q) p_align(Q)
        p_type, _flags, p_offset, p_vaddr, _paddr, _fsz, _msz, p_align = \
            struct.unpack_from('<IIQQQQQQ', data, off)
        if p_type != 1:  # PT_LOAD
            continue
        if p_align < PAGE_16K:
            errs.append(f"{path}: LOAD 段#{i} p_align=0x{p_align:x} < 0x4000")
        elif (p_vaddr % p_align) != (p_offset % p_align):
            errs.append(
                f"{path}: LOAD 段#{i} vaddr=0x{p_vaddr:x} 与 offset=0x{p_offset:x} "
                f"对 p_align=0x{p_align:x} 不同余")
    return errs


def iter_sos(path):
    """目录 → 递归产出 *.so；单文件 → 原样产出（须以 .so 结尾）。"""
    if os.path.isfile(path):
        if path.endswith('.so'):
            yield path
        return
    for dirpath, _dirs, files in os.walk(path):
        for name in sorted(files):
            if name.endswith('.so'):
                yield os.path.join(dirpath, name)


def main():
    roots = sys.argv[1:] or ['lib/arm64-v8a']
    all_errs = []
    checked = 0     # 真正校验过对齐的 64 位库数
    skipped32 = 0   # 按设计跳过的 32 位库数
    for root in roots:
        if not os.path.exists(root):
            # 显式点名的路径不存在必须报错——否则 CI 里路径写错会被
            # 静默跳过、红灯变假绿。
            all_errs.append(f"{root}: 路径不存在")
            continue
        if os.path.isfile(root) and not root.endswith('.so'):
            # R4-01：点名了一个**存在但不是 .so** 的路径。旧实现会静默跳过、
            # checked 保持 0，以「✓ 0 个库」假绿收场——路径写错 = 检查根本没跑。
            all_errs.append(f"{root}: 是文件但不是 .so，不会被检查（路径写错？）")
            continue
        for so in iter_sos(root):
            if is_elf64(so):
                checked += 1
            else:
                skipped32 += 1
            all_errs.extend(check_so(so))
    for e in all_errs:
        print('✗', e)
    if all_errs:
        print(f"\n{len(all_errs)} 处 16KB 对齐违例（{checked} 个库）。"
              "重链请加 -Wl,-z,max-page-size=16384。")
        return 1
    if checked == 0:
        # R4-01/R5-01：一个 64 位库都没扫到（空目录 / 目录里只有 32 位库 /
        # 上面已拦下的非 .so 文件），同样不该以「✓ 0 个」假绿收场。契约是
        # 「点名路径必须真的被检查」，空跑不算通过。
        print(f"✗ 没扫到任何 64 位库（{len(roots)} 个路径都为空、类型不符或只有 32 位库）")
        return 1
    # R5-01：checked 只数 64 位库——32 位库（按设计跳过、未真正校验对齐）不再
    # 混进「已检查」的计数里。
    print(f"✓ {checked} 个 64 位库 LOAD 段全部满足 16KB 页对齐"
          + (f"（{skipped32} 个 32 位库按设计跳过）" if skipped32 else ""))
    return 0


if __name__ == '__main__':
    sys.exit(main())
