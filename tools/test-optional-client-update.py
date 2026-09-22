#!/usr/bin/env python3
"""Check update choice wiring and execute the compiled production continuation."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--classes', type=Path, required=True)
p.add_argument('--classpath', required=True)
p.add_argument('--source-root', type=Path, default=ROOT)
args = p.parse_args()
java = args.source_root / 'patch/src/main/java/io/kamihama/magianative'
ui = (java / 'CNCNDownloadUI.java').read_text(encoding='utf8')
vc = (java / 'CNVersionCheck.java').read_text(encoding='utf8')
start = ui.index('public static void showVersionUpdateDialog(')
end = ui.index('private static void addLogChip(', start)
dialog = ui[start:end]
assert '"继续用旧版"' in dialog, 'missing continue-old-version button'
assert 'stay.setOnClickListener(keepCurrent)' in dialog
assert 'modal.setOnClickListener(keepCurrent)' in dialog
assert 'panel.setClickable(true)' in dialog
assert 'KEYCODE_BACK' in dialog and 'ACTION_UP) continueWithCurrentVersion()' in dialog
assert 'finishAffinity(' not in dialog and 'act.finish(' not in dialog
assert dialog.count('CNVersionCheck.continueWithCurrentVersion();') >= 3
assert 'versionModal = null;' in dialog and 'removeView(modal)' in dialog
assert 'CNApkUpdateActivity.start(act, metadata)' in dialog
assert 'host != overlayView || act.isFinishing()' in dialog
assert 'PROCEEDED.compareAndSet(false, true)' in vc
assert 'Thread(new ContinueCurrentVersion(), "cnv-version-continue")' in vc
print('PASS: continue, outside, back, absent/failed prompt and update actions wired', flush=True)
with tempfile.TemporaryDirectory(prefix='optional-apk-update-') as temp:
    cp = os.pathsep.join([temp, str(args.classes.resolve()), args.classpath])
    subprocess.run(['javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                    '-cp', cp, '-d', temp, str(ROOT / 'tools/OptionalClientUpdateTest.java')], check=True)
    subprocess.run(['java', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                    '-cp', cp, 'io.kamihama.magianative.OptionalClientUpdateTest'], check=True)
