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

## aria2c（`assets/aria2/aria2c-arm`、`assets/aria2/aria2c-arm64`）

| | |
|---|---|
| 软件 | aria2 —— The high speed download utility，v1.37.0 |
| 版权 | Copyright (C) 2006, 2019 Tatsuhiro Tsujikawa |
| 许可 | **GNU General Public License v2 或（由你选择）任何更新版本**，附 OpenSSL 链接例外 |
| 上游源码 | <https://github.com/aria2/aria2>，tag `release-1.37.0` |
| 二进制来源 | <https://github.com/Zackptg5/Cross-Compiled-Binaries-Android>，路径 `aria2/aria2c.bin-arm` 与 `aria2/aria2c.bin-arm64`（**我们改了名**：去掉 `.bin`。上游那边不带 `.bin` 的是个 shell 包装脚本，不是二进制——这是当初只写「来源 Cross-Compiled-Binaries-Android」就再也找不回来的原因） |
| 取用时该仓库 `master` | `9c14dc3ac040c7b085606e8353fd7cc136119ba9`（2026-08-14 记） |
| sha256 | `aria2c-arm` = `b06494c59df4c3536ad68dfc1ce5b33d3e638cd1e845ae5452709b35ed1270bb`<br>`aria2c-arm64` = `6705bac56e0752b26b22d4aa98cf5caa0f4672904e6cbf0ac2f516cc5f05797d` |

**用途**：备用下载引擎（默认关闭），以**独立子进程**运行，通过 JSON-RPC 控制。
它与游戏本体既不链接、也不同进程——是「单纯聚合」里最干净的形态，不向任何其他部分
传播 GPL。

### OpenSSL 链接例外

这两个是**全静态**二进制，OpenSSL 已编入。aria2 的源码文件头带有作者给出的例外
（见 `release-1.37.0` 的 `src/*.cc` 头部，原文）：

> In addition, as a special exception, the copyright holders give permission to
> link the code of portions of this program with the OpenSSL library under
> certain conditions as described in each individual source file, and distribute
> linked combinations including the two. You must obey the GNU General Public
> License in all respects for all of the code used other than OpenSSL.

静态链接 OpenSSL 因此合规。

### 🔴 对应源码（GPL 第 3 条 / v3 第 6 条的义务）

**分发二进制就要让接收者拿得到对应源码。** 这里的「对应源码」包含 aria2 本体、
静态链进去的各个库，以及控制编译的脚本：

1. **aria2 1.37.0 源码**：<https://github.com/aria2/aria2/tree/release-1.37.0>
   （发行 tarball 见该仓库 Releases）；
2. **交叉编译脚本**：上述 Zackptg5 仓库的 `build_script/` 目录（同一 commit）。

**书面要约**：任何收到本项目产物的人，可通过 README 所列联系方式向
MagirecoCN-Revival-Project 索取上述对应源码的完整副本，我们按 GPL 要求提供，
不收取超过介质成本的费用。本要约自分发之日起三年内有效。

> ⚠ **诚实说明两点。**
>
> 一、Zackptg5 的那个仓库**自身没有 LICENSE 文件**（GitHub 也识别不出许可），
> 也就是说它转发 GPL 二进制时并未附上完整声明。所以我们**不能靠「转达上游的要约」
> 来履行义务**，只能自己指向 aria2 官方源码与那份构建脚本，并自己给出上面的书面要约。
>
> 二、我们**没有逐位复现过**这两个二进制与 aria2 1.37.0 官方源码的对应关系
> ——只核验了 sha256 与上游文件一致、版本串为 `1.37.0`、内嵌版权与 GPL 声明
> 属于 aria2。更彻底的做法是**自己在 CI 里从源码构建 aria2**，那样「对应源码」
> 就是我们自己的构建输入，不再依赖第三方转发。目前没做，记在这里。

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
