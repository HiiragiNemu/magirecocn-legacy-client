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
notice=ui.split('private static boolean slowTransferNoticeOffered;',1)[1].split('/** {@link #askSlowNetwork}',1)[0]
for forbidden in ['.await(','.interrupt(','.request(','.resetFileProgress(','.delete(','.setDownloadMode(', 'COLOR_DIM']:
    assert forbidden not in notice, forbidden
assert notice.count('new DismissTransferNotice(panel)')==2
assert '"关闭"' in notice and '"继续下载"' in notice
print('PASS: all three transfer paths are advisory; close/continue dismiss only; no full-screen dim/input trap')
