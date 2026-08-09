#!/usr/bin/env python3
"""Guard the charset-agnostic native engine-i18n prefix contract.

Prefix rules are explicitly authored in ``engine_i18n.tsv``.  They must be
matched regardless of whether the runtime source starts with Japanese, Latin,
or CJK text.  A previous optimisation gated the scan on UTF-8 lead bytes
0xE3/0xE4, silently making Latin and many CJK rules unreachable.

This check intentionally inspects the production function rather than a
duplicate implementation.  The small behaviour model below documents the
expected ordered-prefix semantics and makes failures easier to diagnose.
"""

from __future__ import annotations

from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "magia-native" / "src" / "MagiaLegacy.cpp"
FUNCTION = "enginePrefixLookup"


def extract_function_body(source: str, function: str = FUNCTION) -> str:
    marker = f"static bool {function}("
    start = source.find(marker)
    if start < 0:
        raise AssertionError(f"missing production function: {function}")
    brace = source.find("{", start)
    if brace < 0:
        raise AssertionError(f"missing opening brace: {function}")

    depth = 0
    for pos in range(brace, len(source)):
        char = source[pos]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[brace + 1 : pos]
    raise AssertionError(f"missing closing brace: {function}")


def validate_source(source: str) -> list[str]:
    errors: list[str] = []
    try:
        body = extract_function_body(source)
    except AssertionError as exc:
        return [str(exc)]

    forbidden = {
        "hasKana": "prefix lookup must not depend on a kana prefilter",
        "containsKana": "miss-log filtering must not gate prefix lookup",
        "0xE3": "UTF-8 lead-byte filtering makes Latin/CJK prefixes unreachable",
        "0xE4": "UTF-8 lead-byte filtering makes Latin/CJK prefixes unreachable",
    }
    for token, message in forbidden.items():
        if token in body:
            errors.append(f"{message}: found {token}")

    required = {
        "for (const auto& rule : t->prefix)": "ordered prefix rules are not scanned",
        "memcmp(data, pre.data(), pre.size()) == 0": "prefix bytes are not compared",
        "out = rule.second": "translated prefix is not written",
        "out.append(data + pre.size(), size - pre.size())": "source suffix is not preserved",
    }
    for token, message in required.items():
        if token not in body:
            errors.append(message)
    return errors


def apply_prefix_model(rules: list[tuple[str, str]], value: str) -> str | None:
    for source, translated in rules:
        if value.startswith(source):
            return translated + value[len(source) :]
    return None


def validate_behaviour_model() -> list[str]:
    errors: list[str] = []
    cases = [
        ([('Error code: ', '错误代码：')], 'Error code: 17', '错误代码：17', 'Latin'),
        ([('错误代码：', '错误编号：')], '错误代码：17', '错误编号：17', 'CJK'),
        ([('エラーコード：', '错误代码：')], 'エラーコード：17', '错误代码：17', 'kana'),
        ([('prefix:', '前缀：')], 'other:17', None, 'miss'),
    ]
    for rules, value, expected, label in cases:
        actual = apply_prefix_model(rules, value)
        if actual != expected:
            errors.append(f"{label} model: expected {expected!r}, got {actual!r}")
    return errors


def main() -> int:
    source = SOURCE.read_text(encoding="utf-8")
    errors = validate_source(source) + validate_behaviour_model()
    if errors:
        for error in errors:
            print(f"FAIL: {error}")
        return 1
    print("PASS: enginePrefixLookup scans explicit rules without a charset gate")
    print("PASS: Latin, CJK, kana, suffix-preservation, and miss models")
    return 0


if __name__ == "__main__":
    sys.exit(main())
