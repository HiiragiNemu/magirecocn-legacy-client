#!/usr/bin/env python3
"""验证 class 目录/JAR/APK 中确实含有本轮注入派生端点；不回显地址。"""
import argparse
import os
import re
import sys
import zipfile

DEFAULT_SOURCE = "patch/src/main/java/io/kamihama/magianative/CNEndpoints.java"
DOMAIN_RE = re.compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")


def const_value(text, name):
    m = re.search(r'public static final String\s+%s\s*=\s*"([^"]*)";' % re.escape(name), text)
    return m.group(1) if m else None


def payloads(path):
    if os.path.isdir(path):
        for base, _, names in os.walk(path):
            for name in names:
                p = os.path.join(base, name)
                with open(p, "rb") as f:
                    yield p, f.read()
        return
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as zf:
            for info in zf.infolist():
                if info.is_dir():
                    continue
                yield info.filename, zf.read(info)
        return
    with open(path, "rb") as f:
        yield path, f.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("artifact")
    ap.add_argument("--source", default=DEFAULT_SOURCE)
    args = ap.parse_args()
    try:
        source = open(args.source, encoding="utf-8").read()
    except OSError as e:
        print("✘ %s" % e, file=sys.stderr)
        return 2
    root = const_value(source, "ROOT_DOMAIN")
    pages = const_value(source, "PAGES_HOSTS")
    page_list = [x.strip() for x in (pages or "").split(",") if x.strip()]
    if not root or not DOMAIN_RE.match(root) or not page_list:
        print("✘ 注入源码为空或格式不合法，拒绝验证产物", file=sys.stderr)
        return 2
    literals = [
        "https://www." + root,
        "https://api." + root + "/",
        const_value(source, "CONFIG_URL_OVERRIDE") or "https://api." + root + "/legacy/config.json",
        "https://assets." + root + "/",
        const_value(source, "PRIMARY_BASE_OVERRIDE") or "https://edgeone.assets." + root + "/",
        const_value(source, "SECONDARY_BASE_OVERRIDE") or "https://esa.assets." + root + "/",
        pages,
    ]
    needles = [value.encode("utf-8") for value in literals]
    found = [False] * len(needles)
    for _, data in payloads(args.artifact):
        for i, needle in enumerate(needles):
            if not found[i] and needle in data:
                found[i] = True
        if all(found):
            break
    missing = [i for i, present in enumerate(found) if not present]
    if missing:
        print("✘ 构建产物缺少 %d/%d 个派生端点字面" % (len(missing), len(literals)), file=sys.stderr)
        return 1
    print("✔ 构建产物端点字面验证通过：%d/%d；MIRRORS_URL=https/absolute" % (len(literals), len(literals)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
