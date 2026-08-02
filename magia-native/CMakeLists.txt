# CMake build for libMagiaLegacy.so —— 归档版老客户端的 native hook 库。
#
# 目标：取代包内两个预编译的 Dobby hook 库
#   libuwasa.so   英文汉化组（Kamihama）的补丁库。它按**英文行宽**重排剧情
#                 文本（Label::setMaxLineWidth / setDimensions /
#                 LbUtility::initLabelCenterWidthOutline 等），在中文汉化下
#                 是净损害。只有两个 hook 值得留，已在本文件对应的源码里重写。
#   libcn_hook.so 资源下载流水线（无源码）。
#
# 实现照搬 （旧客户端仓库） 的 （旧 native 层）/（同一个 3.1.9 引擎上验证过），
# 去掉其中 cnv 专有的部分（setURI 代理后端、强制新手教程）。
#
# 设计为 Android NDK 单独驱动：
#
#   cmake -G Ninja \
#         -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 \
#         -B build/arm64-v8a magia-native
#   cmake --build build/arm64-v8a
#
# 输出：libMagiaLegacy.so，进一步由 CI 拷到 lib/<abi>/。
#
# 用 shadowhook (bytedance/android-inline-hook) 而不是 Dobby——
# Dobby 上游对 armv7 路径已停止维护、master 的 arm64 汇编也对 ELF/Android
# 工具链不友好；shadowhook 由字节跳动持续维护，arm64 + armv7 双支持。

cmake_minimum_required(VERSION 3.18)
project(MagiaLegacy C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)
FetchContent_Declare(
    shadowhook
    GIT_REPOSITORY https://github.com/bytedance/android-inline-hook.git
    GIT_TAG        v1.1.1
    GIT_SHALLOW    TRUE
    # shadowhook 的 git 仓库根是 Android Gradle 工程；真正的 CMake
    # build 在 shadowhook/src/main/cpp/。SOURCE_SUBDIR 让 FetchContent
    # 只 add_subdirectory 这个子目录，不要去碰仓库根（仓库根不是
    # CMake 工程）。
    SOURCE_SUBDIR  shadowhook/src/main/cpp
    # shadowhook v1.1.1 的 CMakeLists 有几个 NDK 27 / lld 18 下踩坑
    # 的硬编码，都需要从源头修：
    # (a) target_compile_options(... PUBLIC -std=c17 -Weverything -Werror)
    #     ——PUBLIC 意味着任何链 shadowhook 的 target 编译时也吃这些
    #     flag。我们 MagiaLegacy 是 C++，clang++ 看到 -std=c17 直接报
    #     "'-std=c17' not allowed with 'C++'"。
    # (b) target_(compile|link)_options(... PUBLIC -Oz ...) ——其中
    #     link_options 那行还带 `-Wl,--version-script=shadowhook.map.txt`。
    #     PUBLIC 让我们 MagiaLegacy 链接时也用这个 version script，但
    #     脚本里 global: 段列出的 shadowhook_init 等符号是 libshadowhook.so
    #     提供的、不在我们自己的 object 里。lld 18 strict mode 直接报
    #     "version script assignment of 'global' to symbol ... failed:
    #     symbol not defined"。
    # (c) -Werror + -Weverything —— Clang 18 新增了一堆 -Weverything
    #     拉出的新警告类（unsafe-buffer-usage / reserved-identifier /
    #     declaration-after-statement 等），v1.1.1 写就时还不存在。
    #     -Werror 让 shadowhook 自己也编不过；即使去掉 -Werror，
    #     -Weverything 仍会刷出 ~2000 条噪音 warning 污染 CI log。
    #     shadowhook 是字节维护的 vendored 依赖、对我们就是黑盒，
    #     没必要让它的 self-warning 进我们视野。
    # 修法：把所有 `PUBLIC -std=c17 / -Oz` 一律改 PRIVATE（shadowhook 自己
    # 编译/链接时仍吃这些 flag，不传染消费者），并从源头去掉 -Werror
    # 和 -Weverything。
    PATCH_COMMAND  sed -i
                   -e "s/PUBLIC -std=c17/PRIVATE -std=c17/"
                   -e "s/PUBLIC -Oz/PRIVATE -Oz/g"
                   -e "s/-Weverything//g"
                   -e "s/-Werror//g"
                   shadowhook/src/main/cpp/CMakeLists.txt
)
FetchContent_MakeAvailable(shadowhook)

# shadowhook 的 CMakeLists 顺便建了一个叫 shadowhook_nothing 的"空壳
# so"——用 -nostdlib -nostartfiles -nodefaultlibs 编出来作为占位符，
# 我们用不到。NDK 27 的链接器在装它时会卡（具体原因未知，可能跟
# 16K page size / lld 18 行为有关），把这个 target 从 ALL 里踢掉，
# ninja 只在我们 MagiaLegacy 显式依赖时才会去碰它（而我们没有）。
if(TARGET shadowhook_nothing)
    set_target_properties(shadowhook_nothing PROPERTIES EXCLUDE_FROM_ALL TRUE)
endif()

add_library(MagiaLegacy SHARED
    src/MagiaLegacy.cpp
)

target_include_directories(MagiaLegacy PRIVATE
    ${shadowhook_SOURCE_DIR}/shadowhook/src/main/cpp/include
)

target_link_libraries(MagiaLegacy PRIVATE
    shadowhook
    log
    android
)

# 编译时去掉冗余符号 + strip 非必要信息（CI 不另行 strip）。
target_compile_options(MagiaLegacy PRIVATE
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -Wno-unused-parameter
    # C-L6: 编译期加固 flags
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
    -Wformat
    -Wformat-security
)
target_link_options(MagiaLegacy PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    # C-L6: 链接期加固 flags（RELRO + BIND_NOW + NX）
    -Wl,-z,relro
    -Wl,-z,now
    -Wl,-z,noexecstack
)
