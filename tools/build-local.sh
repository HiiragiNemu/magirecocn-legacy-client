#!/usr/bin/env bash
#
# 本地出包——.github/workflows/build-apk.yml 的**逐步等价**本地版。
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
# 为什么要与 workflow 逐步对齐：2026-08-15 之后 workflow 改了近三十次（版本号注入、
# 主引擎注入、strip、十几道守卫、自检项），本脚本一直停在旧形状；CI 一旦不可用
# （2026-09 就发生过），本脚本就是唯一的出包路径，那时它必须能产出与 CI 同形的包。
# **改 workflow 的构建步骤时同步改这里**，反之亦然；步骤标题与 workflow 一一对应。
#
# 用法：tools/build-local.sh [工作目录]
#   工作目录放中间产物与最终 APK；不写则用 .build-local。
#
# 必需环境变量：
#   NDK           android-ndk 根目录
#   BUILD_TOOLS   Android SDK build-tools/<ver>（d8 / zipalign / apksigner / aapt）
#   BAKSMALI_JAR  baksmali fat jar
#   DEPS_DIR      含 android.jar、okhttp.jar、okio.jar（或 okhttp-*.jar / okio-*.jar）
#   SIGN_KEY      testkey.pk8        SIGN_CERT  testkey.x509.pem
# 可选：
#   CLIENT_ROOT_DOMAIN / CLIENT_PAGES_HOSTS   真实端点；不给则用占位域名（包连不上真实服务）
#   CLIENT_VERSION   注入的客户端版本号（默认 1.0.0——**会被云端强制更新**，装机测试请给一个
#                    不低于线上 client.version 的值，如 1.0.9999）
#   ENGINE           主下载引擎默认值 self|aria2c（默认 self）
#   DEBUG_OVERLAY    1 = 编入调试悬浮窗并保留 SYSTEM_ALERT_WINDOW（默认 0，与正式包一致）
#   VGMSTREAM / OGGENC   BGM 转码工具；缺了只告警，包里没有浮层 BGM
#   BASELINE_JAVA    重建基线树用的 java（需 JDK 19+；默认 PATH 上的 java）
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-${REPO}/.build-local}"
cd "$REPO"

: "${NDK:?请设置 NDK 指向 android-ndk 根目录}"
: "${BUILD_TOOLS:?请设置 BUILD_TOOLS 指向 Android SDK build-tools/<ver>}"
: "${BAKSMALI_JAR:?请设置 BAKSMALI_JAR}"
: "${DEPS_DIR:?请设置 DEPS_DIR（含 android.jar / okhttp / okio）}"
: "${SIGN_KEY:?请设置 SIGN_KEY（.pk8）}"
: "${SIGN_CERT:?请设置 SIGN_CERT（.x509.pem）}"
CLIENT_VERSION="${CLIENT_VERSION:-1.0.0}"
ENGINE="${ENGINE:-self}"
DEBUG_OVERLAY="${DEBUG_OVERLAY:-0}"

ABIS=(arm64-v8a armeabi-v7a)
say() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
die() { echo "✘ $*" >&2; exit 1; }

# ── 被注入的源文件：开工前存档，退出时一律还原 ──────────────────────
# workflow 跑在一次性 runner 上，注入完就丢；本地不是，注入结果留在工作树里就会
# 被顺手提交进历史（版本号、主域、主引擎都不该入库）。用快照还原而不是逐个
# `--reset`：三种注入各有各的还原方式，漏一个就是一次意外提交。
INJECTED=(magia-native/src/MagiaLegacy.cpp
          patch/src/main/java/io/kamihama/magianative/CNEndpoints.java
          patch/src/main/java/io/kamihama/magianative/CNBuildConfig.java)
SNAP="$OUT/snap"
mkdir -p "$SNAP"
for f in "${INJECTED[@]}"; do mkdir -p "$SNAP/$(dirname "$f")"; cp "$f" "$SNAP/$f"; done
restore() { for f in "${INJECTED[@]}"; do cp "$SNAP/$f" "$f"; done; }
trap restore EXIT

mkdir -p "$OUT"
export TREE="$OUT/tree"

