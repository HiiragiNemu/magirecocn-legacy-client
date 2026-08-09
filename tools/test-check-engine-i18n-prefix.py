#!/usr/bin/env python3
"""Self-tests for check-engine-i18n-prefix.py."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
CHECKER_PATH = ROOT / "tools" / "check-engine-i18n-prefix.py"


def load_checker():
    spec = importlib.util.spec_from_file_location("check_engine_i18n_prefix", CHECKER_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load checker")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def require_failure(checker, source: str, needle: str) -> None:
    errors = checker.validate_source(source)
    if not any(needle in error for error in errors):
        raise AssertionError(f"mutation was not rejected ({needle}): {errors}")


def main() -> int:
    checker = load_checker()
    source = checker.SOURCE.read_text(encoding="utf-8")
    errors = checker.validate_source(source)
    if errors:
        raise AssertionError(f"production source failed: {errors}")

    marker = "static bool enginePrefixLookup(const char* data, size_t size, std::string& out) {"
    gated = source.replace(marker, marker + "\n    if (!containsKana(data, size)) return false;", 1)
    require_failure(checker, gated, "must not gate prefix lookup")

    no_compare = source.replace("memcmp(data, pre.data(), pre.size()) == 0", "false", 1)
    require_failure(checker, no_compare, "not compared")

    no_suffix = source.replace("out.append(data + pre.size(), size - pre.size());", "", 1)
    require_failure(checker, no_suffix, "suffix is not preserved")

    model_errors = checker.validate_behaviour_model()
    if model_errors:
        raise AssertionError(f"behaviour model failed: {model_errors}")

    print("PASS: production source accepted")
    print("PASS: kana gate, missing compare, and missing suffix mutations rejected")
    print("PASS: behaviour model accepted")
    return 0


if __name__ == "__main__":
    sys.exit(main())
