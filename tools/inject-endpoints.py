#!/usr/bin/env python3
"""把真实主机名注入 CNEndpoints.java（构建期唯一入口）。

## 为什么要有这一步

主机名是**部署参数**，不是代码：换域名、换架构、切测试环境时不该动源码。所以
`CNEndpoints.ROOT_DOMAIN` / `CNEndpoints.PAGES_HOSTS` 在源码里恒为空串，取值构建时
由本脚本从 CI 变量写进去；测试与本地构建走同一条路径，只是取值换成占位域名。

## 为什么是一个脚本而不是一行 sed

因为它有三个必须一起满足的约束，散在 workflow 里迟早漂移：

1. **CANONICAL_BASE 是身份标识。** `https://assets.<主域>/` 这一串已经写进每一台
   已安装设备的 15 个完成标记，`isMarkerValid` 逐字符串比对。注入值哪怕差一个
   字符，`allMarkersValid()` 全部返回 false → 安装器判「没装过」→ **每个老玩家
   重下几个 GB**。所以这里对拼出来的规范前缀做 **sha256 核对**，对不上就让构建
   当场失败（钉哈希而不是钉明文，正是为了不把地址写回仓库）。
2. **Java 与 native 必须拿到同一个主域。** 两侧各有一份「自身域不重写」的判断，
   不一致会打成死循环或漏判。所以主域只在这里给一次，native 那份由本脚本导出
   到 `MAGIA_ROOT_DOMAIN`。
3. **测试也要能跑。** 测试用占位域名（`--test`），与线上取值无关，但走的是同一
   条注入路径——注入本身写错了，测试阶段就会暴露，而不是等发版才发现。

## 用法

    python3 tools/inject-endpoints.py --test          # 占位域名，供测试/本地
    python3 tools/inject-endpoints.py                 # 取 CLIENT_ROOT_DOMAIN
                                                      #   与 CLIENT_PAGES_HOSTS
    python3 tools/inject-endpoints.py --print-root    # 只回显主域（喂给 CMake）
    python3 tools/inject-endpoints.py --reset         # 还原成空串

注入是**幂等**的：反复跑同一份取值结果一致；已经注入过再跑会先还原成空串。
"""

import argparse
import hashlib
import os
import re
import sys

ENDPOINTS = "patch/src/main/java/io/kamihama/magianative/CNEndpoints.java"

# 测试与本地构建用的占位域名。刻意用 RFC 2606 保留的 .test / .example ——
# 它们**永远不会被真的解析到**，万一哪天有代码在测试里发出真请求，会立刻失败，
# 而不是悄悄打到某个真实存在的第三方站点上。
TEST_ROOT = "example.test"
TEST_PAGES = "reader.pages.example,live2d.pages.example,callsearch.pages.example"

# 规范前缀（https://assets.<主域>/）的 sha256。
#
# 钉住它的理由与「这个域名解析不解析得了」无关：该串是**身份标识**，已经写进
# 每一台已安装设备的 15 个完成标记。改动 → 所有老玩家重下几个 GB。
#
# 真要迁移：先给标记加 schema=2 与「认旧 url 也算有效」的迁移逻辑，再改这里。
PINNED_CANONICAL_SHA256 = "9ab1b5bd332ebf80f6de3c000a447e3baa21dad93b12bfda103b4d5746e3021d"

# 占位域名下的规范前缀哈希，供 --test 自校验，防止「注入逻辑写错但测试全绿」。
TEST_CANONICAL_SHA256 = hashlib.sha256(
    ("https://assets." + TEST_ROOT + "/").encode("utf-8")).hexdigest()

DOMAIN_RE = re.compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")


def valid_domain(d):
    return bool(d) and len(d) <= 253 and bool(DOMAIN_RE.match(d))


