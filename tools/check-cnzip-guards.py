#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-cnzip-guards.py —— libarchive「源码修复 ⇄ shipped 二进制」漂移守卫（补丁 19 配套）。

事实背景（务必先懂）：
  · 设备上的解压跑在 lib/arm64-v8a|armeabi-v7a/libarchive.so 里；
  · 它的源码 magia-native/src/archive_jni.cpp 手动交叉编译（NDK + libarchive
    静态链），CI 不重建它；
  · 因此对 archive_jni.cpp 的任何安全修复（补丁 01 Zip Slip、02 吞错、
    03 膨胀比闸）都依赖「重建并替换两个 ABI 的产物」才真正到达设备。
    重建时 archive_jni.cpp 里 __attribute__((used)) 的 kBuildMarker 编入
    二进制，本脚本用它在 shipped .so 里探测「修复确实进了二进制」。

本脚本守两件事：
  1.（FAIL 级）源码侧防护特征必须存在——防止有人误删/回滚了
     is_safe_entry_name / ioError 等关键逻辑还以为修复在线；
  2.（WARN 级）用 strings 探测 shipped 二进制是否含 kBuildMarker
     （archive_jni.cpp 里 __attribute__((used)) 的字符串，编进 .rodata、
     剥离不会丢）。标记在 = 补丁 01/02/03 的修复确实进了二进制；不在 =
     源码修了但二进制是旧的（漂移）。WARN 不阻断 CI，但每次构建都把
     这条漂移打在日志里，不许它被遗忘。

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

    # 探测 shipped 二进制是否含构建标记（archive_jni.cpp 里 __attribute__((used))
    # 的 kBuildMarker，编进 .rodata、剥不去）。标记在 = 补丁 01/02/03 的修复
    # 确实进了二进制；不在 = 源码修了但二进制是旧的（漂移）。
    marker = 'libarchive-cn-jni-3.7.4-fix-20260817'
    marker_in_so = False
    for abi in ('arm64-v8a', 'armeabi-v7a'):
        so = os.path.join(REPO, 'lib', abi, 'libarchive.so')
        if not os.path.isfile(so):
            continue
        try:
            data = open(so, 'rb').read()
            if marker.encode('utf-8') in data:
                marker_in_so = True
                break
        except OSError:
            pass

    for e in errs:
        print('✗', e)
    if errs:
        print('\n源码防护缺失——设备上的 Java 层双闸（CNZipTool，补丁 19）'
              '是最后防线，请立即恢复源码修复。')
        return 1

    if marker_in_so:
        print('✓ shipped libarchive.so 含构建标记——补丁 01/02/03 的 native '
              '修复已进二进制')
    else:
        print('⚠ 源码防护在位，但 shipped libarchive.so 不含构建标记：')
        print('  补丁 01/02/03 的 native 修复**尚未进二进制**（或构建标记')
        print('  已随版本升级过时）。重建：手动触发 build-libarchive.yml，')
        print('  下载 artifact 替换 lib/ 下两枚 libarchive.so）。在此之前')
        print('  设备侧由 CNZipTool 的 Java 层双闸（补丁 19）兜底。')
        print('  本 WARN 每构建必现，刻意不静默——漂移不许被遗忘。')
    print(f'✓ 源码防护特征齐全（{len(SOURCE_FEATURES)}/{len(SOURCE_FEATURES)}）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
