#!/bin/bash
# 重建 libaria2c_{ossl,gnutls}.so（进程内 aria2 下载引擎，双 ABI × 双 TLS 后端）。
# 配方来源：THIRD-PARTY-NOTICES.md「libaria2c」条目 + 入库 .so 二进制取证
# （NDK r25c / clang 14.0.7 时代手工构建，原脚本在维护机 /mnt/android/ 已佚失，
# 本脚本是对该配方的完整重建）。
#
# 用法:
#   NDK=<ndk路径> \
#   ARIA2_SRC=... OPENSSL_SRC=... GMP_SRC=... NETTLE_SRC=... GNUTLS_SRC=... \
#   XML2_SRC=... SQLITE_SRC=... SSH2_SRC=... ZLIB_SRC=... \
#   bash tools/build-aria2.sh
# 产物: out/<abi>/libaria2c_{ossl,gnutls}.so
#
# 纪律（每一条都是当年踩过的坑，别删）：
#  - minSdk 21、双 ABI；armv7 加 -D_LIBCPP_HAS_NO_OFF_T_FUNCTIONS 且链接
#    compat_arm.c（32 位 bionic 的 fseeko/ftello 是 API 24，见 compat_arm.c 头注）。
#  - 所有静态库 -fPIC（要进共享库）。
#  - -fvisibility=hidden + -Wl,-Bsymbolic + version script：只导出 4 个 JNI
#    入口 @@CNARIA2LIB_1.0，TLS 库符号零泄漏（旧版符号被抢占污染而崩）。
#  - -Wl,-z,max-page-size=16384：LOAD 段 p_align=0x4000，16KB 页设备可加载。
#  - NEEDED 仅 liblog/libdl/libm/libc；-static-libstdc++ 静态链 libc++。
#  - 构建后自检全绿才算成功（见 verify_so）：符号卫生 / NEEDED / 对齐 /
#    SONAME / marker / armv7 对 NDK API-21 stub 的 UND 差集 + 高危符号黑名单。
set -euo pipefail

NDK="${NDK:?请设置 NDK}"
for v in ARIA2_SRC OPENSSL_SRC GMP_SRC NETTLE_SRC GNUTLS_SRC XML2_SRC SQLITE_SRC SSH2_SRC ZLIB_SRC; do
    [ -d "${!v:-}" ] || { echo "✗ 请设置 $v 指向已解压源码目录"; exit 1; }
done
GLUE_DIR="$(cd "$(dirname "$0")/aria2" && pwd)"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
NPROC=$(nproc)
OUT="$(pwd)/out"
export PATH="$TC:$PATH"

# GitHub Actions 可折叠分组：::group::/::endgroup:: 在 Actions 日志生成小三角，
# 点一下收起一大段（configure/make 的刷屏输出）。本地跑（main 主机）不打，
# 避免日志里出现 ::group:: 噪声。
group_start() { if [ "${GITHUB_ACTIONS:-}" = "true" ]; then echo "::group::$1"; fi; }
group_end()   { if [ "${GITHUB_ACTIONS:-}" = "true" ]; then echo "::endgroup::"; fi; }

run_log() { # run_log <log> <cmd...> —— 输出实时 tee 到 stdout 并落盘。
    # libMagiaLegacy 构建（build-apk.yml）就是直接跑 cmake、输出实时可见；
    # 这里同样把 configure/make 的每一行 tee 出来，CI 用户能看到构建在动，
    # 不再是「卡死无反馈」。日志文件照旧落盘（refresh_repo / 排障用）。
    # 脚本 set -o pipefail，命令失败会进失败分支。
    local log="$1"; shift
    local label="$(basename "$log" .log)"
    group_start "$label"
    if ! "$@" 2>&1 | tee "$log"; then
        group_end
        echo "✗ $label 失败：$*"
        echo "── 日志尾部（$log）──"
        tail -60 "$log"
        exit 1
    fi
    group_end
}

