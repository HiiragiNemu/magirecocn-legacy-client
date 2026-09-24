#!/usr/bin/env python3
"""Execute the actual typed-name wrapper with a recording original-function sink."""
from pathlib import Path
import argparse, os, subprocess

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--source', type=Path, default=ROOT/'magia-native/src/MagiaLegacy.cpp')
p.add_argument('--out', type=Path, default=ROOT/'.build/battle-name-test')
p.add_argument('--cxx', default=os.environ.get('CXX', 'c++'))
a = p.parse_args()
text = a.source.read_text('utf8')
begin = '// BEGIN_TYPED_BATTLE_SKILL_NAMES'
end = '// END_TYPED_BATTLE_SKILL_NAMES'
if begin in text:
    assert text.count(begin) == text.count(end) == 1
    code = text.split(begin, 1)[1].split(end, 1)[0]
    assert '(void*)artUnitSetParamNew, (void**)&artUnitSetParamOld' in text
    assert text.count('H("_ZN9QbArtUnit8setParamEN5QbArt4TypeEiiiiiPKcS3_NS0_11MemoriaTypeENS0_14MemoriaDisplayE"') == 1
else:
    # Baseline has no typed hook: replay the original parameter forwarding.
    code = r'''
using ArtUnitSetParamFn = void (*)(void*,int,int,int,int,int,int,const char*,const char*,int,int);
static ArtUnitSetParamFn artUnitSetParamOld=nullptr;
static void artUnitSetParamNew(void* self,int type,int id,int icon,int level,int cost,int voice,
 const char* name,const char* description,int memoriaType,int displayType){
 artUnitSetParamOld(self,type,id,icon,level,cost,voice,name,description,memoriaType,displayType);
}
'''
harness = r'''
#include <cstring>
#include <iostream>
#include <string>
static bool g_dbgNoI18nLabel=false;
''' + code + r'''
struct Seen {void* self;int type,id,icon,level,cost,voice,memoriaType,displayType;const char* name;const char* description;};
static Seen seen;
static int calls=0;
static void original(void* self,int type,int id,int icon,int level,int cost,int voice,
 const char* name,const char* description,int memoriaType,int displayType){
 seen={self,type,id,icon,level,cost,voice,memoriaType,displayType,name,description};++calls;
}
int main(){
 artUnitSetParamOld=original;
 int cases=0,fail=0;
 const char* jp="ファスト・マナアップ";
 const char* desc="MP自動回復[Ⅰ]";
 int dummy;
 auto check=[&](int type,int id,int mt,int display,const char* input,const char* want,bool samePointer){
   int before=calls;
   artUnitSetParamNew(&dummy,type,id,-31,17,444,512,input,desc,mt,display);
   bool sameName=want ? seen.name && std::strcmp(seen.name,want)==0 : seen.name==nullptr;
   bool ok=sameName && (!samePointer || seen.name==input) && seen.self==&dummy &&
     seen.type==type && seen.id==id && seen.icon==-31 && seen.level==17 && seen.cost==444 &&
     seen.voice==512 && seen.description==desc && seen.memoriaType==mt && seen.displayType==display && calls==before+1;
   ++cases;
   if(!ok){++fail;std::cout<<"FAIL type="<<type<<" id="<<id<<" memoriaType="<<mt<<" display="<<display<<"\n";}
 };
 check(3,115201,1,1,jp,"快速魔法提升",false);
 check(3,1144110,1,3,jp,"魔力骤升",false);
 const int ids[]={115200,115201,1144110,1018113,0,99999999};
 for(int type=-1;type<8;++type)for(int mt=-1;mt<5;++mt)for(int d=-1;d<5;++d)for(int id:ids){
   bool match=type==3 && mt==1 && ((id==115201&&d==1)||(id==1144110&&d==3));
   if(!match)check(type,id,mt,d,jp,jp,true);
 }
 for(const char* text:{"ファスト・マナアップ[Ⅰ]","ファスト・マナアップ ","別の技","快速魔法提升","魔力骤升","",static_cast<const char*>(nullptr)}){
   check(3,115201,1,1,text,text,true);check(3,1144110,1,3,text,text,true);
 }
 g_dbgNoI18nLabel=true;
 check(3,115201,1,1,jp,jp,true);check(3,1144110,1,3,jp,jp,true);
 std::cout<<"TYPED_BATTLE_NAME cases="<<cases<<" failures="<<fail<<" numeric_changes=0\n";
 return fail?1:0;
}
'''
a.out.mkdir(parents=True, exist_ok=True)
source = a.out/'battle-name.cpp'
source.write_text(harness, encoding='utf8')
exe = a.out/('battle-name.exe' if os.name == 'nt' else 'battle-name')
cmd = [a.cxx, '-std=c++17', str(source), '-o', str(exe)]
run = subprocess.run(cmd, capture_output=True, encoding='utf8', errors='replace')
print(run.stdout, run.stderr)
if run.returncode: raise SystemExit(run.returncode)
run = subprocess.run([str(exe)], capture_output=True, encoding='utf8', errors='replace')
print(run.stdout, run.stderr)
raise SystemExit(run.returncode)
