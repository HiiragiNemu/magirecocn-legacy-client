#!/usr/bin/env python3
"""Run the production cumulative ZIP transaction with isolated baseline/overlay fixtures."""
import argparse, hashlib, json, os, subprocess, urllib.request, zipfile
from pathlib import Path

R = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--classes', required=True, type=Path)
p.add_argument('--classpath', required=True)
p.add_argument('--out', type=Path, default=R/'.build/js-delta-tests')
a = p.parse_args(); d = a.out.resolve(); d.mkdir(parents=True, exist_ok=True)
jar = d/'json.jar'
expected = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not jar.exists():
    jar.write_bytes(urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar', timeout=30).read())
assert hashlib.sha256(jar.read_bytes()).hexdigest() == expected
member = 'madomagi/resource/image_native/scene/top/toppage_bg_020.png'
base = d/'base.zip'; delta = d/'delta.zip'
with zipfile.ZipFile(base, 'w') as z:
    z.writestr(member, b'old-image')
    z.writestr(member.replace('.png','.plist'), b'unchanged-atlas')
    z.writestr('magica/fonts/music-symbol-test.ttf', b'unchanged-font')
data = b'user-new-image'
meta = dict(schema='magireco-cn-js-delta/v1', version=1, base_js_version=103,
            base_js_sha256='bffc1acc31f65c24cc2f9042a492e4f18c5ba9e41806ed725e1e0730c60a7a9d',
            entries=[dict(path=member, size=len(data), sha256=hashlib.sha256(data).hexdigest())])
with zipfile.ZipFile(delta, 'w') as z:
    z.writestr(member, data); z.writestr('magica/.cn_js_delta.json', json.dumps(meta))
out = d/'classes'; out.mkdir(exist_ok=True)
cp = os.pathsep.join([str(out),str(jar),str(a.classes.resolve()),a.classpath])
records = []
def run(cmd):
    cmd = list(map(str,cmd)); r = subprocess.run(cmd,capture_output=True,text=True,encoding='utf8',errors='replace',cwd=R)
    records.append(dict(command=cmd,exit=r.returncode,stdout=r.stdout,stderr=r.stderr))
    (d/'verification.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf8')
    print(r.stdout,r.stderr,flush=True)
    if r.returncode: raise SystemExit(r.returncode)
run(['javac','-encoding','UTF-8','-source','8','-target','8','-cp',cp,'-d',out,
     R/'tools/teststubs/android/system/Os.java',R/'tools/JsDeltaInstallTest.java'])
run(['java','-Dstdout.encoding=UTF-8','-cp',cp,'io.kamihama.magianative.JsDeltaInstallTest',base,delta,d/'installed'])
# Pin real scheduling callsites, not just the display order.
j = R/'patch/src/main/java/io/kamihama/magianative'
installer = (j/'CNDownloaderFix.java').read_text(encoding='utf8')
hot = (j/'CNHotUpdateCheck.java').read_text(encoding='utf8')
assert 'i < HOT_SLOT_DELTA' in installer
assert installer.index('futures.get(i).get()') < installer.index('new ArchiveTask(HOT_SLOT_DELTA).call()')
assert 'if (pkg.slot != CNDownloaderFix.HOT_SLOT_DELTA) dlPool.execute(task);' in hot
assert '((java.util.concurrent.FutureTask<Boolean>) e.getValue()).run();' in hot
assert 'CNJsDelta.apply(tmp' in hot and 'CNJsDelta.reapplyCached' in hot
print('PASS: first-install and startup defer delta download until previous packages finish; manual reinstall restores cumulative layer')