# autotools 交叉编译封装：env 干净、-fPIC、装到 PREFIX。
xconf() { # xconf <log> <builddir> <srcdir> <host> <cc> <cxx> <extra-cflags> <prefix> [args...]
    local log="$1" builddir="$2" src="$3" host="$4" cc="$5" cxx="$6" xcflags="$7" prefix="$8"
    shift 8
    # 必须在 build 目录里跑 configure（out-of-tree）：否则 Makefile 落在 CWD，
    # 后续 make -C <builddir> 找不到。修复前 libxml2 因此 make 失败。
    # 2>&1 | tee "$log"：configure 的探测输出实时流到 CI 日志（feedback），
    # 同时落盘；脚本 set -o pipefail，configure 失败仍会进入失败分支。
    group_start "$(basename "$log" .log)"
    if ! ( cd "$builddir" && \
        env -i PATH="$PATH" HOME="${HOME:-/tmp}" \
        CC="$cc" CXX="$cxx" AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" \
        STRIP="$TC/llvm-strip" LD="$TC/ld.lld" \
        CFLAGS="-O2 -fPIC $xcflags" CXXFLAGS="-O2 -fPIC $xcflags" \
        CPPFLAGS="-I$prefix/include" LDFLAGS="-L$prefix/lib" \
        PKG_CONFIG_PATH="$prefix/lib/pkgconfig" \
        bash "$src/configure" --host="$host" --prefix="$prefix" "$@" \
        2>&1 | tee "$log" ); then
        group_end
        echo "✗ configure 失败：$src"
        echo "── 日志（$log）──"
        tail -60 "$log"
        exit 1
    fi
    group_end
}
xmake() { # xmake <log> <dir>
    run_log "$1" make -C "$2" -j"$NPROC"
}
apply_aria2_patch() { # 幂等应用 aria2 源码补丁（0001：控制台日志 → Android log sink）
    # F-024：进程内库不再 dup2 宿主 fd 1/2，由源码层把 Console 的输出对象换成
    # AndroidLogFile（直进 logcat）。补丁只动 src/console.cc，非 git 目录也照用
    # git apply（--check/--reverse --check 判定已应用与否，幂等）。
    local patch="$GLUE_DIR/patches/0001-console-android-log-sink.patch"
    if ( cd "$ARIA2_SRC" && git apply --check "$patch" 2>/dev/null ); then
        ( cd "$ARIA2_SRC" && git apply "$patch" )
        echo "✓ aria2 补丁已应用：$(basename "$patch")"
    elif ( cd "$ARIA2_SRC" && git apply --reverse --check "$patch" 2>/dev/null ); then
        echo "✓ aria2 补丁已应用（重复运行，跳过）"
    else
        echo "✗ aria2 补丁无法应用：$patch"
        echo "  源码必须是无改动 aria2-1.37.0；对不上就先还原源码再看"
        exit 1
    fi
}

# ─────────────────────────── 每 ABI 构建全部静态依赖 ───────────────────────────
build_deps() { # build_deps <abi> <host> <cc> <cxx> <openssl-target> <extra-cflags>
    local abi="$1" host="$2" cc="$3" cxx="$4" osstarget="$5" xcflags="$6"
    # 依赖包代码质量差、警告海量（一个 configure 就几千行），编译期关警告；
    # aria2 本体保留警告（build_group 仍用原 $xcflags）。
    local depxc="-w $xcflags"
    local PREFIX="$OUT/deps/$abi"
    mkdir -p "$PREFIX" "$OUT/logs"
    echo "════ 依赖构建 $abi ════"

    # zlib（CMake：autotools configure 对 NDK clang 的 -Werror 探测会 abort，
    # 与 build-libarchive.sh 同路径）
    echo "── zlib ($abi)"
    rm -rf "build-zlib-$abi"
    run_log "$OUT/logs/zlib-$abi-conf.log" cmake -S "$ZLIB_SRC" -B "build-zlib-$abi" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-21 \
        -DBUILD_SHARED_LIBS=OFF -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_C_FLAGS=-w
    run_log "$OUT/logs/zlib-$abi-build.log" cmake --build "build-zlib-$abi" --target zlibstatic -j"$NPROC"
    mkdir -p "$PREFIX/lib" "$PREFIX/include"
    cp "$ZLIB_SRC/zlib.h" "build-zlib-$abi/zconf.h" "$PREFIX/include/"
    cp "build-zlib-$abi/libz.a" "$PREFIX/lib/libz.a"

    # OpenSSL 1.1.1w（两组都要：ossl 组做 TLS；gnutls 组仅供 libssh2 的 SFTP
    # 原语——libssh2 不支持 gnutls 后端，NOTICES 有记）
    echo "── openssl ($abi)"
    rm -rf "build-openssl-$abi" && cp -r "$OPENSSL_SRC" "build-openssl-$abi"
    run_log "$OUT/logs/openssl-$abi-conf.log" \
        env -i PATH="$PATH" HOME="${HOME:-/tmp}" ANDROID_NDK_HOME="$NDK" \
        bash -c "cd build-openssl-$abi && ./Configure $osstarget no-shared no-tests \
            -D__ANDROID_API__=21 -fPIC -w --prefix='$PREFIX'"
    run_log "$OUT/logs/openssl-$abi-build.log" make -C "build-openssl-$abi" -j"$NPROC"
    run_log "$OUT/logs/openssl-$abi-inst.log" make -C "build-openssl-$abi" install_sw

    # libxml2（必须 --without-iconv：bionic 的 iconv* 是 API 28，API21 设备必炸）
    echo "── libxml2 ($abi)"
    rm -rf "build-xml2-$abi" && mkdir "build-xml2-$abi"
    xconf "$OUT/logs/xml2-$abi-conf.log" "build-xml2-$abi" "$XML2_SRC" "$host" "$cc" "$cxx" "$depxc" "$PREFIX" \
        --enable-static --disable-shared \
        --without-python --without-lzma --without-iconv --without-ftp --without-http \
        --with-zlib="$PREFIX"
    xmake "$OUT/logs/xml2-$abi-build.log" "build-xml2-$abi"
    run_log "$OUT/logs/xml2-$abi-inst.log" make -C "build-xml2-$abi" install

    # sqlite3（autoconf 包；fseeko 等由 link 测试把关，API21 stub 无则自动回退）
    echo "── sqlite3 ($abi)"
    rm -rf "build-sqlite-$abi" && mkdir "build-sqlite-$abi"
    xconf "$OUT/logs/sqlite-$abi-conf.log" "build-sqlite-$abi" "$SQLITE_SRC" "$host" "$cc" "$cxx" "$depxc" "$PREFIX" \
        --enable-static --disable-shared
    xmake "$OUT/logs/sqlite-$abi-build.log" "build-sqlite-$abi"
    run_log "$OUT/logs/sqlite-$abi-inst.log" make -C "build-sqlite-$abi" install

    # libssh2（crypto=openssl；两组同此）
    echo "── libssh2 ($abi)"
    rm -rf "build-ssh2-$abi" && mkdir "build-ssh2-$abi"
    xconf "$OUT/logs/ssh2-$abi-conf.log" "build-ssh2-$abi" "$SSH2_SRC" "$host" "$cc" "$cxx" "$depxc" "$PREFIX" \
        --enable-static --disable-shared \
        --with-crypto=openssl --with-libssl-prefix="$PREFIX" --with-libz
    xmake "$OUT/logs/ssh2-$abi-build.log" "build-ssh2-$abi"
    run_log "$OUT/logs/ssh2-$abi-inst.log" make -C "build-ssh2-$abi" install

    # ── gnutls 链（GMP → nettle → GnuTLS）──
    echo "── gmp ($abi)"
    rm -rf "build-gmp-$abi" && mkdir "build-gmp-$abi"
    # gmp 的 mpn 手写 ARM 汇编走绝对寻址（R_ARM_ABS32），非 PIC 版没法链接进
    # .so——armv7 下 libgmp.a 一进最终链接就炸（arm64 是 PC 相对寻址天然 PIC，
    # 所以只有 32 位暴露）。两条路都踩过：
    #   · 开 --enable-shared：PIC 对象只在 .libs/，make install 装的 libgmp.a
    #     仍是 non-PIC，白跑；
    #   · 纯静态 + CFLAGS 加 -DPIC：m4-ccas 把 -DPIC 转发给 m4，汇编走 PIC
    #     变体，安装归档也是 PIC（定向实验 4 个问题对象 R_ARM_ABS32 全为 0）。
    # 取后者，保持纯静态不引入 libgmp.so。
    xconf "$OUT/logs/gmp-$abi-conf.log" "build-gmp-$abi" "$GMP_SRC" "$host" "$cc" "$cxx" "$depxc -DPIC" "$PREFIX" \
        --enable-static --disable-shared
    xmake "$OUT/logs/gmp-$abi-build.log" "build-gmp-$abi"
    run_log "$OUT/logs/gmp-$abi-inst.log" make -C "build-gmp-$abi" install

    echo "── nettle ($abi)"
    rm -rf "build-nettle-$abi" && mkdir "build-nettle-$abi"
    # armv7 禁汇编：nettle 的 NEON 汇编按目标三元组启用，老 armv7 设备无 NEON
    # 会 SIGILL；TLS 加解密不是 CDN 下载瓶颈，换确定性。arm64 全系 NEON 保留。
    local nettle_asm=()
    if [ "$abi" = "armeabi-v7a" ]; then nettle_asm=(--disable-assembler); fi
    xconf "$OUT/logs/nettle-$abi-conf.log" "build-nettle-$abi" "$NETTLE_SRC" "$host" "$cc" "$cxx" "$depxc" "$PREFIX" \
        --enable-static --disable-shared --disable-documentation --disable-openssl \
        "${nettle_asm[@]}"
    xmake "$OUT/logs/nettle-$abi-build.log" "build-nettle-$abi"
    run_log "$OUT/logs/nettle-$abi-inst.log" make -C "build-nettle-$abi" install

    echo "── gnutls ($abi)"
    rm -rf "build-gnutls-$abi" && mkdir "build-gnutls-$abi"
    xconf "$OUT/logs/gnutls-$abi-conf.log" "build-gnutls-$abi" "$GNUTLS_SRC" "$host" "$cc" "$cxx" "$depxc" "$PREFIX" \
        --enable-static --disable-shared \
        --with-included-libtasn1 --with-included-unistring \
        --without-p11-kit --without-idn --without-zlib --without-brotli --without-zstd \
        --without-tpm --without-tpm2 \
        --disable-doc --disable-tests --disable-tools --disable-guile --disable-cxx
    xmake "$OUT/logs/gnutls-$abi-build.log" "build-gnutls-$abi"
    run_log "$OUT/logs/gnutls-$abi-inst.log" make -C "build-gnutls-$abi" install

    echo "  ✓ deps-$abi 完成"
}

# ─────────────────────────── aria2 + 最终链接（每组） ───────────────────────────
build_group() { # build_group <abi> <host> <cc> <cxx> <xcflags> <backend: ossl|gnutls>
    local abi="$1" host="$2" cc="$3" cxx="$4" xcflags="$5" be="$6"
    local PREFIX="$OUT/deps/$abi"
    local BUILD="build-aria2-$abi-$be"
    echo "── aria2 ($abi/$be)"

    rm -rf "$BUILD" && mkdir "$BUILD"
    local tls_args
    if [ "$be" = "ossl" ]; then
        tls_args="--with-openssl --without-gnutls --without-libnettle --without-libgmp"
    else
        tls_args="--with-gnutls --without-openssl --with-libnettle --with-libgmp"
    fi
    # pkg-config 套 --static：静态链时 configure 的探测链接需要 Libs.private
    # （openssl.pc 的 -ldl 等），否则误报找不到库。
    local pcwrap="$OUT/pkg-config-static"
    printf '#!/bin/sh\nexec pkg-config --static "$@"\n' > "$pcwrap" && chmod +x "$pcwrap"

    # 与 xconf 同款：aria2 的 configure 也必须在 $BUILD 里跑（out-of-tree），
    # 否则 Makefile 落在 CWD、make -C "$BUILD/src" 找不到（修复前 ossl 组如此失败）。
    # F-024：ANDROID_LOG_SINK 激活 console.cc 的 AndroidLogFile 分支（见
    # patches/0001-console-android-log-sink.patch），控制台直进 logcat。
    group_start "aria2-$abi-$be-conf"
    ( cd "$BUILD" && \
        env -i PATH="$PATH" HOME="${HOME:-/tmp}" \
        CC="$cc" CXX="$cxx" AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" \
        STRIP="$TC/llvm-strip" LD="$TC/ld.lld" \
        CFLAGS="-O2 -fPIC -DANDROID_LOG_SINK $xcflags" \
        CXXFLAGS="-O2 -fPIC -DANDROID_LOG_SINK $xcflags" \
        CPPFLAGS="-I$PREFIX/include" LDFLAGS="-L$PREFIX/lib" \
        PKG_CONFIG="$pcwrap" PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" \
        bash "$ARIA2_SRC/configure" --host="$host" --prefix="$PREFIX" \
            --enable-static --disable-shared \
            $tls_args \
            --without-libuv --without-libcares --without-libexpat \
            --with-libxml2 --with-sqlite3 --with-libz --with-libssh2 \
            --without-jemalloc --without-tcmalloc --without-appletls \
            --without-wintls --without-libgcrypt \
            2>&1 | tee "$OUT/logs/aria2-$abi-$be-conf.log" ) \
        || { group_end; echo "✗ aria2 configure 失败 ($abi/$be)"; tail -60 "$OUT/logs/aria2-$abi-$be-conf.log"; exit 1; }
    group_end
    # 顶层 make：aria2 自带的 deps/wslay 要先于 src 编（configure 检测 WebSocket:yes，
# 引用 deps/wslay/lib/libwslay.la）；只编 src 会因找不到 wslay 在 aria2c 链接处失败。
run_log "$OUT/logs/aria2-$abi-$be-build.log" make -C "$BUILD" -j"$NPROC"

    local LIBARIA2="$BUILD/src/.libs/libaria2.a"
    [ -f "$LIBARIA2" ] || { echo "✗ 找不到 $LIBARIA2"; exit 1; }

    # 最终链接：libaria2.a + JNI 胶水 → libaria2c_<be>.so
    local SONAME="libaria2c_${be}.so"
    local compat=()
    if [ "$abi" = "armeabi-v7a" ]; then compat=("$GLUE_DIR/compat_arm.c"); fi
    local deplibs
    if [ "$be" = "ossl" ]; then
        deplibs="libssl.a libcrypto.a libssh2.a libxml2.a libsqlite3.a libz.a"
    else
        deplibs="libgnutls.a libhogweed.a libnettle.a libgmp.a libssh2.a libssl.a libcrypto.a libxml2.a libsqlite3.a libz.a"
    fi
    local dl=()
    for l in $deplibs; do dl+=("$PREFIX/lib/$l"); done
    # WebSocket 是 aria2 自带 deps/wslay（configure 检测 WebSocket:yes），
    # libaria2.a 的 WebSocketSession 引它；最终链接必须带上，否则 undefined symbol。
    local wslay="$BUILD/deps/wslay/lib/.libs/libwslay.a"
    [ -f "$wslay" ] || { echo "✗ 缺 wslay 静态库（需先 make $BUILD 顶层）"; exit 1; }
    dl+=("$wslay")
    for l in "${dl[@]}"; do [ -f "$l" ] || { echo "✗ 缺静态库 $l"; exit 1; }; done

    echo "── 链接 $SONAME ($abi)"
    mkdir -p "$OUT/$abi"
    run_log "$OUT/logs/link-$abi-$be.log" "$cxx" -shared -O2 -fPIC -std=c++17 \
        -DHAVE_CONFIG_H \
        -I"$BUILD" -I"$ARIA2_SRC/src" -I"$ARIA2_SRC/src/includes" \
        -I"$PREFIX/include" \
        $xcflags \
        -fvisibility=hidden \
        "$GLUE_DIR/aria2_jni.cpp" "${compat[@]}" \
        "$LIBARIA2" \
        -Wl,--start-group "${dl[@]}" -Wl,--end-group \
        -Wl,--version-script="$GLUE_DIR/libaria2c.map" -Wl,-Bsymbolic \
        -Wl,--no-undefined -Wl,-z,relro,-z,now \
        -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 \
        -static-libstdc++ \
        -Wl,-soname,"$SONAME" \
        -llog -ldl -lm \
        -o "$OUT/$abi/$SONAME"
    # strip 掉 .symtab 等（基线取证：未 strip 的 ossl 15.4MB→8.8MB，与基线
    # 8.3MB 持平）。--strip-unneeded 只删局部符号，动态符号/标记/NEEDED 全保留，
    # verify_so 在 strip 后跑，验的正是最终产物。
    "$TC/llvm-strip" --strip-unneeded "$OUT/$abi/$SONAME"
    echo "  ✓ $OUT/$abi/$SONAME ($(stat -c%s "$OUT/$abi/$SONAME") bytes)"
}

# ─────────────────────────── 构建后自检（全绿才算产物） ───────────────────────────
verify_so() { # verify_so <so路径> <abi>
    local so="$1" abi="$2"
    local base; base="$(basename "$so")"
    echo "── 自检 $base ($abi)"
    local RE="$TC/llvm-readelf" NM="$TC/llvm-nm"

    # 1) NEEDED ⊆ {liblog,libdl,libm,libc}（取证基线）
    local needed
    needed="$("$RE" -d "$so" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p' | sort)"
    local bad_needed
    bad_needed="$(echo "$needed" | grep -vx -e liblog.so -e libdl.so -e libm.so -e libc.so || true)"
    [ -z "$bad_needed" ] || { echo "✗ $base NEEDED 越界: $bad_needed"; exit 1; }

    # 2) SONAME 必须等于文件名（两组共存靠 SONAME 去重，cp 改名是事故源）
    local soname
    soname="$("$RE" -d "$so" | sed -n 's/.*SONAME.*\[\(.*\)\]/\1/p')"
    [ "$soname" = "$base" ] || { echo "✗ $base SONAME=$soname ≠ $base"; exit 1; }

    # 3) 导出符号恰 4 个 JNI 入口（符号卫生硬指标，旧版崩溃的根因）。
    #    version script（libaria2c.map）给这 4 个符号打 @@CNARIA2LIB_1.0 版本
    #    节点，nm 输出带后缀（基线取证同款）；对拍剥掉后缀，只验符号身份——
    #    恰这 4 个、没有多的。
    local exports
    exports="$("$NM" -D --defined-only "$so" | awk '{print $NF}' | grep -v '^$' | sed 's/@@.*//' | sort)"
    local expect
    expect="$(printf '%s\n' \
        Java_io_kamihama_magianative_CNAria2Lib_nativeIsRunning \
        Java_io_kamihama_magianative_CNAria2Lib_nativeStart \
        Java_io_kamihama_magianative_CNAria2Lib_nativeWaitStopped \
        JNI_OnLoad | sort)"
    [ "$exports" = "$expect" ] || { echo "✗ $base 导出符号异常:"; diff <(echo "$expect") <(echo "$exports") || true; exit 1; }

    # 4) LOAD 段全 0x4000（16KB 页对齐）
    if "$RE" -lW "$so" | grep 'LOAD' | grep -vq '0x4000$'; then
        echo "✗ $base 存在非 16K 对齐的 LOAD 段:"; "$RE" -lW "$so" | grep LOAD; exit 1
    fi

    # 5) 构建标记可探测（strings 级核验设备上的库出自本链）。
    #    注意别用 grep -q：脚本开头的 set -o pipefail 下，grep -q 一命中就关
    #    管道退出，strings 收 SIGPIPE(141)，pipefail 把整条管道判失败——
    #    第 5 轮构建就因此误报「缺 kBuildMarker」。用 grep >/dev/null 全量消费。
    strings -a "$so" | grep 'magirecocn-libaria2c: build-aria2.sh' >/dev/null \
        || { echo "✗ $base 缺 kBuildMarker"; exit 1; }

    # 6) armv7：UND 符号对 NDK **API-21 stub** 差集必须为空 + 高危符号黑名单。
    #    用户教训：别信编译参数，NDK 可能分错版本——这里直接拿 r25c 的 API-21
    #    stub 导出符号当真值核对；黑名单兜 stub 本身分错的历史案（均出自
    #    NDK 官方文档/bionic 头 __INTRODUCED_IN 标注）：
    #      fseeko/ftello/fseeko64/ftello64 — 32 位 API 24（NDK「32 位 ABI 的
    #        64 位文件偏移」文档）
    #      getrandom/getentropy            — API 28（bionic unistd.h 标注）
    #      aligned_alloc                   — API 28（bionic malloc.h 标注）
    #      glob/globfree                   — API 28（bionic glob.h 标注）
    #      iconv/iconv_open/iconv_close    — API 28（bionic iconv.h 标注）
    if [ "$abi" = "armeabi-v7a" ]; then
        local stubdir="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/arm-linux-androideabi/21"
        # 逐文件读 stub（cat 拼接多 ELF 喂给 nm - 只解析到第一个 libc，libm/
        # libdl/liblog 全丢——第 9 轮因此把 sin/cos/dlopen 整批误报成缺口）；
        # 版本后缀必须剥：stub 定义是 `sym@@LIBC`、so 强 UND 是 `sym@LIBC`，
        # 不剥永远不等。
        local avail
        avail="$(for f in libc libm libdl liblog; do
                    "$NM" -D --defined-only "$stubdir/$f.so" 2>/dev/null
                  done | awk '{print $NF}' | sed 's/@.*//' | sort -u)"
        # 强 UND 才参与校验；弱 UND（nm 打头 w）按定义可空指针回退——
        # 取证：入库基线 armv7 就带 `w getentropy`（gnutls/nettle 的可选
        # 加速路径，API21 上解析为 NULL 后走 /dev/urandom），属合法。
        local undef
        undef="$("$NM" -D -u "$so" | awk '$1=="U"||$1==""{print $NF}' | sed 's/@.*//' | grep -v '^$' | sort -u)"
        local weak
        weak="$("$NM" -D -u "$so" | awk '$1=="w"{print $NF}' | sed 's/@.*//' | sort -u | tr '\n' ' ')"
        [ -z "$weak" ] || echo "  · 弱 UND（合法可空回退）: $weak"
        local gap
        gap="$(comm -13 <(echo "$avail") <(echo "$undef"))"
        [ -z "$gap" ] || { echo "✗ $base 对 API-21 stub 存在 UND 缺口:"; echo "$gap"; exit 1; }
        local hit
        hit="$(echo "$undef" | grep -x -e fseeko -e ftello -e fseeko64 -e ftello64 \
               -e getrandom -e getentropy -e aligned_alloc -e glob -e globfree \
               -e iconv -e iconv_open -e iconv_close || true)"
        [ -z "$hit" ] || { echo "✗ $base 强引用高 API 符号（黑名单命中）:"; echo "$hit"; exit 1; }
    fi

    # 7) Android log sink 必须编译进来（F-024）。sink 的 emit() 走
    #    __android_log_write；基线（fd 转发时代）UND 只有 __android_log_print。
    #    缺它 = console.cc 没编进 ANDROID_LOG_SINK 分支，修复白做。
    local sink_ok
    sink_ok="$("$NM" -D -u "$so" | awk '{print $NF}' | sed 's/@.*//' | grep -x __android_log_write || true)"
    [ -n "$sink_ok" ] || { echo "✗ $base 缺 Android log sink（UND 无 __android_log_write）"; exit 1; }

    echo "  ✓ $base 自检全绿"
}

