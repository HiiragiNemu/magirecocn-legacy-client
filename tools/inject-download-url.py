#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把下载直链注入介绍站（部署期唯一入口）。

## 为什么下载地址不写在仓库里

它是**部署参数**，不是内容。换 CDN、加一条线路、把文件挪到别处，都不该变成一次
代码改动加一次部署——那会让「能不能搬」取决于「有没有人去改仓库」。

与客户端那边（`tools/inject-endpoints.py`）同一个取向：源码里只留结构，取值构建
/ 部署时给。区别是这边没有身份标识要钉，因为下载页只是**指路**，指错了顶多点不
开，不会像规范前缀那样让老玩家重下几个 GB。

## 两个注入位

    docs-site/index.md      `link: __DOWNLOAD_URL__`   ← 首屏那个按钮，只要第一条
    docs-site/download.md   `<!-- DOWNLOAD_LINKS -->`  ← 展开成完整线路清单

线路顺序就是 `DOWNLOAD_URLS` 里的顺序，第一条即页面上「推荐」的那条。

## 用法

    python3 tools/inject-download-url.py --test    # 占位地址，供本地预览
    python3 tools/inject-download-url.py           # 取 DOWNLOAD_URLS
    python3 tools/inject-download-url.py --reset   # 还原成占位符

注入是幂等的：`--reset` 之后再注入结果一致。CI 在 `npm run docs:build` 之前跑它。
"""

import argparse
import os
import re
import sys
import urllib.parse

INDEX = "docs-site/index.md"
DOWNLOAD = "docs-site/download.md"

URL_SLOT = "__DOWNLOAD_URL__"
LINKS_SLOT = "<!-- DOWNLOAD_LINKS -->"

# 本地预览用的占位地址。RFC 2606 保留域，永远解析不到——万一哪天预览环境里
# 有什么东西真去请求它，会立刻失败，而不是悄悄打到某个真实存在的站点上。
TEST_URLS = "https://cdn1.example.test/app-latest.apk,https://cdn2.example.test/app-latest.apk"

# 注入结果长什么样：一条一行的无序列表。写成函数而不是模板串，是因为线路条数
# 由部署参数决定，不是固定的三条。
def render(urls):
    lines = []
    for i, u in enumerate(urls, 1):
        name = urllib.parse.urlsplit(u).netloc.split(".")[0] or ("线路 %d" % i)
        lines.append("- 线路 %d（`%s`）：<%s>" % (i, name, u))
    return "\n".join(lines)


def valid(u):
    # R4-01：拒控制字符。DOWNLOAD_URLS 是部署参数，一旦带换行/回车，
    # render() 会把它原样写进 markdown，列表结构被打破——一行封住这个注入面。
    if re.search(r"[\x00-\x1f\x7f]", u):
        return False
    p = urllib.parse.urlsplit(u)
    return p.scheme in ("http", "https") and bool(p.netloc) and bool(p.path)


def read(path):
    try:
        return open(path, encoding="utf-8").read()
    except OSError as e:
        raise SystemExit("✘ %s" % e)


def write(path, text):
    open(path, "w", encoding="utf-8").write(text)


def current_links_block(text):
    """取 download.md 里当前的注入区（注入过就是渲染结果，没注入就是那行占位符）。

    注入区的边界：从占位符或列表首行起，到下一个空行为止。用「到空行为止」而不是
    再加一个结束标记，是因为结束标记本身也会被反复注入/还原，多一个位置就多一处
    可能对不上——而这一段在页面上本来就是一个独立段落。
    """
    if LINKS_SLOT in text:
        return LINKS_SLOT
    m = re.search(r"^(?:- 线路 \d+.*(?:\n|$))+", text, re.M)
    return m.group(0).rstrip("\n") if m else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", action="store_true", help="用占位地址（.example.test），供本地预览")
    ap.add_argument("--urls", default=None, help="下载地址，逗号分隔；默认读环境变量 DOWNLOAD_URLS")
    ap.add_argument("--reset", action="store_true", help="把两个注入位还原成占位符")
    args = ap.parse_args()

    index, download = read(INDEX), read(DOWNLOAD)
    block = current_links_block(download)
    if block is None:
        raise SystemExit("✘ %s 里找不到注入区（既没有 %s，也没有已注入的线路清单）——"
                         "写法被改了？" % (DOWNLOAD, LINKS_SLOT))

    if args.reset:
        write(INDEX, re.sub(r"link: \S+\.apk\b", "link: " + URL_SLOT, index, count=1)
              if URL_SLOT not in index else index)
        write(DOWNLOAD, download.replace(block, LINKS_SLOT))
        print("✔ 注入位已还原成占位符")
        return 0

    raw = TEST_URLS if args.test else (
        args.urls if args.urls is not None else os.environ.get("DOWNLOAD_URLS", ""))
    urls = [u.strip() for u in raw.split(",") if u.strip()]
    if not urls:
        print("✘ 没有拿到下载地址：--urls 或环境变量 DOWNLOAD_URLS 都是空的。\n"
              "  注入缺失会发布一个「下载按钮点不开」的站点，所以这里直接失败。",
              file=sys.stderr)
        return 2
    for u in urls:
        if not valid(u):
            print("✘ 下载地址不合法：%r（要带协议、主机名与路径）" % u, file=sys.stderr)
            return 2

    if URL_SLOT in index:
        index = index.replace(URL_SLOT, urls[0])
    else:
        index, n = re.subn(r"link: \S+\.apk\b", "link: " + urls[0], index, count=1)
        if n != 1:
            print("✘ %s 里找不到首屏下载按钮的注入位" % INDEX, file=sys.stderr)
            return 2
    write(INDEX, index)
    write(DOWNLOAD, download.replace(block, render(urls)))

    # 只报条数，不回显地址：部署日志会长期留存。
    print("✔ 下载地址注入完成：%d 条线路" % len(urls))
    return 0


if __name__ == "__main__":
    sys.exit(main())
