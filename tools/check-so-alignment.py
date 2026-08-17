#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-so-alignment.py —— 校验 lib/arm64-v8a/*.so 的 LOAD 段 16KB 页对齐（F-F-07）。

背景：Android 15+ 的 16KB 页设备要求 64 位 native 库的每个 PT_LOAD 段
p_align >= 0x4000 且 p_vaddr 与 p_offset 对 p_align 同余；不满足的库在
16KB 页内核上直接拒绝加载（dlopen 失败）。armeabi-v7a（32 位）不在此要求
范围内（16KB 页配置只针对 64 位内核），故本脚本只查 arm64-v8a。

现行 libcnzip.so（预编译）四段违例——这正是「重链时加
-Wl,-z,max-page-size=16384」的依据。本脚本钉死这条红线：重链产物必须
通过本校验才允许入库。

用法：python3 tools/check-so-alignment.py [lib/arm64-v8a]
退出码：0 = 全部合格；1 = 存在违例。
"""
import os
import struct
import sys

PAGE_16K = 0x4000


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


# 已知违例、待重链的库：放行并留痕，不允许默默从磁盘上消失。
# 目前只有 libcnzip.so（预编译二进制，重链时需加
# -Wl,-z,max-page-size=16384）。它由 check-cnzip-guards.py 单独跟踪
# 「修复未进二进制」的状态；本脚本排除它，是为了让**重链/新编**的库
# （libMagiaLegacy / libaria2c）的违例仍能一票否决 CI。
KNOWN_UNALIGNED = {"libcnzip.so": "预编译二进制，待重链（见 check-cnzip-guards.py）"}


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else 'lib/arm64-v8a'
    all_errs = []
    skipped = []
    checked = 0
    for dirpath, _dirs, files in os.walk(root):
        for name in sorted(files):
            if not name.endswith('.so'):
                continue
            if name in KNOWN_UNALIGNED:
                skipped.append(name)
                continue
            checked += 1
            all_errs.extend(check_so(os.path.join(dirpath, name)))
    for e in all_errs:
        print('✗', e)
    if all_errs:
        print(f"\n{len(all_errs)} 处 16KB 对齐违例（{checked} 个已检查库）。"
              "重链请加 -Wl,-z,max-page-size=16384。")
        return 1
    for name in skipped:
        print(f"↷ {name}：已排除（{KNOWN_UNALIGNED[name]}）")
    print(f"✓ {checked} 个 arm64 库 LOAD 段全部满足 16KB 页对齐")
    return 0


if __name__ == '__main__':
    sys.exit(main())
