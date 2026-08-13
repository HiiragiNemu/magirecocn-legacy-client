import io.kamihama.magianative.CNDebugBridge;
import io.kamihama.magianative.CNDebugOverlay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 调试悬浮窗本体的纯逻辑回归测试（风格同 DebugBridgeTest）。
 *
 * <p>界面本身要真机才能看，这里钉的是它的<b>纯函数骨架</b>：P7 过滤、
 * 单选控件的读写映射、分组映射、注释表降级。这些函数全部 public static、
 * 不碰 Android——测试若照抄一份逻辑，测的就是副本而不是真代码。
 */
public class DebugOverlayTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    public static void main(String[] args) {
        // ── [1] P7：断同步开关不进面板、仍进 activeFlags ─────────────
        String[][] panel = CNDebugBridge.flagTable();
        boolean leaked = false;
        for (int i = 0; i < panel.length; i++) {
            String n = panel[i][CNDebugBridge.COL_NAME];
            if ("skipVersionCheck".equals(n) || "skipHotUpdate".equals(n)
                    || "skipMirrorConfig".equals(n)) leaked = true;
        }
        check("[1a] 三开关不出现在面板表", !leaked && panel.length > 0);

        String[][] raw = CNDebugBridge.rawFlagTableForTest();
        boolean activeHas = false;
        for (int i = 0; i < raw.length; i++) {
            if ("skipHotUpdate".equals(raw[i][CNDebugBridge.COL_NAME])) {
                raw[i][CNDebugBridge.COL_ON_BOOT] = "1";
            }
        }
        for (String n : CNDebugBridge.activeFlagsOf(raw)) {
            if ("skipHotUpdate".equals(n)) activeHas = true;
        }
        check("[1b] 开着的 skipHotUpdate 仍出现在 activeFlags", activeHas);

        // ── [2] 单选控件：磁盘 → 选项映射（0 / 1 / 多个三种情形）──────
        CNDebugOverlay.RadioGroup tut = CNDebugOverlay.radioGroupOf("tutorialSkipToBattle1");
        check("[2a] 跳段开关属于序章单选控件",
                tut != null && tut.flagsInOrder.length == 6
                && tut.optionLabels.length == 7);

        String[] flags = tut.flagsInOrder;
        check("[2b] 磁盘 0 个跳段文件 → 默认项",
                CNDebugOverlay.radioSelection(flags, set()) == 0);
        check("[2c] 磁盘 1 个 → 对应选项",
                CNDebugOverlay.radioSelection(flags, set("tutorialSkipToBattle2")) == 3);
        // 多个时按引擎实际行为显示最远生效项（下标最大者）
        check("[2d] 磁盘多个 → 最远生效项",
                CNDebugOverlay.radioSelection(flags,
                        set("tutorialSkipToBattle1", "tutorialSkipAfterBattle2")) == 4);
        check("[2e] 多个时挂修正提示", CNDebugOverlay.radioHasMultiple(flags,
                set("tutorialSkipToBattle1", "tutorialSkipAfterBattle2")));
        check("[2f] 单个/零个不挂修正提示",
                !CNDebugOverlay.radioHasMultiple(flags, set("tutorialSkipToBattle2"))
                && !CNDebugOverlay.radioHasMultiple(flags, set()));

        CNDebugOverlay.RadioGroup miss = CNDebugOverlay.radioGroupOf("logI18nMiss");
        check("[2g] 缺漏记录是 2 合 1 三选一",
                miss != null && miss.flagsInOrder.length == 2
                && miss.optionLabels.length == 3);
        check("[2h] All ⊃ Miss：两个都在时显示「记录全部」",
                CNDebugOverlay.radioSelection(miss.flagsInOrder,
                        set("logI18nMiss", "logI18nMissAll")) == 2);

        // ── [3] 单选控件：选项 → 写回集合映射 ───────────────────────
        // 选一项就只写哪个，同组其余清掉；组外开关不动。
        Set<String> desired = set("tutorialSkipToBattle1", "tutorialSkipAfterBattle2",
                "noFontHook");
        Set<String> wrote = CNDebugOverlay.radioDesired(flags, 3, desired);
        check("[3a] 多文件时选一项后清掉多余",
                wrote.contains("tutorialSkipToBattle2")
                && !wrote.contains("tutorialSkipToBattle1")
                && !wrote.contains("tutorialSkipAfterBattle2"));
        check("[3b] 写回不动组外开关", wrote.contains("noFontHook") && wrote.size() == 2);
        check("[3c] 选默认项 → 组内全清",
                CNDebugOverlay.radioDesired(flags, 0, set("tutorialSkipToBattle3")).isEmpty());
        check("[3d] 越界下标不炸也不写",
                CNDebugOverlay.radioDesired(flags, 99, set("noFontHook")).equals(set("noFontHook"))
                && CNDebugOverlay.radioDesired(flags, -1, set()).isEmpty());

