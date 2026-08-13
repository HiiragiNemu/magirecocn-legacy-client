package io.kamihama.magianative;

/**
 * 日志行的解析器：把一条原始日志拆成 <b>来源 / 时刻 / 级别 / 组件 / 正文</b>。
 *
 * <h3>为什么要它</h3>
 *
 * LOG 面板原先是把 {@link CNLog#tail(int)} 拼出来的一大坨字符串整个塞进一个
 * {@code TextView}。真机上 logcat 一秒能灌几百行，结果就是<b>满屏等宽字在飞</b>
 * ——开发的人也只能靠肉眼扫「有没有红字」，普通玩家更是只看得见字在动。而这个
 * 面板的用途恰恰是「玩家把问题现场发给客服」，看不懂就等于没有。
 *
 * <p>拆开之后，面板能把每条渲染成「来源徽章 + 时刻 + 正文」，级别高的标色。
 * 信息量一点没变，但眼睛有了落点。
 *
 * <h3>为什么单独一个类</h3>
 *
 * 这里全是纯字符串处理，不碰任何 Android 类型——所以能在 JVM 上直接测
 * （{@code tools/LogFormatTest.java}）。渲染那半截（Spannable、颜色）留在
 * {@code CNCNDownloadUI} 里，那部分在测试 JVM 上是桩，测不了。
 *
 * <p>两种输入格式：
 *
 * <pre>
 *   本补丁自己打的（{@link CNLog#SRC_APP}，见 CNLog.write）：
 *     ［2026-08-13 12:03:11］[界面][INFO] 线路测速 cn=31ms
 *
 *   logcat 回收来的（SRC_LOGCAT / SRC_NATIVE，`logcat -v time`）：
 *     08-13 12:03:13.123 W/chromium(12345): [WARNING] ...
 * </pre>
 *
 * <p>解析<b>一律不抛</b>：认不出的行原样进正文。日志面板绝不能因为某一行格式
 * 古怪就整个白屏——那会把「排查工具」变成第二个故障点。
 */
public final class CNLogFormat {

    /** 来源徽章。三个字母等宽，面板里能对齐成一列。 */
    public static final String BADGE_APP    = "APP";
    public static final String BADGE_LOGCAT = "LOG";
    public static final String BADGE_NATIVE = "NAT";

    /** 级别。空串表示这一行没给出级别。 */
    public static final String LV_VERBOSE = "VERBOSE";
    public static final String LV_DEBUG   = "DEBUG";
    public static final String LV_INFO    = "INFO";
    public static final String LV_WARN    = "WARN";
    public static final String LV_ERROR   = "ERROR";
    public static final String LV_FATAL   = "FATAL";

    /** 时刻串的长度，{@code HH:mm:ss}。 */
    private static final int TIME_LEN = 8;

    private CNLogFormat() {}

    /** 拆好的一条。字段一律非 null，取不到就是空串。 */
    public static final class Parsed {
        /** {@link #BADGE_APP} / {@link #BADGE_LOGCAT} / {@link #BADGE_NATIVE}。 */
        public final String badge;
        /** {@code HH:mm:ss}，取不到为空串。 */
        public final String time;
        /** {@code LV_*} 之一，取不到为空串。 */
        public final String level;
        /** 组件名（本补丁）或 logcat tag，取不到为空串。 */
        public final String comp;
        /** 正文。认不出格式时就是整行原文。 */
        public final String text;

        Parsed(String badge, String time, String level, String comp, String text) {
            this.badge = badge;
            this.time  = time;
            this.level = level;
            this.comp  = comp;
            this.text  = text;
        }
    }

    /** 来源码 → 徽章。未知来源按 logcat 处理。 */
    public static String badgeOf(int src) {
        if (src == CNLog.SRC_APP) return BADGE_APP;
        if (src == CNLog.SRC_NATIVE) return BADGE_NATIVE;
        return BADGE_LOGCAT;
    }

    /** 这个级别值不值得标色（面板上只给 WARN 以上上色，否则等于没上色）。 */
    public static boolean isBad(String level) {
        return LV_WARN.equals(level) || LV_ERROR.equals(level) || LV_FATAL.equals(level);
    }

    /** 比 WARN 更重，面板上用更强的色。 */
    public static boolean isFatal(String level) {
        return LV_ERROR.equals(level) || LV_FATAL.equals(level);
    }