# ─────────────────────────── 主流程 ───────────────────────────
build_abi() { # build_abi <abi> <host> <openssl-target>
    local abi="$1" host="$2" osstarget="$3"
    local cc="$TC/${host}21-clang" cxx="$TC/${host}21-clang++"
    local xcflags=""
    if [ "$abi" = "armeabi-v7a" ]; then
        # armv7 wrapper 三元组不同（armv7a-），且要 libc++ off_t 关闭宏
        cc="$TC/armv7a-linux-androideabi21-clang"
        cxx="$TC/armv7a-linux-androideabi21-clang++"
        xcflags="-D_LIBCPP_HAS_NO_OFF_T_FUNCTIONS"
    fi
    build_deps "$abi" "$host" "$cc" "$cxx" "$osstarget" "$xcflags"
    build_group "$abi" "$host" "$cc" "$cxx" "$xcflags" ossl
    build_group "$abi" "$host" "$cc" "$cxx" "$xcflags" gnutls
    verify_so "$OUT/$abi/libaria2c_ossl.so" "$abi"
    verify_so "$OUT/$abi/libaria2c_gnutls.so" "$abi"
}

# 先补 aria2 源码（控制台日志 sink，F-024）再进构建；幂等，重复跑不重复打。
apply_aria2_patch

build_abi arm64-v8a   aarch64-linux-android   android-arm64
build_abi armeabi-v7a arm-linux-androideabi   android-arm

echo "════ 产物 sha256（入库前用于更新 baseline/NOTICES pin）════"
sha256sum "$OUT"/*/libaria2c_*.so
