"""Exercise real source sorting and per-file rounds; no network or Android device."""
from pathlib import Path
import importlib.util, tempfile, subprocess

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'patch/src/main/java/io/kamihama/magianative'
spec=importlib.util.spec_from_file_location('fixtures',ROOT/'tools/test-public-resource-fallback.py')
fixtures=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixtures)
stubs=dict(fixtures.STUBS);stubs.pop('CNDownloadRoute')
stubs['CNEndpoints']=stubs['CNEndpoints'].replace(' static final String MIRRORS_URL=',
    ' static final String PRIMARY_BASE_OVERRIDE=EDGEONE_BASE, SECONDARY_BASE_OVERRIDE=ESA_BASE;\n static final String MIRRORS_URL=')
stubs['CNMirrors']=stubs['CNMirrors'].replace('  final String name,base;', '  volatile long cooldownUntilNs=0;\n  final String name,base;')
harness=r'''package io.kamihama.magianative;
import java.util.*;
public final class PrivateSourceRoundTest {
 static int count;
 static void ok(boolean yes,String why){count++;if(!yes)throw new AssertionError(why);}
 static CNMirrors.Mirror m(String base){return new CNMirrors.Mirror(base,base,100,4,true);}
 public static void main(String[] args)throws Exception{
  String old=CNPublicResources.LEGACY_RELEASE_BASE,pub=CNPublicResources.RELEASE_BASE;
  List<CNMirrors.Mirror> list=new ArrayList<CNMirrors.Mirror>(Arrays.asList(m(old),m(pub),m(CNEndpoints.EDGEONE_BASE),m(CNEndpoints.LEGACY_ESA_BASE),m(CNEndpoints.LEGACY_EDGEONE_BASE)));
  CNMirrors.list=list;
  CNDownloadRoute.sortSources(list);
  String[] wanted={CNEndpoints.LEGACY_EDGEONE_BASE,CNEndpoints.LEGACY_ESA_BASE,CNEndpoints.EDGEONE_BASE,pub,old};
  for(int i=0;i<5;i++)ok(list.get(i).base.equals(wanted[i]),"tier "+i);
  CNDownloadRoute.Round round=new CNDownloadRoute.Round();
  for(int i=0;i<5;i++)ok(round.next(list).mirror.base.equals(wanted[i]),"failed route must not be silently repeated "+i);
  boolean exhausted=false;try{round.next(list);}catch(IllegalStateException e){exhausted=true;}
  ok(exhausted,"sixth route launch must stop, not recreate the budget");
  round.restartByUser();ok(round.next(list).mirror.base.equals(wanted[0]),"explicit player retry starts a round");
  CNDownloadRoute.select(old);round.restartByUser();ok(round.next(list).mirror.base.equals(wanted[0]),"legacy never precedes healthy public sources");
  CNDownloadRoute.select(pub);round.restartByUser();ok(round.next(list).mirror.base.equals(pub),"manual public choice retained");
  CNDownloadRoute.select("");
  for(CNMirrors.Mirror m:list)m.cooldownUntilNs=Long.MAX_VALUE;
  CNDownloadRoute.Round cooled=new CNDownloadRoute.Round();
  for(int i=0;i<5;i++)ok(cooled.next(list).mirror.base.equals(wanted[i]),"another file cooldown must not veto a bounded attempt");
  exhausted=false;try{cooled.next(list);}catch(IllegalStateException e){exhausted=true;}
  ok(exhausted,"cooldown fallback never loops a source");
  for(CNMirrors.Mirror m:list)ok(m.cooldownUntilNs==Long.MAX_VALUE,"global health was not reset");
  final List<String> visited=Collections.synchronizedList(new ArrayList<String>());
  CNUpdateSources.collect(Arrays.asList(pub+"version_js.json",old+"version_js.json"),new CNUpdateSources.Loader<String>(){
   public String load(String url){visited.add(url);return "good";}
  },3000);
  ok(visited.size()==1&&!CNPublicResources.legacyUrl(visited.get(0)),"no legacy query if public metadata works");
  visited.clear();
  List<CNUpdateSources.Reply<String>> answers=CNUpdateSources.collect(Arrays.asList(pub+"version_js.json",old+"version_js.json"),new CNUpdateSources.Loader<String>(){
   public String load(String url)throws Exception{visited.add(url);if(!CNPublicResources.legacyUrl(url))throw new java.io.IOException("public unavailable");return "reopened";}
  },3000);
  ok(visited.size()==2&&answers.get(1).value.equals("reopened"),"reopened emergency source works next round");
  visited.clear();
  CNUpdateSources.collect(Arrays.asList(pub+"version_js.json",old+"version_js.json"),new CNUpdateSources.Loader<String>(){
   public String load(String url)throws Exception{visited.add(url);throw new java.io.IOException("404");}
  },3000);
  ok(visited.size()==2,"both metadata failures terminate after one attempt each");
  System.out.println("PASS "+count+" real per-file round and discovery assertions");
 }
}'''
with tempfile.TemporaryDirectory(prefix='private-source-round-') as temp:
    out=Path(temp)
    for name,body in stubs.items(): (out/(name+'.java')).write_text(fixtures.PACKAGE+body,encoding='utf-8')
    for name in ('CNDownloadRoute','CNPublicResources','CNUpdateSources'):
        (out/(name+'.java')).write_bytes((SOURCE/(name+'.java')).read_bytes())
    (out/'PrivateSourceRoundTest.java').write_text(harness,encoding='utf-8')
    subprocess.run(['javac','--release','8','-encoding','UTF-8','-d',str(out)]+[str(p) for p in out.glob('*.java')],check=True,timeout=60)
    subprocess.run(['java','-cp',str(out),'io.kamihama.magianative.PrivateSourceRoundTest'],check=True,timeout=30)
