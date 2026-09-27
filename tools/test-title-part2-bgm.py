"""Part II title changes only its cue, not global audio or other title scenes."""
from pathlib import Path
import hashlib,json,sys,zipfile
root=Path(__file__).resolve().parents[1]
rel='assets/package/top/toppage_bg_02.ExportJson'
data=(root/'baseline/replace'/rel).read_bytes()
op=next(x for x in json.loads((root/'baseline/baseline.json').read_text(encoding='utf-8'))['ops'] if x['path']==rel)
assert data.count(b'bgm_bgm00_system02')==1
assert b'bgm_bgm00_system01' not in data
assert hashlib.sha256(data).hexdigest()==op['post']
original=data.replace(b'bgm_bgm00_system02',b'bgm_bgm00_system01')
assert hashlib.sha256(original).hexdigest()==op['pre'],'only one cue replacement is allowed'
json.loads(data)
if len(sys.argv)>1:
 with zipfile.ZipFile(sys.argv[1]) as apk:
  assert apk.read(rel)==data
  for n in ('01','02'):
   assert len(apk.read('assets/resource/sound_native/bgm/bgm00_system'+n+'_hca.hca'))>1000000
print('PASS Part II title cue bgm00_system02; exact one-cue change; original cue recoverable'+('; built APK verified' if len(sys.argv)>1 else ''))
