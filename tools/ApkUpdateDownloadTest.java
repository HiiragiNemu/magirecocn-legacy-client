package io.kamihama.magianative;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

public final class ApkUpdateDownloadTest {
    static int count;static byte[] good="verified apk fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static String hash;
    static final CNApkDownload.Observer OBS=new Observer();
    static final CNApkDownload.Cancellation NO=new Cancel(false);
    static final class Observer implements CNApkDownload.Observer {public void update(long d,long t,long b,int r){if(d<0 || d>t)throw new AssertionError("progress bounds");}}
    static final class Cancel implements CNApkDownload.Cancellation {boolean value;Cancel(boolean v){value=v;}public boolean cancelled(){return value;}}
    static final class Response implements CNApkDownload.Response {
        byte[] data;long declared;boolean closed;Response(byte[] b,long l){data=b;declared=l;}
        public InputStream stream(){return new ByteArrayInputStream(data);}public long length(){return declared;}public void close(){closed=true;}
    }
    static final class Transport implements CNApkDownload.Transport {
        final Map<String,Response> values=new HashMap<String,Response>();int calls;
        public CNApkDownload.Response open(String u)throws IOException{calls++;Response r=values.get(u);if(r==null)throw new IOException("route down");return r;}
    }
    static void check(boolean b,String n){if(!b)throw new AssertionError(n);count++;System.out.println("PASS "+n);}
    static File dir()throws IOException{return Files.createTempDirectory("apk-download-test-").toFile();}
    static File fetch(File d,Transport t,CNApkDownload.Cancellation c)throws IOException{return CNApkDownload.fetch(Arrays.asList("edge","personal"),d,good.length,hash,t,OBS,c);}
    static void fails(File d,Transport t,CNApkDownload.Cancellation c,String label)throws Exception {
        boolean failed=false;try{fetch(d,t,c);}catch(IOException e){failed=true;}
        check(failed,label);check(!new File(d,hash+".apk").exists(),label+"_not_promoted");check(!new File(d,hash+".part").exists(),label+"_partial_removed");
    }
    public static void main(String[] args)throws Exception {
        hash=CNApkDownload.hex(MessageDigest.getInstance("SHA-256").digest(good));
        Transport t=new Transport();Response wrong=new Response(new byte[3],3);t.values.put("edge",wrong);Response ok=new Response(good,good.length);t.values.put("personal",ok);
        File d=dir();File f=fetch(d,t,NO);check(Arrays.equals(Files.readAllBytes(f.toPath()),good),"old_edge_size_rejected_same_identity_fallback");check(t.calls==2 && wrong.closed && ok.closed,"both_connections_closed");
        int before=t.calls;fetch(d,t,NO);check(t.calls==before,"verified_cache_reused");
        Files.write(f.toPath(),new byte[good.length]);fetch(d,t,NO);check(t.calls>before && CNApkDownload.matches(f,good.length,hash),"corrupt_cached_apk_revalidated_and_replaced");
        t=new Transport();t.values.put("personal",new Response(new byte[good.length],good.length));fails(dir(),t,NO,"hash_mismatch");
        t=new Transport();t.values.put("personal",new Response(new byte[good.length+1],-1));fails(dir(),t,NO,"oversized_body");
        t=new Transport();t.values.put("personal",new Response(new byte[good.length-1],-1));fails(dir(),t,NO,"truncated_body");
        t=new Transport();fails(dir(),t,new Cancel(true),"cancel_before_request");check(t.calls==0,"cancel_makes_no_request");
        t=new Transport();t.values.put("personal",new Response(good,-1));check(fetch(dir(),t,NO).isFile(),"unknown_http_length_still_checks_full_digest");
        boolean invalid=false;try{CNApkDownload.fetch(Arrays.asList("personal"),dir(),good.length,"../escape",t,OBS,NO);}catch(IOException e){invalid=true;}check(invalid,"cache_name_requires_sha256");
        File save=new File(d,"save-sentinel");Files.write(save.toPath(),good);fetch(d,t,NO);check(Arrays.equals(Files.readAllBytes(save.toPath()),good),"unrelated_saved_file_untouched");
        System.out.println("APK_DOWNLOAD_PASS "+count);
    }
}
