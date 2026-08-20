# 第三方组件声明（THIRD-PARTY NOTICES）

本文件登记**由本项目加入**、随产物一同分发、且**不属于原包版权方**的第三方文件。

`assets/`、`lib/`、`res/` 底下绝大多数东西来自基础 APK，归游戏版权方所有（见
`LICENSE.additional-terms` 第 3 条与 README「原始署名与免责声明」）。**下面这几个不是**
——它们是我们自己塞进去的自由软件与字体。它们各自的许可证要求随分发附上声明，
而第 3 条管不到它们：那条讲的是原包素材，与这些无关。本文件就是那些声明。

> **判据**：`assets/` 或 `lib/` 下的文件，只要**不来自基础 APK**，就必须在这里有一条。
> `tools/check-third-party-notices.py` 在 CI 里按 `baseline/baseline.json` 核对——
> 清单里 `kind=add` 或标为非原包的二进制，一个都不许缺条目。

---

## libaria2c（`lib/arm64-v8a/libaria2c_ossl.so`、`lib/armeabi-v7a/libaria2c_ossl.so`、`lib/arm64-v8a/libaria2c_gnutls.so`、`lib/armeabi-v7a/libaria2c_gnutls.so`）

| | |
|---|---|
| 软件 | aria2 —— The high speed download utility，v1.37.0，编译为**进程内共享库**（ET_DYN），**双 TLS 后端** |
| 版权 | Copyright (C) 2006, 2019 Tatsuhiro Tsujikawa |
| 许可 | **GNU General Public License v2 或（由你选择）任何更新版本**；openssl 组附 OpenSSL 链接例外；gnutls 组额外静态链入 LGPL 组件（见下） |
| 上游源码 | <https://github.com/aria2/aria2>，tag `release-1.37.0` |
| 怎么来的 | 2026-08-17 用 Android NDK r25c（clang 14.0.7）交叉编译。**两个 ABI 均 minSdk 21**：armv7 用 `-D_LIBCPP_HAS_NO_OFF_T_FUNCTIONS` + `compat_arm.c` 消除 libc++ 对 fseeko/ftello（32 位 bionic 为 API24）的引用，动态符号表对 API21 零缺口。openssl 组 TLS=OpenSSL 1.1.1w；gnutls 组 TLS=GnuTLS 3.8.3 + nettle 3.9.1 + GMP 6.3.0。共同静态依赖 libxml2 2.9.14 / zlib 1.3.1 / sqlite3 3.44.2 / libssh2 1.11.0。`-fvisibility=hidden` + `-Wl,-Bsymbolic` + version script 只导出 4 个 JNI 入口（`JNI_OnLoad` / `nativeStart` / `nativeIsRunning` / `nativeWaitStopped`，`@@CNARIA2LIB_1.0`），TLS 库符号**零泄漏**；`-Wl,-z,max-page-size=16384` 使 LOAD 段 `p_align=0x4000`，4KB/16KB 页设备通吃。NEEDED 仅 liblog/libdl/libm/libc。构建与验证见归档（下述） |
| sha256 | `libaria2c_ossl.so`（arm64-v8a）= `e4047d89be1bd289ae55e5a3fd65c5fe561d0e947b09d0dfa8aca49a9d50e189`<br>`libaria2c_ossl.so`（armeabi-v7a）= `6bc1c7ee37ddc9bc234711176f2a689db9e03d48678f9ede953a072247418d56`<br>`libaria2c_gnutls.so`（arm64-v8a）= `95457b62411b9d736f70463b296c0e64e28350c40c2fa294c1c3c6adcc7db96a`<br>`libaria2c_gnutls.so`（armeabi-v7a）= `ec4c3e404bcfa2bbb36c971609c9e4c27dd99a4a2224627d59fe80662996aa41` |

**用途**：进程内 aria2 下载引擎（备用，或构建期选择作为主引擎）。**两组一起打包**
（文件名/SONAME 各异，bionic 按 SONAME 去重，故必须是重链产物而非 cp 改名）。
默认加载 openssl 组；`Aria2EngineFailover` 的 dead-man's switch 在**原生崩溃**（进程死、
armed 标记留在盘上）或**加载期失败**时自动换到另一组，连续 4 次死亡后放弃、回退
自建引擎。控制面是 loopback JSON-RPC，aria2 以线程跑在调用进程内。经
`System.loadLibrary` 由 linker 加载（落点在只读 nativeLibraryDir），**无 exec**——
绕开 Android 10+ 的 SELinux W^X 闸与 16KB 页对齐限制（与 libarchive 同思路）。

