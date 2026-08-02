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

# shadowhook 的 version script（shadowhook.map.txt）把各架构的符号列在一起，
# 例如 armv7 专有的 shadowhook_interceptor_glue_vfpv3d16/d32 在 arm64 构建里
# 根本不存在。NDK 27 默认带 -Wl,--no-undefined-version，lld 18 会把这种情况
# 直接判成错误：
#     ld.lld: error: version script assignment of 'global' to symbol
#             'shadowhook_interceptor_glue_vfpv3d16' failed: symbol not defined
# 这里放宽回旧行为。写在 FetchContent 之前，让 shadowhook 子目录也继承；
# 命令行上出现在 NDK 那个 flag 之后，后者被覆盖。
add_link_options(-Wl,--undefined-version)

include(FetchContent)
FetchContent_Declare(
    shadowhook
    GIT_REPOSITORY https://github.com/bytedance/android-inline-hook.git
    # v1.1.1（（旧 native 层） 用的版本）在新 Android 上起不来：真机日志里是
    #     shadowhook init(mode: UNIQUE, debuggable: false), return: 12
    #     [Hook] ✗ ... errno=12 Init linker mod failed
    # 十七个 hook 全军覆没。errno 12 = 初始化 linker 模块失败——v1.1.1 是
    # 2023 年的版本，其 linker 适配跟不上新系统。v2.0.1 修了这一块。
    # API 与我们用到的四个函数（init / hook_sym_name / get_errno / to_errmsg）
    # 完全兼容，SHADOWHOOK_MODE_UNIQUE 仍为 1。
    GIT_TAG        v2.0.1
    GIT_SHALLOW    TRUE
    # 真正的 CMake 工程在 shadowhook/src/main/cpp/，仓库根是 Gradle 工程。
    SOURCE_SUBDIR  shadowhook/src/main/cpp
    # v2.0.1 已经把编译选项都改成 PRIVATE 了（v1.1.1 那一堆 PUBLIC 传染问题
    # 不复存在），只剩 -Weverything -Werror 需要去掉：Clang 18 新增的警告类
    # 在 v2.0.1 写就时还不存在，-Werror 会让它自己编不过，-Weverything 还会
    # 刷出大量噪音污染 CI 日志。shadowhook 对我们是黑盒，没必要让它的
    # self-warning 进我们视野。
    # 三处改动，用同一条 sed 打（各表达式只对目标文件命中一次）：
    #
    # (1) 去掉 -Weverything -Werror：Clang 18 新增的警告类在 v2.0.1 写就时还
    #     不存在，-Werror 会让 shadowhook 自己编不过。
    #
    # (2) **让 linker mod 初始化失败变成非致命。**
    #     shadowhook_init 里唯一的硬性门槛是
    #         if (0 != sh_linker_init()) GOTO_END(SHADOWHOOK_ERRNO_INIT_LINKER);
    #     sh_linker_init 要从 linker64 解析 __dl__ZN6soinfo17call_constructorsEv
    #     与 __dl_g_dl_mutex，在 Android 16 上解析不到，于是整个 shadowhook 停摆
    #     （真机 errno=12「Init linker mod failed」，17 个 hook 全挂）。
    #
    #     但那套 linker mod 只服务两件事：监听**后续**加载的 .so（延迟 hook）、
    #     以及与 dlopen 串行化。我们是在 JNI_OnLoad 里对**已经加载好**的
    #     libmadomagi_native.so 同步下钩，两件都用不上：
    #       - 立即 hook 走 sh_linker_get_addr_info_by_name（xdl 解析，与 ctors
    #         hook 无关）；
    #       - sh_linker_register_dl*_callback 只出现在延迟（PENDING）路径上；
    #       - sh_linker.c 的静态全局都有安全初值（SIZE_MAX / false /
    #         TAILQ_HEAD_INITIALIZER），未 init 也不会解引用野指针。
    #     bytesig / sh_safe / sh_island / sh_enter / sh_switch 这些真正的 hook
    #     机器在 sh_linker_init 之前就已初始化成功（见真机日志的失败位置）。
    #
    #     代价：延迟 hook 不可用。若某次 hook 返回 SHADOWHOOK_ERRNO_PENDING
    #     （=目标库当时没加载），它将永远不会被补上——我们的代码会把它当失败
    #     报出来，见 MagiaLegacy.cpp 里的 H()。
    #
    # (3) **把 cmake_minimum_required 从 4.0.2 降回 3.22。**
    #     v2.0.1 把最低版本提到了 4.0.2，但 GitHub runner 自带 CMake 只有
    #     3.31.6，configure 阶段直接报「CMake 4.0.2 or higher is required」。
    #     已逐行核对它的 CMakeLists：只用了 project / file(GLOB) / add_library
    #     / target_* / find_program / add_custom_command 这些 3.x 时代就有的
    #     命令，4.0.2 纯属写的人照抄本机版本，并非真实特性需求。
    #     若上游哪天真的用了 4.x 特性，这里会在 configure 期以别的方式报错，
    #     届时再升级 CI 的 CMake 不迟。
    PATCH_COMMAND  sed -i
                   -e "s/-Weverything//g"
                   -e "s/-Werror//g"
                   -e "s/GOTO_END(SHADOWHOOK_ERRNO_INIT_LINKER)/((void)0)/"
                   -e "s/cmake_minimum_required(VERSION 4\\.0\\.2)/cmake_minimum_required(VERSION 3.22)/"
                   shadowhook/src/main/cpp/CMakeLists.txt
                   shadowhook/src/main/cpp/shadowhook.c
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
