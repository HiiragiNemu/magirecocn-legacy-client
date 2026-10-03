#!/usr/bin/env python3
"""Execute production route selection and verify every installer/UI entry is wired."""
import argparse, os, pathlib, subprocess, tempfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--classes',type=pathlib.Path,required=True)
p.add_argument('--classpath',required=True)
p.add_argument('--source-root',type=pathlib.Path,default=ROOT)
a=p.parse_args();src=a.source_root/'patch/src/main/java/io/kamihama/magianative'
read=lambda n:(src/(n+'.java')).read_text(encoding='utf-8')
route=read('CNDownloadRoute');ui=read('CNCNDownloadUI');install=read('CNDownloaderFix')
assert 'sv.routeView.setText(CNPackageReceipt.label(idx, st))' in ui, 'per-ZIP installed identity display missing'
assert 'CNDownloadRoute.currentFile(index)' in read('CNPackageReceipt'), 'actual route is not retained'
assert 'CNPackageReceipt.installed(name,' in install and 'CNPackageReceipt.installed(pkg.slot,' in read('CNHotUpdateCheck'), 'successful install receipts missing'
assert 'CNUpdateSources.downloadMirrors(expected)' in read('CNHotUpdate'), 'hot route eligibility not wired'
assert 'CNUpdateSources.downloadMirrors(pinnedHot)' in install, 'aria2 route eligibility not wired'
assert 'CNUpdateSources.downloadMirrors(hotMeta)' in install, 'first install route eligibility not wired'
assert install.count('CNHotUpdateCheck.metaForSlot(')==1, 'first install refetches a different identity after route selection'
assert read('CNChunkedDownload').count('instanceof RouteSink')==2, 'actual hash/byte segment route callbacks missing'
assert 'CNDownloadRoute.clearFile(i)' in ui, 'stale labels retained on reset'
print('PASS: first install / hot update / aria2 / actual block callback / UI row wiring',flush=True)
with tempfile.TemporaryDirectory(prefix='package-route-test-') as d:
    cp=os.pathsep.join([d,str(a.classes.resolve()),a.classpath])
    subprocess.run(['javac','-encoding','UTF-8','-source','8','-target','8','-cp',cp,'-d',d,str(ROOT/'tools/PackageRoutingTest.java')],check=True)
    subprocess.run(['java','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-cp',cp,'io.kamihama.magianative.PackageRoutingTest'],check=True)
