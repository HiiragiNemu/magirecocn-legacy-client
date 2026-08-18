/*
 * Copyright (C) 2024-2026 MagirecoCN-Revival-Project
 *
 * compat_arm.c — 仅 armeabi-v7a (32 位, minSdk 21) 链接进 libaria2c_*.so。
 *
 * 背景（Android NDK 官方文档"使用 64 位文件偏移"与 bionic 头注释可查）：
 * 32 位 bionic 的 fseeko/ftello/fseeko64/ftello64 是 **API 24** 才加进 libc.so
 * 的；64 位 ABI 从一开始（API 21）就有。若某个依赖库的 configure 用「仅编译」
 * 或「错的 stub」探测到这些符号，编出来的 .so 在 Android 7.0 以下 32 位设备
 * 上会因 dlsym 缺口直接拒绝加载。
 *
 * 双保险设计：
 *  a) 编译依赖时加 -D_LIBCPP_HAS_NO_OFF_T_FUNCTIONS，让 libc++ 的 filebuf
 *     不引用 fseeko/ftello（这是主要来源）；
 *  b) 本文件提供本地实现兜底——version script 会把它们 local 化，绝不外泄，
 *     同时满足「若有对象引用它们则本地解析」。off_t 在 32 位 bionic 就是
 *     32 位 long，与 fseek/ftell 原型等价；>2GB 单文件定位精度在 32 位上
 *     本就无解（与系统行为一致）。
 *
 * 真正防回归的是 build-aria2.sh 构建后的「UND vs NDK API-21 stub 差集」
 * 硬校验：任何漏网的高 API 符号（含本文件兜不住的）都会让构建失败。
 */
#if defined(__ANDROID__) && defined(__arm__)

#include <stdio.h>

__attribute__((visibility("hidden"))) int fseeko(FILE* fp, off_t off,
                                                 int whence) {
  return fseek(fp, off, whence);
}

__attribute__((visibility("hidden"))) off_t ftello(FILE* fp) {
  return ftell(fp);
}

#endif /* __ANDROID__ && __arm__ */
