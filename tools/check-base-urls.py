#!/usr/bin/env python3
"""守住「补丁源码里不出现真实业务地址」，以及规范前缀的那几条老不变量。

## 这个脚本换过一次职责，先说清楚为什么

**旧版**核对的是「CNMirrors.CANONICAL_BASE 这个写死的串是不是还等于钉死值、
CNHotUpdateCheck.PACKAGES 里那几条硬编码 URL 是不是都以它开头」。它拦的是一类
**静默**故障：

    CNHotUpdate.mainLineFileName    前缀对不上 → 返回 null＝「非主线，直连下载」
                                    → 热更包悄悄退化成不换线
    CNHotUpdateCheck.fetchMeta      前缀对不上 → 剥不出文件名，name 保留整条 URL
                                    → 拼出 https://<镜像>/https://…/version_js.json
                                    → 每条线路都失败，热更**静默停摆**

坏掉的时候没有任何报错：查询在每条线路上失败，被 fetchMetaSafe 吞掉，玩家看到的
是「已是最新」。等有人发现台词包几个月没更新，已经隔了很久，而且没人会想到是改
兜底线路引起的。

**现在**这两处的地址不再各写一份了：`PACKAGES` 与 `CNCNDownloadUI.FILE_URLS` 都是
拿文件名去拼 `CANONICAL_BASE`，「前缀对不上」在结构上不可能发生。所以本脚本转去守
**那个结构本身**——只要没人把绝对地址写回去，上面那类故障就回不来。

顺带守住第二件事：主机名只有 `CNEndpoints` 一个来源（取值构建期注入，见
`tools/inject-endpoints.py`）。这两件事必须由同一个守卫看着，因为破坏它们的是
同一个动作：图省事把一条完整 URL 直接写进源码。

## 规范前缀为什么不能改（这条没变）

`https://assets.<主域>/` 已经写进每一台已安装设备的 15 个完成标记（marker 的
`url=` 字段），`isMarkerValid` 做逐字符串比对。一改 → `allMarkersValid()` 全部
返回 false → 安装器判定「没装过」→ **每个老玩家重下几个 GB**。

钉死值改成了 **sha256**：由 `tools/inject-endpoints.py` 核对注入结果。钉哈希
而不是钉明文，才能同时满足「不许改」和「取值由部署参数给」。

用法：python3 tools/check-base-urls.py
"""

import os
import re
import sys

PATCH_DIR = "patch/src/main/java"
ENDPOINTS = "patch/src/main/java/io/kamihama/magianative/CNEndpoints.java"
MIRRORS   = "patch/src/main/java/io/kamihama/magianative/CNMirrors.java"
HOTCHECK  = "patch/src/main/java/io/kamihama/magianative/CNHotUpdateCheck.java"
INSTALLER = "patch/src/main/java/io/kamihama/magianative/CNDownloaderFix.java"
DOWNUI    = "patch/src/main/java/io/kamihama/magianative/CNCNDownloadUI.java"

# 常量必须**委托**给这些表达式，而不是自带一个字面量。
DELEGATES = [
    (MIRRORS,   "MIRRORS_URL",       "CNEndpoints.MIRRORS_URL"),
    (MIRRORS,   "DEFAULT_BASE",      "CNEndpoints.EDGEONE_BASE"),
    (MIRRORS,   "CANONICAL_BASE",    "CNEndpoints.ASSETS_BASE"),
    (INSTALLER, "RESOURCE_BASE_URL", "CNEndpoints.ASSETS_BASE"),
]

# 允许留在补丁源码里的绝对地址。判据是「它会不会跟着部署走」：
#
#   · 第三方公共站点（B 站、爱发电）—— 不是我们部署的，换域名换架构都不会动它，
#     没有理由跟着我们的部署参数一起变；
#   · 游戏后端 —— 由原包决定，不是我们能配的；
#   · 组织主页 —— 仓库本身的 owner，与部署无关。
#
# 除此之外的绝对地址一律拦下：会随部署变的东西都该走 CNEndpoints。
ALLOWED_ABS = (
    "https://b23.tv/",
    "https://www.bilibili.com/",
    "https://afdian.com/",
    "https://ifdian.net/",
    "https://github.com/MagirecoCN-Revival-Project",
    "https://totentanz-",          # 游戏后端，上游原包里就有
    "http://127.0.0.1:",           # 本机回环（调试桥）
)

# 结构片段：CNEndpoints 就是靠它们拼出真实地址的，那个文件整体豁免。
EXEMPT_FILES = (ENDPOINTS,)

ABS_URL = re.compile(r'"(https?://[^"\s]*)"')


def const_rhs(text, name):
    """取 `... String NAME = <RHS>;` 的右侧表达式原文。"""
    m = re.search(r'\bString\s+%s\s*=\s*([^;]+);' % re.escape(name), text)
    return m.group(1).strip() if m else None


def block_after(text, marker):
    """取 marker 之后那一对花括号里的内容；找不到返回 None。

    marker 自身以 `{` 结尾，所以从它末尾开始按深度配对即可。字符串字面量里
    的花括号在本仓库这几处不出现，不为它引入一个半吊子的 Java 词法分析。
    """
    i = text.find(marker)
    if i < 0:
        return None
    i += len(marker)
    depth, j = 1, i
    while j < len(text) and depth:
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
        j += 1
    return text[i:j - 1] if depth == 0 else None


def is_placeholder(url):
    """注释里的示例、协议前缀片段、正则字面量——都不是真的地址。"""
    if url in ("https://", "http://"):
        return True
    return any(c in url for c in "…<[\\")


def java_files():
    for root, _dirs, files in os.walk(PATCH_DIR):
        for f in sorted(files):
            if f.endswith(".java"):
                yield os.path.join(root, f)


