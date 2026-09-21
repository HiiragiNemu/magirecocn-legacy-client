#!/usr/bin/env python3
"""Run the exact download presentation calculations without an Android device."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='download-ui-tests-') as out:
    subprocess.run(['javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-d', out,
                    str(ROOT / 'patch/src/main/java/io/kamihama/magianative/CNDownloadPresentation.java'),
                    str(ROOT / 'tools/DownloadPresentationTest.java')], check=True)
    subprocess.run(['java', '-cp', out, 'DownloadPresentationTest'], check=True)

ui = (ROOT / 'patch/src/main/java/io/kamihama/magianative/CNCNDownloadUI.java').read_text(encoding='utf-8')
assert 'overallProgressHighWater' not in ui
assert 'setProgress(totals.percent)' in ui and 'formatMb(totals.doneMb)' in ui
assert 'darkMode = backgroundPeriod == CNDownloadPresentation.NIGHT;' in ui
assert 'if (darkMode && backgroundPeriod != CNDownloadPresentation.NIGHT)' in ui
assert '? 0x4418112A : 0xCC18112A' in ui
assert 'new StorageAccessClick(act)' in ui
print('PASS: UI consumes shared totals, night assets have no dim filter, optional manual storage entry')