# ── 🔑 预检必需的输入（对应 workflow「预检必需的 secret」）──────────
say "预检工具与依赖"
for t in d8 zipalign apksigner aapt; do [ -x "$BUILD_TOOLS/$t" ] || die "缺 $BUILD_TOOLS/$t"; done
[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || die "NDK 目录不对：$NDK"
ANDROID_JAR="$DEPS_DIR/android.jar"
OKHTTP_JAR="$(ls "$DEPS_DIR"/okhttp*.jar | head -1)"; OKIO_JAR="$(ls "$DEPS_DIR"/okio*.jar | head -1)"
[ -f "$ANDROID_JAR" ] && [ -n "$OKHTTP_JAR" ] && [ -n "$OKIO_JAR" ] || die "DEPS_DIR 里缺 android.jar / okhttp / okio"
[ -f "$SIGN_KEY" ] && [ -f "$SIGN_CERT" ] || die "签名密钥不存在"
case "$ENGINE" in self|aria2c) ;; *) die "非法引擎值：$ENGINE（只接受 self / aria2c）" ;; esac
if [ -n "${CLIENT_ROOT_DOMAIN:-}" ] && [ -n "${CLIENT_PAGES_HOSTS:-}" ]; then REAL_ENDPOINTS=1; else REAL_ENDPOINTS=0; fi
echo "版本号 $CLIENT_VERSION · 主引擎 $ENGINE · 调试悬浮窗 $DEBUG_OVERLAY · 端点 $([ $REAL_ENDPOINTS = 1 ] && echo 真实 || echo 占位)"

# ── 🚧🔒🧬⚖️ 取包之前的离线守卫 ─────────────────────────────────────
say "离线守卫（native 入口保护 / 调试开关边界 / patchset 自洽 / 第三方声明）"
python3 tools/check-entry-guard.py
python3 tools/check-debug-flag-boundary.py
python3 tools/check-baseline.py
python3 tools/test-check-baseline.py
python3 tools/check-third-party-notices.py
python3 tools/test-check-third-party-notices.py

# ── 🌱 从整包重建工程树 ──────────────────────────────────────────────
# 取件地址由 BASELINE_APK_URL / OVERLAY_URL 给（或事先把文件放进 work/baseline/，
# 内容按 sha256 认）。重建要 JDK 19+，理由见 workflow 同名步骤。
say "从整包重建工程树"
python3 tools/baseline.py fetch ${BASELINE_JAVA:+--java "$BASELINE_JAVA"}
rm -rf "$TREE"
python3 tools/baseline.py apply --out "$TREE"
echo "重建树：$(find "$TREE" -type f | wc -l) 个文件"
APKTOOL_JAR="$REPO/work/baseline/apktool.jar"
[ -f "$APKTOOL_JAR" ] || die "baseline.py 没有留下 apktool.jar"

# ── 🚫 F-043 正式包剔除悬浮窗权限 ───────────────────────────────────
MANIFEST="$TREE/AndroidManifest.xml"
if [ "$DEBUG_OVERLAY" != 1 ]; then
    say "F-043：正式包剔除 SYSTEM_ALERT_WINDOW"
    sed -i '/android.permission.SYSTEM_ALERT_WINDOW/d' "$MANIFEST"
    grep -q 'SYSTEM_ALERT_WINDOW' "$MANIFEST" && die "正式包仍含 SYSTEM_ALERT_WINDOW，剔除失败"
fi

# ── 🛠 F-051 移除离线导入跳板的 noHistory ────────────────────────────
say "F-051：移除 CNOfflineImportActivity 的 noHistory"
sed -i '/CNOfflineImportActivity/s/ android:noHistory="true"//' "$MANIFEST"
grep 'CNOfflineImportActivity' "$MANIFEST" | grep -q 'noHistory="true"' && die "noHistory 移除失败"

# ── 🗑 剔除死库 ─────────────────────────────────────────────────────
say "剔除死库 libbacktrace-native / libcrashpad_handler"
if grep -rlsI -e 'backtrace-native' -e 'crashpad' \
     --include='*.smali' --include='*.xml' --include='*.json' --include='*.java' \
     --include='*.cpp' --include='*.h' --include='*.txt' --include='*.yml' "$TREE"; then
    die "工程树里仍有 backtrace/crashpad 引用（见上），不能删库"
fi
for abi in "${ABIS[@]}"; do rm -f "$TREE/lib/$abi/libbacktrace-native.so" "$TREE/lib/$abi/libcrashpad_handler.so"; done
for f in "$TREE"/lib/*/libbacktrace-native.so "$TREE"/lib/*/libcrashpad_handler.so; do [ -e "$f" ] && die "$f 删除失败"; done
echo "已剔除 4 枚死库"