> ⚠ **许可形态变化（2026-08-16）**。旧版（转发 Zackptg5 的预编译 aria2c 可执行
> 文件）以**独立子进程**运行，曾主张「单纯聚合、不传播 GPL」。现改**进程内 JNI
> 链接**——不再是聚合，而是链接进同一进程。合规依据是两层：本项目整体按 **GPLv3**
> 分发，aria2 是 **GPLv2-or-later**（「或任何更新版本」条款使其可与 GPLv3 结合）；
> openssl 组静态链入的 OpenSSL 由 aria2 源码头附带的**链接例外**覆盖（全文见下）；
> gnutls 组静态链入的 LGPL 组件（GnuTLS/nettle/GMP）在 GPLv3 工作里静态链接是
> 许可的（LGPL 与 GPLv3 兼容），其「提供可重链对象 / 对应源码」义务由下方
> 「🔴 对应源码」与书面要约一并覆盖。旧的两个 exec 二进制已删除。

### OpenSSL 链接例外

openssl 组（`libaria2c_ossl.so`）**静态链入** OpenSSL 1.1.1w。aria2 的源码文件头
带有作者给出的例外（见 `release-1.37.0` 的 `src/*.cc` 头部，原文）：

> In addition, as a special exception, the copyright holders give permission to
> link the code of portions of this program with the OpenSSL library under
> certain conditions as described in each individual source file, and distribute
> linked combinations including the two. You must obey the GNU General Public
> License in all respects for all of the code used other than OpenSSL.

静态链接 OpenSSL 因此合规。

### GnuTLS 组（LGPL 组件）

gnutls 组（`libaria2c_gnutls.so`）的 TLS 路径 100% 走 **GnuTLS 3.8.3**，静态链入
**GnuTLS 3.8.3（LGPL-2.1-or-later）+ nettle 3.9.1（LGPL-3.0-or-later）+ GMP 6.3.0
（LGPL-3.0-or-later 与 GPL-2.0-or-later 双许可）**（内嵌的 libcrypto 1.1.1w 仅供
libssh2 的 SFTP 原语，非 TLS 路径）。静态链接 LGPL 库进 GPLv3 工作是许可的；
LGPL 第 4(d) 条要求的「可重链对象/源码」与本项目对 aria2 的对应源码义务
（下方）合并履行——对应源码含全部静态链入组件与构建脚本，书面要约三年有效。

**本地补丁**（F-024）：`tools/aria2/patches/0001-console-android-log-sink.patch`
把 aria2 控制台输出对象换成 Android log sink（直进 logcat，不再重定向宿主进程
fd 1/2）。改动仅限 `src/console.cc`，由编译期 `-DANDROID_LOG_SINK` 宏激活；
构建脚本 `tools/build-aria2.sh` 幂等应用并自检 sink 已编入。对应源码义务里，
这份补丁随本仓库交付。

### 🔴 对应源码（GPL 第 3 条 / v3 第 6 条的义务）

**分发二进制就要让接收者拿得到对应源码。** 这里的「对应源码」包含 aria2 本体、
静态链进去的**全部**库（openssl 组：OpenSSL 1.1.1w / libxml2 2.9.14 / zlib 1.3.1 /
sqlite3 3.44.2 / libssh2 1.11.0；gnutls 组另含 **GnuTLS 3.8.3 / nettle 3.9.1 /
GMP 6.3.0**——这三个是 **LGPL**，其「接收者可替换并重链」的 relink 义务由
「完整源码 + 构建脚本可整体重建」一并履行），以及控制编译的脚本：

1. **aria2 1.37.0 源码**：<https://github.com/aria2/aria2/tree/release-1.37.0>
   （发行 tarball 见该仓库 Releases）；
2. **LGPL 组件源码**（gnutls 组静态链入，可替换/重链所必需）：
   - **GnuTLS 3.8.3**：<https://www.gnutls.org/>（LGPL-2.1-or-later）
   - **nettle 3.9.1**：<https://ftp.gnu.org/gnu/nettle/>（LGPL-3.0-or-later）
   - **GMP 6.3.0**：<https://ftp.gnu.org/gnu/gmp/>（LGPL-3.0-or-later 与 GPL-2.0-or-later 双许可）
