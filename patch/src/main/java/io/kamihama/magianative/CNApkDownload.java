package io.kamihama.magianative;

import java.io.*;
import java.security.MessageDigest;
import java.util.List;

/** 下载只认本次选中的文件身份；旧线路成功响应也不得成为安装候选。 */
public final class CNApkDownload {
    private CNApkDownload() {}
    public interface Response extends Closeable {
        InputStream stream() throws IOException;
        long length();
    }
    public interface Transport { Response open(String url) throws IOException; }
    public interface Observer { void update(long bytes, long total, long bytesPerSecond, int route); }
    public interface Cancellation { boolean cancelled(); }

    public static File fetch(List<String> urls, File dir, long size, String hash,
                             Transport transport, Observer observer, Cancellation cancel) throws IOException {
        if (size<=0 || size>1024L*1024*1024 || hash==null || !hash.matches("[0-9a-fA-F]{64}"))
            throw new IOException("更新文件身份不完整");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("创建更新缓存失败");
        String name=hash.toLowerCase(java.util.Locale.US);
        File target=new File(dir,name+".apk"), part=new File(dir,name+".part");
        if (matches(target,size,hash)) { observer.update(size,size,0,0); return target; }
        if (dir.getUsableSpace()<size+8L*1024*1024) throw new IOException("更新缓存空间不足");
        IOException last=new IOException("没有可用的同版本更新线路");
        for(int route=0;route<urls.size();route++) {
            checkCancel(cancel);
            observer.update(0,size,0,route+1);
            try (Response response=transport.open(urls.get(route))) {
                if (response.length()>0 && response.length()!=size) throw new IOException("线路文件大小与选定版本不符");
                MessageDigest digest=digest();long done=0,start=System.nanoTime(),lastUi=0;
                try (InputStream in=response.stream(); FileOutputStream out=new FileOutputStream(part,false)) {
                    byte[] buf=new byte[65536];int got;
                    while((got=in.read(buf))!=-1) {
                        checkCancel(cancel);if(got==0)continue;
                        if(got>size-done)throw new IOException("线路响应超过选定文件大小");
                        out.write(buf,0,got);digest.update(buf,0,got);done+=got;
                        long now=System.nanoTime();
                        if(now-lastUi>=150000000L || done==size) {
                            observer.update(done,size,(long)(done*1000000000.0/Math.max(1,now-start)),route+1);lastUi=now;
                        }
                    }
                    if(done!=size || !hex(digest.digest()).equalsIgnoreCase(hash))throw new IOException("APK 完整性校验失败");
                    out.getFD().sync();
                }
                checkCancel(cancel);
                if(target.exists() && !target.delete())throw new IOException("替换无效更新缓存失败");
                if(!part.renameTo(target))throw new IOException("保存已校验 APK 失败");
                return target;
            } catch(IOException e) { last=e; }
            finally { if(part.exists() && !part.delete())part.deleteOnExit(); }
        }
        checkCancel(cancel);throw last;
    }
    private static void checkCancel(Cancellation c) throws IOException {
        if(c.cancelled() || Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消下载");
    }
    public static boolean matches(File f,long size,String hash) throws IOException {
        if(!f.isFile() || f.length()!=size)return false;
        MessageDigest md=digest();try(InputStream in=new FileInputStream(f)) {
            byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);
        }
        return hex(md.digest()).equalsIgnoreCase(hash);
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static String hex(byte[] b) {
        StringBuilder out=new StringBuilder();for(byte x:b)out.append(String.format(java.util.Locale.US,"%02x",x&255));return out.toString();
    }
}