# ── 🔤🕸🔀🧭🧵🧩🔄🔗📝 读工程树与源码的守卫 ───────────────────────────
say "工程树与源码守卫"
python3 tools/check-fonts.py --tree "$TREE"
python3 tools/check-webview-interceptor.py
python3 tools/check-proxy-hooks.py
python3 tools/check-initlabel-abi.py
python3 tools/check-native-string-layout.py
python3 tools/test-check-native-string-layout.py
python3 tools/check-cnzip-guards.py
python3 tools/check-engine-i18n-prefix.py
python3 tools/test-check-engine-i18n-prefix.py
python3 tools/check-engine-i18n-reload.py
python3 tools/test-check-engine-i18n-reload.py
python3 tools/check-base-urls.py
tools/check-native-syntax.sh

# ── 🔢 注入客户端版本号 ─────────────────────────────────────────────
say "注入客户端版本号 $CLIENT_VERSION"
sed -i -E "s/CLIENT_VERSION = \"[^\"]+\"/CLIENT_VERSION = \"${CLIENT_VERSION}\"/" magia-native/src/MagiaLegacy.cpp
grep -q "CLIENT_VERSION = \"${CLIENT_VERSION}\"" magia-native/src/MagiaLegacy.cpp || die "版本号注入失败（MagiaLegacy.cpp）"
# Java 侧不再有版本号常量可注入（CNUserAgent 向 native 要），理由见
# MagiaLegacy.cpp 里 CLIENT_VERSION 的注释。

# ── 🌐 注入端点 ─────────────────────────────────────────────────────
# 不回显取值：主域是部署参数，终端记录也会长期留存。
say "注入端点"
if [ "$REAL_ENDPOINTS" = 1 ]; then
    python3 tools/inject-endpoints.py
else
    echo "⚠ 未设 CLIENT_ROOT_DOMAIN / CLIENT_PAGES_HOSTS，用占位域名——本包连不上真实服务，不要拿去装"
    python3 tools/inject-endpoints.py --test
fi
MAGIA_ROOT_DOMAIN="$(python3 tools/inject-endpoints.py --print-root)"

# ── 🛠 编译 libMagiaLegacy.so ───────────────────────────────────────
say "编译 native（${#ABIS[@]} 个 ABI）+ strip"
STRIP="$(find "$NDK/toolchains/llvm/prebuilt" -name llvm-strip 2>/dev/null | head -1)"
for abi in "${ABIS[@]}"; do
    bdir="magia-native/build/$abi"
    # 缓存已在时 configure 不会重跑；显式重设主域与调试开关，否则 native 侧沿用上次的值——
    # 「自身域不重写」两侧不一致会打成死循环，是最难查的那类症状。
    cmake -G Ninja \
          -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
          -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-21 \
          -DCMAKE_BUILD_TYPE=Release \
          -DMAGIA_ROOT_DOMAIN="$MAGIA_ROOT_DOMAIN" \
          -DMAGIA_DEBUG_OVERLAY="$DEBUG_OVERLAY" \
          -B "$bdir" magia-native > "$OUT/cmake-$abi.log" 2>&1 || { cat "$OUT/cmake-$abi.log"; die "[$abi] cmake configure 失败"; }
    cmake --build "$bdir" --parallel
    so="$bdir/libMagiaLegacy.so"; [ -f "$so" ] || die "[$abi] 未产出 libMagiaLegacy.so"
    cp "$so" "$TREE/lib/$abi/libMagiaLegacy.so"
    [ -n "$STRIP" ] && "$STRIP" --strip-unneeded "$TREE/lib/$abi/libMagiaLegacy.so"
    # shadowhook 是动态库，libMagiaLegacy 对它有 DT_NEEDED，漏了真机启动即 dlopen 失败
    sh="$bdir/_deps/shadowhook-build/libshadowhook.so"; [ -f "$sh" ] || die "[$abi] 未产出 libshadowhook.so"
    cp "$sh" "$TREE/lib/$abi/libshadowhook.so"
    [ -n "$STRIP" ] && "$STRIP" --strip-unneeded "$TREE/lib/$abi/libshadowhook.so"
    echo "[$abi] MagiaLegacy=$(du -h "$TREE/lib/$abi/libMagiaLegacy.so" | cut -f1) shadowhook=$(du -h "$TREE/lib/$abi/libshadowhook.so" | cut -f1)"
