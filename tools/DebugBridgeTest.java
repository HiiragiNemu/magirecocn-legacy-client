import io.kamihama.magianative.CNDebugBridge;
import io.kamihama.magianative.CNDebugFlags;

import java.util.ArrayList;
import java.util.List;

/**
 * 调试悬浮窗接线层的回归测试。
 *
 * <p>跑在没有 native 库、没有设备目录的 JVM 上，所以只钉<b>纯逻辑</b>那几条：
 * 总闸的 fail-closed 语义、接管判据、HUD 排版、开关名的形状白名单。
 * 挂载、写盘、重启这些都要真环境，靠真机验收（见设计文档「验收」一节）。
 */
public class DebugBridgeTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    public static void main(String[] args) {
        // ── [1] 总闸：取不到 native 一律按「关」 ──────────────────────
        // 这条是分界，不是可用性：库没起来时与其猜个宽松默认值，不如不出现。
        CNDebugBridge.resetForTest();
        check("[1a] 没有 native 库时总闸按关处理", !CNDebugBridge.overlayAllowed());
        check("[1b] 总闸关时 isActive 恒假", !CNDebugBridge.isActive());

        // [1c] 真机 bug 的回归（2026-08-13）：libMagiaLegacy.so 在
        // Cocos2dxActivity 里链式加载，而 triggerInstaller 跑在 Application
        // .onCreate 的线程上——比它早。第一次问总闸必然 UnsatisfiedLinkError。
        // 早先把这个失败缓存成 false，于是整个会话不再重试：悬浮窗永不出现，
        // 连权限提示都到不了，症状看起来像「功能没做进去」。
        //
        // JVM 上没有 native 库，这里走的正是那条抛异常的路。
        check("[1c] 问不到总闸时**不缓存**，下次还会重试",
                CNDebugBridge.cachedForTest() == null);
        // [1c2] 2026-08-13 第二次真机失败：看门狗把「问不到」当成「明确是关」，
        // 于是在库加载前 1 毫秒放弃。三态是这条的唯一防线——问不到必须是 null，
        // 而不是 FALSE，否则调用方无从选择「继续等」。
        check("[1c2] 问不到时 overlayGate() 是 null，不是 FALSE",
                CNDebugBridge.overlayGate() == null);
        CNDebugBridge.setAllowedForTest(true);
        check("[1d] 稍后问到了就认这个答案", CNDebugBridge.overlayAllowed()
                && Boolean.TRUE.equals(CNDebugBridge.cachedForTest()));
        check("[1d2] 问到之后 overlayGate() 给出确定答案",
                Boolean.TRUE.equals(CNDebugBridge.overlayGate()));
        CNDebugBridge.setAllowedForTest(false);
        check("[1e] 明确是关时 overlayGate() 是 FALSE（可据此放弃，与 null 不同）",
                Boolean.FALSE.equals(CNDebugBridge.overlayGate()));
        CNDebugBridge.resetForTest();

        // ── [2] 接管判据：允许挂 ≠ 已经挂上 ─────────────────────────
        // 浮层靠这条决定要不要撤掉「停留」「重下」。判错的代价是玩家卡住时
        // 失去自救手段，所以两个条件必须同时成立才算接管。
        CNDebugBridge.setAllowedForTest(true);
        check("[2a] 允许挂但没挂上时不算接管", !CNDebugBridge.isActive());
        CNDebugBridge.setActive(true);
        check("[2b] 允许且已挂上才算接管", CNDebugBridge.isActive());
        CNDebugBridge.setAllowedForTest(false);
        check("[2c] 总闸一关立刻不再接管", !CNDebugBridge.isActive());
        CNDebugBridge.resetForTest();
        check("[2d] reset 后回到未挂载", !CNDebugBridge.isActive());

        // ── [3] HUD 排版 ────────────────────────────────────────────
        int max = CNDebugBridge.hudMaxNamesForTest();
        check("[3a] 无开关无待改时不显示", CNDebugBridge.formatHud(names(0), false) == null);

        String one = CNDebugBridge.formatHud(names(1), false);
        check("[3b] 一个开关时点名", one != null && one.contains("flag0")
                && !one.contains("+"));

        String exact = CNDebugBridge.formatHud(names(max), false);
        check("[3c] 正好摆得下时不折叠", exact != null && !exact.contains("+"));

        String over = CNDebugBridge.formatHud(names(max + 3), false);
        check("[3d] 超出上限折成 +N", over != null && over.contains("+3"));
        check("[3e] 折叠后仍点名前几个", over != null && over.contains("flag0"));

        // 「改了还没重启」必须能看出来：开关只在启动时读一次，勾完不重启
        // 是无效的，而这正是最容易让人误判「开关坏了」的时刻。
        String pending = CNDebugBridge.formatHud(names(0), true);
        check("[3f] 只有待重启改动时也要显示", pending != null
                && pending.contains("待重启"));
        check("[3g] 有开关且有待重启改动时两者都提",
                over != null && CNDebugBridge.formatHud(names(2), true).contains("待重启"));
        check("[3h] null 入参不炸", CNDebugBridge.formatHud(null, false) == null);