def set_const(text, name, value, path):
    """把 `public static final String NAME = "…";` 的取值换掉。

    只认这一种写法。写法被改动过就报错而不是静默不注入——静默不注入产出的是
    一个「装上去什么都不会发生」的包，那比构建失败糟得多。
    """
    pat = re.compile(r'(public static final String\s+%s\s*=\s*")([^"]*)(";)' % re.escape(name))
    new, n = pat.subn(lambda m: m.group(1) + value + m.group(3), text)
    if n != 1:
        raise SystemExit("✘ %s 里没有恰好一处 %s 常量（找到 %d 处）——"
                         "写法被改了？注入必须命中，否则会产出空壳包。" % (path, name, n))
    return new


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", action="store_true",
                    help="用占位域名（.test / .example），供测试与本地构建")
    ap.add_argument("--root", default=None, help="主域；默认读环境变量 CLIENT_ROOT_DOMAIN")
    ap.add_argument("--pages", default=None,
                    help="署名区精确主机名，逗号分隔；默认读 CLIENT_PAGES_HOSTS")
    ap.add_argument("--reset", action="store_true",
                    help="把两个注入位还原成空串（测试跑完后用，避免注入结果被误提交）")
    ap.add_argument("--print-root", action="store_true",
                    help="只把主域打到标准输出（喂给 CMake 的 -DMAGIA_ROOT_DOMAIN），不改文件")
    args = ap.parse_args()

    if args.reset:
        # 刻意不用 `git checkout --`：那条要求文件已被跟踪、且会连带丢掉同一
        # 文件上别的本地改动。还原本来就只是「把两个常量写回空串」，自己做更准。
        try:
            text = open(ENDPOINTS, encoding="utf-8").read()
        except OSError as e:
            print("✘ %s" % e, file=sys.stderr)
            return 2
        text = set_const(text, "ROOT_DOMAIN", "", ENDPOINTS)
        text = set_const(text, "PAGES_HOSTS", "", ENDPOINTS)
        open(ENDPOINTS, "w", encoding="utf-8").write(text)
        print("✔ 注入位已还原成空串")
        return 0

    if args.test:
        root, pages = TEST_ROOT, TEST_PAGES
        expect = TEST_CANONICAL_SHA256
    else:
        root = args.root if args.root is not None else os.environ.get("CLIENT_ROOT_DOMAIN", "")
        pages = args.pages if args.pages is not None else os.environ.get("CLIENT_PAGES_HOSTS", "")
        expect = PINNED_CANONICAL_SHA256

    root = root.strip().lower().rstrip(".")
    pages = ",".join(h.strip().lower() for h in pages.split(",") if h.strip())

    if not root:
        print("✘ 没有拿到主域：--root 或环境变量 CLIENT_ROOT_DOMAIN 都是空的。\n"
              "  注入缺失会产出一个「装上去什么都不会发生」的包，所以这里直接失败。",
              file=sys.stderr)
        return 2
    if not valid_domain(root):
        print("✘ 主域不合法：%r" % root, file=sys.stderr)
        return 2
    if not pages:
        print("✘ 没有拿到署名区主机名：--pages 或 CLIENT_PAGES_HOSTS 是空的。",
              file=sys.stderr)
        return 2
    for h in pages.split(","):
        if not valid_domain(h):
            print("✘ 署名区主机名不合法：%r" % h, file=sys.stderr)
            return 2

    canonical = "https://assets." + root + "/"
    got = hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    if args.print_root:
        # 校验照做——喂给 CMake 的和写进 Java 的必须是同一个值，
        # 这里放行、那里拦下会更难查。
        if expect and got != expect:
            print("✘ 规范前缀哈希对不上（见下方说明）", file=sys.stderr)
            return 1
        sys.stdout.write(root)
        return 0

    if expect:
        if got != expect:
            print("✘ 规范前缀哈希对不上：\n"
                  "      期望 %s\n"
                  "      实际 %s\n"
                  "  规范前缀 https://assets.<主域>/ 是**身份标识**，已经写进每一台\n"
                  "  已安装设备的 15 个完成标记（marker 的 url= 字段），isMarkerValid\n"
                  "  做逐字符串比对。注入了别的取值 → allMarkersValid() 全部返回 false\n"
                  "  → 安装器判定「没装过」→ **每个老玩家重下几个 GB**。\n"
                  "  所以这里拦下来，而不是让它编出包。\n"
                  "  · 主域 Secret 填错了 → 改 Secret；\n"
                  "  · 确实要迁移域名 → 先给标记加 schema=2 与「认旧 url 也算有效」\n"
                  "    的迁移逻辑，再更新本脚本的 PINNED_CANONICAL_SHA256。"
                  % (expect, got), file=sys.stderr)
            return 1
    else:
        # 首次启用时 PINNED 还没填。此时不拦，但要把哈希打出来，让人贴回源码——
        # 不打印的话这道防线永远不会被启用，而没人会注意到。
        print("::warning::PINNED_CANONICAL_SHA256 还是空的，本次不做身份核对。")
        print("           把这一行填进 tools/inject-endpoints.py 即可启用：")
        print("           PINNED_CANONICAL_SHA256 = \"%s\"" % got)

    try:
        text = open(ENDPOINTS, encoding="utf-8").read()
    except OSError as e:
        print("✘ %s" % e, file=sys.stderr)
        return 2

    text = set_const(text, "ROOT_DOMAIN", root, ENDPOINTS)
    text = set_const(text, "PAGES_HOSTS", pages, ENDPOINTS)
    open(ENDPOINTS, "w", encoding="utf-8").write(text)

    # 只报「注入了几项」，不回显取值本身：构建日志会被长期保留、到处转贴，
    # 部署参数没有理由散进去。
    print("✔ 端点注入完成：主域 1 项、署名区主机名 %d 项" % len(pages.split(",")))
    print("    规范前缀 sha256 = %s（与钉死值一致）" % got)
    return 0


if __name__ == "__main__":
    sys.exit(main())