done
for f in "$TREE"/lib/arm64-v8a/libuwasa.so "$TREE"/lib/armeabi-v7a/libuwasa.so "$TREE"/lib/arm64-v8a/libcn_hook.so; do
    [ -e "$f" ] && die "$f 仍然存在，会与 libMagiaLegacy 冲突"
done

# ── 🎵 BGM：HCA → OGG ──────────────────────────────────────────────
say "转换 BGM（HCA → OGG）"
VGMS="${VGMSTREAM:-$(command -v vgmstream-cli || true)}"
OGGENC="${OGGENC:-$(command -v oggenc || true)}"
if [ -n "$VGMS" ] && [ -n "$OGGENC" ]; then
    python3 tools/convert-bgm.py --vgmstream "$VGMS" --oggenc "$OGGENC" --tree "$TREE"
    for f in bgm1.ogg bgm2.ogg bgm.json; do
        [ -s "$TREE/assets/magia/$f" ] || echo "⚠ 缺少 assets/magia/$f，本包浮层将没有 BGM"
    done
else
    echo "⚠ 找不到 vgmstream-cli 或 oggenc（可用 VGMSTREAM= / OGGENC= 指定；oggenc 来自 vorbis-tools）——本包浮层将没有 BGM"
fi

# ── 🚂 注入主下载引擎 ───────────────────────────────────────────────
say "注入主下载引擎 $ENGINE"
sed -i -E "s/MAIN_ENGINE = \"[a-z0-9]+\"/MAIN_ENGINE = \"${ENGINE}\"/" patch/src/main/java/io/kamihama/magianative/CNBuildConfig.java
grep -q "MAIN_ENGINE = \"${ENGINE}\"" patch/src/main/java/io/kamihama/magianative/CNBuildConfig.java || die "引擎注入失败"

# ── ☕ 编译补丁源码 → dex ────────────────────────────────────────────
say "编译补丁源码"
rm -rf "$OUT/classes" "$OUT/dexui" "$OUT/dex3" "$OUT/smaliui" "$OUT/smali3"
mkdir -p "$OUT/classes" "$OUT/dexui" "$OUT/dex3" "$OUT/stubs/io/kamihama/magianative"
# RestClient 只作为编译期桩：真实实现在 smali_classes2 里，不参与 dex 产出（与 workflow 同一份）。
cat > "$OUT/stubs/io/kamihama/magianative/RestClient.java" <<'STUB'
package io.kamihama.magianative;
import android.app.Activity;
public class RestClient {
    public static Activity getCurrentActivity() { return null; }
}
STUB
CP="$ANDROID_JAR:$OKHTTP_JAR:$OKIO_JAR"
mapfile -t SRC < <(find patch/src/main/java -name '*.java')
echo "编译 ${#SRC[@]} 个补丁源文件"
javac -nowarn -source 8 -target 8 -encoding UTF-8 -cp "$CP" -d "$OUT/classes" \
      "${SRC[@]}" "$OUT/stubs/io/kamihama/magianative/RestClient.java" 2> >(grep -v "^warning: \[options\]" >&2 || true)
python3 tools/check-d8-pitfalls.py "$OUT/classes"

# 分组必须与 workflow 一致：UI 类进 classes2，其余用**排除法**进 classes3，每个类恰好一组。
mapfile -t DEX_UI < <(find "$OUT/classes" -name 'CNCNDownloadUI*.class' | sort)
mapfile -t DEX_3  < <(find "$OUT/classes" -name '*.class' ! -name 'CNCNDownloadUI*.class' ! -name 'RestClient*.class' | sort)
echo "classes2 组 ${#DEX_UI[@]} 个类，classes3 组 ${#DEX_3[@]} 个类"
[ "${#DEX_UI[@]}" -gt 0 ] && [ "${#DEX_3[@]}" -gt 0 ] || die "dex 分组为空，补丁类没被编译出来"
TOTAL=$(find "$OUT/classes" -name '*.class' ! -name 'RestClient*.class' | wc -l)
[ "$(( ${#DEX_UI[@]} + ${#DEX_3[@]} ))" -eq "$TOTAL" ] || die "dex 分组没覆盖全部补丁类（$TOTAL 个）"

