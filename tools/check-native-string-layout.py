#!/usr/bin/env python3
"""Guard the classic libc++ ``std::__ndk1::string`` layout used by native hooks.

The APK ships both arm64-v8a and armeabi-v7a.  The object is three machine words
on both ABIs, but the byte offsets and short-string capacity are different.  A
literal ``+8/+16/22`` therefore silently makes the ARMv7 hook read outside the
12-byte object.  This checker keeps the product source ABI-derived and makes the
dual-ABI build contract explicit.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
from dataclasses import dataclass


@dataclass(frozen=True)
class Layout:
    word_bytes: int
    object_bytes: int
    short_capacity: int
    long_size_offset: int
    long_data_offset: int


def layout_for_word(word_bytes: int) -> Layout:
    if word_bytes not in (4, 8):
        raise ValueError("Android target word size must be 4 or 8 bytes")
    object_bytes = 3 * word_bytes
    return Layout(
        word_bytes=word_bytes,
        object_bytes=object_bytes,
        short_capacity=object_bytes - 2,
        long_size_offset=word_bytes,
        long_data_offset=2 * word_bytes,
    )


def _function_body(text: str, name: str) -> str:
    match = re.search(r"\b" + re.escape(name) + r"\s*\([^;]*?\)\s*\{", text, re.S)
    if not match:
        return ""
    start = match.end() - 1
    depth = 0
    for index in range(start, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[start : index + 1]
    return ""


def validate_native_source(text: str) -> list[str]:
    problems: list[str] = []
    required = {
        "word width": "kNdkStringWordBytes      = sizeof(size_t)",
        "object width": "kNdkStringObjectBytes    = 3 * kNdkStringWordBytes",
        "long size offset": "kNdkStringLongSizeOffset = kNdkStringWordBytes",
        "long data offset": "kNdkStringLongDataOffset = 2 * kNdkStringWordBytes",
        "short capacity": "kNdkStringShortCapacity  = kNdkStringObjectBytes - 2",
        "32/64-bit assertion": "sizeof(void*) == 8 ? 22u : 10u",
        "fake object assertion": "sizeof(FakeNdkStr) == kNdkStringObjectBytes",
    }
    for label, snippet in required.items():
        if snippet not in text:
            problems.append(f"missing {label}: {snippet}")

    for name in ("ndkStrRead", "fakeNdkStr"):
        body = _function_body(text, name)
        if not body:
            problems.append(f"cannot find function body: {name}")
            continue
        for literal in (8, 16):
            if re.search(rf"\bs\s*\+\s*{literal}\b", body):
                problems.append(f"{name} hard-codes ARM64 byte offset +{literal}")
        if re.search(r"\bn\s*<=\s*22\b", body):
            problems.append(f"{name} hard-codes ARM64 short capacity 22")

    read_body = _function_body(text, "ndkStrRead")
    for name in ("kNdkStringLongSizeOffset", "kNdkStringLongDataOffset"):
        if name not in read_body:
            problems.append(f"ndkStrRead does not use {name}")

    # 字体路径原地改写已移除。仍在使用的汉化参数构造器借用调用期内的
    # 中文缓冲区：检查其字段次序、字宽、long 标记与长度，而非要求旧函数回归。
    if not re.search(r"struct\s+FakeNdkStr\s*\{\s*size_t\s+cap;\s*"
                     r"size_t\s+size;\s*const\s+char\s*\*\s*data;\s*\}", text):
        problems.append("FakeNdkStr fields must be cap/size/data in target-word layout")
    write_body = _function_body(text, "fakeNdkStr")
    for pattern in (r"fk\.cap\s*=\s*\(zh\.size\(\)\s*\+\s*1\)\s*\|\s*1\s*;",
                    r"fk\.size\s*=\s*zh\.size\(\)\s*;",
                    r"fk\.data\s*=\s*zh\.c_str\(\)\s*;"):
        if not re.search(pattern, write_body):
            problems.append("fakeNdkStr must preserve long tag, byte length and borrowed buffer")
    if re.search(r"\b(?:delete|free)\b", write_body):
        problems.append("fakeNdkStr must not free its borrowed translation buffer")
    return problems


def validate_workflow(text: str) -> list[str]:
    if not re.search(r"for\s+ABI\s+in\s+arm64-v8a\s+armeabi-v7a", text):
        return ["build workflow no longer compiles arm64-v8a and armeabi-v7a together"]
    return []


def check_repo(root: pathlib.Path) -> list[str]:
    native = root / "magia-native" / "src" / "MagiaLegacy.cpp"
    workflow = root / ".github" / "workflows" / "build-apk.yml"
    problems: list[str] = []
    if not native.is_file():
        problems.append(f"missing {native}")
    else:
        problems.extend(validate_native_source(native.read_text(encoding="utf-8")))
    if not workflow.is_file():
        problems.append(f"missing {workflow}")
    else:
        problems.extend(validate_workflow(workflow.read_text(encoding="utf-8")))
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "root",
        nargs="?",
        type=pathlib.Path,
        default=pathlib.Path(__file__).resolve().parents[1],
    )
    args = parser.parse_args(argv)
    for word in (4, 8):
        current = layout_for_word(word)
        print(
            f"word={word} object={current.object_bytes} short={current.short_capacity} "
            f"size@{current.long_size_offset} data@{current.long_data_offset}"
        )
    problems = check_repo(args.root.resolve())
    if problems:
        for problem in problems:
            print("ERROR: " + problem, file=sys.stderr)
        return 1
    print("native string layout: OK (arm64-v8a + armeabi-v7a)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