    /**
     * 拆一条日志。任何认不出的部分都退化为空串 / 原文，<b>不抛异常</b>。
     */
    public static Parsed parse(int src, String line) {
        String badge = badgeOf(src);
        if (line == null || line.length() == 0) {
            return new Parsed(badge, "", "", "", "");
        }
        try {
            Parsed p = (src == CNLog.SRC_APP) ? parseApp(badge, line) : null;
            if (p == null) p = parseLogcat(badge, line);
            if (p == null) p = new Parsed(badge, "", "", "", line);
            return p;
        } catch (Throwable t) {
            // 面板绝不能因为一行怪格式就整个塌掉
            return new Parsed(badge, "", "", "", line);
        }
    }

    /**
     * 本补丁自己的格式：{@code ［yyyy-MM-dd HH:mm:ss］[组件][级别] 正文}。
     *
     * <p>注意时刻用的是<b>全角</b>方括号 {@code ［］}（CNLog.write 就这么写的），
     * 组件与级别是半角 {@code []}。认不出返回 null，交给下一个解析器。
     */
    private static Parsed parseApp(String badge, String line) {
        if (line.charAt(0) != '［') return null;
        int tsEnd = line.indexOf('］');
        if (tsEnd < 0) return null;

        String stamp = line.substring(1, tsEnd).trim();
        // 「yyyy-MM-dd HH:mm:ss」只取后半截：面板一屏就几分钟的事，日期是噪音
        String time = stamp.length() >= TIME_LEN
                ? stamp.substring(stamp.length() - TIME_LEN) : stamp;

        String rest = line.substring(tsEnd + 1);
        String comp = "";
        String level = "";
        // 后面跟着 [组件][级别]，缺哪个都当没有，不硬要求
        for (int i = 0; i < 2 && rest.startsWith("["); i++) {
            int end = rest.indexOf(']');
            if (end < 0) break;
            String field = rest.substring(1, end);
            rest = rest.substring(end + 1);
            if (i == 0) comp = field; else level = normalizeLevel(field);
        }
        if (rest.startsWith(" ")) rest = rest.substring(1);
        return new Parsed(badge, time, level, comp, rest);
    }

    /**
     * {@code logcat -v time} 的格式：
     * {@code MM-DD HH:MM:SS.mmm L/Tag( PID): 正文}。
     *
     * <p>只认「时刻 + 单字母级别 + 斜杠」这个骨架，PID 那段宽度随机（logcat 会
     * 右对齐补空格），所以按分隔符找而不是按列数切。认不出返回 null。
     */
    private static Parsed parseLogcat(String badge, String line) {
        // 形如 "08-13 12:03:13.123 W/chromium(12345): msg"
        int sp = line.indexOf(' ');
        if (sp < 0 || sp + 1 >= line.length()) return null;
        int sp2 = line.indexOf(' ', sp + 1);
        if (sp2 < 0) return null;

        String clock = line.substring(sp + 1, sp2);           // 12:03:13.123
        if (clock.length() < TIME_LEN || clock.charAt(2) != ':' || clock.charAt(5) != ':') {
            return null;
        }
        String time = clock.substring(0, TIME_LEN);

        String rest = line.substring(sp2 + 1);                 // W/chromium(12345): msg
        if (rest.length() < 2 || rest.charAt(1) != '/') return null;
        String level = normalizeLevel(rest.substring(0, 1));
        rest = rest.substring(2);                              // chromium(12345): msg

        // tag 到 '(' 为止；没有括号的变体（有的 ROM 不打 PID）就到第一个 ':'。
        int paren = rest.indexOf('(');
        int colon = rest.indexOf(':');
        int tagEnd = (paren >= 0 && (colon < 0 || paren < colon)) ? paren : colon;
        if (tagEnd < 0) return null;
        String comp = rest.substring(0, tagEnd).trim();

        // 正文从 tag 之后的第一个 ':' 起算（跳过 "(12345)" 那段）
        int msgAt = rest.indexOf(':', tagEnd);
        String text = msgAt < 0 ? "" : rest.substring(msgAt + 1);
        if (text.startsWith(" ")) text = text.substring(1);
        return new Parsed(badge, time, level, comp, text);
    }

    private static String normalizeLevel(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toUpperCase(java.util.Locale.US);
        if (s.length() == 0) return "";
        if (s.startsWith("V")) return LV_VERBOSE;
        if (s.startsWith("D")) return LV_DEBUG;
        if (s.startsWith("I")) return LV_INFO;
        if (s.startsWith("W")) return LV_WARN;
        if (s.startsWith("E")) return LV_ERROR;
        if (s.startsWith("F") || s.startsWith("A")) return LV_FATAL;
        return "";
    }
}