2. **交叉编译脚本与验证记录**：由构建方（Kimi）归档为 `aria2c-so.zip`，内含
   README.md / SHA256SUMS.txt / VERIFICATION.md 与 **`build/` 全套**——
   `rebuild-armv7-api21.sh`（含 `-D_LIBCPP_HAS_NO_OFF_T_FUNCTIONS` 与 compat 的
   minSdk21 重编）、`compat_arm.c/h`、`aria2_jni.cpp`、smoke 脚本、各 ABI 完整
   UND 清单（构建参数见 VERIFICATION「复现信息」）。归档暂存于维护机
   /mnt/android/；书面要约下随对应源码一并提供。

**书面要约**：任何收到本项目产物的人，可通过 README 所列联系方式向
MagirecoCN-Revival-Project 索取上述对应源码的完整副本，我们按 GPL 要求提供，
不收取超过介质成本的费用。本要约自分发之日起三年内有效。

> 相比旧版（转发 Zackptg5 的二进制）的进步：现在是**我们自己的构建**，「对应源码」
> 就是我们自己的构建输入与存档（含 `build/` 全套脚本，可逐位复现），不再依赖
> 第三方转发、也不再背「逐位复现不了」的账。

---

## libarchive（`lib/arm64-v8a/libarchive.so`、`lib/armeabi-v7a/libarchive.so`）

| | |
|---|---|
| 软件 | libarchive 3.7.4 的 JNI 封装（进程内解压 zip） |
| 版权 | Copyright (c) 2003-2024 Tim Kientzle 及 libarchive 贡献者 |
| 许可 | **BSD 2-Clause License**（与 GPLv3 兼容，无 copyleft / 无静态链接义务） |
| 上游源码 | <https://github.com/libarchive/libarchive>，tag `v3.7.4` |
| 交叉编译 | 用 Android NDK 交叉编译（构建脚本 `tools/build-libarchive.sh`，双 ABI `arm64-v8a / armeabi-v7a`，minApi 21），libarchive 静态链入，zlib 静态链入。**R3-03 订正**：实测产物的 NDK note 为 **r27d**（CI runner 自带 NDK，未在 workflow 中钉版本），并非此前描述的 r25c；实际构建**未使用** `-static-libstdc++`（链接命令以 clang C 驱动直接链入 libarchive 静态库与 libz.a，`-Wl,--no-undefined` 兜底无未定义符号——这也意味着该脚本依赖「clang 的 C 驱动能接受 `-std=c++17` 编译 C++ 源码」这一行为，升级 NDK 时若改走 c++ 驱动需复核链接参数）。如需可复现构建，建议将 runner 的 NDK 版本一并钉入 workflow（见 `build-libarchive.yml` 的 NDK 定位步骤，已加版本打印与期望值 WARN） |
| sha256 | `libarchive.so`（arm64-v8a）= `10b479e8715114df758eb0a6722bf52c96d2d75ffe80bb7039378bea63ee5071`<br>`libarchive.so`（armeabi-v7a）= `6e7966634e70cd1cfb8d15bdc26b9e06931af2c77c78c8ffe74b6afeac297a42`<br>（与 `baseline.json` 的 `post` pin 同值；R3-01：此前沿用了旧 libcnzip.so 的哈希） |

**用途**：解压基础资源包（zip），替代 `java.util.zip.ZipFile`。资源包含「冗余
ZIP64」结构（普通 EOCD 自洽却多挂一个 zip64 EOCD），老设备 ZipFile 可能报
「结构非法」；libarchive 实测能正确处理。经 `System.loadLibrary` 由 linker 加载
（落点在只读的 nativeLibraryDir），**无 exec**——绕开 Android 10+ 的 SELinux
W^X 闸与 16KB 页对齐限制，这是从 exec bsdtar 改为 JNI 的原因。进程内解压，
不向任何其他部分传播许可证。

**zlib**：静态链入，zlib 许可证（zlib License，与 GPLv3 兼容）。zlib 的版权声明
随 libarchive 的构建产物一并保留。

---

## ShadowHook（`lib/arm64-v8a/libshadowhook.so`、`lib/armeabi-v7a/libshadowhook.so`）

