import io.kamihama.magianative.CNDownloadSlowNotice;
public class SlowDownloadNoticeTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        CNDownloadSlowNotice n = new CNDownloadSlowNotice(150000,15000000000L);
        check(!n.observe(1024,1000000000L,0));
        check(!n.observe(1024,1000000000L,14999999999L));
        check(n.observe(1024,1000000000L,15000000000L));
        check(!n.observe(1024,1000000000L,30000000000L));
        n=new CNDownloadSlowNotice(150000,15000000000L);
        check(!n.observe(1024,1000000000L,0));
        check(!n.observe(150000,1000000000L,10000000000L));
        check(!n.observe(1024,1000000000L,15000000000L));
        check(!n.observe(1024,1000000000L,29999999999L));
        check(n.observe(1024,1000000000L,30000000000L));
        check(!new CNDownloadSlowNotice(0,0).observe(1,1000000000L,0));
        check(!new CNDownloadSlowNotice(150000,0).observe(0,1000000000L,0));
        check(!new CNDownloadSlowNotice(150000,0).observe(1,0,0));
        check(!new CNDownloadSlowNotice(150000,0).observe(Long.MAX_VALUE,1,0));
        System.out.println("PASS: advisory timing, once-only, recovery reset, disabled threshold, zero data, overflow");
    }
}
