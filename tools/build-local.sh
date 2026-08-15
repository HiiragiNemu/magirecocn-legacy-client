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

# ── 0. 工程树：从Totentanz 整包重建 ────────────────────────────────
# 仓库里不再有 客户端基线树（2026-08-14 起原包派生文件已删除，见 README
# 「基线与补丁」）。所有写进工程树的步骤都用 $TREE，读工程树的守卫也认它。
# ⚠ 重建要 JDK 19+：构建链给 const 附的 float 注释来自 Float.toString，
#   JDK 19 换过算法。要用别的 JDK 重建就设 BASELINE_JAVA 指到它的 java。
say "从Totentanz 整包重建工程树"
export TREE="$OUT/tree"
python3 tools/baseline.py fetch ${BASELINE_JAVA:+--java "$BASELINE_JAVA"}
python3 tools/baseline.py apply --out "$TREE"
echo "重建树：$(find "$TREE" -type f | wc -l) 个文件"

python3 tools/check-fonts.py --tree "$TREE"
python3 tools/check-webview-interceptor.py

# ── 0'. 端点注入 ─────────────────────────────────────────────
# 源码里 CNEndpoints 的主机名常量恒为空串，真值不入库。本地出包默认用**占位**
# 域名（example.test / *.pages.example）——够跑通全流程与全部自检，但那个包
# **连不上任何真实服务**，不要拿去装。
#
# 要出能真用的本地包：设 CLIENT_ROOT_DOMAIN 与 CLIENT_PAGES_HOSTS 再跑，
# 脚本会用真值注入（规范前缀 sha256 对不上时会当场失败——那一串是写进已装
# 设备 15 个完成标记里的身份串，注错等于让老玩家重下几个 GB）。
#
# 退出时一律还原成空串，避免注入结果被顺手提交进历史。
say "注入端点"
if [ -n "${CLIENT_ROOT_DOMAIN:-}" ] && [ -n "${CLIENT_PAGES_HOSTS:-}" ]; then
    python3 tools/inject-endpoints.py
    ENDPOINT_ROOT="$(python3 tools/inject-endpoints.py --print-root)"
else
    echo "⚠ 未设 CLIENT_ROOT_DOMAIN / CLIENT_PAGES_HOSTS，用占位域名——本包连不上真实服务"
    python3 tools/inject-endpoints.py --test
    ENDPOINT_ROOT="example.test"
fi
trap 'python3 tools/inject-endpoints.py --reset >/dev/null 2>&1 || true' EXIT

# ── 1. native ────────────────────────────────────────────────
say "编译 native（${#ABIS[@]} 个 ABI）"
for abi in "${ABIS[@]}"; do
    bdir="magia-native/build/$abi"
    if [ ! -f "$bdir/CMakeCache.txt" ]; then
        cmake -G Ninja \
              -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
              -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-21 \
              -DMAGIA_ROOT_DOMAIN="$ENDPOINT_ROOT" \
              -B "$bdir" magia-native
    else
        # 缓存已在时 configure 不会重跑，光靠上面那一行改不动主域。显式重设，
        # 否则换了 CLIENT_ROOT_DOMAIN 之后 native 侧还用着上一次的值——而
        # 「自身域不重写」两侧不一致会打成死循环，是最难查的那类症状。
        cmake -DMAGIA_ROOT_DOMAIN="$ENDPOINT_ROOT" -B "$bdir" magia-native >/dev/null
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
        cp "$src" "$TREE/lib/$abi/$so"
        if ! cmp -s "$src" "$TREE/lib/$abi/$so"; then
            echo "✘ $abi/$so 拷贝后与构建产物不一致"; exit 1
        fi
    done
    src_sh="magia-native/build/$abi/_deps/shadowhook-build/libshadowhook.so"
    if [ -f "$src_sh" ]; then
        cp "$src_sh" "$TREE/lib/$abi/libshadowhook.so"
        cmp -s "$src_sh" "$TREE/lib/$abi/libshadowhook.so" || { echo "✘ $abi/libshadowhook.so 不一致"; exit 1; }
    fi
    echo "  ✔ $abi"
done

# ── 2.5 BGM：HCA → OGG ───────────────────────────────────────
# 这一步以前不在本脚本里，于是本地出的包**没有浮层 BGM**，而所有静态检查照过
# ——正是本文件头注里骂的那类坑。与 build-apk.yml 的同名步骤同一套做法。
# 工具不在就只告警：音频不是关键路径，但「悄悄没有」不行，得说出来。
say "转换 BGM（HCA → OGG）"
VGMS="${VGMSTREAM:-$(command -v vgmstream-cli || true)}"
FF="${FFMPEG:-$(command -v ffmpeg || true)}"
if [ -n "$VGMS" ] && [ -n "$FF" ]; then
    python3 tools/convert-bgm.py --vgmstream "$VGMS" --ffmpeg "$FF" --tree "$TREE"
    for f in bgm1.ogg bgm2.ogg bgm.json; do
        [ -s "$TREE/assets/magia/$f" ] || echo "⚠ 缺少 assets/magia/$f，本包浮层将没有 BGM"
    done
else
    echo "⚠ 找不到 vgmstream-cli 或 ffmpeg（可用 VGMSTREAM= / FFMPEG= 指定）"
    echo "  本包浮层将没有 BGM——这不是构建失败，但别拿它去验 BGM 相关的改动。"
fi

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
rm -f "$TREE"/smali_classes2/io/kamihama/magianative/CNCNDownloadUI*.smali
cp "$OUT"/smaliui/io/kamihama/magianative/CNCNDownloadUI*.smali \
   "$TREE/smali_classes2/io/kamihama/magianative/"
rm -rf "$TREE/smali_classes3" && mkdir -p "$TREE/smali_classes3"
cp -r "$OUT"/smali3/. "$TREE/smali_classes3/"

# ── 4. 打包 / 对齐 / 签名 ────────────────────────────────────
say "apktool b"
java -jar "$APKTOOL_JAR" b "$TREE" -o "$OUT/unsigned.apk" --use-aapt2
"$BUILD_TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BUILD_TOOLS/apksigner" sign --key "$SIGN_KEY" --cert "$SIGN_CERT" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT/magireco-legacy.apk" "$OUT/aligned.apk"

# 从前这里要 git checkout 还原被覆盖的 smali——现在改动全落在 $TREE 里，
# 仓库工作树自始至终没被碰过，不需要还原。

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
