package io.kamihama.magianative;

import java.util.Locale;

/** 下载页的无 Android 依赖计算：MB 均为十进制字节单位，kbps 为千比特/秒。 */
public final class CNDownloadPresentation {
    private CNDownloadPresentation() {}

    public static long kilobitsToBytesPerSecond(int kbps) {
        return Math.max(0L, (long) kbps) * 1000L / 8L;
    }

    public static double megabytesPerSecond(long bytes, long elapsedNanos) {
        return bytes <= 0L || elapsedNanos <= 0L ? 0d
                : (bytes * 1.0e9d / elapsedNanos) / 1_000_000d;
    }

    public static String formatMegabytesPerSecond(float mbPerSecond) {
        return String.format(Locale.US, "%.2f MB/s", clean(mbPerSecond));
    }

    private static float clean(float n) {
        return Float.isNaN(n) || Float.isInfinite(n) || n < 0f ? 0f : n;
    }

    public static final class Totals {
        public final float sizeMb, doneMb;
        public final int percent;
        Totals(float size, float done, int percent) {
            this.sizeMb = size; this.doneMb = done; this.percent = percent;
        }
    }

    /** 每次从本轮槽位计算；不跨批次保存历史最大值，文字和进度条共用结果。 */
    public static Totals totals(int[] status, int[] progress, float[] sizes, float[] downloaded) {
        double size = 0d, done = 0d;
        int count = 0, sum = 0;
        boolean unknown = false;
        if (status != null) for (int i = 0; i < status.length; i++) {
            if (status[i] == 4) continue; // 本轮未检查
            count++;
            int pct = status[i] == 2 ? 100 : progress != null && i < progress.length
                    ? Math.max(0, Math.min(100, progress[i])) : 0;
            sum += pct;
            float total = sizes != null && i < sizes.length ? clean(sizes[i]) : 0f;
            if (total == 0f) {
                if (status[i] != 2) unknown = true;
                continue;
            }
            size += total;
            float bytes = downloaded != null && i < downloaded.length ? clean(downloaded[i]) : 0f;
            done += status[i] == 2 ? total : Math.min(bytes, total);
        }
        int pct = size > 0d && !unknown ? (int) (done * 100d / size)
                : count == 0 ? 0 : sum / count;
        return new Totals((float) size, (float) done, Math.max(0, Math.min(100, pct)));
    }

    /** Compact labels do not invent historic routes or numeric versions for fixed archives. */
    public static String packageIdentity(int version, String route, boolean downloading) {
        String identity = version > 0 ? "v" + version : version == 0 ? "固定包" : "版本未记录";
        String source = route == null || route.isEmpty() ? "来源未记录" : route;
        source = source.replace("MadeInMagius Cloudflare", "Magius/CF")
                .replace("MadeInMagius GitHub", "Magius/GH")
                .replace("CyberNova EdgeOne", "CyberNova/EO")
                .replace("CyberNova ESA", "CyberNova/ESA");
        return (downloading ? "下载 " : "") + identity + " · " + source;
    }

    public static final int DAY = 0, SUNSET = 1, NIGHT = 2;
    /** 按用户设备当地时间：06–16 点白天，17–18 点黄昏，19–05 点夜景。 */
    public static int periodForHour(int hour) {
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("hour");
        return hour >= 19 || hour < 6 ? NIGHT : hour >= 17 ? SUNSET : DAY;
    }

    public static String backgroundForPeriod(int period) {
        return period == NIGHT ? "magia/background_night.png"
                : period == SUNSET ? "magia/background_sunset.png" : "magia/background_day.png";
    }
}
