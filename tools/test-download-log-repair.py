from pathlib import Path
import argparse,subprocess,tempfile,shutil,os
p=argparse.ArgumentParser();p.add_argument('--source',type=Path,default=Path(__file__).resolve().parents[1]);p.add_argument('--classes',type=Path,required=True);p.add_argument('--expect-baseline',action='store_true');p.add_argument('--android-jar',type=Path,required=True);a=p.parse_args()
J=Path('patch/src/main/java/io/kamihama/magianative');D=Path(__file__).resolve().parent
def run(args,expected=0):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True,encoding='utf-8',errors='replace');print(r.stdout,r.stderr)
 assert r.returncode==expected, str(args)
selector=r'''package io.kamihama.magianative;
import java.util.*;
public class RepairSelectorTest {
 static CNHotUpdateValidate.VerMeta m(int v,char h,String source){char[] chars=new char[32];Arrays.fill(chars,h);return new CNHotUpdateValidate.VerMeta(v,100,new String(chars),source);}
 static void ok(boolean v,String s){if(!v)throw new AssertionError(s);}
 static CNHotUpdateValidate.VerMeta remember(String file,CNHotUpdateValidate.VerMeta m)throws Exception{
  try{return (CNHotUpdateValidate.VerMeta)CNUpdateSources.class.getDeclaredMethod("rememberHot",String.class,CNHotUpdateValidate.VerMeta.class).invoke(null,file,m);}
  catch(NoSuchMethodException baseline){return CNUpdateSources.highestHot(Arrays.asList(m));}
 }
 public static void main(String[]args)throws Exception{
  String gh=CNPublicResources.RELEASE_BASE,cf=CNEndpoints.EDGEONE_BASE;
  CNHotUpdateValidate.VerMeta newest=m(3300,'a',gh),old=m(3289,'b',cf);
  remember("scenario",newest);ok(remember("scenario",old).version==3300,"retry regressed from 3300 to 3289");
  ok(remember("scenario",null).version==3300,"metadata outage discarded confirmed identity");
  ok(remember("js",m(103,'c',cf)).version==103,"file identities contaminated");
  ok(remember("scenario",m(3301,'d',cf)).version==3301,"newer version not adopted");
  boolean conflict=false;try{remember("scenario",m(3301,'e',gh));}catch(java.lang.reflect.InvocationTargetException e){conflict=e.getCause() instanceof java.io.IOException;}
  ok(conflict,"same-version conflict accepted");
  java.lang.reflect.Method match=CNUpdateSources.class.getDeclaredMethod("matchingHotSources",CNHotUpdateValidate.VerMeta.class,List.class);
  CNHotUpdateValidate.VerMeta r=(CNHotUpdateValidate.VerMeta)match.invoke(null,newest,Arrays.asList(old,m(3300,'a',cf),m(3300,'f',"https://wrong.example/"),m(3301,'d',"https://future.example/")));
  ok(r.sourceBases.size()==2&&r.sourceBases.contains(cf)&&r.sourceBases.contains(gh),"late fallback admitted old or mismatching bytes");
  java.lang.reflect.Method workers=CNChunkedDownload.class.getDeclaredMethod("byteWorkerCount",int.class,int.class,int.class);
  ok(((Integer)workers.invoke(null,4,8,8)).intValue()==4,"old 8-segment resume ignored current 4-worker limit");
  ok(((Integer)workers.invoke(null,1,8,8)).intValue()==1,"single-thread resume lost");
  ok(((Integer)workers.invoke(null,4,8,2)).intValue()==2,"completed segments cause extra workers");
  System.out.println("PASS 9 production selector / retry / worker assertions");
 }
}'''
STUBS={
'android/content/Context.java':'''package android.content; public class Context { public java.io.File cache; public Context(java.io.File f){cache=f;} public java.io.File getCacheDir(){return cache;} }''',
'android/content/ContentProvider.java':'''package android.content; public abstract class ContentProvider { public Context context; public Context getContext(){return context;} public abstract boolean onCreate(); public abstract String getType(android.net.Uri u); public abstract android.os.ParcelFileDescriptor openFile(android.net.Uri u,String m)throws java.io.FileNotFoundException; public abstract android.database.Cursor query(android.net.Uri u,String[]p,String s,String[]sa,String o); public abstract android.net.Uri insert(android.net.Uri u,ContentValues v);public abstract int update(android.net.Uri u,ContentValues v,String s,String[]sa);public abstract int delete(android.net.Uri u,String s,String[]sa);}''',
'android/content/ContentValues.java':'package android.content; public class ContentValues {}',
'android/net/Uri.java':'''package android.net; public class Uri { public String value; private Uri(String s){value=s;} public static Uri parse(String s){return new Uri(s);} public static String encode(String s){return s;} public String getLastPathSegment(){return value.substring(value.lastIndexOf('/')+1);} }''',
'android/database/Cursor.java':'package android.database; public interface Cursor {}',
'android/database/MatrixCursor.java':'''package android.database; public class MatrixCursor implements Cursor {public String[]columns;public Object[]row;public MatrixCursor(String[]c,int n){columns=c;}public void addRow(Object[]r){row=r;}}''',
'android/provider/OpenableColumns.java':'package android.provider; public interface OpenableColumns {String DISPLAY_NAME="_display_name",SIZE="_size";}',
'android/os/ParcelFileDescriptor.java':'''package android.os; public class ParcelFileDescriptor {public static final int MODE_READ_ONLY=1;public static ParcelFileDescriptor open(java.io.File f,int mode){return new ParcelFileDescriptor();}}''',
'android/content/ClipData.java':'''package android.content;public class ClipData {public android.net.Uri uri;public static ClipData newRawUri(String s,android.net.Uri u){ClipData c=new ClipData();c.uri=u;return c;}}''',
'android/content/Intent.java':'''package android.content;public class Intent {public static final String ACTION_SEND="send",EXTRA_STREAM="stream",EXTRA_SUBJECT="subject";public static final int FLAG_GRANT_READ_URI_PERMISSION=1;public java.util.Map<String,Object>extras=new java.util.HashMap<>();public ClipData clip;public int flags;public Intent inner;public Intent(String a){} public Intent setType(String s){return this;}public Intent putExtra(String s,Object o){extras.put(s,o);return this;}public void setClipData(ClipData c){clip=c;}public Intent addFlags(int f){flags|=f;return this;}public static Intent createChooser(Intent i,String t){Intent o=new Intent("chooser");o.inner=i;return o;}}''',
'android/app/Activity.java':'''package android.app;public class Activity {public boolean destroyed;public boolean isFinishing(){return false;}public boolean isDestroyed(){return destroyed;}}''',
'io/kamihama/magianative/CNRestClientActivity.java':'''package io.kamihama.magianative;public class CNRestClientActivity {public static android.app.Activity current;public static android.app.Activity getCurrentActivity(){return current;}}''',
'io/kamihama/magianative/CNLog.java':'''package io.kamihama.magianative;public class CNLog {public static int launchSeq(){return 11;}public static void i(String t,String m){}public static void e(String t,String m,Throwable e){e.printStackTrace();}public static void w(String t,String m){}public static void w(String t,String m,Throwable e){e.printStackTrace();}}''',
'io/kamihama/magianative/CNIo.java':'''package io.kamihama.magianative;public class CNIo {public static void closeQuietly(java.io.Closeable c){try{if(c!=null)c.close();}catch(Exception e){}}}'''
}
share=r'''package io.kamihama.magianative;
import java.io.*;import java.nio.file.*;import android.database.*;import android.content.*;import android.net.*;import android.app.*;
public class RepairShareTest {
 static void ok(boolean v,String s){if(!v)throw new AssertionError(s);}
 public static void main(String[]args)throws Exception{
 File root=new File(args[0]),cache=new File(root,"cache"),logs=new File(root,"log");cache.mkdirs();logs.mkdirs();
 File f=new File(logs,"0011_20260927-191753.log");FileOutputStream os=new FileOutputStream(f);
 byte[] row="STARTUP NOISE 0123456789\n".getBytes("UTF-8");for(int i=0;i<120000;i++)os.write(row);os.write("LATEST DOWNLOAD FAILURE 保留末尾错误\n".getBytes("UTF-8"));os.close();
 File out=CNLogBundle.write(new Context(cache),logs.getAbsolutePath());ok(out!=null,"bundle missing");
 String text=new String(Files.readAllBytes(out.toPath()),"UTF-8");ok(text.contains("LATEST DOWNLOAD FAILURE 保留末尾错误"),"latest failure truncated from log bundle");
 CNLogShareProvider p=new CNLogShareProvider();p.context=new Context(cache);
 Uri u=Uri.parse("content://"+CNLogShareProvider.AUTHORITY+"/"+out.getName());
 MatrixCursor c=(MatrixCursor)p.query(u,null,null,null,null);ok(c!=null,"share provider returned null metadata");
 ok(c.columns[0].equals("_display_name")&&c.row[0].equals(out.getName())&&((Long)c.row[1])==out.length(),"wrong file metadata");
 c=(MatrixCursor)p.query(u,new String[]{"_size","unsupported","_display_name"},null,null,null);ok(c.row[1]==null&&c.row[2].equals(out.getName()),"projection mismatch");
 boolean denied=false;try{p.openFile(u,"w");}catch(FileNotFoundException e){denied=true;}ok(denied,"write mode accepted");p.openFile(u,"r");
 java.lang.reflect.Method chooser=CNLogShareProvider.class.getDeclaredMethod("chooserIntent",File.class);Intent intent=(Intent)chooser.invoke(null,out);
 ok(intent.clip!=null&&intent.inner.clip!=null&&intent.flags==1&&intent.inner.flags==1,"URI permissions absent on share/chooser");
 Activity captured=new Activity();captured.destroyed=true;Activity current=new Activity();CNRestClientActivity.current=current;
 java.lang.reflect.Method host=CNLogShareProvider.class.getDeclaredMethod("liveHost",Activity.class);ok(host.invoke(null,captured)==current,"stale Activity used after recreation");
 CNRestClientActivity.current=null;boolean stale=false;try{host.invoke(null,captured);}catch(java.lang.reflect.InvocationTargetException e){stale=true;}ok(stale,"destroyed Activity silently accepted");
 System.out.println("PASS 9 production log export / provider / intent / lifecycle assertions (host stubs, not sharesheet UI)");
 }
}'''
with tempfile.TemporaryDirectory(prefix='cn-repair-test-') as td:
 t=Path(td);(t/'RepairSelectorTest.java').write_text(selector,encoding='utf-8')
 android=a.android_jar
 cp=os.pathsep.join(map(str,[t,a.classes,android]));run(['javac','-encoding','UTF-8','-cp',cp,'-d',t,t/'RepairSelectorTest.java'])
 run(['java','-cp',cp,'io.kamihama.magianative.RepairSelectorTest'],1 if a.expect_baseline else 0)
 sh=t/'share';sh.mkdir()
 for name,s in STUBS.items():q=sh/name;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(s,encoding='utf-8')
 for name in ['CNLogBundle','CNLogShareProvider']:
  shutil.copyfile(a.source/J/(name+'.java'),sh/'io/kamihama/magianative'/(name+'.java'))
 (sh/'RepairShareTest.java').write_text(share,encoding='utf-8')
 run(['javac','-encoding','UTF-8','-d',sh]+list(sh.rglob('*.java')))
 run(['java','-cp',sh,'io.kamihama.magianative.RepairShareTest',sh/'fixture'],1 if a.expect_baseline else 0)
if a.expect_baseline:print('EXPECTED FAIL: baseline retries old version and exports startup rather than latest failure')
