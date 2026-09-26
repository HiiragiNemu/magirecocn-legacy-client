#!/usr/bin/env python3
"""Verify final APK font bytes and licenses without weakening the existing font guard."""
import argparse,hashlib,json,pathlib,subprocess,sys,tempfile,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('apk',type=pathlib.Path);a=p.parse_args()
reviewed=ROOT/'.reviewed-fonts/magica'
manifest=json.loads((reviewed/'font-licenses/font194-manifest.json').read_text(encoding='utf8'))
assert manifest['version']=='1.0.194'
old={'1fe1fdc28cc7347e26099bf2fb54b85370617acd91efc6e63903cf3c33a62541','c69dea79d5b33864bbda85645641d5208790f8c394a291992e898a3753dd71d3'}
record={'version':'1.0.194','fonts':{},'device_visual_acceptance':'pending maintainer','font_routing_changed':False,'download_priority_changed':False}
with zipfile.ZipFile(a.apk) as z,tempfile.TemporaryDirectory() as tmp:
    names=z.namelist();assert len(names)==len(set(names)),'Duplicate APK entries'
    tree=pathlib.Path(tmp)
    for name in names:
        if name.startswith('assets/fonts/') and not name.endswith('/'):
            data=z.read(name);path=tree/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
        if name.lower().endswith(('.ttf','.otf','.woff','.woff2')):
            data=z.read(name);h=hashlib.sha256(data).hexdigest()
            assert h not in old,'Old proprietary font found: '+name
            for word in ['Tensentype JiaLiDaYuan','Tensentype ZhiHei','腾祥嘉丽大圆','腾祥智黑']:
                assert word.encode('utf-16-be') not in data and word.encode('utf8') not in data,'Old family metadata: '+name
            record['fonts'][name]={'sha256':h,'size':len(data)}
    for name,row in manifest['fonts'].items():
        data=z.read('assets/fonts/'+name)
        assert len(data)==row['size'] and hashlib.sha256(data).hexdigest()==row['sha256'],name
    licenses=list((reviewed/'font-licenses').iterdir());assert len(licenses)>=8
    for f in licenses:
        if f.is_file():assert z.read('assets/font-licenses/'+f.name)==f.read_bytes(),'Missing/mismatched license: '+f.name
    subprocess.run([sys.executable,str(ROOT/'tools/check-fonts.py'),'--tree',str(tree)],check=True,cwd=ROOT)
    record['all_registered_fonts_passed']=True
    record['required_supplemental_characters_per_font']=len(next(iter(manifest['fonts'].values()))['required_coverage'])
    record['license_files_verified']=len(licenses)
(ROOT/'.build/font194-apk-verification.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf8')
print('PASS_FINAL_APK_FONT_REPLACEMENT',json.dumps(record))
