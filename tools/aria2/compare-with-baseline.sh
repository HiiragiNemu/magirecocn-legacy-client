#!/bin/bash
# 新构建产物 vs 入库基线 .so 的结构化对拍（信息性，不置失败——
# NDK 漂移导致的合理差异由人审阅；但所有可核验指纹必须逐项列出）。
# 用法: NDK=<ndk路径> NEW_DIR=out bash tools/aria2/compare-with-baseline.sh
set -uo pipefail

NDK="${NDK:?请设置 NDK}"
NEW_DIR="${NEW_DIR:-out}"
RE="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
NM="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

fingerprint() { # fingerprint <so> —— 打印结构指纹
    local so="$1"
    echo "  size:      $(stat -c%s "$so")"
    echo "  SONAME:    $("$RE" -d "$so" | sed -n 's/.*SONAME.*\[\(.*\)\]/\1/p')"
    echo "  NEEDED:    $("$RE" -d "$so" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p' | sort | tr '\n' ' ')"
    echo "  exports:   $("$NM" -D --defined-only "$so" | awk '{print $NF}' | grep -v '^$' | sort | tr '\n' ' ')"
    echo "  LOADalign: $("$RE" -lW "$so" | awk '$1=="LOAD"{print $NF}' | sort -u | tr '\n' ' ')"
    echo "  NDK note:  $("$RE" -n "$so" 2>/dev/null | grep -a -o 'r[0-9][0-9a-z]*' | head -1)"
    echo "  marker:    $(strings -a "$so" | grep -o 'magirecocn-libaria2c[^"]*' | head -1 || echo '(无)')"
}

rc=0
for abi in arm64-v8a armeabi-v7a; do
  for be in ossl gnutls; do
    local_so="$ROOT/lib/$abi/libaria2c_${be}.so"
    new_so="$NEW_DIR/$abi/libaria2c_${be}.so"
    [ -f "$local_so" ] && [ -f "$new_so" ] || continue
    echo "════ $abi / $be ════"
    echo "── 基线（$local_so）"; fingerprint "$local_so"
    echo "── 新构建（$new_so）";  fingerprint "$new_so"
    # 硬性等价项：SONAME / NEEDED / 导出符号面 / LOAD 对齐 必须逐项一致
    for item in SONAME NEEDED; do
      a="$("$RE" -d "$local_so" | sed -n "s/.*$item.*\[\(.*\)\]/\1/p" | sort)"
      b="$("$RE" -d "$new_so"   | sed -n "s/.*$item.*\[\(.*\)\]/\1/p" | sort)"
      if [ "$a" != "$b" ]; then echo "✗ $item 不一致：基线={$a} 新={$b}"; rc=1; fi
    done
    a="$("$NM" -D --defined-only "$local_so" | awk '{print $NF}' | sort)"
    b="$("$NM" -D --defined-only "$new_so"   | awk '{print $NF}' | sort)"
    if [ "$a" != "$b" ]; then echo "✗ 导出符号面不一致"; rc=1; fi
    a="$("$RE" -lW "$local_so" | awk '$1=="LOAD"{print $NF}' | sort -u)"
    b="$("$RE" -lW "$new_so"   | awk '$1=="LOAD"{print $NF}' | sort -u)"
    if [ "$a" != "$b" ]; then echo "✗ LOAD 对齐不一致：基线={$a} 新={$b}"; rc=1; fi

    # ── 功能面对拍：aria2 的编译期 feature 摘要 + 组件版本字面量 ──
    # aria2 FeatureConfig.cc 把启用的功能编成字面量串进二进制（"Enabled
    # Features" 清单）；usedLibs() 同理嵌入组件版本。基线取证值：
    #   启用: BitTorrent / Firefox3 Cookie / GZip / HTTPS / Message Digest /
    #         Metalink / XML-RPC / SFTP（WebSocket 类符号在，属 XML-RPC 附带）
    #   禁用: Async DNS（→ 原构建无 c-ares/libuv，与 build-aria2.sh 一致）
    feature_profile() { # feature_profile <so> —— 打印排序后的功能指纹
      strings -a "$1" | grep -x -e 'Async DNS' -e 'BitTorrent' \
        -e 'Firefox3 Cookie' -e 'GZip' -e 'HTTPS' -e 'Message Digest' \
        -e 'Metalink' -e 'XML-RPC' -e 'SFTP' | sort -u
      strings -a "$1" | grep -oE 'aria2/[0-9.]+|sqlite3/[0-9.]+' | sort -u
    }
    a="$(feature_profile "$local_so")"
    b="$(feature_profile "$new_so")"
    if [ "$a" != "$b" ]; then
      echo "✗ 功能面不一致（feature/组件版本指纹 diff）:"
      diff <(echo "$a") <(echo "$b") || true
      rc=1
    else
      echo "  ✓ 功能面一致（feature 摘要 + aria2/sqlite3 版本指纹逐项相同）"
    fi
  done
done
echo "════ 对拍完成（退出码 $rc；size/NDK note 差异属预期，其余须人工确认）════"
exit "$rc"
