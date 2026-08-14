#!/usr/bin/env bash
#
# 本地出包：native → lib/ → javac → d8 → baksmali → apktool → zipalign → 签名 → 自检。
#
# 为什么要有这个脚本：这条流水线手工拼过好几轮，每次都踩同一类坑——**某一步的产物
# 没能进包，而所有静态检查都过**：
#
#   · CNBgm 编译出了 .class，却不在任何一组 d8 的输入里 → 类根本不在 APK 里，
#     浮层建到一半抛 NoClassDefFoundError；
#   · libMagiaLegacy.so 编译好了，却忘了从 magia-native/build/ 拷进 lib/ →
#     发出去的包带的是上一版库，新加的 JNI 调用一行日志都不打，看起来像「功能
#     没生效」，实际是根本没装上。
#
# 两次都靠真机日志才发现，各费掉一整轮往返。所以：每一步产物都在这里核对，
# 对不上就直接失败，别让它走到玩家手上。
#
# 用法：tools/build-local.sh <工作目录>
#   工作目录用于放中间产物与最终 APK；不写则用 .build-local。
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-${REPO}/.build-local}"
cd "$REPO"

# ── 外部工具位置（可用环境变量覆盖）──────────────────────────
: "${NDK:?请设置 NDK 指向 android-ndk 根目录}"
: "${BUILD_TOOLS:?请设置 BUILD_TOOLS 指向 Android SDK build-tools/<ver>}"
: "${APKTOOL_JAR:?请设置 APKTOOL_JAR}"
: "${BAKSMALI_JAR:?请设置 BAKSMALI_JAR}"
: "${DEPS_DIR:?请设置 DEPS_DIR（含 android.jar / okhttp / okio）}"
: "${SIGN_KEY:?请设置 SIGN_KEY（.pk8）}"
: "${SIGN_CERT:?请设置 SIGN_CERT（.x509.pem）}"

ABIS=(arm64-v8a armeabi-v7a)

say() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }

# ── 1. native ────────────────────────────────────────────────
say "编译 native（${#ABIS[@]} 个 ABI）"
for abi in "${ABIS[@]}"; do
    bdir="magia-native/build/$abi"
    if [ ! -f "$bdir/CMakeCache.txt" ]; then
        cmake -G Ninja \
              -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
              -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-21 \
              -B "$bdir" magia-native
    fi
    cmake --build "$bdir"
done

# ── 2. 拷进 lib/，并**核对确实拷到了** ───────────────────────
# 这一步就是上面说的第二个坑。只 cp 不校验等于没校验：cp 失败、拷错路径、
# 拷了但被别的步骤覆盖，都还是会出一个带旧库的包。
say "把 native 产物拷进 lib/ 并校验"
for abi in "${ABIS[@]}"; do
    for so in libMagiaLegacy.so; do
        src="magia-native/build/$abi/$so"
        cp "$src" "lib/$abi/$so"
        if ! cmp -s "$src" "lib/$abi/$so"; then
            echo "✘ $abi/$so 拷贝后与构建产物不一致"; exit 1
        fi
    done
    src_sh="magia-native/build/$abi/_deps/shadowhook-build/libshadowhook.so"
    if [ -f "$src_sh" ]; then
        cp "$src_sh" "lib/$abi/libshadowhook.so"
        cmp -s "$src_sh" "lib/$abi/libshadowhook.so" || { echo "✘ $abi/libshadowhook.so 不一致"; exit 1; }
    fi
    echo "  ✔ $abi"
done

# ── 3. Java → dex → smali ────────────────────────────────────
say "编译补丁源码"
rm -rf "$OUT/classes" "$OUT/dexui" "$OUT/dex3" "$OUT/smaliui" "$OUT/smali3"
mkdir -p "$OUT/classes" "$OUT/dexui" "$OUT/dex3" "$OUT/stubs/io/kamihama/magianative"

# RestClient 只作为编译期桩：真实实现在 smali_classes2 里，不参与 dex 产出。
# 与 .github/workflows/build-apk.yml 里那段保持一致。
cat > "$OUT/stubs/io/kamihama/magianative/RestClient.java" <<'STUB'
package io.kamihama.magianative;
import android.app.Activity;
public class RestClient {
    public static Activity getCurrentActivity() { return null; }
    public static void restartApp() {}
}
STUB

