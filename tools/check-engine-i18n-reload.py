#!/usr/bin/env python3
"""Guard the native engine-i18n hot-reload file-identity contract.

The hot-update transaction installs files with a temporary file plus atomic
rename.  A seconds-only path mtime check can miss same-second replacements;
opening the old inode and then statting the new path can also bind old content
to the new timestamp forever.  Production must fingerprint the opened fd,
verify that it stayed stable while parsing, publish the table and fingerprint
together, and compare the whole fingerprint on later path checks.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "magia-native" / "src" / "MagiaLegacy.cpp"


def extract_function_body(source: str, marker: str) -> str:
    start = source.find(marker)
    if start < 0:
        raise AssertionError(f"missing production function: {marker}")
    brace = source.find("{", start)
    if brace < 0:
        raise AssertionError(f"missing opening brace: {marker}")

    depth = 0
    for pos in range(brace, len(source)):
        char = source[pos]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[brace + 1 : pos]
    raise AssertionError(f"missing closing brace: {marker}")


def extract_braced_block(source: str, opening_brace: int) -> str:
    if opening_brace < 0 or opening_brace >= len(source) or source[opening_brace] != "{":
        raise AssertionError("invalid opening brace for critical section")
    depth = 0
    for pos in range(opening_brace, len(source)):
        char = source[pos]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[opening_brace + 1 : pos]
    raise AssertionError("missing closing brace for critical section")


def validate_source(source: str) -> list[str]:
    errors: list[str] = []
    if "g_engineI18nMtime" in source:
        errors.append("seconds-only g_engineI18nMtime state must not return")

    required_global = {
        "struct EngineI18nFileStamp": "missing file-identity stamp type",
        "dev_t  device": "stamp does not track device identity",
        "ino_t  inode": "stamp does not track inode identity",
        "off_t  size": "stamp does not track file size",
        "time_t mtimeSeconds": "stamp does not track mtime seconds",
        "long   mtimeNanoseconds": "stamp does not track mtime nanoseconds",
        "static EngineI18nFileStamp g_engineI18nStamp": "missing published stamp state",
    }
    for token, message in required_global.items():
        if token not in source:
            errors.append(message)

    try:
        stamp_from_stat = extract_function_body(
            source,
            "static EngineI18nFileStamp engineI18nStampFromStat(",
        )
        same_stamp = extract_function_body(
            source,
            "static bool engineI18nSameStamp(",
        )
        loaded_stamp = extract_function_body(
            source,
            "static EngineI18nFileStamp engineI18nLoadedStamp()",
        )
        loader = extract_function_body(source, "static void loadEngineI18n()")
        reloader = extract_function_body(source, "static void maybeReloadEngineI18n()")
    except AssertionError as exc:
        return errors + [str(exc)]

    required_stamp_capture = {
        "stamp.device = st.st_dev": "stat device is not captured",
        "stamp.inode = st.st_ino": "stat inode is not captured",
        "stamp.size = st.st_size": "stat size is not captured",
        "stamp.mtimeSeconds = st.st_mtime": "stat mtime seconds are not captured",
        "stamp.mtimeNanoseconds = st.st_mtim.tv_nsec": "Android/Linux mtime nanoseconds are not captured",
        "stamp.valid = true": "captured stamp is not marked valid",
    }
    for token, message in required_stamp_capture.items():
        if token not in stamp_from_stat:
            errors.append(message)

    for field in (
        "device",
        "inode",
        "size",
        "mtimeSeconds",
        "mtimeNanoseconds",
        "valid",
    ):
        if f"a.{field}" not in same_stamp or f"b.{field}" not in same_stamp:
            errors.append(f"full stamp comparison omits {field}")

    if "std::lock_guard<std::mutex> lk(g_engineI18nMutex)" not in loaded_stamp:
        errors.append("published stamp accessor is not protected by g_engineI18nMutex")
    if "return g_engineI18nStamp" not in loaded_stamp:
        errors.append("published stamp accessor does not return the stored stamp")

    if loader.count("::fstat(::fileno(f),") < 2:
        errors.append("loader must fstat the opened fd before and after parsing")
    if "::stat(ENGINE_I18N_PATH" in loader:
        errors.append("loader must not bind parsed fd content to a later path stat")
    required_loader = {
        "ferror(f)": "loader does not reject read errors",
        "engineI18nStampFromStat(openedBefore)": "loader does not fingerprint the pre-read fd",
        "engineI18nStampFromStat(openedAfter)": "loader does not fingerprint the post-read fd",
        "!engineI18nSameStamp(beforeStamp, afterStamp)": "loader accepts an fd changed during parsing",
        "g_engineI18nTable = fresh": "loader does not publish the parsed table",
        "g_engineI18nStamp = afterStamp": "loader does not publish the matching fd stamp",
    }
    for token, message in required_loader.items():
        if token not in loader:
            errors.append(message)

    before_pos = loader.find("::fstat(::fileno(f), &openedBefore)")
    read_pos = loader.find("while (fgets(buf, sizeof(buf), f))")
    error_pos = loader.find("ferror(f)")
    after_pos = loader.find("::fstat(::fileno(f), &openedAfter)")
    # The pre-read fstat error path legitimately contains an earlier fclose;
    # select the close paired with the successful post-read fstat.
    close_pos = loader.find("fclose(f)", after_pos)
    compare_pos = loader.find("!engineI18nSameStamp(beforeStamp, afterStamp)")
    table_pos = loader.find("g_engineI18nTable = fresh")
    stamp_pos = loader.find("g_engineI18nStamp = afterStamp")
    positions = [
        before_pos,
        read_pos,
        error_pos,
        after_pos,
        close_pos,
        compare_pos,
        table_pos,
        stamp_pos,
    ]
    if any(pos < 0 for pos in positions) or positions != sorted(positions):
        errors.append("loader fd-check/read/publish operations are not in the required order")

    publish_lock = loader.find("std::lock_guard<std::mutex> lk(g_engineI18nMutex)")
    publish_table = loader.find("g_engineI18nTable = fresh")
    publish_stamp = loader.find("g_engineI18nStamp = afterStamp")
    if not (0 <= publish_lock < publish_table < publish_stamp):
        errors.append("table and matching fd stamp are not published under the same mutex")
    else:
        try:
            lock_open = loader.rfind("{", 0, publish_lock)
            publish_block = extract_braced_block(loader, lock_open)
            for token in (
                "std::lock_guard<std::mutex> lk(g_engineI18nMutex)",
                "g_engineI18nTable = fresh",
                "g_engineI18nStamp = afterStamp",
                "g_engineI18nReady.store",
            ):
                if token not in publish_block:
                    errors.append("table, stamp, and ready state must share one mutex block")
                    break
        except AssertionError as exc:
            errors.append(str(exc))

    required_reloader = {
        "engineI18nStampFromStat(st)": "reload check does not fingerprint the current path",
        "engineI18nLoadedStamp()": "reload check does not read the published fd stamp",
        "!engineI18nSameStamp(pathStamp, loadedStamp)": "reload check does not compare the full stamp",
        "loadEngineI18n()": "reload check does not reload a changed file",
    }
    for token, message in required_reloader.items():
        if token not in reloader:
            errors.append(message)
    return errors


@dataclass(frozen=True)
class Stamp:
    device: int
    inode: int
    size: int
    mtime_seconds: int
    mtime_nanoseconds: int
    valid: bool = True


def same_stamp(a: Stamp, b: Stamp) -> bool:
    return a.valid == b.valid and (
        not a.valid
        or (
            a.device == b.device
            and a.inode == b.inode
            and a.size == b.size
            and a.mtime_seconds == b.mtime_seconds
            and a.mtime_nanoseconds == b.mtime_nanoseconds
        )
    )


def validate_behaviour_model() -> list[str]:
    errors: list[str] = []
    base = Stamp(1, 10, 4096, 100, 20)
    cases = [
        (base, base, True, "identical"),
        (base, Stamp(2, 10, 4096, 100, 20), False, "device change"),
        (base, Stamp(1, 11, 4096, 100, 20), False, "same-second atomic rename"),
        (base, Stamp(1, 10, 4097, 100, 20), False, "same-inode size change"),
        (base, Stamp(1, 10, 4096, 101, 20), False, "mtime seconds change"),
        (base, Stamp(1, 10, 4096, 100, 21), False, "same-second nanosecond change"),
        (base, Stamp(1, 10, 4096, 100, 20, False), False, "validity change"),
        (
            Stamp(1, 10, 4096, 100, 20, False),
            Stamp(9, 99, 0, 0, 0, False),
            True,
            "both invalid",
        ),
    ]
    for left, right, expected, label in cases:
        actual = same_stamp(left, right)
        if actual != expected:
            errors.append(f"{label}: expected {expected}, got {actual}")
    return errors


def main() -> int:
    source = SOURCE.read_text(encoding="utf-8")
    errors = validate_source(source) + validate_behaviour_model()
    if errors:
        for error in errors:
            print(f"FAIL: {error}")
        return 1
    print("PASS: engine i18n reload binds parsed content to the opened fd fingerprint")
    print("PASS: device/inode/size/mtime-sec/mtime-nsec changes trigger reload")
    print("PASS: read errors and files changed during parsing preserve the last good snapshot")
    return 0


if __name__ == "__main__":
    sys.exit(main())
