#!/usr/bin/env python3
"""Fail the APK build when production endpoint secrets are blank or misbound.

The Java source intentionally keeps endpoint constants empty; this check runs on
the workflow inputs before injection so a build can never produce the historical
"更新未完成 / 版本查询失败" shell again.
"""
import os
import sys

ROOT = "magireco.top"
PAGES = (
    "magireader.pages.dev",
    "magiaexedralive2dviewer.pages.dev",
    "search-api.pages.dev",
)


def main() -> int:
    root = os.environ.get("CLIENT_ROOT_DOMAIN", "").strip().lower().rstrip(".")
    pages = tuple(
        h.strip().lower().rstrip(".")
        for h in os.environ.get("CLIENT_PAGES_HOSTS", "").split(",")
        if h.strip()
    )
    if root != ROOT:
        print(f"production endpoint root mismatch: expected {ROOT!r}", file=sys.stderr)
        return 1
    if pages != PAGES:
        print(
            "production pages host list mismatch: expected " + ",".join(PAGES),
            file=sys.stderr,
        )
        return 1
    print("production endpoint inputs verified: root=1, pages=3")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
