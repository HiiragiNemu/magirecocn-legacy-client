import io.kamihama.magianative.CNLog;
import io.kamihama.magianative.CNLogFormat;

/**
 * 日志行解析器的回归测试。
 *
 * <p>这层东西的失败方式很不起眼：某个格式认不出来，面板上那一行就退化成一整条
 * 原始文本混在结构化的行里——不崩、不报错，只是**又变回原来那样看不懂**。
 * 所以两种正常格式各钉几条，畸形输入单独一组（真机上 logcat 什么都可能吐出来）。
 */
public class LogFormatTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    private static void eq(String name, String want, String got) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (!ok) System.out.println("      期望[" + want + "] 实际[" + got + "]");
        check(name, ok);
    }

    public static void main(String[] args) {
        // ── [1] 本补丁自己的格式 ────────────────────────────────────
        // ［yyyy-MM-dd HH:mm:ss］[组件][级别] 正文   ← 时刻是全角方括号
        CNLogFormat.Parsed a = CNLogFormat.parse(CNLog.SRC_APP,
                "［2026-08-13 12:03:11］[界面][INFO] 线路测速 cn=31ms");
        eq("[1a] 徽章", CNLogFormat.BADGE_APP, a.badge);
        eq("[1b] 只留时刻，丢掉日期", "12:03:11", a.time);
        eq("[1c] 组件", "界面", a.comp);
        eq("[1d] 级别", CNLogFormat.LV_INFO, a.level);
        eq("[1e] 正文", "线路测速 cn=31ms", a.text);

        CNLogFormat.Parsed w = CNLogFormat.parse(CNLog.SRC_APP,
                "［2026-08-13 12:03:12］[MagiaCNHotUpdate][WARN] 校验失败");
        eq("[1f] WARN 认得出", CNLogFormat.LV_WARN, w.level);
        check("[1g] WARN 算「要上色」", CNLogFormat.isBad(w.level));
        check("[1h] WARN 不算最重", !CNLogFormat.isFatal(w.level));

        CNLogFormat.Parsed e = CNLogFormat.parse(CNLog.SRC_APP,
                "［2026-08-13 12:03:13］[CNLog][ERROR] 写盘失败 / java.io.IOException");
        check("[1i] ERROR 算最重", CNLogFormat.isFatal(e.level));
        eq("[1j] 正文保留异常尾巴",
                "写盘失败 / java.io.IOException", e.text);

        // 正文里带方括号不能把解析带偏（只吃开头那两段）
        CNLogFormat.Parsed br = CNLogFormat.parse(CNLog.SRC_APP,
                "［2026-08-13 12:03:14］[界面][INFO] [DEBUG] 全表如下 [ON] skipHotUpdate");
        eq("[1k] 正文里的方括号原样保留",
                "[DEBUG] 全表如下 [ON] skipHotUpdate", br.text);

        // ── [2] logcat -v time 的格式 ───────────────────────────────
        // MM-DD HH:MM:SS.mmm L/Tag( PID): 正文
        CNLogFormat.Parsed l = CNLogFormat.parse(CNLog.SRC_LOGCAT,
                "08-13 12:03:13.123 W/chromium(12345): [WARNING] something");
        eq("[2a] 徽章", CNLogFormat.BADGE_LOGCAT, l.badge);
        eq("[2b] 时刻去掉毫秒", "12:03:13", l.time);
        eq("[2c] tag", "chromium", l.comp);
        eq("[2d] 单字母级别展开", CNLogFormat.LV_WARN, l.level);
        eq("[2e] 正文", "[WARNING] something", l.text);

        // PID 右对齐补空格是 logcat 的常态，不能按固定列切
        CNLogFormat.Parsed pad = CNLogFormat.parse(CNLog.SRC_LOGCAT,
                "08-13 12:03:13.123 E/AndroidRuntime(  987): FATAL EXCEPTION: main");
        eq("[2f] PID 带前导空格也认得", "AndroidRuntime", pad.comp);
        eq("[2g] 正文", "FATAL EXCEPTION: main", pad.text);
        check("[2h] E 算最重", CNLogFormat.isFatal(pad.level));

        // 有的 ROM 不打 PID
        CNLogFormat.Parsed nopid = CNLogFormat.parse(CNLog.SRC_LOGCAT,
                "08-13 12:03:13.123 I/Tag: hello");
        eq("[2i] 没有 PID 也认得出 tag", "Tag", nopid.comp);
        eq("[2j] 没有 PID 时的正文", "hello", nopid.text);

        // native 来源只影响徽章，格式解析走同一条路
        CNLogFormat.Parsed n = CNLogFormat.parse(CNLog.SRC_NATIVE,
                "08-13 12:03:25.001 I/MagiaCN_Legacy(  432): [i18n] miss: \"Puella Magi\"");
        eq("[2k] native 徽章", CNLogFormat.BADGE_NATIVE, n.badge);
        eq("[2l] native 正文", "[i18n] miss: \"Puella Magi\"", n.text);

        // 正文里有冒号不能被当成 tag 的分隔
        CNLogFormat.Parsed colon = CNLogFormat.parse(CNLog.SRC_LOGCAT,
                "08-13 12:03:13.123 I/Net(  10): GET https://a.example/b: 200");
        eq("[2m] tag 只取到括号前", "Net", colon.comp);
        eq("[2n] 正文里的冒号原样", "GET https://a.example/b: 200", colon.text);

        // ── [3] 畸形输入：一律不抛，退化成整行正文 ──────────────────
        // 真机上 logcat 什么都可能吐出来；面板绝不能因为一行怪格式就整个白屏。
        String[] junk = {
            "", "x", "没有任何结构的一行中文",
            "［没有右括号的时间戳 后面还有字",
            "［2026-08-13 12:03:11］",
            "08-13 lolnotatime W/x(1): y",
            "08-13",
            "----- beginning of main",
            "\t\t",
        };
        boolean threw = false;
        boolean allNonNull = true;
        for (int i = 0; i < junk.length; i++) {
            for (int src = 0; src <= 2; src++) {
                try {
                    CNLogFormat.Parsed p = CNLogFormat.parse(src, junk[i]);
                    if (p == null || p.badge == null || p.time == null
                            || p.level == null || p.comp == null || p.text == null) {
                        allNonNull = false;
                    }
                } catch (Throwable t) {
                    threw = true;
                    System.out.println("      抛了: [" + junk[i] + "] " + t);
                }
            }
        }
        check("[3a] 畸形输入一律不抛", !threw);
        check("[3b] 字段永不为 null", allNonNull);

        CNLogFormat.Parsed nul = CNLogFormat.parse(CNLog.SRC_APP, null);
        check("[3c] null 行也不抛", nul != null && nul.text.length() == 0);

        CNLogFormat.Parsed plain = CNLogFormat.parse(CNLog.SRC_LOGCAT,
                "----- beginning of main");
        eq("[3d] 认不出的行原样进正文", "----- beginning of main", plain.text);
        eq("[3e] 认不出时时刻为空", "", plain.time);

        // ── [4] 级别归一化 ──────────────────────────────────────────
        check("[4a] 没有级别时不上色", !CNLogFormat.isBad(""));
        check("[4b] INFO 不上色", !CNLogFormat.isBad(CNLogFormat.LV_INFO));
        check("[4c] FATAL 算最重", CNLogFormat.isFatal(CNLogFormat.LV_FATAL));
        eq("[4d] 未知来源按 logcat 处理",
                CNLogFormat.BADGE_LOGCAT, CNLogFormat.badgeOf(99));
        CNLogFormat.Parsed v = CNLogFormat.parse(CNLog.SRC_LOGCAT,
                "08-13 12:03:13.123 V/Tag(1): x");
        eq("[4e] V → VERBOSE", CNLogFormat.LV_VERBOSE, v.level);
        CNLogFormat.Parsed d = CNLogFormat.parse(CNLog.SRC_LOGCAT,
                "08-13 12:03:13.123 D/Tag(1): x");
        eq("[4f] D → DEBUG", CNLogFormat.LV_DEBUG, d.level);
        CNLogFormat.Parsed unk = CNLogFormat.parse(CNLog.SRC_LOGCAT,
                "08-13 12:03:13.123 ?/Tag(1): x");
        eq("[4g] 认不出的级别留空", "", unk.level);

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