# 并且 d8 一有告警就失败：minSdk 21 下 default 方法必须靠 desugar 才能在 API 21–23 上跑，
# 看不见接口时 d8 只报一句告警，然后产出装得上、跑起来炸的类。与 workflow 的 run_d8 同一套判据。
run_d8() {
    local out="$1"; shift
    local log="$OUT/d8-$(basename "$out").log"
    if ! "$BUILD_TOOLS/d8" --min-api 21 --output "$out" --lib "$ANDROID_JAR" --classpath "$OUT/classes" "$@" > "$log" 2>&1; then
        cat "$log"; die "d8 失败（$out）"
    fi
    cat "$log"
    if grep -qE '^(Warning|Error)' "$log"; then
        die "d8 出了告警（$out）——desugar 相关告警在 minSdk 21 上可能意味着产出的类装得上但跑起来炸"
    fi
}
run_d8 "$OUT/dexui" "${DEX_UI[@]}"
run_d8 "$OUT/dex3"  "${DEX_3[@]}"
java -jar "$BAKSMALI_JAR" d "$OUT/dexui/classes.dex" -o "$OUT/smaliui"
java -jar "$BAKSMALI_JAR" d "$OUT/dex3/classes.dex"  -o "$OUT/smali3"

# ── 🔁 用编译产物覆盖补丁 smali ─────────────────────────────────────
say "用编译产物覆盖补丁 smali"
rm -f "$TREE"/smali_classes2/io/kamihama/magianative/CNCNDownloadUI*.smali
cp "$OUT"/smaliui/io/kamihama/magianative/CNCNDownloadUI*.smali "$TREE/smali_classes2/io/kamihama/magianative/"
rm -rf "$TREE/smali_classes3"; mkdir -p "$TREE/smali_classes3"
cp -r "$OUT/smali3/." "$TREE/smali_classes3/"
echo "smali_classes2 补丁类：$(ls "$TREE"/smali_classes2/io/kamihama/magianative/CNCNDownloadUI*.smali | wc -l) 个"
echo "smali_classes3 类：$(find "$TREE/smali_classes3" -name '*.smali' | wc -l) 个"

# ── 🔨📐🔏 重组 / 对齐 / 签名 ───────────────────────────────────────
say "apktool b / zipalign / apksigner"
java -jar "$APKTOOL_JAR" b "$TREE" -o "$OUT/unsigned.apk" --use-aapt2
"$BUILD_TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BUILD_TOOLS/zipalign" -c 4 "$OUT/aligned.apk"
APK="$OUT/legacy-client.apk"
"$BUILD_TOOLS/apksigner" sign --key "$SIGN_KEY" --cert "$SIGN_CERT" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$APK" "$OUT/aligned.apk"

# ── 🔍 构建后自检（与 workflow 同一清单）────────────────────────────
say "构建后自检"
echo "sha256: $(sha256sum "$APK" | cut -d' ' -f1)"
echo "大小:   $(stat -c%s "$APK") 字节"
python3 tools/check-asset-compression.py "$APK"
python3 tools/check-so-deps.py "$APK"
python3 tools/check-so-alignment.py
python3 tools/check-so-alignment.py "$TREE/lib/arm64-v8a/libMagiaLegacy.so" "$TREE/lib/arm64-v8a/libshadowhook.so"
python3 tools/check-apk-freshness.py "$APK"
python3 tools/check-no-plain-version.py "$APK" "$CLIENT_VERSION"
FP=$("$BUILD_TOOLS/apksigner" verify --print-certs "$APK" | grep -m1 'SHA-256 digest' | awk '{print $NF}')
EXPECT="a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"
[ "$FP" = "$EXPECT" ] || die "签名指纹不符，实得 $FP"
echo "签名指纹校验通过（AOSP testkey）"
MIN_SDK=$("$BUILD_TOOLS/aapt" dump badging "$APK" | awk -F"'" '/^sdkVersion:/{print $2}')
[ "$MIN_SDK" = "21" ] || die "产物 minSdkVersion=${MIN_SDK:-?}，期望 21"
echo "minSdkVersion=$MIN_SDK"
python3 tools/check-api-levels.py
# 不用 grep -q：它读到匹配就退出，unzip 被 SIGPIPE 打断，pipefail 下整条管线判失败——
# 明明包里有 classes3.dex 却报「缺少」。让 grep 读完全部输入再判。
unzip -Z1 "$APK" | grep -x 'classes3.dex' > /dev/null || die "产物缺少 classes3.dex"
echo
echo "✔ 出包完成：$APK（版本 $CLIENT_VERSION，端点 $([ $REAL_ENDPOINTS = 1 ] && echo 真实 || echo 占位)）"