def main():
    problems = []
    try:
        endpoints = open(ENDPOINTS, encoding="utf-8").read()
        hotcheck = open(HOTCHECK, encoding="utf-8").read()
        downui = open(DOWNUI, encoding="utf-8").read()
    except OSError as e:
        print("✘ %s" % e, file=sys.stderr)
        return 2

    # ---- 1. 入库的源码里，注入位必须是空的 ----
    #
    # 拦的是「本地注入过、顺手 git add 了」。那样一来真实域名就跟着提交进了
    # 历史，而这一整套的目的正是不让它进历史。
    for name in ("ROOT_DOMAIN", "PAGES_HOSTS"):
        m = re.search(r'public static final String\s+%s\s*=\s*"([^"]*)";' % name, endpoints)
        if not m:
            problems.append("%s 里找不到 %s 常量——注入脚本会失手，"
                            "而失手的产物是一个「装上去什么都不会发生」的包。"
                            % (ENDPOINTS, name))
        elif m.group(1) != "":
            problems.append(
                "%s 的 %s 不是空串（当前 %d 个字符）。\n"
                "      真实取值只该由 tools/inject-endpoints.py 在**构建时**写入，\n"
                "      不该入库。看到这条多半是本地注入过之后顺手提交了——\n"
                "      跑 `git checkout -- %s` 还原即可。"
                % (ENDPOINTS, name, len(m.group(1)), ENDPOINTS))

    # ---- 2. 几个基址常量必须委托给 CNEndpoints，不能自带字面量 ----
    for path, name, expect in DELEGATES:
        try:
            text = open(path, encoding="utf-8").read()
        except OSError as e:
            problems.append(str(e))
            continue
        rhs = const_rhs(text, name)
        if rhs is None:
            problems.append("%s 里找不到常量 %s" % (path, name))
        elif rhs != expect:
            problems.append(
                "%s 的 %s 应当委托给 %s，实际是 %s。\n"
                "      全仓库只该有一个规范前缀：安装器的完成标记与热更的文件名剥取\n"
                "      都以它为准，两者不一致时同一个文件会有两个身份。"
                % (path, name, expect, rhs))

    # ---- 3. 热更表与浮层文件表里不得再出现绝对地址 ----
    #
    # 这两处曾经各写一份完整 URL，改一边就静默失效（见文件头）。现在都是拿
    # 文件名拼 CANONICAL_BASE，所以只要这两个**块内**不出现绝对地址，那个故障
    # 就回不来。只看块内而不是整份文件：同一个文件里还有署名区那些第三方外链，
    # 它们与本条无关，混在一起报会把真问题淹掉。
    blocks = [
        (HOTCHECK, "PACKAGES 表", block_after(hotcheck, "Pkg[] PACKAGES = {")),
        (DOWNUI, "FILE_NAMES 表", block_after(downui, "String[] FILE_NAMES = {")),
        (DOWNUI, "buildFileUrls()", block_after(downui, "private static String[] buildFileUrls() {")),
    ]
    for path, what, body in blocks:
        if body is None:
            problems.append("%s 里找不到 %s——写法被改了？本条守卫会失效。" % (path, what))
            continue
        for m in ABS_URL.finditer(body):
            url = m.group(1)
            if is_placeholder(url):
                continue
            problems.append(
                "%s 的 %s 里出现了绝对地址 %s。\n"
                "      这里只该写**文件名**，完整地址由 CANONICAL_BASE 当场拼出来。\n"
                "      各写一份的后果是静默的：前缀对不上 → 每条线路都失败 →\n"
                "      玩家看到「已是最新」，热更从此不再生效。"
                % (path, what, url))

    # ---- 4. FILE_URLS 必须由 FILE_NAMES 拼出来 ----
    if "buildFileUrls()" not in downui or "CNMirrors.CANONICAL_BASE + FILE_NAMES[i]" not in downui:
        problems.append(
            "%s 的 FILE_URLS 不再是由 FILE_NAMES 逐项拼出来的。\n"
            "      两张表必须按下标严格并行；分开写就有写歪一行的机会，而写歪的\n"
            "      后果是那一个包的完成标记永远对不上，玩家反复重下同一个包。"
            % DOWNUI)

    # ---- 5. 补丁源码里不得有未列入白名单的绝对地址 ----
    for path in java_files():
        if path in EXEMPT_FILES:
            continue
        try:
            text = open(path, encoding="utf-8").read()
        except OSError:
            continue
        for m in ABS_URL.finditer(text):
            url = m.group(1)
            if is_placeholder(url) or url.startswith(ALLOWED_ABS):
                continue
            problems.append(
                "%s 里出现了绝对地址 %s。\n"
                "      会随部署变的地址一律走 CNEndpoints（构建期注入）。\n"
                "      确实是不随部署变的第三方站点，就把前缀加进本脚本的\n"
                "      ALLOWED_ABS，并在那里写清楚为什么它不会变。"
                % (path, url))

    if problems:
        print("✘ 基址核对未通过：", file=sys.stderr)
        for p in problems:
            print("  · " + p, file=sys.stderr)
        return 1

    print("✔ 基址核对通过")
    print("    · CNEndpoints 的两个注入位都是空串（取值构建期注入）")
    print("    · %d 个基址常量全部委托给 CNEndpoints" % len(DELEGATES))
    print("    · 热更表与浮层文件表里没有绝对地址（前缀对不上在结构上已不可能）")
    print("    · FILE_URLS 由 FILE_NAMES 逐项拼出")
    print("    · 补丁源码里没有白名单之外的绝对地址")
    return 0


if __name__ == "__main__":
    sys.exit(main())
