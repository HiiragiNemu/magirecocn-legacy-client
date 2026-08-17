#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-cnzip-guards.py —— libcnzip「源码修复 ⇄ shipped 二进制」漂移守卫（补丁 19 配套）。

事实背景（务必先懂）：
  · 设备上的解压跑在**预编译** lib/arm64-v8a|armeabi-v7a/libcnzip.so 里；
  · 它的源码 magia-native/src/archive_jni.cpp **不在 CMake 编译目标内**
    （magia-native/CMakeLists.txt 只编 MagiaLegacy.cpp），CI 也从不重建它；
  · 因此对 archive_jni.cpp 的任何安全修复（补丁 01 Zip Slip、02 吞错、
    03 膨胀比闸）都不会自动到达设备——必须手工重建 libcnzip.so 并替换
    两个 ABI 的预编译产物，修复才真正生效。

本脚本守两件事：
  1.（FAIL 级）源码侧防护特征必须存在——防止有人误删/回滚了
     is_safe_entry_name / ioError 等关键逻辑还以为修复在线；
  2.（WARN 级）archive_jni.cpp 是否已纳入 CMake 编译目标。shipped
     libcnzip.so 是剥离过符号的预编译二进制，函数名不会留在里面，
     strings 探测不可靠；「修复有没有进二进制」只能用「CMake 是否开始
     编译该源码」这个间接判据看。WARN 不阻断 CI（在真正重建之前它
     必然成立），但每次构建都会把这条漂移打在日志里，不许它被遗忘。

设备侧即时防线：CNZipTool 的 Java 层双闸（Zip Slip 预扫 + 解压后尺寸
核对，补丁 19）在二进制重建之前兜底——两闸与本源码修复同语。

退出码：0 = 源码防护在位（二进制漂移仅 WARN）；1 = 源码防护缺失。
"""
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO, 'magia-native', 'src', 'archive_jni.cpp')

# 源码侧必须存在的防护特征（补丁 01/02/03 引入）。只列**补丁特有的**符号：
# archive_entry_filetype 在修复前的源码里就有（它不是 01 引入的判据），
# 列为特征会让「该串被误删」也检测不到——特征必须对补丁唯一才敏感。
SOURCE_FEATURES = [
    ('is_safe_entry_name', 'Zip Slip 条目名校验（补丁 01）'),
    ('ioError', '读写错误中止（补丁 02）'),
    ('ARCHIVE_EOF', '循环退出 EOF 校验（补丁 02）'),
    ('maxWriteBytes', '膨胀比总量闸（补丁 03）'),
]


def main():
    errs = []
    try:
        with open(SRC, 'r', encoding='utf-8') as fp:
            src = fp.read()
    except OSError as e:
        print(f'✗ 读不到 {SRC}: {e}')
        return 1

    for needle, desc in SOURCE_FEATURES:
        if needle not in src:
            errs.append(f'源码缺防护特征 {needle!r}（{desc}）')

    # 交叉确认：CMake 确实仍不编译 archive_jni.cpp（若哪天纳入编译目标，
    # 本脚本的 WARN 逻辑就过时了，应改为 FAIL）
    cmake = ''
    try:
        with open(os.path.join(REPO, 'magia-native', 'CMakeLists.txt'),
                  'r', encoding='utf-8') as fp:
            cmake = fp.read()
    except OSError:
        pass
    in_build = 'archive_jni' in cmake

    for e in errs:
        print('✗', e)
    if errs:
        print('\n源码防护缺失——设备上的 Java 层双闸（CNZipTool，补丁 19）'
              '是最后防线，请立即恢复源码修复。')
        return 1

    if in_build:
        print('✓ archive_jni.cpp 已纳入 CMake 编译目标——本脚本使命完成，'
              '可考虑退役或改为比对二进制构建产物。')
    else:
        print('⚠ 源码防护在位，但 archive_jni.cpp 不在 CI 编译目标内：')
        print('  补丁 01/02/03 的 native 修复**不会到达设备**，直到有人重建')
        print('  libcnzip.so（需 NDK + libarchive 源码）并替换 lib/ 下两个')
        print('  ABI 的预编译产物。在此之前，设备侧由 CNZipTool 的 Java 层')
        print('  双闸（补丁 19：Zip Slip 预扫 + 解压后存在性核对）兜底。')
        print('  本 WARN 每构建必现，刻意不静默——漂移不许被遗忘。')
    print(f'✓ 源码防护特征齐全（{len(SOURCE_FEATURES)}/{len(SOURCE_FEATURES)}）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
