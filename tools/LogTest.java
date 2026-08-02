import io.kamihama.magianative.CNLog;
import java.io.*; import java.lang.reflect.*; import java.util.*;

/** 验证日志目录：按序号+时间命名、序号递增、保留上限、来源过滤。 */
public class LogTest {
    static int pass=0, fail=0;
    static void check(String n, boolean ok, String d){
        if(ok){pass++;System.out.println("  ✅ "+n+(d.isEmpty()?"":"  — "+d));}
        else{fail++;System.out.println("  ❌ "+n+"  — "+d);} }

    static void resetOpened() throws Exception {
        Field f=CNLog.class.getDeclaredField("openedOnce"); f.setAccessible(true); f.setBoolean(null,false);
    }
    static String[] logs(File d){
        String[] n=d.list(); if(n==null) return new String[0];
        List<String> l=new ArrayList<String>();
        for(String x:n) if(x.endsWith(".log")) l.add(x);
        Collections.sort(l); return l.toArray(new String[0]);
    }
    public static void main(String[] a) throws Exception {
        File root=new File("t"); deleteRec(root); root.mkdirs();
        String priv=new File(root,"priv").getAbsolutePath();
        File pd=new File(priv,"log");

        System.out.println("\n[1] 首次启动建目录与文件");
        CNLog.init(new File(priv)); CNLog.i("测试","第一次");
        CNLog.close();
        String[] l1=logs(pd);
        check("log/ 目录已建且有一个日志", l1.length==1, Arrays.toString(l1));
        check("命名为 序号_时间.log", l1.length==1 && l1[0].matches("\\d{4}_\\d{8}-\\d{6}\\.log"), l1.length==1?l1[0]:"");
        check("序号为 1", CNLog.launchSeq()==1, "seq="+CNLog.launchSeq());
        check("外部路径拼装正确", CNLog.publicLogPath()
              .startsWith("/sdcard/Android/data/io.kamihama.totentanz/files/log/"),
              CNLog.publicLogPath());

        System.out.println("\n[2] 再启动 4 次，序号递增、文件各自独立");
        for(int i=0;i<4;i++){ resetOpened(); Thread.sleep(1100);
            CNLog.init(new File(priv)); CNLog.i("测试","第"+(i+2)+"次"); CNLog.close(); }
        String[] l2=logs(pd);
        check("共 5 个日志文件", l2.length==5, Arrays.toString(l2));
        check("序号递增到 5", CNLog.launchSeq()==5, "seq="+CNLog.launchSeq());
        boolean seqOk=true;
        for(int i=0;i<l2.length;i++) if(!l2[i].startsWith(String.format("%04d_",i+1))) seqOk=false;
        check("序号连续且字典序=时间序", seqOk, Arrays.toString(l2));

        System.out.println("\n[3] 反复启动不会无限增长（保留上限 30）");
        for(int i=0;i<32;i++){ resetOpened();
            CNLog.init(new File(priv)); CNLog.i("测试","批量"+i); CNLog.close(); }
        String[] l3=logs(pd);
        check("被裁剪到上限附近", l3.length<=31, "剩 "+l3.length+" 个");
        check("保留的是最新一批", l3[l3.length-1].startsWith(
              String.format("%04d_", CNLog.launchSeq())), l3[l3.length-1]);
        check("最旧的已被删除", !l3[0].startsWith("0001_"), l3[0]);

        System.out.println("\n[4] 来源过滤即时生效");
        CNLog.setShowLogcat(true); CNLog.setShowNative(true);
        int base=CNLog.visibleSize();
        CNLog.writeRaw("08-02 01:00:00.000 I/SomeOtherApp: 普通行", CNLog.SRC_LOGCAT);
        CNLog.writeRaw("08-02 01:00:00.001 I/MagiaClientJNI: native 行", CNLog.SRC_NATIVE);
        check("两条都可见", CNLog.visibleSize()==base+2, "visible="+CNLog.visibleSize());
        CNLog.setShowLogcat(false);
        check("关掉 logcat 后少一条", CNLog.visibleSize()==base+1, "visible="+CNLog.visibleSize());
        CNLog.setShowNative(false);
        check("再关原生后回到基线", CNLog.visibleSize()==base, "visible="+CNLog.visibleSize());
        CNLog.setShowLogcat(true); CNLog.setShowNative(true);
        check("重新打开后条目回来（未被丢弃）", CNLog.visibleSize()==base+2, "visible="+CNLog.visibleSize());

        System.out.println("\n[5] native 行归类正确");
        Method cl=CNLog.class.getDeclaredMethod("classify",String.class); cl.setAccessible(true);
        check("MagiaClientJNI -> NATIVE", ((Integer)cl.invoke(null,"I/MagiaClientJNI: x"))==CNLog.SRC_NATIVE,"");
        check("Cocos2dx -> NATIVE", ((Integer)cl.invoke(null,"I/Cocos2dxActivity: x"))==CNLog.SRC_NATIVE,"");
        check("其它 -> LOGCAT", ((Integer)cl.invoke(null,"I/WifiService: x"))==CNLog.SRC_LOGCAT,"");

        System.out.println("\n通过 "+pass+" / 失败 "+fail);
        if(fail>0) System.exit(1);
    }
    static void deleteRec(File f){ if(f.isDirectory()){File[] c=f.listFiles(); if(c!=null) for(File x:c) deleteRec(x);} f.delete(); }
}