        // ── [4] 开关名形状白名单 ────────────────────────────────────
        // 这个目录不是通用文件柜。没有这条，界面上的一个 bug 就能在应用私有
        // 目录里建出任意路径的文件。
        check("[4a] 正常小驼峰放行", CNDebugFlags.nameShapeOkForTest("skipHotUpdate"));
        check("[4b] 拒绝路径分隔符", !CNDebugFlags.nameShapeOkForTest("a/b"));
        check("[4c] 拒绝上跳目录", !CNDebugFlags.nameShapeOkForTest("../x"));
        check("[4d] 拒绝点号", !CNDebugFlags.nameShapeOkForTest("a.b"));
        check("[4e] 拒绝空串", !CNDebugFlags.nameShapeOkForTest(""));
        check("[4f] 拒绝 null", !CNDebugFlags.nameShapeOkForTest(null));
        check("[4g] 拒绝前导数字", !CNDebugFlags.nameShapeOkForTest("1flag"));
        check("[4h] 拒绝空格", !CNDebugFlags.nameShapeOkForTest("a b"));

        // ── [5] Java 侧全表 ─────────────────────────────────────────
        String[][] known = CNDebugFlags.knownTable();
        check("[5a] Java 侧全表非空", known.length > 0);
        boolean shaped = true;
        for (int i = 0; i < known.length; i++) {
            if (known[i].length != 2 || known[i][0] == null || known[i][0].isEmpty()
                    || known[i][1] == null || known[i][1].isEmpty()) shaped = false;
            if (!CNDebugFlags.nameShapeOkForTest(known[i][0])) shaped = false;
        }
        check("[5b] 每行都是{名字,说明}且名字合法", shaped);

        // 副本改了不能影响原表——界面拿到的是可写数组。
        String was = known[0][0];
        known[0][0] = "篡改";
        check("[5c] knownTable 返回的是副本",
                was.equals(CNDebugFlags.knownTable()[0][0]));

        // 自举文件那套已经废弃：门槛烧在包里，不再有 enableOverlay 这个开关。
        boolean hasEnableOverlay = false;
        for (int i = 0; i < known.length; i++) {
            if ("enableOverlay".equals(CNDebugFlags.knownTable()[i][0])) {
                hasEnableOverlay = true;
            }
        }
        check("[5d] 不再有 enableOverlay 自举开关", !hasEnableOverlay);

        // ── [5e] P7：断同步开关不进面板，但 HUD 照常看得见 ───────────
        // skipVersionCheck / skipHotUpdate / skipMirrorConfig 会切断服务器→客户端的
        // 下发通道（设计理念 §2-P7），一律不进面板；过滤做在接线层出口（§11-6d），
        // 不抄进界面层。但维护者 su 手动打开时，activeFlags/HUD 必须照常列出——
        // 开发者看截图依然能发现。
        check("[5e] P7 过滤名单正是那三个断同步开关",
                CNDebugBridge.panelHidesForTest("skipVersionCheck")
                && CNDebugBridge.panelHidesForTest("skipHotUpdate")
                && CNDebugBridge.panelHidesForTest("skipMirrorConfig")
                && !CNDebugBridge.panelHidesForTest("skipInstaller"));

        String[][] panel = CNDebugBridge.flagTable();
        boolean leaked = false;
        for (int i = 0; i < panel.length; i++) {
            String n = panel[i][CNDebugBridge.COL_NAME];
            if ("skipVersionCheck".equals(n) || "skipHotUpdate".equals(n)
                    || "skipMirrorConfig".equals(n)) leaked = true;
        }
        check("[5f] 三开关不出现在 flagTable（面板视图）", !leaked && panel.length > 0);

        boolean namesLeak = false;
        List<String> all = CNDebugBridge.allFlagNames();
        for (int i = 0; i < all.size(); i++) {
            String n = all.get(i);
            if ("skipVersionCheck".equals(n) || "skipHotUpdate".equals(n)
                    || "skipMirrorConfig".equals(n)) namesLeak = true;
        }
        check("[5g] 三开关也不出现在 allFlagNames", !namesLeak);

        // 不过滤的全表里它们还在（JVM 上只有 Java 侧 15 行，三个都是 Java 侧开关），
        // 把 skipHotUpdate 标成「正在生效」后，activeFlags 的纯函数必须挑出它——
        // 这正是维护者手动打开时 HUD 会显示它的那条路。
        String[][] raw = CNDebugBridge.rawFlagTableForTest();
        boolean rawHas = false;
        boolean activeHas = false;
        for (int i = 0; i < raw.length; i++) {
            if ("skipHotUpdate".equals(raw[i][CNDebugBridge.COL_NAME])) {
                rawHas = true;
                raw[i][CNDebugBridge.COL_ON_BOOT] = "1";   // 模拟维护者手动打开并重启后
            }
        }
        List<String> active = CNDebugBridge.activeFlagsOf(raw);
        for (int i = 0; i < active.size(); i++) {
            if ("skipHotUpdate".equals(active.get(i))) activeHas = true;
        }
        check("[5h] 开着的 skipHotUpdate 仍出现在 activeFlags（不过滤）", rawHas && activeHas);

        // ── [6] 总闸关时拒绝写盘 ────────────────────────────────────
        // 分界写在代码里，不是只写在界面上：正式发布包翻了那个布尔之后，
        // 任何一处调用都不该还能写进调试目录。
        CNDebugBridge.resetForTest();
        List<String> universe = new ArrayList<String>();
        universe.add("skipHotUpdate");
        check("[6a] 总闸关时 writeState 拒绝并返回 -1",
                CNDebugFlags.writeState(CNDebugBridge.newSetForTest(), universe) == -1);

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    private static List<String> names(int n) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < n; i++) out.add("flag" + i);
        return out;
    }
}
