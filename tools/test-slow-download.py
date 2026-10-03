#!/usr/bin/env python3
"""Advice contract. Production monitor behavior is tested by SlowDownloadMonitorTest."""
from pathlib import Path
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'patch/src/main/java/io/kamihama/magianative'
with tempfile.TemporaryDirectory(prefix='slow-download-') as out:
    subprocess.run(['javac','-encoding','UTF-8','-source','8','-target','8','-d',out,
                    str(JAVA/'CNDownloadSlowNotice.java'),str(ROOT/'tools/SlowDownloadNoticeTest.java')],check=True)
    subprocess.run(['java','-cp',out,'SlowDownloadNoticeTest'],check=True)
for name in ['CNDownloaderFix.java','CNHotUpdate.java','CNChunkedDownload.java']:
    source=(JAVA/name).read_text(encoding='utf-8')
    assert 'SLOW_FAIL_NS' not in source and '镜像速度过慢' not in source and '线路过慢：' not in source
    assert 'slowNotice.observe(' in source
    assert 'CNDownloadRestart.cancelled(' in source if name!='CNChunkedDownload.java' else 'sink.isCancelled()' in source
ui=(JAVA/'CNCNDownloadUI.java').read_text(encoding='utf-8')
def body(signature):
    start=ui.index(signature); start=ui.index('{',start); depth=1; end=start+1
    while depth:
        if ui[end]=='{': depth+=1
        elif ui[end]=='}': depth-=1
        end+=1
    return ui[start:end]
for signature in ['public static void offerSlowTransferNotice()', 'public static int askSlowNetwork(',
                  'public static int askDownloadFallback(final Activity act, final String fileName,']:
    # Overloads also delegate to the silent implementation; no modal construction remains here.
    text=body(signature)
    for forbidden in ['.await(', 'AlertDialog', 'new Slow', 'new DownloadFallback', 'runOnUiThread(', '.resetFileProgress(']:
        assert forbidden not in text, (signature,forbidden)
assert 'SlowTransferNoticeBuild' not in ui
assert 'return SLOW_SKIP;' in body('public static int askSlowNetwork(')
assert '下载自动重试已结束，保留进度' in ui
hot=(JAVA/'CNHotUpdateCheck.java').read_text('utf8')
assert 'askVersionSlow' not in hot and 'if (query.isDone()) metas[i] = query.get();' in hot
print('PASS: slow transfers stay uninterrupted; metadata budget is bounded; terminal failures remain inline; no slow-choice modal')
