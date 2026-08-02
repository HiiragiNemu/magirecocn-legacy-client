#!/usr/bin/env python3
"""校验 BGM 的 ogg 在 APK 里是**未压缩**存储的。

AssetManager.openFd() 只能打开未压缩（Stored）的 asset；一旦被 deflate，
它会抛 "This file can not be opened as a file descriptor; it is probably
compressed"，浮层 BGM 就静默失效——而且是那种「界面一切正常、就是没声音」的
失效，光看界面根本发现不了。这个坑踩过一次了。

播放器里有 cacheDir 兜底（压缩了就先释放一份再放），所以这条不是致命的，
但兜底要多花一次 1.6MB 的写盘。能在打包阶段挡住就别留到运行时。

用法：python3 tools/check-asset-compression.py <apk>
"""

import sys
import zipfile

# (路径前缀, 后缀) → 必须 Stored
MUST_BE_STORED = [("assets/magia/", ".ogg")]


def main():
    if len(sys.argv) < 2:
        print("用法: check-asset-compression.py <apk>", file=sys.stderr)
        return 2
    apk = sys.argv[1]

    bad = []
    checked = 0
    with zipfile.ZipFile(apk) as z:
        for info in z.infolist():
            for prefix, suffix in MUST_BE_STORED:
                if info.filename.startswith(prefix) and info.filename.endswith(suffix):
                    checked += 1
                    if info.compress_type != zipfile.ZIP_STORED:
                        bad.append(info.filename)

    if checked == 0:
        print("::warning::APK 里没有 assets/magia/*.ogg —— 浮层将没有 BGM")
        return 0

    if bad:
        print("✘ 以下 asset 被压缩存储，AssetManager.openFd() 打不开：")
        for f in bad:
            print("   " + f)
        print("  修法：在 apktool.yml 的 doNotCompress 里加上 ogg")
        return 1

    print(f"✔ asset 压缩方式检查通过（{checked} 个 ogg 均为 Stored）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
