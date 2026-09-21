package io.kamihama.magianative;

import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

/** Real HTTP, production downloader/route scopes and real persisted resume state. */
public class ManualRouteIntegrationTest {
    static String a,b,md5;
    static File out,fixture;
    static long size;
    static void check(boolean ok,String text) { if(!ok)throw new AssertionError(text);System.out.println("PASS "+text); }
    static final class Sink implements CNChunkedDownload.SlowSink {
        long first=-1,last; boolean switched; String switchTo;
        Sink(String to){switchTo=to;}
        public void onTotal(long n){}
        public void onProgress(long n,long total){
            if(first<0)first=n;last=n;
            if(!switched && switchTo!=null && n>=65536L){switched=true;CNDownloadRoute.select(switchTo);}
        }
        public void onSpeed(float n){}
        public void onSlowTransfer(){}
        public boolean isCancelled(){return false;}
    }
    static CNHotUpdateValidate.VerMeta meta(int version){return new CNHotUpdateValidate.VerMeta(version,size,md5);}
    static CNChunkedDownload.Result fetch(File target,Sink sink,CNHotUpdateValidate.VerMeta meta,
                                         CNChunkedDownload.ChunkHashes hashes)throws Exception{
        CNDownloadRoute.Plan plan=CNDownloadRoute.plan(CNMirrors.healthy(),1);
        CNDownloadRoute.enter(plan);
        try{
            CNDownloadRoute.check();String url=plan.mirror.urlFor("fixture.zip");
            return CNChunkedDownload.download(url,target,2,true,CNChunkedDownload.probe(url,true),
                sink,plan.mirror,"fixture.zip",true,hashes,meta);
        }finally{CNDownloadRoute.leave();}
    }
    static Sink interrupted(File file,CNHotUpdateValidate.VerMeta meta,CNChunkedDownload.ChunkHashes hashes)throws Exception{
        CNDownloadRoute.select(a);Sink sink=new Sink(b);boolean changed=false;
        int generation=CNDownloadRestart.generation(0);
        try{fetch(file,sink,meta,hashes);}catch(CNDownloadRoute.Changed expected){changed=true;}
        check(changed && sink.last>0 && sink.last<size,"active download exits through route-change, bytes="+sink.last);
        check(CNDownloadRestart.generation(0)==generation && !Thread.currentThread().isInterrupted(),"route change does not request destructive restart or interrupt owner");
        return sink;
    }
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte v:bytes)s.append(String.format("%02x",v&255));return s.toString();}
    static CNChunkedDownload.ChunkHashes hashes()throws Exception{
        List<String> list=new ArrayList<String>();FileInputStream in=new FileInputStream(fixture);
        byte[] block=new byte[65536];int n;
        try{while((n=in.read(block))>0){MessageDigest md=MessageDigest.getInstance("MD5");md.update(block,0,n);list.add(hex(md.digest()));}}
        finally{in.close();}return new CNChunkedDownload.ChunkHashes(65536,size,list);
    }
    static void sameIdentity()throws Exception{
        File target=new File(out,"same-version.zip");interrupted(target,meta(1),null);
        Sink next=new Sink(null);fetch(target,next,meta(1),null);
        check(next.first>0,"same version resumes across different URLs and ETags, retained="+next.first);
        check(CNHotUpdateValidate.verifyZip(target,meta(1))==null,"resumed dynamic ZIP passes full size, MD5 and ZIP validation");
    }
    static void changedIdentity()throws Exception{
        File target=new File(out,"different-version.zip");interrupted(target,meta(1),null);
        Sink next=new Sink(null);fetch(target,next,meta(2),null);
        check(next.first==0,"different version never accepts prior version partial data");
        check(CNHotUpdateValidate.verifyZip(target,meta(2))==null,"new version validated before use");
    }
    static void verifiedBlocks()throws Exception{
        File target=new File(out,"verified-base.zip");CNChunkedDownload.ChunkHashes hashes=hashes();
        interrupted(target,null,hashes);Sink next=new Sink(null);fetch(target,next,null,hashes);
        check(next.first>=65536,"verified base blocks survive manual mirror switch, retained="+next.first);
        check(CNHotUpdateValidate.verifyZip(target,meta(1))==null,"base bytes remain correct after resumed block download");
    }
    static void singleIdentity()throws Exception{
        Method prepare=CNHotUpdate.class.getDeclaredMethod("prepareSingleIdentity",File.class,CNHotUpdateValidate.VerMeta.class);prepare.setAccessible(true);
        File part=new File(out,"single.part");prepare.invoke(null,part,meta(1));
        FileOutputStream stream=new FileOutputStream(part);stream.write(new byte[4096]);stream.close();
        prepare.invoke(null,part,meta(1));check(part.length()==4096,"single stream same identity keeps partial bytes");
        prepare.invoke(null,part,meta(2));check(!part.exists(),"single stream rejects different version identity");
    }
    static void choices()throws Exception{
        check(!CNDownloadRoute.select("https://not-configured.invalid/"),"only configured enabled mirrors are selectable");
        CNDownloadRoute.select(a);CNDownloadRoute.Plan old=CNDownloadRoute.plan(CNMirrors.healthy(),1);
        CNDownloadRoute.select(b);CNDownloadRoute.enter(old);
        check(CNDownloadRoute.changed(),"selection race between plan and enter is observed");
        CNDownloadRoute.leave();check(!CNDownloadRoute.changed(),"route generation cannot interrupt install/extraction outside transfer scope");
        Class<?> type=Class.forName("io.kamihama.magianative.CNCNDownloadUI$Aria2Answer");
        Constructor<?> c=type.getDeclaredConstructor();c.setAccessible(true);Object answer=c.newInstance();
        Method choose=type.getDeclaredMethod("choose",int.class);choose.setAccessible(true);
        check((Boolean)choose.invoke(answer,6),"close resolves the failed-download question");
        check(!(Boolean)choose.invoke(answer,2),"late retry click after close is ignored");
        Field value=type.getDeclaredField("choice");value.setAccessible(true);
        check(((int[])value.get(answer))[0]==6,"closed answer never turns into default retry");
    }
    static final class SwitchAfter implements Runnable {
        public void run(){try{Thread.sleep(800);CNDownloadRoute.select(b);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
    static void fullHotSingle()throws Exception{
        Field loaded=CNMirrors.class.getDeclaredField("loaded");loaded.setAccessible(true);loaded.setBoolean(null,true);
        CNDownloadMode.setPlayerChoice(true);
        CNDownloadRoute.select(a);File target=new File(out,"full-hot-single.zip");
        CNHotUpdateValidate.VerMeta pinned=new CNHotUpdateValidate.VerMeta(73,size,md5,a);
        Thread switcher=new Thread(new SwitchAfter());switcher.start();
        boolean ok=CNHotUpdate.download(CNMirrors.CANONICAL_BASE+"fixture.zip",target.getPath(),"测试热更新",0,pinned);
        switcher.join();
        check(ok && CNHotUpdateValidate.verifyZip(target,pinned)==null,"full production hot-update single-stream route switch preserves identity and completes");
        check(!Thread.currentThread().isInterrupted(),"hot-update owner returned without a restart interrupt");
        CNDownloadMode.setPlayerChoice(false);
    }
    static void baseSingle()throws Exception{
        Method once=CNDownloaderFix.class.getDeclaredMethod("downloadOnce",String.class,File.class,int.class,boolean.class,int.class,long.class);
        once.setAccessible(true);CNDownloadRoute.select(a);
        CNDownloadRoute.Plan plan=CNDownloadRoute.plan(CNMirrors.healthy(),1);
        File target=new File(out,"base-single.zip");Thread switcher=new Thread(new SwitchAfter());switcher.start();
        boolean changed=false;CNDownloadRoute.enter(plan);
        try{once.invoke(null,plan.mirror.urlFor("fixture.zip"),target,0,true,CNDownloadRestart.generation(0),size);}
        catch(InvocationTargetException e){if(e.getCause() instanceof CNDownloadRoute.Changed)changed=true;else throw e;}
        finally{CNDownloadRoute.leave();switcher.join();}
        File part=new File(target.getPath()+".part");long retained=part.length();
        check(changed && retained>0 && retained<size,"base single-stream route change retains partial bytes="+retained);
        boolean unsupported=false;
        try{once.invoke(null,a.replace("/a/","/norange/")+"fixture.zip",target,0,true,CNDownloadRestart.generation(0),size);}
        catch(InvocationTargetException e){unsupported=e.getCause() instanceof IOException;}
        check(unsupported && part.length()==retained,"non-Range mirror cannot destroy a pinned partial download");
        plan=CNDownloadRoute.plan(CNMirrors.healthy(),1);CNDownloadRoute.enter(plan);
        try{once.invoke(null,plan.mirror.urlFor("fixture.zip"),target,0,true,CNDownloadRestart.generation(0),size);}
        finally{CNDownloadRoute.leave();}
        check(CNHotUpdateValidate.verifyZip(target,meta(1))==null,"base single-stream resumes despite mirror-specific ETag and validates final bytes");
    }
    public static void main(String[] args)throws Exception{
        a=args[0];b=args[1];out=new File(args[2]);out.mkdirs();fixture=new File(args[3]);size=fixture.length();md5=args[4];
        Field mirrors=CNMirrors.class.getDeclaredField("mirrors");mirrors.setAccessible(true);
        mirrors.set(null,Arrays.asList(new CNMirrors.Mirror("A",a,100,2,true),new CNMirrors.Mirror("B",b,90,2,true)));
        choices();singleIdentity();sameIdentity();changedIdentity();verifiedBlocks();fullHotSingle();baseSingle();
        System.out.println("PASS all manual route integration cases");
    }
}
