import io.kamihama.magianative.CNDownloadPresentation;

public class DownloadPresentationTest {
    private static void eq(double actual, double expected) {
        if (Math.abs(actual - expected) > 0.00001) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        eq(CNDownloadPresentation.kilobitsToBytesPerSecond(1200), 150000);
        eq(CNDownloadPresentation.megabytesPerSecond(150000, 1000000000L), 0.15);
        eq(CNDownloadPresentation.megabytesPerSecond(450000, 3000000000L), 0.15);
        if (!"0.15 MB/s".equals(CNDownloadPresentation.formatMegabytesPerSecond(0.15f))) throw new AssertionError();
        int[] status = {2,1,4}; int[] progress = {100,52,100};
        float[] sizes = {0,294,4000}; float[] downloaded = {0,153,4000};
        eq(CNDownloadPresentation.totals(status, progress, sizes, downloaded).percent, 52);
        eq(CNDownloadPresentation.totals(status, progress, sizes, downloaded).sizeMb, 294);
        downloaded[1] = 16.5f;
        eq(CNDownloadPresentation.totals(status, progress, sizes, downloaded).percent, 5);
        status[1] = 2;
        eq(CNDownloadPresentation.totals(status, progress, sizes, downloaded).percent, 100);
        status[1] = 1; downloaded[1] = 153;
        eq(CNDownloadPresentation.totals(status, progress, sizes, downloaded).percent, 52);
        eq(CNDownloadPresentation.totals(new int[]{1,4}, new int[]{40,100}, new float[]{0,1000}, new float[]{0,1000}).percent,40);
        eq(CNDownloadPresentation.totals(new int[]{1,1}, new int[]{50,25}, new float[]{100,300}, new float[]{50,75}).percent,31);
        eq(CNDownloadPresentation.totals(new int[]{4}, new int[]{100}, new float[]{1000}, new float[]{1000}).percent,0);
        eq(CNDownloadPresentation.totals(null,null,null,null).percent,0);
        for (int hour=0;hour<24;hour++) {
            int want = hour<6 || hour>=19 ? 2 : hour>=17 ? 1 : 0;
            eq(CNDownloadPresentation.periodForHour(hour),want);
            if (want==2 && !CNDownloadPresentation.backgroundForPeriod(want).endsWith("background_night.png")) throw new AssertionError();
        }
        System.out.println("PASS: bit/byte speed units, single/multiple/next-round progress, 24 local-time boundaries");
    }
}