CP="$DEPS_DIR/android.jar:$(ls "$DEPS_DIR"/okhttp-*.jar):$(ls "$DEPS_DIR"/okio-*.jar)"
mapfile -t SRC < <(find patch/src/main/java -name '*.java')
javac -nowarn -source 8 -target 8 -encoding UTF-8 -cp "$CP" -d "$OUT/classes" \
      "${SRC[@]}" "$OUT/stubs/io/kamihama/magianative/RestClient.java"

# 分组必须与 workflow 一致：UI 类进 classes2，其余进 classes3（排除编译期桩）。
mapfile -t DEX_UI < <(find "$OUT/classes" -name 'CNCNDownloadUI*.class' | sort)
mapfile -t DEX_3  < <(find "$OUT/classes" -name '*.class' \
                        ! -name 'CNCNDownloadUI*.class' ! -name 'RestClient*.class' | sort)
TOTAL=$(find "$OUT/classes" -name '*.class' ! -name 'RestClient*.class' | wc -l)
echo "  classes2 组 ${#DEX_UI[@]}，classes3 组 ${#DEX_3[@]}，补丁类共 $TOTAL"
if [ "$(( ${#DEX_UI[@]} + ${#DEX_3[@]} ))" -ne "$TOTAL" ]; then
    echo "✘ dex 分组没覆盖全部补丁类——有类会静默缺席"; exit 1
fi

# 两组各自出 dex，但 desugar 要看见**全部**补丁类，所以整个 classes 目录都作为
# --classpath 传进去（只供解析类型，不写进输出 dex——加与不加实测逐字节相同）。
# 并且 d8 一有告警就失败：minSdk 21 下 default 方法必须靠 desugar 才能在
# API 21–23 上跑，看不见接口时 d8 只报一句告警，然后产出装得上、跑起来炸的类。
# 与 build-apk.yml 的 run_d8 同一套判据，改一处要改两处。
run_d8() {
    local out="$1"; shift
    local log="$OUT/d8-$(basename "$out").log"
    if ! "$BUILD_TOOLS/d8" --min-api 21 --output "$out" \
         --lib "$DEPS_DIR/android.jar" --classpath "$OUT/classes" "$@" > "$log" 2>&1; then
        cat "$log"; echo "✘ d8 失败（$out）"; exit 1
    fi
    cat "$log"
    if grep -qE '^(Warning|Error)' "$log"; then
        echo "✘ d8 出了告警（$out）——desugar 相关告警在 minSdk 21 上可能意味着"
        echo "  产出的类装得上但跑起来炸，不要当噪音略过。"
        exit 1
    fi
}
run_d8 "$OUT/dexui" "${DEX_UI[@]}"
run_d8 "$OUT/dex3"  "${DEX_3[@]}"
java -jar "$BAKSMALI_JAR" d "$OUT/dexui/classes.dex" -o "$OUT/smaliui"
java -jar "$BAKSMALI_JAR" d "$OUT/dex3/classes.dex"  -o "$OUT/smali3"

say "用编译产物覆盖补丁 smali"
rm -f smali_classes2/io/kamihama/magianative/CNCNDownloadUI*.smali
cp "$OUT"/smaliui/io/kamihama/magianative/CNCNDownloadUI*.smali \
   smali_classes2/io/kamihama/magianative/
rm -rf smali_classes3 && mkdir -p smali_classes3
cp -r "$OUT"/smali3/. smali_classes3/

# ── 4. 打包 / 对齐 / 签名 ────────────────────────────────────
say "apktool b"
java -jar "$APKTOOL_JAR" b . -o "$OUT/unsigned.apk" --use-aapt2
"$BUILD_TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BUILD_TOOLS/apksigner" sign --key "$SIGN_KEY" --cert "$SIGN_CERT" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT/magireco-legacy.apk" "$OUT/aligned.apk"

# 生成的 smali 是产物，不入库——还原工作树，免得误提交
git checkout -- smali_classes2 smali_classes3 2>/dev/null || true
git clean -fdq smali_classes2 smali_classes3 2>/dev/null || true

# ── 5. 自检 ──────────────────────────────────────────────────
say "自检"
python3 tools/check-so-deps.py           "$OUT/magireco-legacy.apk"
python3 tools/check-entry-guard.py       "$OUT/magireco-legacy.apk"
python3 tools/check-asset-compression.py "$OUT/magireco-legacy.apk"
python3 tools/check-apk-freshness.py     "$OUT/magireco-legacy.apk"

"$BUILD_TOOLS/apksigner" verify --print-certs "$OUT/magireco-legacy.apk" \
    | grep -i "SHA-256 digest" || true
sha256sum "$OUT/magireco-legacy.apk"
echo "✔ 出包完成：$OUT/magireco-legacy.apk"
