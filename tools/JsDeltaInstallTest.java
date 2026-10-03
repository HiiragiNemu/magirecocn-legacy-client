package io.kamihama.magianative;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Exercises the real transaction and cumulative-layer code against the first public candidate. */
public final class JsDeltaInstallTest {
    static int count;
    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        System.out.println("PASS " + name); count++;
    }
    static byte[] member(File zip, String name) throws Exception {
        ZipFile z = new ZipFile(zip);
        try { InputStream in = z.getInputStream(z.getEntry(name));
            try { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray();
            } finally { in.close(); }
        } finally { z.close(); }
    }
    static File variant(File original, File dir, String name, int version, String baseHash, String content) throws Exception {
        File out = new File(dir,name); ZipFile z = new ZipFile(original);
        org.json.JSONObject m = new org.json.JSONObject(new String(member(original,CNJsDelta.MANIFEST),"UTF-8"));
        m.put("version",version); if(baseHash!=null)m.put("base_js_sha256",baseHash);
        if(content!=null){
            byte[] b=content.getBytes("UTF-8"); org.json.JSONObject e=m.getJSONArray("entries").getJSONObject(0);
            StringBuilder h=new StringBuilder();for(byte v:java.security.MessageDigest.getInstance("SHA-256").digest(b))h.append(String.format("%02x",v&255));
            e.put("size",b.length);e.put("sha256",h.toString());
        }
        ZipOutputStream o=new ZipOutputStream(new FileOutputStream(out));
        try { Enumeration<? extends ZipEntry> all=z.entries();while(all.hasMoreElements()){
            ZipEntry e=all.nextElement();o.putNextEntry(new ZipEntry(e.getName()));
            o.write(e.getName().equals(CNJsDelta.MANIFEST)?m.toString().getBytes("UTF-8"):
                content==null?member(original,e.getName()):content.getBytes("UTF-8"));o.closeEntry();
        }}finally{o.close();z.close();}return out;
    }
    static void reject(File archive,File root,int version,String label)throws Exception{
        byte[] old=Files.readAllBytes(new File(root,"madomagi/resource/image_native/scene/top/toppage_bg_020.png").toPath());
        boolean bad=false;try{CNJsDelta.apply(archive,root,103,version);}catch(Exception e){bad=true;}
        check(bad,label);check(Arrays.equals(old,Files.readAllBytes(new File(root,"madomagi/resource/image_native/scene/top/toppage_bg_020.png").toPath())),label+" leaves installed content unchanged");
    }
    public static void main(String[] args) throws Exception {
        File base = new File(args[0]), delta = new File(args[1]), root = new File(args[2]);
        String path = "madomagi/resource/image_native/scene/top/toppage_bg_020.png";
        byte[] before = member(base, path), after = member(delta, path);
        root.mkdirs();
        check(!Arrays.equals(before, after), "test asset actually differs from fixture");
        check(CNCNDownloadUI.FILE_NAMES.length == 16, "16 UI package slots");
        check(CNCNDownloadUI.FILE_NAMES[15].equals(CNJsDelta.NAME), "delta is last slot");
        check(CNDownloaderFix.isHotSlot(15) && !CNDownloaderFix.usesChunkManifest(CNJsDelta.NAME), "delta uses version identity not static manifest");
        check(CNDownloaderFix.parseFinalFlag("schema=2\narchives=15\n"), "old 15-package completion preserved");
        check(CNDownloaderFix.parseFinalFlag("schema=2\narchives=16\n"), "new completion supported");
        CNJsDelta.validate(delta, 103, 1);
        for (int wrong : new int[]{0, 101, 102}) {
            boolean rejected = false;
            try { CNJsDelta.validate(delta, wrong, 1); } catch (Exception e) { rejected = true; }
            check(rejected, "reject different base " + wrong);
        }
        boolean rejected = false;
        try { CNJsDelta.validate(delta, 103, 2); } catch (Exception e) { rejected = true; }
        check(rejected, "reject wrong delta version");
        CNHotUpdateTx.apply(base, root, "js");
        check(Arrays.equals(before, Files.readAllBytes(new File(root,path).toPath())), "baseline real fixture installation");
        CNJsDelta.apply(delta, root, 103, 1);
        check(Arrays.equals(after, Files.readAllBytes(new File(root,path).toPath())), "delta replaces exact scene/top PNG");
        check(CNJsDelta.installedMatches(root,103,1), "installed manifest and payload match");
        String plist = path.replace(".png", ".plist");
        check(Arrays.equals(member(base,plist), Files.readAllBytes(new File(root,plist).toPath())), "paired plist unchanged");
        CNHotUpdateTx.apply(base, root, "js");
        check(!CNJsDelta.installedMatches(root,103,1), "old package overwrite detected");
        CNJsDelta.reapplyCached(root,103);
        check(Arrays.equals(after, Files.readAllBytes(new File(root,path).toPath())), "manual base reinstall restored from cached delta without downloading");
        CNJsDelta.reapplyCached(root,103);
        check(CNJsDelta.installedMatches(root,103,1), "repeat apply is idempotent");
        File newer=variant(delta,root.getParentFile(),"newer.zip",2,null,"latest reviewed story");
        CNJsDelta.apply(newer,root,103,2);
        reject(delta,root,1,"old delta rejected");
        File collision=variant(newer,root.getParentFile(),"collision.zip",2,null,"stale same version");
        reject(collision,root,2,"same version different body rejected");
        File wrongBase=variant(newer,root.getParentFile(),"wrong-base.zip",3,new String(new char[64]).replace('\0','0'),null);
        reject(wrongBase,root,3,"wrong baseline SHA rejected");
        File active=new File(root,CNJsDelta.MANIFEST);byte[] manifest=Files.readAllBytes(active.toPath());
        Files.delete(active.toPath());reject(delta,root,1,"cache alone retains version floor");
        Files.write(active.toPath(),manifest);
        File cache=new File(root,".cn_js_delta_cache.zip");byte[] cached=Files.readAllBytes(cache.toPath());
        Files.write(cache.toPath(),Files.readAllBytes(delta.toPath()));
        boolean blocked=false;try{CNJsDelta.requireReplayReady(root,103);}catch(Exception e){blocked=true;}
        check(blocked,"stale cache blocks baseline before overwrite");
        Files.delete(cache.toPath());blocked=false;try{CNJsDelta.requireReplayReady(root,103);}catch(Exception e){blocked=true;}
        check(blocked,"missing cache blocks baseline before overwrite");
        Files.write(cache.toPath(),"corrupt".getBytes("UTF-8"));blocked=false;try{CNJsDelta.requireReplayReady(root,103);}catch(Exception e){blocked=true;}
        check(blocked,"corrupt cache blocks baseline before overwrite");
        CNJsDelta.apply(newer,root,103,2);check(CNJsDelta.installedMatches(root,103,2),"valid download repairs corrupt cache");
        synchronized(CNDownloaderFix.extractCommitLock()){
            CNJsDelta.requireReplayReady(root,103);CNHotUpdateTx.apply(base,root,"js");CNJsDelta.reapplyCached(root,103);
        }
        check(CNJsDelta.installedMatches(root,103,2),"baseline reinstall finishes on latest bytes");
        blocked=false;try{CNJsDelta.verifyBaseline(base,false);}catch(Exception e){blocked=true;}
        check(blocked,"unrecognized full ZIP rejected");
        System.out.println("JS_DELTA_INSTALL_PASS " + count);
    }
}
