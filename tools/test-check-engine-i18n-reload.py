#!/usr/bin/env python3
"""Mutation self-tests for check-engine-i18n-reload.py."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
CHECKER_PATH = ROOT / "tools" / "check-engine-i18n-reload.py"


def load_checker():
    spec = importlib.util.spec_from_file_location("check_engine_i18n_reload", CHECKER_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load checker")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
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

    seconds_only = source.replace(
        "static EngineI18nFileStamp g_engineI18nStamp;",
        "static std::atomic<time_t> g_engineI18nMtime{0};",
        1,
    )
    require_failure(checker, seconds_only, "seconds-only")

    no_inode = source.replace("stamp.inode = st.st_ino;", "stamp.inode = 0;", 1)
    require_failure(checker, no_inode, "inode is not captured")

    no_inode_compare = source.replace("&& a.inode == b.inode", "", 1)
    require_failure(checker, no_inode_compare, "omits inode")

    no_nsec_compare = source.replace("&& a.mtimeNanoseconds == b.mtimeNanoseconds", "", 1)
    require_failure(checker, no_nsec_compare, "omits mtimeNanoseconds")

    no_nsec_capture = source.replace(
        "stamp.mtimeNanoseconds = st.st_mtim.tv_nsec;",
        "stamp.mtimeNanoseconds = 0;",
        1,
    )
    require_failure(checker, no_nsec_capture, "nanoseconds are not captured")

    no_seconds_capture = source.replace(
        "stamp.mtimeSeconds = st.st_mtime;",
        "stamp.mtimeSeconds = 0;",
        1,
    )
    require_failure(checker, no_seconds_capture, "seconds are not captured")

    first_fstat = "if (::fstat(::fileno(f), &openedBefore) != 0) {"
    no_first_fstat = source.replace(first_fstat, "if (true) {", 1)
    require_failure(checker, no_first_fstat, "before and after")

    second_fstat = "bool statFailed = ::fstat(::fileno(f), &openedAfter) != 0;"
    no_second_fstat = source.replace(second_fstat, "bool statFailed = true;", 1)
    require_failure(checker, no_second_fstat, "before and after")

    no_read_error = source.replace("bool readFailed = ferror(f) != 0;", "bool readFailed = false;", 1)
    require_failure(checker, no_read_error, "read errors")

    no_stability_check = source.replace(
        "if (!engineI18nSameStamp(beforeStamp, afterStamp)) {",
        "if (false) {",
        1,
    )
    require_failure(checker, no_stability_check, "changed during parsing")

    seconds_reload = source.replace(
        "if (!engineI18nSameStamp(pathStamp, loadedStamp)) {",
        "if (pathStamp.mtimeSeconds != loadedStamp.mtimeSeconds) {",
        1,
    )
    require_failure(checker, seconds_reload, "full stamp")

    path_stat_loader = source.replace(
        "struct stat openedBefore;",
        "struct stat pathStat; ::stat(ENGINE_I18N_PATH.c_str(), &pathStat);\n    struct stat openedBefore;",
        1,
    )
    require_failure(checker, path_stat_loader, "later path stat")

    unlocked_accessor = source.replace(
        "std::lock_guard<std::mutex> lk(g_engineI18nMutex);\n    return g_engineI18nStamp;",
        "return g_engineI18nStamp;",
        1,
    )
    require_failure(checker, unlocked_accessor, "not protected")

    no_stamp_publish = source.replace("g_engineI18nStamp = afterStamp;", "", 1)
    require_failure(checker, no_stamp_publish, "matching fd stamp")

    split_publish_lock = source.replace(
        "g_engineI18nTable = fresh;\n        g_engineI18nStamp = afterStamp;",
        "g_engineI18nTable = fresh;\n    }\n    {\n        g_engineI18nStamp = afterStamp;",
        1,
    )
    require_failure(checker, split_publish_lock, "share one mutex block")

    model_errors = checker.validate_behaviour_model()
    if model_errors:
        raise AssertionError(f"behaviour model failed: {model_errors}")

    print("PASS: production source accepted")
    print("PASS: seconds-only, field/fstat/read/stability/order/lock mutations rejected")
    print("PASS: same-second atomic-rename and in-place-update models accepted")
    return 0


if __name__ == "__main__":
    sys.exit(main())
