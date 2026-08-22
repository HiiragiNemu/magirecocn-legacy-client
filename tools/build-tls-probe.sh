#!/usr/bin/env bash
# 构建 magia-tls-probe（两个 ABI），并在构建前把它要 dlsym 的符号与**实际的**
# 引擎 so 对一遍。
#
# 为什么要预检符号：探针是靠 dlsym 拿 OpenSSL 的，符号名写错或者将来换了基线
# 导致某个符号没了，程序会在运行到那一行才失败——而那时候你人已经在设备前面、
# 端点也起好了。宁可在编译机上当场红。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/tools/magia-tls-probe.c"
OUT="$ROOT/.build/probe"
: "${NDK:?请设置 NDK 指向 android-ndk 根目录}"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

echo "── 预检：探针要的符号，引擎 so 里在不在 ──"
missing=0
for abi in arm64-v8a armeabi-v7a; do
  so="$ROOT/work/baseline/dec/lib/$abi/libmadomagi_native.so"
  if [ ! -f "$so" ]; then
    echo "  ⏭  $abi：找不到 $so（先跑 tools/baseline.py fetch），跳过预检"
    continue
  fi
  # 从源码里抠出所有 SYM(h, "...") 的名字，逐个去 so 的动态符号表里找
  syms=$(grep -oE 'SYM\(h, "[A-Za-z0-9_]+"' "$SRC" | sed 's/.*"\(.*\)"/\1/' | sort -u)
  have=$(llvm-nm --defined-only --dynamic "$so" 2>/dev/null | awk '{print $NF}' | sort -u)
  for s in $syms; do
    if ! grep -qxF "$s" <<<"$have"; then
      echo "  ✘ $abi 缺符号: $s"; missing=1
    fi
  done
  echo "  ✔ $abi：$(wc -w <<<"$syms") 个符号全部存在"
done
[ "$missing" -eq 0 ] || { echo "✘ 符号预检未通过，不构建"; exit 1; }

mkdir -p "$OUT"
"$BIN/aarch64-linux-android21-clang"   -O2 -Wall -Wextra -o "$OUT/magia-tls-probe-arm64" "$SRC" -ldl -llog
"$BIN/armv7a-linux-androideabi21-clang" -O2 -Wall -Wextra -o "$OUT/magia-tls-probe-armv7" "$SRC" -ldl -llog
echo "✔ 构建完成："
ls -la "$OUT"
cat <<'USAGE'

下一步（真机）：
  1) 电脑上起端点：
       python3 tools/tls12-endpoint-probe.py --host 0.0.0.0 --port 8443 --serve
  2) 推探针 + **原版**引擎 so：
       adb push .build/probe/magia-tls-probe-arm64 /data/local/tmp/
       adb push work/baseline/dec/lib/arm64-v8a/libmadomagi_native.so /data/local/tmp/
  3) 连：
       adb shell 'cd /data/local/tmp && chmod +x magia-tls-probe-arm64 && \
           LD_LIBRARY_PATH=. ./magia-tls-probe-arm64 <电脑IP> 8443'

判据：SSL_connect 返回 1 = 自签名可用、死穴解除；失败则看打印的 err 码是不是
又是 0x140920E3（336142563）。
USAGE
