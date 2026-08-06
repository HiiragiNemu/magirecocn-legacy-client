#!/usr/bin/env python3
"""核对 CANONICAL_BASE 仍然是所有热更包/版本 json 地址的前缀。

## 这个不变量是什么

CNMirrors 里有两个长得很像的常量，它们是**两个概念**：

    DEFAULT_BASE     兜底线路——「从哪里取字节」，随时可以换成任何一条快的线路
    CANONICAL_BASE   规范前缀——「这条地址是不是主线资源」，是身份判据

CANONICAL_BASE 被两处依赖：

    CNHotUpdate.mainLineFileName    前缀对不上 → 返回 null＝「非主线，直连下载」
                                    → 热更包悄悄退化成不换线
    CNHotUpdateCheck.fetchMeta      前缀对不上 → 剥不出文件名，name 保留整条 URL
                                    → 拼出 https://<镜像>/https://r2.../version_js.json
                                    → 每条线路都失败，热更**静默停摆**

而热更包与版本 json 的地址是硬编码在 CNHotUpdateCheck.PACKAGES 里的。
两边任何一边单独改动，前缀就对不上了。

## 为什么必须机器核对

因为坏掉的时候**没有任何报错**。热更查询在每条线路上失败，被 fetchMetaSafe
吞掉、记一行「版本查询失败，跳过本项」，玩家看到的是「已是最新」。等到有人
发现台词包几个月没更新，已经隔了很久，而且没人会想到是改兜底线路引起的。

这两个常量早先本来就是同一个（都叫 DEFAULT_BASE）。把兜底线路换成 edge
时才发现这个耦合——差一点就把热更改坏了。拆开之后加这个核对，是为了让下一个
换线路的人不必重新踩一遍。

用法：python3 tools/check-base-urls.py
"""

import re
import sys

MIRRORS = "patch/src/main/java/io/kamihama/magianative/CNMirrors.java"
HOTCHECK = "patch/src/main/java/io/kamihama/magianative/CNHotUpdateCheck.java"
INSTALLER = "patch/src/main/java/io/kamihama/magianative/CNDownloaderFix.java"

# 规范前缀不能用具体 CDN 的域名。这个串**永远不会被真的请求**——两处用它的
# 地方都是「剥出文件名后逐条线路试」。拿某个 CDN 当身份，那个 CDN 一停用，
# 字符串就成了一句谎话，而且会误导下一个人以为它是个真实下载源。
#
# 已经发生过一次：早先规范前缀是 r2.assets.example.test，而 object-storage 自定义域只在
# CDN 接管 DNS 时才生效，换 NS 之后那个子域彻底废掉。
CDN_PREFIXES = ("r2.", "edge.", "esa.", "（已下线线路）.", "cdn1.", "cdn2.", "cdn3.")


def const(text, name, path):
    m = re.search(r'%s\s*=\s*"([^"]*)"' % re.escape(name), text)
    if not m:
        raise LookupError("在 %s 里找不到常量 %s" % (path, name))
    return m.group(1)


def main():
    try:
        mirrors = open(MIRRORS, encoding="utf-8").read()
        hotcheck = open(HOTCHECK, encoding="utf-8").read()
        installer = open(INSTALLER, encoding="utf-8").read()
        canonical = const(mirrors, "CANONICAL_BASE", MIRRORS)
        default = const(mirrors, "DEFAULT_BASE", MIRRORS)
        resource = const(installer, "RESOURCE_BASE_URL", INSTALLER)
    except (OSError, LookupError) as e:
        print("✘ %s" % e, file=sys.stderr)
        return 2

    problems = []

    # 全仓库只该有一个规范前缀。曾经不是：安装器用 assets.example.test，
    # 热更用 r2.assets.example.test，同一对 zip 有两个「规范」地址。
    if canonical != resource:
        problems.append(
            "规范前缀有两个，必须统一：\n"
            "        CNMirrors.CANONICAL_BASE        = %s\n"
            "        CNDownloaderFix.RESOURCE_BASE_URL = %s\n"
            "      安装器的完成标记与热更的文件名剥取都以「规范前缀」为准，"
            "两者不一致时同一个文件会有两个身份。" % (canonical, resource))

    host = canonical.split("//", 1)[-1]
    for pre in CDN_PREFIXES:
        if host.startswith(pre):
            problems.append(
                "规范前缀用了具体 CDN 的域名（%s）。它永远不会被真的请求，"
                "只作身份标识；某个 CDN 停用后这个串就成了谎话。\n"
                "      改用不绑定 CDN 的域名（当前约定：https://assets.example.test/）。"
                % host.rstrip("/"))
            break
    if not canonical.endswith("/"):
        problems.append("CANONICAL_BASE 必须以 / 结尾，否则剥文件名会多带一个字符：%s"
                        % canonical)
    if not default.endswith("/"):
        problems.append("DEFAULT_BASE 必须以 / 结尾，否则拼出来的地址会少一个分隔符：%s"
                        % default)

    # PACKAGES 表里的分发地址与版本 json
    urls = re.findall(r'"(https://[^"]+?\.(?:json|zip))"', hotcheck)
    if not urls:
        problems.append("在 %s 里没找到任何热更地址——PACKAGES 表被改了写法？"
                        % HOTCHECK)
    for u in urls:
        if not u.startswith(canonical):
            problems.append(
                "%s\n      不以 CANONICAL_BASE（%s）开头。\n"
                "      后果是静默的：版本查询在每条线路上失败 → 被当成"
                "「已是最新」→ 热更永远不再生效。\n"
                "      改地址时请同步 CNMirrors.CANONICAL_BASE。"
                % (u, canonical))

    if problems:
        print("✘ 基址核对未通过：", file=sys.stderr)
        for p in problems:
            print("  · " + p, file=sys.stderr)
        return 1

    print("✔ 基址核对通过")
    print("    规范前缀 = %s" % canonical)
    print("      · 与 CNDownloaderFix.RESOURCE_BASE_URL 一致")
    print("      · %d 条热更地址全部以它开头" % len(urls))
    print("      · 未绑定任何具体 CDN")
    print("    兜底线路 = %s（可独立更换，不牵动上面任何一条）" % default)
    return 0


if __name__ == "__main__":
    sys.exit(main())
