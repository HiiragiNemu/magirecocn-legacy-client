#!/bin/bash
# 构建 libarchive.so（JNI 解压库）两 ABI：arm64-v8a + armeabi-v7a。
# 在 CI（GitHub ubuntu-latest，自带 NDK）上跑；NDK 的 clang wrapper 处理
# -lgcc/sysroot/crt，无需本机工具链。
#
# 用法: NDK=<ndk路径> LIBARCHIVE_SRC=<源码目录> ZLIB_SRC=<源码目录> bash tools/build-libarchive.sh
# 产物: out/<abi>/libarchive.so（相对当前目录，绝对路径）
set -euo pipefail

NDK="${NDK:?请设置 NDK}"
SRC_LA="${LIBARCHIVE_SRC:?请设置 LIBARCHIVE_SRC}"
SRC_ZL="${ZLIB_SRC:?请设置 ZLIB_SRC}"
WRAPPER="$(cd "$(dirname "$0")/.." && pwd)/magia-native/src/archive_jni.cpp"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
NPROC=$(nproc)
# 绝对路径：zlib 构建会 cd 进临时目录，相对 out/ 会在那里重定向出错。
OUT="$(pwd)/out"

# 跑命令并落日志；失败打印日志尾部并退出（否则 CI 上错误被吞进文件看不见）。
run_log() {
    local log="$1"; shift
    if ! "$@" >"$log" 2>&1; then
        echo "✗ 命令失败：$*"
        echo "── 日志（$log）──"
        tail -50 "$log"
        exit 1
    fi
}

build_abi() {
    local abi="$1" clang="$2"
    local PREFIX="$OUT/$abi"
    echo "════ 构建 $abi ($clang) ════"
    mkdir -p "$PREFIX" "$OUT"

    # ── 1. zlib 静态（CMake + NDK toolchain：zlib 的 autotools configure 对
    #    NDK clang 的 -Werror 探测太严会 abort，CMake 路径干净） ──
    echo "── zlib ($abi)"
    rm -rf "build-zlib-$abi"
    run_log "$OUT/zlib-$abi-conf.log" cmake -S "$SRC_ZL" -B "build-zlib-$abi" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-21 \
        -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$PREFIX"
    run_log "$OUT/zlib-$abi-build.log" cmake --build "build-zlib-$abi" --target zlibstatic -j"$NPROC"
    # 手动落位（zlib 的 cmake --install 在静态目标上偶发找不到产物）：
    # zlib.h 在源码树、zconf.h 由 CMake 生成进构建目录、libz.a 在构建目录。
    mkdir -p "$PREFIX/lib" "$PREFIX/include"
    cp "$SRC_ZL/zlib.h" "$PREFIX/include/"
    cp "build-zlib-$abi/zconf.h" "$PREFIX/include/"
    cp "build-zlib-$abi/libz.a" "$PREFIX/lib/libz.a"
    echo "  ✓ libz.a"

    # ── 2. libarchive 静态（CMake + NDK toolchain） ──
    echo "── libarchive ($abi)"
    rm -rf "cmake-$abi" && mkdir -p "cmake-$abi"
    run_log "$OUT/libarchive-$abi-conf.log" cmake -S "$SRC_LA" -B "cmake-$abi" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-21 \
        -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_PREFIX_PATH="$PREFIX" \
        -DENABLE_ZLIB=ON -DZLIB_LIBRARY="$PREFIX/lib/libz.a" -DZLIB_INCLUDE_DIR="$PREFIX/include" \
        -DENABLE_TEST=OFF -DENABLE_TAR=OFF -DENABLE_CPIO=OFF -DENABLE_CAT=OFF \
        -DENABLE_BZip2=OFF -DENABLE_LZMA=OFF -DENABLE_LZ4=OFF -DENABLE_LZO=OFF \
        -DENABLE_ZSTD=OFF -DENABLE_LIBB2=OFF -DENABLE_OPENSSL=OFF -DENABLE_MBEDTLS=OFF -DENABLE_NETTLE=OFF \
        -DENABLE_LIBXML2=OFF -DENABLE_EXPAT=OFF -DENABLE_ACL=OFF -DENABLE_ICONV=OFF -DENABLE_XATTR=OFF \
        -DENABLE_PCREPOSIX=OFF -DENABLE_PCRE2POSIX=OFF -DENABLE_CNG=OFF -DENABLE_LIBGCC=OFF \
        -DPOSIX_REGEX_LIB=NONE
    run_log "$OUT/libarchive-$abi-build.log" cmake --build "cmake-$abi" --target archive_static -j"$NPROC"
    echo "  ✓ libarchive.a"

    # ── 3. JNI 包装 → libarchive.so ──
    echo "── 链接 libarchive.so ($abi)"
    "$clang" -shared -fPIC -O2 -std=c++17 \
        "$WRAPPER" \
        "cmake-$abi/libarchive.a" "$PREFIX/lib/libz.a" \
        -I "cmake-$abi" -I "$SRC_LA/libarchive" -I "$PREFIX/include" \
        -Wl,--no-undefined -Wl,--build-id=sha1 -Wl,-z,relro,-z,now \
        -Wl,-z,max-page-size=16384 \
        -o "$PREFIX/libarchive.so"
    echo "  ✓ $PREFIX/libarchive.so ($(stat -c%s "$PREFIX/libarchive.so") bytes)"
}

build_abi arm64-v8a   "$TC/aarch64-linux-android21-clang"
build_abi armeabi-v7a "$TC/armv7a-linux-androideabi21-clang"

echo "════ 完成：两 ABI libarchive.so ════"
ls -la "$OUT"/*/libarchive.so