| | |
|---|---|
| 软件 | ShadowHook（bytedance/android-inline-hook），v2.0.1（取自 `.so` 内嵌版本串） |
| 版权 | Copyright (c) 2021-2026 ByteDance Inc. |
| 许可 | **MIT License** |
| 上游 | <https://github.com/bytedance/android-inline-hook>，tag `v2.0.1` |
| 怎么来的 | **CI 从上游源码构建**（`magia-native/CMakeLists.txt` 的 `FetchContent`，`GIT_TAG v2.0.1`）。仓库里那两个 `.so` 是同版本的既有副本，每次构建都会被 CI 的产物覆盖 |

**用途**：`libMagiaLegacy.so` 的 inline hook 后端，被它按 `DT_NEEDED` 动态链接。

**我们改过它的源码**（MIT 不要求声明改动，但本项目附加条款第 1 条要求不得掩饰
来源，所以照实写）。构建时对 shadowhook 源码打三处 `sed`，见
`magia-native/CMakeLists.txt` 里逐条带理由的注释：

1. 去掉 `-Weverything -Werror`——Clang 18 新增的警告类在 v2.0.1 写就时还不存在，
   `-Werror` 会让它自己编不过；
2. 让 linker mod 初始化失败变成非致命；
3. 放宽 version script 的 `--no-undefined-version`（各架构专有符号列在同一份
   `.map.txt` 里，lld 18 会判成错误）。

因此随包分发的 `libshadowhook.so` 是**改动过的构建**，不是上游原版二进制。

MIT 要求版权声明与许可全文随副本一同提供：

```
MIT License

Copyright (c) 2021-2026 ByteDance Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## MagiReco CN Medium（`assets/fonts/mbm_20160902.ttf`）

| | |
|---|---|
| 字体 | MagiReco CN Medium，Version 1.001 |
| 版权 | Copyright © 2017 Adobe Systems Incorporated. All Rights Reserved. & MagiReco CN |
| 许可 | **Apache License, Version 2.0** —— <http://www.apache.org/licenses/LICENSE-2.0.html> |
| 上游 | Adobe Source Han 家族的派生品（字体 `name` 表自述，`makeotf` / FontForge 产出） |

许可与版权均取自字体文件自身的 `name` 表（nameID 0/13/14），不是转述。

**Apache-2.0 第 4(b) 条要求注明改动**：本文件**不是** Adobe 原始 Source Han 字形集，
而是国服（MagiReco CN）在其基础上改制的版本——重命名家族为 `MagiReco CN Medium`、
按国服需要调整了字形覆盖（格式 12 cmap、30823 码位）。本项目**未再对字体本身做任何
修改**，只在 native 层把引擎请求的字体路径重定向到它
（`magia-native/src/MagiaLegacy.cpp` 的 `fontPathFix`）。

> 文件名 `mbm_20160902.ttf` 是**引擎硬编码的路径**，与字体内容无关，改不得。

---

## 不在此列的（说明，免得下次又搞混）

- **`assets/magia/logo.png`** —— 下载浮层的 logo，**原样取自国服官方包**，一个字节
  没改，归原包版权方，见 `LICENSE.additional-terms` §3，**不归 GPLv3**。
  它不在本基线（日服 / Totentanz 一脉）里、重建不出来，所以只能整份存着——
  与 85 个汉化图集同处，不在工程树里，构建时按 sha256 取回。
- **`assets/magia/background_light.png`** —— 下载浮层的背景，**原样取自基线树里的
  `assets/resource/image_native/bg/web/web_common0.png`**，一个字节没改，同样归原包
  版权方。它**哪儿都不存**：`baseline.json` 里那条 op 是 `from: baseline` + `src`，
  构建时从基线树拷过来，post hash 照常核对。
- **`lib/*/libMagiaLegacy.so`** —— 本项目自制，源码在 `magia-native/`，归 GPLv3。
- **`assets/fonts/witchText-export.png`** —— 在原包位图字体基础上重绘的汉化图集，
  属于对原包素材的衍生，归原包版权方一侧，见 `LICENSE.additional-terms` §3。
  它和另外 84 个汉化图集一样**不在本仓库**：构建时按 sha256 取回（`baseline.json`
  的 `overlay` 段）。配套的 `.fnt` 是原包原样，由基线树提供。
- **`res/xml/network_security_config.xml`** —— 本项目自制配置，归 GPLv3。