        // ── [4] 分组映射 ────────────────────────────────────────────
        check("[4a] 已知开关进对组",
                "C".equals(CNDebugOverlay.groupIdOf("noFontHook"))
                && "A".equals(CNDebugOverlay.groupIdOf("skipInstaller"))
                && "B".equals(CNDebugOverlay.groupIdOf("useAria2"))
                && "D".equals(CNDebugOverlay.groupIdOf("tutorialSkipAfterBattle3"))
                && "E".equals(CNDebugOverlay.groupIdOf("noAdxSampleRate"))
                && "F".equals(CNDebugOverlay.groupIdOf("failDownload")));
        check("[4b] 未知开关进「其他」（P6 降级）",
                "OTHER".equals(CNDebugOverlay.groupIdOf("brandNewFlag"))
                && "OTHER".equals(CNDebugOverlay.groupIdOf(null)));

        // 设计 §4.2：31 个面板开关 → 25 个控件（C 7→6，D 9→4）
        String[][] full = syntheticFullTable();
        int totalControls = 0;
        for (String g : new String[] {"A", "B", "C", "D", "E", "F", "OTHER"}) {
            List<CNDebugOverlay.Control> cs = CNDebugOverlay.controlsFor(full, g);
            totalControls += cs.size();
            if ("C".equals(g)) {
                check("[4c] C 类 7 开关合并成 6 个控件", cs.size() == 6);
            }
            if ("D".equals(g)) {
                int radios = 0;
                for (CNDebugOverlay.Control c : cs) {
                    if (c.type == CNDebugOverlay.Control.TYPE_RADIO) radios++;
                }
                check("[4d] D 类 9 开关合并成 4 个控件、跳段只有一枚单选",
                        cs.size() == 4 && radios == 1);
            }
        }
        check("[4e] 全表 31 开关 → 25 个控件", totalControls == 25);

        // ── [5] 注释表 ──────────────────────────────────────────────
        CNDebugOverlay.Note note = CNDebugOverlay.noteFor("noFontHook");
        check("[5a] 有注释的开关拿得到白话说明", note != null
                && note.plain != null && note.plain.length() > 0);
        check("[5b] 缺名降级为 null（界面只显示官方说明）",
                CNDebugOverlay.noteFor("noSuchFlag") == null
                && CNDebugOverlay.noteFor(null) == null);
        // 注释表里多出的名字（开关已删）静默忽略：表只按名查询，不遍历对全表——
        // 这条由 noteFor 的语义保证，这里钉「查询未知名不炸」即可。

        // ── [6] 待重启计数 ──────────────────────────────────────────
        String[][] t = {
                row("a", "1", "0"),
                row("b", "1", "1"),
                row("c", "0", "1"),
        };
        check("[6a] 磁盘≠生效才计待重启", CNDebugOverlay.countPending(t) == 2);
        check("[6b] null/空表不炸", CNDebugOverlay.countPending(null) == 0
                && CNDebugOverlay.countPending(new String[0][]) == 0);

        // ── [7] F 类分类名（设计修订：「注入」命中禁用词表，改「演习」）───
        CNDebugOverlay.GroupDef f = CNDebugOverlay.groupDef("F");
        check("[7a] F 类分类名是「故障演习 · 仅供开发」",
                "故障演习 · 仅供开发".equals(f.title));
        check("[7b] F 类标题与简介都不含禁用词「注入」",
                !f.title.contains("注入") && !f.blurb.contains("注入"));

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    private static Set<String> set(String... names) {
        return new HashSet<String>(Arrays.asList(names));
    }

    private static String[] row(String name, String onDisk, String onBoot) {
        String[] r = new String[CNDebugBridge.COLS];
        r[CNDebugBridge.COL_NAME] = name;
        r[CNDebugBridge.COL_DESC] = "";
        r[CNDebugBridge.COL_SIDE] = "java";
        r[CNDebugBridge.COL_ON_DISK] = onDisk;
        r[CNDebugBridge.COL_ON_BOOT] = onBoot;
        return r;
    }

    /**
     * 设计 §4.3 的 31 个面板可见开关（P7 已剔除 3 个）。测试自己拼这张表，
     * 因为 JVM 上没有 native 侧 19 行；控件合并逻辑吃的是表而不是常量名单。
     */
    private static String[][] syntheticFullTable() {
        String[] names = {
                // A 5
                "skipInstaller", "skipOverlay", "skipRestart", "skipSlowAsk", "noOverlayGate",
                // B 4
                "useAria2", "skipWebProxy", "noProxyEndpoint", "noHttp2Bump",
                // C 7
                "logI18nMiss", "logI18nMissAll", "noI18nLabel", "noI18nSetString",
                "noFontHook", "noInitLabelHook", "noTtfHooks",
                // D 9
                "skipTutorialPrompt", "noTutorialForce", "noTutorialGuard",
                "tutorialSkipToBattle1", "tutorialSkipAfterBattle1",
                "tutorialSkipToBattle2", "tutorialSkipAfterBattle2",
                "tutorialSkipToBattle3", "tutorialSkipAfterBattle3",
                // E 1
                "noAdxSampleRate",
                // F 5
                "failConfigFetch", "failVersionQuery", "slowVersionQuery",
                "failDownload", "failHotUpdateApply",
        };
        List<String[]> rows = new ArrayList<String[]>();
        for (int i = 0; i < names.length; i++) rows.add(row(names[i], "0", "0"));
        return rows.toArray(new String[rows.size()][]);
    }
}
