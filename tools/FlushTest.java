import io.kamihama.magianative.CNLog;
import java.io.*; import java.lang.reflect.*; import java.util.*;

/**
 * 复现并验证「日志文件断在 flush 边界」这个 bug。
 *
 * <p>玩家上传的 0001_20260802134344.log 里 logcat 原始行正好 250 行 = 5×50，
 * 断点恰是出问题的现场。原因是 writeRaw 只按行数攒（每 50 行落一次盘），
 * 不足 50 行的尾巴永远留在 BufferedWriter 里。
 */
public class FlushTest {
    static int pass=0, fail=0;
    static void check(String n, boolean ok, String d){
        if(ok){pass++;System.out.println("  ✅ "+n+(d.isEmpty()?"":"  — "+d));}
        else{fail++;System.out.println("  ❌ "+n+"  — "+d);} }

    static void resetOpened() throws Exception {
        Field f=CNLog.class.getDeclaredField("openedOnce"); f.setAccessible(true); f.setBoolean(null,false);
    }
    /** 数落盘文件里的 logcat 原始行（不含头行与自有模块行）。 */
    static int rawOnDisk(File f) throws IOException {
        if(!f.isFile()) return -1;
        BufferedReader br=new BufferedReader(new InputStreamReader(new FileInputStream(f),"UTF-8"));
        int n=0; String l;
        while((l=br.readLine())!=null){ if(!l.startsWith("［") && !l.startsWith("====")) n++; }
        br.close(); return n;
    }
    static File theLog(File priv){
        File d=new File(priv,"log"); String[] ns=d.list();
        if(ns==null) return null;
        List<String> l=new ArrayList<String>();
        for(String x:ns) if(x.endsWith(".log")) l.add(x);
        if(l.isEmpty()) return null;
        Collections.sort(l);
        return new File(d,l.get(l.size()-1));
    }

    public static void main(String[] a) throws Exception {
        File root=new File("tf"); deleteRec(root); root.mkdirs();
        File priv=new File(root,"priv");

        System.out.println("\n[1] 写 137 行（非 50 的整数倍），立刻取文件");
        resetOpened(); CNLog.init(priv);
        for(int i=0;i<137;i++) CNLog.writeRaw("08-02 13:43:5"+(i%10)+".000 I/Foo( 1): 行"+i, CNLog.SRC_LOGCAT);
        File f=theLog(priv);
        int n1=rawOnDisk(f);
        System.out.println("     立即读到 "+n1+" 行（写入 137）");
        check("不再断在 50 的整数倍", !(n1==100||n1==50||n1==150), "落盘 "+n1);

        System.out.println("\n[2] 日志安静下来后，定时线程应把尾巴冲出去");
        // 手动触发 flushNow：等价于定时线程那一跳，且不必真等 1 秒
        CNLog.flushNow();
        int n2=rawOnDisk(f);
        check("全部 137 行都在文件里", n2==137, "落盘 "+n2);

        System.out.println("\n[3] 时间阈值：跨过 1 秒后写入的行会自行落盘");
        for(int i=0;i<3;i++) CNLog.writeRaw("08-02 13:44:00.000 I/Foo( 1): 尾"+i, CNLog.SRC_LOGCAT);
        int n3a=rawOnDisk(f);
        Thread.sleep(1100);
        CNLog.writeRaw("08-02 13:44:02.000 I/Foo( 1): 触发时间阈值", CNLog.SRC_LOGCAT);
        int n3b=rawOnDisk(f);
        check("超时后一次写入把欠账一起冲掉", n3b==141,
              "超时前 "+n3a+" → 超时后 "+n3b+"（应为 141）");

        System.out.println("\n[4] 自有模块的行仍然逐条落盘");
        CNLog.i("测试","这一行必须立刻可见");
        BufferedReader br=new BufferedReader(new InputStreamReader(new FileInputStream(f),"UTF-8"));
        String l,last=null; while((l=br.readLine())!=null) last=l; br.close();
        check("最后一行就是刚写的自有日志",
              last!=null && last.contains("这一行必须立刻可见"), String.valueOf(last));

        System.out.println("\n[5] 无欠账时 flushNow 不做多余 I/O");
        long before=f.lastModified();
        Thread.sleep(1100);
        CNLog.flushNow();
        check("空转不改文件", f.lastModified()==before, "mtime 未变");

        CNLog.close();
        System.out.println("\n通过 "+pass+" / 失败 "+fail);
        if(fail>0) System.exit(1);
    }
    static void deleteRec(File f){ if(f.isDirectory()){File[] c=f.listFiles(); if(c!=null) for(File x:c) deleteRec(x);} f.delete(); }
}
