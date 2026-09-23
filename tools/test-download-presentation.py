#!/usr/bin/env python3
"""Run the exact download presentation calculations without an Android device."""
from pathlib import Path
import argparse
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--all-files-access', choices=['enabled', 'disabled'], default='disabled',
                    help='Expected build flag; source defaults remain disabled.')
parser.add_argument('--tree', type=Path, help='Also check the rebuilt APK manifest.')
args = parser.parse_args()
enabled = args.all_files_access == 'enabled'
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
assert 'if (CNBuildConfig.ALL_FILES_ACCESS) {' in ui
flag = (ROOT / 'patch/src/main/java/io/kamihama/magianative/CNBuildConfig.java').read_text(encoding='utf-8')
matches = re.findall(r'boolean\s+ALL_FILES_ACCESS\s*=\s*(true|false)\s*;', flag)
assert matches == [str(enabled).lower()], 'ALL_FILES_ACCESS does not match the requested build mode'
if args.tree:
    manifest = ET.parse(args.tree / 'AndroidManifest.xml').getroot()
    permission = 'android.permission.MANAGE_EXTERNAL_STORAGE'
    name = '{http://schemas.android.com/apk/res/android}name'
    count = sum(node.get(name) == permission for node in manifest.findall('uses-permission'))
    assert count == int(enabled), 'Manifest permission and ALL_FILES_ACCESS disagree'
print('PASS: UI consumes shared totals, night assets have no dim filter; manual storage mode=' + args.all_files_access)
