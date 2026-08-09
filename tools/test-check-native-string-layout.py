#!/usr/bin/env python3
"""Tests for check-native-string-layout.py."""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools" / "check-native-string-layout.py"
SPEC = importlib.util.spec_from_file_location("native_string_layout_check", MODULE_PATH)
assert SPEC and SPEC.loader
CHECK = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECK
SPEC.loader.exec_module(CHECK)


class LayoutTest(unittest.TestCase):
    def test_arm64_layout(self):
        self.assertEqual(CHECK.layout_for_word(8), CHECK.Layout(8, 24, 22, 8, 16))

    def test_armv7_layout(self):
        self.assertEqual(CHECK.layout_for_word(4), CHECK.Layout(4, 12, 10, 4, 8))

    def test_rejects_unknown_word_size(self):
        with self.assertRaises(ValueError):
            CHECK.layout_for_word(16)

    def test_current_product_source_uses_derived_layout(self):
        self.assertEqual(CHECK.check_repo(ROOT), [])

    def test_hard_coded_arm64_offsets_are_rejected(self):
        source = (ROOT / "magia-native/src/MagiaLegacy.cpp").read_text(encoding="utf-8")
        source = source.replace(
            "s + kNdkStringLongDataOffset", "s + 16", 1
        ).replace(
            "s + kNdkStringLongSizeOffset", "s + 8", 1
        )
        problems = CHECK.validate_native_source(source)
        self.assertTrue(any("+16" in problem for problem in problems), problems)
        self.assertTrue(any("+8" in problem for problem in problems), problems)

    def test_single_abi_workflow_is_rejected(self):
        workflow = "for ABI in arm64-v8a; do\n  echo $ABI\ndone\n"
        self.assertNotEqual(CHECK.validate_workflow(workflow), [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
