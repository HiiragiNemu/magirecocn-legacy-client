#!/bin/bash
# 把 aria2_jni.cpp 编进 libaria2.so（含 JNI 导出），产出两个 ABI。
#
# 用途：libaria2 备用下载引擎（CNAria2）的预置二进制。**不在 CI 构建**——
# aria2 的静态库需要整套交叉编译环境（zlib/OpenSSL/c-ares/libxml2 + aria2
# 本体，见下方「前置」），只有维护机器（hk，154.37.218.131）上有。产物提交在
# lib/<abi>/libaria2.so，apktool 打包时随 lib/ 进 APK。
#
# 前置（hk 机器，路径按实际调整）：
#   NDK=/root/android-ndk-r25c
#   交叉编译好的依赖静态库：/root/aria2-android（arm64）、
#   /root/aria2-android-v7（v7），含 libssl/libcrypto/libz/libcares/libxml2 的 .a
#   aria2 源码：/root/aria2-1.37.0（本脚本只重编它自带的 libaria2.a，
#   src/.libs/libaria2.a 每 ABI 覆盖一次）
#   JNI 桥接：/root/aria2_jni.cpp（先 scp 过去）
#
# 用法：bash tools/build-aria2.sh arm64|v7|all
# 产出：/root/aria2-android/libaria2.so（arm64）、
#       /root/aria2-android-v7/libaria2.so（v7）——拉回 lib/<abi>/libaria2.so。
#
# 注意：v7 版因 bionic 的 ftello 在 32 位下 API 24 才引入，用 API=24 编译
# （arm64 版天然 64 位 off_t，保持 API=21）。编译勿加 -fvisibility=hidden，
# JNI 符号要默认导出。
set -euo pipefail
export NDK=/root/android-ndk-r25c
export ANDROID_NDK_ROOT=$NDK
export TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64
export PATH=$TOOLCHAIN/bin:$PATH
ARIA2SRC=/root/aria2-1.37.0
INC=$ARIA2SRC/src/includes
BRIDGE=/root/aria2_jni.cpp

build_one() {
  local ABI=$1 TARGET_HOST=$2 CXXBIN=$3 API=$4 PREFIX=$5 EXTRA_FLAGS=$6
  echo "===== ABI=$ABI (API=$API) ====="
  export API=$API
  export TARGET=$TARGET_HOST
  export SYSROOT=$TOOLCHAIN/sysroot
  export PREFIX=$PREFIX
  export CC=$TOOLCHAIN/bin/$CXXBIN$API-clang
  export CXX=$TOOLCHAIN/bin/$CXXBIN$API-clang++
  export AR=$TOOLCHAIN/bin/llvm-ar
  export RANLIB=$TOOLCHAIN/bin/llvm-ranlib
  export CFLAGS="-O2 -fPIC -DNDEBUG $EXTRA_FLAGS"
  export CXXFLAGS="-O2 -fPIC -DNDEBUG $EXTRA_FLAGS"
  export PKG_CONFIG_PATH=$PREFIX/lib/pkgconfig
  export PKG_CONFIG_LIBDIR=$PREFIX/lib/pkgconfig

  # 1) 重编 aria2 静态库（PIC）——src/.libs/libaria2.a 每 ABI 覆盖一次
  cd $ARIA2SRC
  make distclean >/dev/null 2>&1 || true
  ./configure \
    --host=$TARGET_HOST --build=x86_64-pc-linux-gnu --prefix=$PREFIX \
    --enable-libaria2 --disable-libaria2-doc \
    --disable-shared --enable-static \
    --without-gnutls --without-nettle --without-gcrypt --without-libssh2 \
    --with-openssl --with-libxml2 --with-libcares --with-libz \
    --without-sqlite3 --without-libexpat --without-libgmp \
    --disable-bittorrent --disable-metalink \
    >/tmp/aria2_cfg_$ABI.log 2>&1
  make -j4 >/tmp/aria2_build_$ABI.log 2>&1
  ls -la $ARIA2SRC/src/.libs/libaria2.a >/dev/null

  # 2) 编译桥接 + 链接 libaria2.so
  $CXX -O2 -fPIC -shared -std=c++17 -I$INC -o $PREFIX/libaria2.so $BRIDGE \
    -Wl,--whole-archive $ARIA2SRC/src/.libs/libaria2.a -Wl,--no-whole-archive \
    $PREFIX/lib/libssl.a $PREFIX/lib/libcrypto.a $PREFIX/lib/libz.a \
    $PREFIX/lib/libcares.a $PREFIX/lib/libxml2.a \
    -lm -lc -ldl -llog -static-libgcc -static-libstdc++ \
    >/tmp/aria2_link_$ABI.log 2>&1
  $TOOLCHAIN/bin/llvm-strip $PREFIX/libaria2.so
  echo "OK $ABI: $PREFIX/libaria2.so"
  ls -la $PREFIX/libaria2.so
}

MODE="${1:-all}"
if [ "$MODE" = arm64 ] || [ "$MODE" = all ]; then
  build_one arm64 aarch64-linux-android aarch64-linux-android 21 /root/aria2-android ""
fi
if [ "$MODE" = v7 ] || [ "$MODE" = all ]; then
  build_one v7 arm-linux-androideabi armv7a-linux-androideabi 24 /root/aria2-android-v7 "-D_LARGEFILE_SOURCE -D_FILE_OFFSET_BITS=64"
fi
echo "ALL_JNI_DONE"
