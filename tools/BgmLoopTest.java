import io.kamihama.magianative.CNBgm;
import java.lang.reflect.*;

/**
 * 验证「按循环点循环」的帧算术。
 *
 * <p>音质与真机播放没法在这里验证，但循环这件事的全部要害就是一句算术：
 * 每块解码输出该写多少帧。写多一帧 → 尾部编码器 padding 被放出来（原版不会）；
 * 写少一帧 → 每圈都掉采样，循环点漂移。这里用真实的循环点跑满整圈来检查。
 */
public class BgmLoopTest {
    static int pass=0, fail=0;
    static void check(String n, boolean ok, String d){
        if(ok){pass++;System.out.println("  ✅ "+n+(d.isEmpty()?"":"  — "+d));}
        else{fail++;System.out.println("  ❌ "+n+"  — "+d);} }

    static Method M;
    static int ftw(int avail, long written, long loopEnd) throws Exception {
        return (Integer) M.invoke(null, avail, written, loopEnd);
    }

    /** 跑满一圈，返回实际写出的总帧数；顺便检查从不越界。 */
    static long runOneLoop(long loopStart, long loopEnd, int blockFrames,
                           boolean[] overshot) throws Exception {
        long written = loopStart;
        long guard = 0;
        while (written < loopEnd) {
            int w = ftw(blockFrames, written, loopEnd);
            if (w <= 0) break;
            written += w;
            if (written > loopEnd) { overshot[0] = true; break; }
            if (++guard > 10_000_000L) break;
        }
        return written;
    }

    public static void main(String[] a) throws Exception {
        M = CNBgm.class.getDeclaredMethod("framesToWrite", int.class, long.class, long.class);
        M.setAccessible(true);

        System.out.println("\n[1] 基本裁剪");
        check("正常块整块写", ftw(1024, 0, 100000)==1024, "");
        check("最后一块被裁到循环点", ftw(1024, 99500, 100000)==500, "");
        check("到达循环点后不再写", ftw(1024, 100000, 100000)==0, "");
        check("越过循环点也不写负数", ftw(1024, 100500, 100000)==0, "");
        check("空块返回 0", ftw(0, 0, 100000)==0, "");
        check("负数块返回 0", ftw(-5, 0, 100000)==0, "");

        System.out.println("\n[2] 真实曲目：bgm1 loop=[0, 3968999)");
        long[][] tracks = { {0, 3968999, 3969234}, {0, 3924900, 3925135} };
        String[] names = {"bgm1","bgm2"};
        for (int i=0;i<tracks.length;i++){
            long ls=tracks[i][0], le=tracks[i][1], total=tracks[i][2];
            for (int bf : new int[]{1024, 2048, 4096, 100, 1}) {
                boolean[] over={false};
                long w=runOneLoop(ls, le, bf, over);
                boolean ok = !over[0] && w==le;
                check(names[i]+" 块大小 "+bf+" 跑满一圈精确停在循环点",
                      ok, "写出 "+w+" 帧，期望 "+le+(over[0]?"（越界！）":""));
            }
            long padding = total - le;
            check(names[i]+" 尾部 "+padding+" 帧 padding 永不播出",
                  ftw(4096, le, le)==0,
                  String.format("%.1fms", padding*1000.0/44100));
        }

        System.out.println("\n[3] 连续循环 5 圈，每圈长度都一致");
        long le=3968999;
        long first=-1; boolean same=true;
        for(int c=0;c<5;c++){
            boolean[] over={false};
            long w=runOneLoop(0, le, 1024, over);
            if(first<0) first=w; else if(w!=first) same=false;
            if(over[0]) same=false;
        }
        check("5 圈长度恒定且无越界", same && first==le, "每圈 "+first+" 帧");

        System.out.println("\n通过 "+pass+" / 失败 "+fail);
        if(fail>0) System.exit(1);
    }
}
