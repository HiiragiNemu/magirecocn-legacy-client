#!/usr/bin/env python3
"""check-api-levels.py 的离线自测——合成源码验证判据，不碰真实代码。

这类守卫脚本尤其不能「写完跑一遍当前代码绿灯就算数」：它平时守的是未来
回归，只有合成一个「没守卫」「守卫不够」的样本才能确认它真的会拦。
"""

import importlib.util
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "cal", HERE / "check-api-levels.py")
cal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cal)

SAMPLE = """
package io.kamihama.magianative;
import android.app.{clazz};
public class CNTest {{
    {body}
}}
"""


def check_source(body, clazz="NotificationChannel"):
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "CNTest.java"
        p.write_text(SAMPLE.format(clazz=clazz, body=body), encoding="utf-8")
        return cal.check_file(p)


class CheckApiLevelsTest(unittest.TestCase):
    def test_current_repo_passes(self):
        problems = []
        for path in sorted(cal.SRC.rglob("*.java")):
            problems += cal.check_file(path)
        self.assertEqual(problems, [], "当前补丁源码不应有任何 API 门槛违规")

    def test_unguarded_high_api_is_flagged(self):
        problems = check_source(
            "void make(NotificationChannel c) { }\n"
            "    NotificationChannel f;")
        self.assertTrue(any("NotificationChannel" in p and "不含" in p
                            for p in problems),
                        f"未守卫的 NotificationChannel 应被拦，实得 {problems}")

    def test_guard_level_too_low_is_flagged(self):
        problems = check_source(
            "String name(android.app.Application a) {\n"
            "        if (Build.VERSION.SDK_INT >= 24) {\n"
            "            return a.getProcessName();\n"
            "        }\n"
            "        return \"\";\n"
            "    }",
            clazz="NotificationChannel")
        self.assertTrue(any("getProcessName" in p and "低于所需" in p
                            for p in problems),
                        f"守卫级别不够应被拦，实得 {problems}")

    def test_proper_guard_passes(self):
        problems = check_source(
            "String name(android.app.Application a) {\n"
            "        if (Build.VERSION.SDK_INT >= 28) {\n"
            "            return a.getProcessName();\n"
            "        }\n"
            "        return \"\";\n"
            "    }",
            clazz="NotificationChannel")
        self.assertEqual(problems, [], f"守卫到位不应拦，实得 {problems}")

    def test_comment_mention_not_flagged(self):
        problems = check_source(
            "// NotificationChannel 是 API 26 的类，注释里提到不算用。\n"
            "    void noop() { }")
        self.assertEqual(problems, [], f"注释里的符号不应被拦，实得 {problems}")

    def test_desugar_required_library_is_flagged(self):
        problems = check_source(
            "long t = java.time.Instant.now().toEpochMilli();")
        self.assertTrue(any("desugar" in p for p in problems),
                        f"java.time 使用应被拦（未配 desugaring），实得 {problems}")

    def test_import_of_desugar_required_library_is_flagged(self):
        with tempfile.TemporaryDirectory() as td:
            src = Path(td) / "CNTest.java"
            src.write_text(
                "package io.kamihama.magianative;\n"
                "import java.util.Optional;\n"
                "public class CNTest { Optional<String> f; }\n",
                encoding="utf-8")
            probs = cal.check_file(src)
        self.assertTrue(any("desugar" in p for p in probs),
                        f"Optional import 应被拦，实得 {probs}")

    def test_gt_guard_counts_as_n_plus_one(self):
        # SDK_INT > 29 是 API 30+ 的守卫，getProcessName(28) 应通过
        problems = check_source(
            "String name(android.app.Application a) {\n"
            "        if (Build.VERSION.SDK_INT > 29) {\n"
            "            return a.getProcessName();\n"
            "        }\n"
            "        return \"\";\n"
            "    }",
            clazz="NotificationChannel")
        self.assertEqual(problems, [], f"> N 守卫应算 N+1，实得 {problems}")

    def test_comment_mentioning_import_not_flagged(self):
        # 注释里写 `import java.util.Optional;` 不该触发 desugar 检查
        problems = check_source(
            "// 别写 import java.util.Optional;——desugaring 没配。\n"
            "    void noop() { }")
        self.assertEqual(problems, [], f"注释里的 import 不应被拦，实得 {problems}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
