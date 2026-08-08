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
        check("日志目录在应用数据目录下", CNLog.logDirPath()
              .equals("/data/data/io.kamihama.totentanz/log"), CNLog.logDirPath());
        check("当前日志路径 = 目录 + 文件名", CNLog.currentLogPath()
              .startsWith("/data/data/io.kamihama.totentanz/log/"), CNLog.currentLogPath());

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

        System.out.println("\n[3b] 序号涨到 5 位时，删的仍然是最旧的那份");
        // 回归用例。原实现对文件名排字典序，注释写着「名字以四位序号开头，字典序
        // 即时间序」——序号一过 9999 这个前提就静默失效：字典序里 "10000_" 排在
        // "9995_" 前面（'1' < '9'），于是每次启动都把**最新**那份当成最旧的删掉，
        // 4 位的老日志反而永远删不掉，保留策略整个倒过来。表现是「刚跑完那次没有
        // 日志」，极易误判成日志线程崩了。这里把数值序钉死。
        // ⚠ 数据必须选「首位数字大于 '1' 的 4 位序号」（这里 9970..9999）与 5 位
        // 序号共存。用 0001.. 那种前导零的名字是抓不到的：'0' < '1'，字典序恰好
        // 也对，测试会在坏代码上一起通过——写回归测试时最容易掉进的坑。
        File pd3b = new File(new File(root, "seq5").getAbsolutePath(), "log");
        pd3b.mkdirs();
        Method prune = CNLog.class.getDeclaredMethod("pruneOldLogs", File.class);
        prune.setAccessible(true);
        Field keepF = CNLog.class.getDeclaredField("KEEP_LOGS"); keepF.setAccessible(true);
        int keepN = keepF.getInt(null);
        for (int i = 0; i < keepN; i++)                       // 9970..9999，最旧
            new File(pd3b, String.format("%04d_20260808-100000.log", 10000-keepN+i))
                    .createNewFile();
        new File(pd3b, "10000_20260808-173756.log").createNewFile();   // 跨过 9999
        new File(pd3b, "10001_20260808-174500.log").createNewFile();
        prune.invoke(null, pd3b);
        List<String> left = Arrays.asList(logs(pd3b));
        check("5 位序号的最新两份没有被删",
              left.contains("10000_20260808-173756.log")
                  && left.contains("10001_20260808-174500.log"),
              "剩 "+left.size()+" 个");
        check("被删的是序号最小的那两份",
              !left.contains(String.format("%04d_20260808-100000.log", 10000-keepN))
                  && !left.contains(String.format("%04d_20260808-100000.log", 10001-keepN)),
              left.isEmpty()?"空":"最小剩余 "+left.get(0));

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

        System.out.println("\n[6] initEarly() 绝不抛异常（hook 不变量）");
        // 这里的路径在 JVM 上多半建不出来（无 /data/data 写权限），正好用来验证
        // 「日志起不来也只能降级、不能把异常漏给 JNI」这条不变量。
        resetOpened();
        boolean threw=false;
        try { CNLog.initEarly(); } catch(Throwable t){ threw=true; }
        check("路径不可写时也正常返回", !threw, threw?"抛了异常":"已降级");
        resetOpened();
        threw=false;
        try { CNLog.initEarly(); } catch(Throwable t){ threw=true; }
        check("重复调用也正常返回", !threw, threw?"抛了异常":"ok");

        System.out.println("\n通过 "+pass+" / 失败 "+fail);
        if(fail>0) System.exit(1);
    }
    static void deleteRec(File f){ if(f.isDirectory()){File[] c=f.listFiles(); if(c!=null) for(File x:c) deleteRec(x);} f.delete(); }
}
