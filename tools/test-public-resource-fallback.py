#!/usr/bin/env python3
"""Host-JVM tests for the real update-source selector with unavailable old routes.

Network and Android collaborators are minimal stubs, not a device/integration test.
No credential or Android SDK is required. Run from any working directory.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'patch/src/main/java/io/kamihama/magianative'
PACKAGE = 'package io.kamihama.magianative;\n'
STUBS = {
'CNEndpoints': '''final class CNEndpoints {
 static final String EDGEONE_BASE="https://old-pages.example/", ESA_BASE="https://old-github.example/";
 static final String LEGACY_EDGEONE_BASE="https://edgeone.example/", LEGACY_ESA_BASE="https://esa.example/";
 static final String MIRRORS_URL="https://old-config.example/legacy/config.json", API_BASE="https://old-api.example/";
}''',
'CNMirrors': '''final class CNMirrors {
 static java.util.List<Mirror> list = new java.util.ArrayList<Mirror>();
 static java.util.List<Mirror> selectable(){return list;} static java.util.List<Mirror> healthy(){return list;}
 static final class Mirror {
  final String name,base; final boolean enabled;
  Mirror(String n,String b,int w,int c,boolean e){name=n;base=b;enabled=e;}
  String urlFor(String n){return base+n;}
 }
}''',
'CNDownloadRoute': '''final class CNDownloadRoute {
 static void sortSources(java.util.List<CNMirrors.Mirror> rows){}
 static boolean accelerated(CNMirrors.Mirror m){return m.base.contains("edgeone.example");}
}''',
'CNHotUpdateValidate': '''final class CNHotUpdateValidate {
 static final class VerMeta {
  final int version; final long size; final String md5,sourceBase; final java.util.List<String> sourceBases;
  VerMeta(int v,long s,String m,String b,java.util.List<String> bs){version=v;size=s;md5=m;sourceBase=b;sourceBases=bs;}
 }
}''',
'CNVersionCheck': '''final class CNVersionCheck {
 static int compareVersion(String a,String b){
  String[] x=a.split("\\\\."),y=b.split("\\\\.");
  for(int i=0;i<Math.max(x.length,y.length);i++){
   java.math.BigInteger p=new java.math.BigInteger(i<x.length?x[i]:"0"),q=new java.math.BigInteger(i<y.length?y[i]:"0");
   int c=p.compareTo(q);if(c!=0)return c;
  }return 0;
 }
}''',
}
HARNESS = r'''import java.util.*;
public final class PublicResourceFallbackTest {
 static int count=0;
 static void ok(boolean value,String why){count++;if(!value)throw new AssertionError(why);}
 static boolean has(List<CNMirrors.Mirror> rows,String base){for(CNMirrors.Mirror m:rows)if(base.equals(m.base))return true;return false;}
 static String letters(char c,int n){char[] a=new char[n];Arrays.fill(a,c);return new String(a);}
 static CNHotUpdateValidate.VerMeta hot(int v,long size,char hash,String base){return new CNHotUpdateValidate.VerMeta(v,size,letters(hash,32),base,Arrays.asList(base));}
 public static void main(String[] args)throws Exception{
  final String pub=CNPublicResources.RELEASE_BASE, old=CNEndpoints.EDGEONE_BASE;
  List<CNMirrors.Mirror> routes=CNUpdateSources.mirrors(null);
  ok(has(routes,pub),"public publisher missing");
  ok(has(routes,old)&&has(routes,CNEndpoints.ESA_BASE),"old publishers removed");
  ok(!has(routes,CNEndpoints.LEGACY_EDGEONE_BASE)&&!has(routes,CNEndpoints.LEGACY_ESA_BASE),"retired hostnames reintroduced");
  ok(CNUpdateSources.mirrors(pub).get(0).base.equals(pub),"preferred identity publisher not first");
  ok(CNUpdateSources.clientUrls().contains(pub+CNUpdateSources.CLIENT_META),"public APK discovery missing");
  ok(CNUpdateSources.clientUrls().contains(CNPublicResources.CONFIG_URL),"public config discovery missing");
  ok(CNUpdateSources.clientUrls().contains(CNEndpoints.MIRRORS_URL),"old config removed");
  ok(has(CNUpdateSources.downloadMirrors(null),pub),"base package fallback lost when old config unavailable");
  CNMirrors.list.add(new CNMirrors.Mirror("CDN",CNEndpoints.LEGACY_EDGEONE_BASE,140,8,true));
  ok(CNUpdateSources.downloadMirrors(null).get(0).base.equals(CNEndpoints.LEGACY_EDGEONE_BASE),"CDN acceleration precedence changed");
  CNHotUpdateValidate.VerMeta expected=hot(102,100,'a',old);
  ok(!has(CNUpdateSources.downloadMirrors(expected),pub),"unverified public hot identity admitted");
  expected=CNUpdateSources.highestHot(Arrays.asList(expected,hot(102,100,'a',pub)));
  ok(has(CNUpdateSources.downloadMirrors(expected),pub),"same identity public hot fallback missing");
  ok(expected.sourceBases.size()==2,"same-byte identity sources did not merge");
  CNHotUpdateValidate.VerMeta newest=CNUpdateSources.highestHot(Arrays.asList(hot(101,99,'b',old),hot(102,100,'a',pub)));
  ok(newest.version==102&&newest.sourceBases.size()==1&&newest.sourceBases.get(0).equals(pub),"old hot identity mixed with new");
  boolean conflict=false;
  try{CNUpdateSources.highestHot(Arrays.asList(hot(102,100,'a',pub),hot(102,100,'b',old)));}catch(java.io.IOException e){conflict=true;}
  ok(conflict,"same-version hot conflict was not rejected");
  List<CNUpdateSources.ClientIdentity> ids=Arrays.asList(new CNUpdateSources.ClientIdentity("1.0.193",100,letters('a',64)),new CNUpdateSources.ClientIdentity("1.0.194",101,letters('b',64)));
  ok(CNUpdateSources.highestClientIndex(ids)==1,"highest client selection changed");
  conflict=false;
  try{CNUpdateSources.highestClientIndex(Arrays.asList(ids.get(1),new CNUpdateSources.ClientIdentity("1.0.194",101,letters('c',64))));}catch(java.io.IOException e){conflict=true;}
  ok(conflict,"conflicting client bytes accepted");
  List<CNUpdateSources.Reply<String>> replies=CNUpdateSources.collect(CNUpdateSources.clientUrls(),new CNUpdateSources.Loader<String>(){
   public String load(String url)throws Exception{
    if(url.startsWith(pub)||url.equals(CNPublicResources.CONFIG_URL))return "available";
    throw new java.io.IOException("HTTP 404: simulated private old source");
   }
  },3000);
  int successes=0,failures=0;for(CNUpdateSources.Reply<String> r:replies){if(r.error==null)successes++;else failures++;}
  ok(successes==2&&failures>0,"old source failures prevented independent public discovery");
  CNMirrors.list.add(new CNMirrors.Mirror("duplicate",pub,70,4,true));
  int n=0;for(CNMirrors.Mirror m:CNUpdateSources.mirrors(null))if(pub.equals(m.base))n++;
  ok(n==1,"public source duplicated by remote config");
  System.out.println("PASS: "+count+" public resource selector assertions (host JVM; not device certification)");
 }
}
'''

def main():
    for tool in ('javac', 'java'):
        if not shutil.which(tool):
            raise SystemExit('Required JDK tool not found: '+tool)
    with tempfile.TemporaryDirectory(prefix='public-resource-test-') as directory:
        root=Path(directory)
        for name,body in STUBS.items():
            (root/(name+'.java')).write_text(PACKAGE+body,encoding='utf8')
        (root/'PublicResourceFallbackTest.java').write_text(PACKAGE+HARNESS,encoding='utf8')
        for name in ('CNPublicResources.java','CNUpdateSources.java'):
            content=(SOURCE/name).read_text(encoding='utf8')
            if name=='CNPublicResources.java':
                assert 'static final boolean ENABLED = true;' in content
            (root/name).write_text(content,encoding='utf8')
        subprocess.run(['javac','--release','8','-encoding','UTF-8','-d',str(root)]+[str(p) for p in root.glob('*.java')],check=True,timeout=60)
        subprocess.run(['java','-cp',str(root),'io.kamihama.magianative.PublicResourceFallbackTest'],check=True,timeout=30)

if __name__=='__main__':main()
