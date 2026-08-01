import io.kamihama.magianative.CNMirrors;
import java.lang.reflect.*;
import java.util.*;

/** 验证「值不值得换线」的决策逻辑。 */
public class ThrottleTest {
    static int pass=0, fail=0;
    static void check(String n, boolean ok, String d){
        if(ok){pass++;System.out.println("  ✅ "+n+(d.isEmpty()?"":"  — "+d));}
        else{fail++;System.out.println("  ❌ "+n+"  — "+d);} }

    static CNMirrors.Mirror mk(String name,int w) throws Exception {
        Constructor<CNMirrors.Mirror> c = CNMirrors.Mirror.class
            .getDeclaredConstructor(String.class,String.class,int.class,int.class,boolean.class);
        c.setAccessible(true);
        return c.newInstance(name,"https://"+name+"/",w,4,true);
    }
    static void setMirrors(List<CNMirrors.Mirror> l) throws Exception {
        Field f=CNMirrors.class.getDeclaredField("mirrors"); f.setAccessible(true); f.set(null,l);
    }
    static void setBaseline(CNMirrors.Mirror m,long bps) throws Exception {
        Field f=CNMirrors.Mirror.class.getDeclaredField("baselineBps"); f.setAccessible(true); f.setLong(m,bps);
    }
    static long baseline(CNMirrors.Mirror m) throws Exception {
        Field f=CNMirrors.Mirror.class.getDeclaredField("baselineBps"); f.setAccessible(true); return f.getLong(m);
    }

    public static void main(String[] a) throws Exception {
        CNMirrors.Mirror fast = mk("fast",80), slow = mk("slow",60), unknown = mk("unknown",40);

        System.out.println("\n[1] 快线被限速，但备选更慢 -> 不换");
        setBaseline(fast, 10*1024*1024); setBaseline(slow, 1*1024*1024);
        setMirrors(new ArrayList<CNMirrors.Mirror>(Arrays.asList(fast, slow)));
        // 快线被限到 5MB/s，仍远快于慢线的 1MB/s
        check("留在原地", !CNMirrors.worthSwitching(fast, 5L*1024*1024), "限速后 5MB/s vs 备选 1MB/s");

        System.out.println("\n[2] 快线被限到低于备选 -> 换");
        check("换线", CNMirrors.worthSwitching(fast, 500*1024), "限速后 0.5MB/s vs 备选 1MB/s");

        System.out.println("\n[3] 存在未测速线路 -> 值得一试");
        setMirrors(new ArrayList<CNMirrors.Mirror>(Arrays.asList(fast, unknown)));
        check("换线", CNMirrors.worthSwitching(fast, 5L*1024*1024), "unknown 尚无基准");

        System.out.println("\n[4] 只有自己一条 -> 不换");
        setMirrors(new ArrayList<CNMirrors.Mirror>(Arrays.asList(fast)));
        check("留在原地", !CNMirrors.worthSwitching(fast, 100*1024), "无备选");

        System.out.println("\n[5] 基准取最大值（不被限速后的低速拉低）");
        CNMirrors.Mirror m = mk("m",50);
        CNMirrors.reportBaseline(m, 8L*1024*1024);
        CNMirrors.reportBaseline(m, 2L*1024*1024);
        check("基准仍为 8MB/s", baseline(m)==8L*1024*1024, (baseline(m)/1024/1024)+"MB/s");

        System.out.println("\n[6] 被限速的线路降级到末尾但不被禁用");
        setBaseline(slow, 1*1024*1024);
        setMirrors(new ArrayList<CNMirrors.Mirror>(Arrays.asList(fast, slow)));
        CNMirrors.reportThrottled(fast);
        List<CNMirrors.Mirror> h = CNMirrors.healthy();
        check("仍在可用列表里", h.size()==2, "size="+h.size());
        check("被排到末尾", h.get(h.size()-1)==fast, "首位="+h.get(0).name);

        System.out.println("\n通过 "+pass+" / 失败 "+fail);
        if(fail>0) System.exit(1);
    }
}
