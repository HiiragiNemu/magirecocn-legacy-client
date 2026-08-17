package io.kamihama.magianative;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 调试悬浮窗的<b>接线层</b>：把两侧的开关表、停留、单包重下、日志导出收成一组
 * 不带任何界面代码的静态方法，供 {@code CNDebugOverlay}（悬浮窗本体）调用。
 *
 * <p>本类<b>不碰任何 View</b>。这么切是有理由的：悬浮窗本体会反复改（布局、
 * 配色、摆放），而下面这些语义——「哪些开关存在」「写进去要满足什么」「重下走
 * 哪条路」——不该跟着一起动。本仓库刚被浮层返工教育过一次：显示和逻辑缠在一起
 * 时，改显示会连带打断正在接的功能线。
 *
 * <h2>总闸烧在包里</h2>
 *
 * 悬浮窗允不允许出现，由 native 的 {@code DEBUG_OVERLAY_ENABLED} 决定
 * （见 {@code MagiaLegacy.cpp}），<b>没有任何运行时手段能改它</b>。公测结束就
 * 把那个 1 改成 0 出包，一步收回；内部测试包编译时传
 * {@code -DMAGIA_DEBUG_OVERLAY=1} 覆盖，不受影响。
 *
 * <p>曾经的方案是「先用 su 建一个 {@code enableOverlay} 文件自举」。它错在
 * 门槛正好架在目标受众面前：能建那个文件的人本来就能直接 touch 开关，而真正
 * 需要悬浮窗的人建不出来。{@code android:debuggable} 已经这么白开了两天又收
 * 回去（{@code f38ffea2} 打开，两天后收回）——要用它得会 adb 或 Termux，
 * 而实际会用的人几乎没有。同一个错误不犯第二次。
 *
 * <h2>🔴 边界</h2>
 *
 * 悬浮窗只是这些开关的<b>另一个写入口</b>，不改变开关本身能做什么。
 * {@link CNDebugFlags} 那条「开关只退功能、绝不退防线」的边界原样成立，
 * 且 {@code tools/check-debug-flag-boundary.py} 另有一条规则：
 * {@code CNDebugBridge} / {@code CNDebugOverlay} 不得出现在任何安全判据里
 * ——否则等于开了一条「界面上点一下就能碰防线」的路。
 *
 * <h2>给不了「即时生效」</h2>
 *
 * 开关只在进程启动时读一次，两侧都是（{@code CNDebugFlags.ensureLoaded} 的
 * {@code if (loaded) return}；native 的 {@code JNI_OnLoad → loadDebugFlags}）。
 * 而且 {@code noInitLabelHook} / {@code noTtfHooks} 决定的是<b>钩子装不装</b>，
 * {@code JNI_OnLoad} 跑完就定死了。所以本类提供的是「一键写文件 + 一键重启」，
 * 不是热生效——{@link #flagTable} 因此把「磁盘上」和「正在生效」分成两列，
 * 别把它们合并掉。
 */
public final class CNDebugBridge {

    private static final String TAG = "CNDebugBridge";

    /** {@link #flagTable} 的列序。 */
    public static final int COL_NAME     = 0;
    /** 说明文字（两侧表里自带的那句）。 */
    public static final int COL_DESC     = 1;
    /** {@code "java"} 或 {@code "native"}——决定它由哪一侧读。 */
    public static final int COL_SIDE     = 2;
    /** 磁盘上有没有这个文件，{@code "1"}/{@code "0"}：<b>下次启动</b>会不会生效。 */
    public static final int COL_ON_DISK  = 3;
    /** 本次进程启动时读到的值，{@code "1"}/{@code "0"}：<b>现在</b>生效没有。 */
    public static final int COL_ON_BOOT  = 4;
    /** 一行有几列。 */
    public static final int COLS         = 5;

    /** HUD 小字最多点名几个开关，超出的折成「+N」。 */
    private static final int HUD_MAX_NAMES = 4;

    /** native 表是扁平三元组：名字, 说明, 当前是否生效。 */
    private static final int NATIVE_COLS = 3;

    /**
     * 不进面板的「断同步」开关（设计理念 §2-P7）。
     *
     * <p>这三个开关的效果是「让远端配置/更新对这台客户端失效」：版本检查、热更、
     * 线路配置（换线、proxy 模式、force_aria2 都走 config.json）。客户端版本号烧在
     * native 里，APK 外观完全一样——玩家一旦切断下发通道，没有任何外部信号能发现
     * 他已掉队，图标、版本号全都正常，只是我们再也推不动他。所以它们<b>一律不进
     * 面板</b>，只保留给维护者 su 手动排障用。
     *
     * <p>过滤只在面板视图（{@link #flagTable} / {@link #allFlagNames}）出口做：
     * {@link #activeFlags} / {@link #hudText} / {@link #hasPendingChanges}
     * <b>不过滤</b>——维护者手动打开时 HUD 照常列出，开发者看截图依然能发现。
     * 过滤名单放在接线层而不是界面层，是为了出错风险归一到一处（§11-6d）。
     */
    private static final Set<String> PANEL_HIDDEN = new HashSet<String>(java.util.Arrays.asList(
            "skipVersionCheck",
            "skipHotUpdate",
            "skipMirrorConfig"));

    private static volatile Boolean allowedCache;
    private static volatile boolean active;

    private CNDebugBridge() {}

    // ══ 总闸 ═════════════════════════════════════════════════════════

    /**
     * 本包允不允许调试悬浮窗。<b>问不到时返回 false</b>。
     *
     * <p>要区分「问不到」和「明确是关」的调用方（挂载看门狗就是）必须用
     * {@link #overlayGate()}，别用这个——把两者压成一个 false 已经害过一次，
     * 见那边的注释。
     */
    public static boolean overlayAllowed() {
        Boolean gate = overlayGate();
        return gate != null && gate.booleanValue();
    }

    /**
     * 总闸的<b>三态</b>：{@code TRUE}/{@code FALSE} = native 明确答复；
     * {@code null} = <b>现在还问不到</b>（库没加载）。
     *
     * <h3>为什么必须是三态</h3>
     *
     * 「问不到」和「明确是关」压成同一个 false，就是 2026-08-13 第二次真机失败：
     *
     * <pre>
     *   16:15:33.521  UnsatisfiedLinkError（库还没加载）
     *   16:15:33      调试悬浮窗：等到 Activity（1ms），总闸=关   ← 据此放弃
     *   16:15:33.522  Load libMagiaLegacy.so … ok
     *   16:15:33.522  [DEBUG] 调试悬浮窗总闸: 开
     * </pre>
     *
     * 相差<b>一毫秒</b>。当时的挂载看门狗以为「等到 Activity 就说明库加载好了」
     * ——错的：{@code RestClient.getCurrentActivity()} 在 {@code onCreate} 里
     * 比 {@code System.loadLibrary} 更早被设上。
     *
     * <p>看门狗要等的从来不是 Activity，而是<b>一个确定的答案</b>。有了三态，
     * 它可以「继续等」而不是「据此放弃」。
     */
    public static Boolean overlayGate() {
        Boolean cached = allowedCache;
        if (cached != null) return cached;
        try {
            boolean ok = nativeDebugOverlayEnabled();
            allowedCache = Boolean.valueOf(ok);
            return allowedCache;
        } catch (Throwable t) {
            // 库还没加载（UnsatisfiedLinkError）。**不缓存**，下次再问。
            // 只记一次，免得 HUD 每秒刷新时刷屏。
            if (!warnedNoNative) {
                warnedNoNative = true;
                CNLog.i(TAG, "暂时取不到调试总闸（native 库还没加载），稍后重试: " + t);
            }
            return null;
        }
    }

    /** 「取不到总闸」只记一行，别跟着 HUD 刷新刷屏。 */
    private static volatile boolean warnedNoNative;

    /**
     * 悬浮窗<b>此刻真的挂在屏幕上</b>没有。由本体在挂载/摘除时调
     * {@link #setActive}。
     *
     * <p>浮层里的「重下」「停留」用它决定要不要让位（见
     * {@code CNDownloadUiAssist}）。判据是「真的挂上了」而不是「允许挂」，
     * 这个区别很重要：本体还没实现、权限没授予、挂载抛异常——任何一种情况下
     * 浮层里的按钮都必须<b>原样留着</b>，否则玩家会连修复手段一起失去。
     */
    public static boolean isActive() {
        return active && overlayAllowed();
    }

    /** 悬浮窗本体挂上/摘掉时调。 */
    public static void setActive(boolean value) {
        if (active == value) return;
        active = value;
        CNLog.i(TAG, "调试悬浮窗" + (value ? "已挂载" : "已摘除"));
        // 让浮层立刻把冗余按钮收掉/放回来，不必等下一次刷新。
        try { CNDownloadUiAssist.ensureInstalled(); } catch (Throwable ignore) {}
    }

    /**
     * 唯一的挂载入口：允许就把悬浮窗本体叫起来。任何情况下都不抛。
     *
     * <p>用反射找本体。<b>最初</b>的理由是接线先于本体落地——那时
     * {@code CNDebugOverlay} 还不存在，直接引用连编译都过不去。本体现在已经有了，
     * 但反射<b>保留</b>，因为第二个理由一直成立：本仓库的 dex 分组是按文件名
     * 排除法分的，某个类漏进任何一组就会「编译得出 .class 却进不了 dex」，真机上
     * 直接 NoClassDefFoundError（{@code CNBgm} 撞过一次，静态检查一律看不见）。
     * 走反射时那种事故只是悬浮窗不出现，游戏照跑；直接引用则是整条安装线炸掉。
     */
    public static boolean mount(Activity act) {
        if (act == null || !overlayAllowed()) return false;
        try {
            Class<?> body = Class.forName("io.kamihama.magianative.CNDebugOverlay");
            Object r = body.getMethod("mount", Activity.class).invoke(null, act);
            return (r instanceof Boolean) && ((Boolean) r).booleanValue();
        } catch (ClassNotFoundException e) {
            // 本体本该在（已随接线一起入库）。走到这里说明它没进 dex——
            // 见上方注释里那条 dex 分组的坑，日志要说得出这个可能性。
            CNLog.w(TAG, "调试总闸是开的，但找不到 CNDebugOverlay"
                    + "（多半是它没进任何一组 dex，见 build-apk.yml 的分组）");
            return false;
        } catch (Throwable t) {
            CNLog.w(TAG, "调试悬浮窗挂载失败（不影响游戏）: " + t);
            return false;
        }
    }

    // ══ 开关表 ═══════════════════════════════════════════════════════

    /**
     * 合并两侧的开关全表，每行 {@link #COLS} 列（列序见 {@code COL_*}）。
     * Java 侧在前、native 侧在后，各自保持表内原顺序。
     *
     * <p>表是<b>生成</b>的不是硬编码的：硬编码的副本一定会过期，而两边不一致时
     * 人只会得出「这个开关坏了」这个错结论，恰恰是这套开关最不该造成的效果。
     * native 侧取不到（库没起来/没绑上）就只回 Java 侧那份，不抛。
     */
    public static String[][] flagTable() {
        return flagTable(true);
    }

    /**
     * {@link #flagTable()} 的不过滤版本与过滤版本的公共实现。
     * {@code forPanel} 为 true 时剔除 {@link #PANEL_HIDDEN}（P7）；
     * activeFlags / hasPendingChanges 走 false 那条，维护者手动开的断同步开关
     * 照样能被 HUD 看见。
     */
    private static String[][] flagTable(boolean forPanel) {
        Set<String> disk = CNDebugFlags.onDisk();
        List<String[]> rows = new ArrayList<String[]>();

        String[][] java = CNDebugFlags.knownTable();
        for (int i = 0; i < java.length; i++) {
            String name = java[i][0];
            if (forPanel && PANEL_HIDDEN.contains(name)) continue;
            rows.add(row(name, java[i][1], "java",
                    disk.contains(name), CNDebugFlags.isOn(name)));
        }

        String[] nat = nativeTableSafe();
        for (int i = 0; nat != null && i + NATIVE_COLS <= nat.length; i += NATIVE_COLS) {
            String name = nat[i];
            if (name == null || name.length() == 0) continue;
            if (forPanel && PANEL_HIDDEN.contains(name)) continue;
            rows.add(row(name, nat[i + 1], "native",
                    disk.contains(name), "1".equals(nat[i + 2])));
        }

        return rows.toArray(new String[rows.size()][]);
    }

    private static String[] row(String name, String desc, String side,
                                boolean onDisk, boolean onBoot) {
        String[] r = new String[COLS];
        r[COL_NAME]    = name;
        r[COL_DESC]    = desc == null ? "" : desc;
        r[COL_SIDE]    = side;
        r[COL_ON_DISK] = onDisk ? "1" : "0";
        r[COL_ON_BOOT] = onBoot ? "1" : "0";
        return r;
    }

    private static String[] nativeTableSafe() {
        try {
            return nativeDebugFlagTable();
        } catch (Throwable t) {
            CNLog.i(TAG, "取不到 native 开关表，只列 Java 侧: " + t);
            return null;
        }
    }

    /** 全表的名字集合——{@link CNDebugFlags#writeState} 的 universe。 */
    public static List<String> allFlagNames() {
        String[][] table = flagTable();
        List<String> names = new ArrayList<String>(table.length);
        for (int i = 0; i < table.length; i++) names.add(table[i][COL_NAME]);
        return names;
    }

    /**
     * 把勾选结果落盘，然后重启。{@code desired} 里没有的开关会被删掉。
     *
     * <p>落盘与重启<b>分两步且都可能失败</b>，所以返回值只说「写没写成」：
     * 写失败就不该重启（白重启一次还找不到原因）；写成功但重启没起来时，
     * {@link CNRestart} 自己会提示玩家手动重开。
     */
    public static boolean applyAndRestart(Set<String> desired) {
        int changed = CNDebugFlags.writeState(desired, allFlagNames());
        if (changed < 0) return false;
        try {
            CNRestart.restartWithNotice(
                    changed == 0 ? "调试开关无改动，3 秒后重启"
                                 : ("已改 " + changed + " 个调试开关，3 秒后重启生效"),
                    3000L);
        } catch (Throwable t) {
            CNLog.w(TAG, "调试开关已写入，但重启失败（请手动重开游戏）: " + t);
        }
        return true;
    }

    // ══ 常驻小字（HUD）══════════════════════════════════════════════

    /**
     * 当前<b>真正在生效</b>的开关名（本次启动读到的那份），两侧合并。
     *
     * <p>取 boot 状态而不是磁盘状态：小字要回答的是「这局游戏现在为什么是
     * 这个行为」，而不是「下次启动会怎样」。勾了没重启的那些属于后者。
     */
    public static List<String> activeFlags() {
        // 不过滤（P7）：维护者 su 手动打开的断同步开关也必须在这里看得见。
        return activeFlagsOf(flagTable(false));
    }

    /**
     * {@link #activeFlags} 的纯函数部分：给定全表，挑出「正在生效」的名字。
     * 与 {@link #formatHud} 同一个拆法——测试若照抄一份，测的就是副本不是真代码。
     */
    public static List<String> activeFlagsOf(String[][] table) {
        List<String> on = new ArrayList<String>();
        for (int i = 0; table != null && i < table.length; i++) {
            if (table[i] != null && table[i].length >= COLS
                    && "1".equals(table[i][COL_ON_BOOT])) on.add(table[i][COL_NAME]);
        }
        return on;
    }

    /** 磁盘上与正在生效的不一致——即「改了还没重启」。 */
    public static boolean hasPendingChanges() {
        // 不过滤，理由同 activeFlags。
        return pendingIn(flagTable(false));
    }

    /** {@link #hasPendingChanges} 的纯函数部分。 */
    public static boolean pendingIn(String[][] table) {
        for (int i = 0; table != null && i < table.length; i++) {
            if (table[i] != null && table[i].length >= COLS
                    && !table[i][COL_ON_DISK].equals(table[i][COL_ON_BOOT])) return true;
        }
        return false;
    }

    /**
     * 常驻小字的内容；没有任何开关生效且无待重启改动时返回 {@code null}。
     *
     * <p>这行字要一直浮在所有页面上，是<b>本方案里唯一给普通玩家的保障</b>：
     * 悬浮窗把改开关的门槛降到点两下之后，「玩家自己点开了降级模式却不知道，
     * 然后来报游戏坏了」就成了最现实的风险。把生效中的开关一直摆在屏幕上，
     * 这个风险基本消失——他自己看得见，截图报错时我们也看得见。
     *
     * <p>所以它<b>不跟随悬浮窗的显示与否</b>：悬浮窗收起来了，小字照旧。
     * 名字多到摆不下时折成「+N」，但绝不整行省略。
     */
    public static String hudText() {
        return formatHud(activeFlags(), hasPendingChanges());
    }

    /**
     * {@link #hudText} 的纯函数部分：给定「生效中的开关」与「有无待重启改动」
     * 排版成一行。单独抽出来是为了能在没有 native、没有磁盘的 JVM 上直接测
     * ——测试若照抄一份排版逻辑，测的就是那份副本而不是真代码。
     */
    public static String formatHud(List<String> on, boolean pending) {
        if (on == null) on = new ArrayList<String>();
        if (on.isEmpty() && !pending) return null;
        StringBuilder sb = new StringBuilder("调试模式");
        if (!on.isEmpty()) {
            sb.append('：');
            int shown = Math.min(on.size(), HUD_MAX_NAMES);
            for (int i = 0; i < shown; i++) {
                if (i > 0) sb.append(" · ");
                sb.append(on.get(i));
            }
            if (on.size() > shown) sb.append(" +").append(on.size() - shown);
        }
        if (pending) sb.append("（有改动待重启）");
        return sb.toString();
    }

    // ══ 停留（委托 CNDownloadUiAssist）══════════════════════════════
    //
    // 这两个连同下面的重下，原先是下载浮层顶栏/资源行里的胶囊。搬进悬浮窗是
    // 因为浮层只在下载页存在，而这些恰恰是「卡住了要自救」时才想用的东西。
    // 动作逻辑本来就和按钮分开（CNDownloadUiAssist / CNManualRedownload 的
    // public 方法），这里只是转一道手，没有第二份状态。

    public static boolean isStay() {
        try { return CNDownloadUiAssist.shouldStayOnPage(); }
        catch (Throwable t) { return false; }
    }

    /** 停留=留在资源页；取消停留=放行进游戏（与浮层顶栏那颗胶囊同一状态）。 */
    public static void setStay(boolean stay) {
        try {
            if (!stay) {
                // 「进入游戏」得先问重下协调器：还有任务在跑就不能走。
                Activity act = RestClient.getCurrentActivity();
                if (CNManualRedownload.handleLeaveRequest(act)) return;
            }
            CNDownloadUiAssist.setStayOnPage(stay);
        } catch (Throwable t) {
            CNLog.w(TAG, "切换停留失败: " + t);
        }
    }

    // ══ 单包重下（委托 CNManualRedownload）══════════════════════════

    /** 15 个资源包的显示名，与浮层列表同序。 */
    public static String[] resourceNames() {
        String[] names = CNCNDownloadUI.FILE_NAMES;
        return names == null ? new String[0] : names.clone();
    }

    /** 该包是不是正在（手动或安装器）处理。 */
    public static boolean isBusy(int index) {
        try {
            if (CNManualRedownload.isRunning(index)) return true;
            int[] st = CNCNDownloadUI.fileStatus;
            return st != null && index >= 0 && index < st.length
                    && st[index] == CNCNDownloadUI.ST_RUNNING;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 正在跑的手动重下任务数（上限 3，见 CNManualRedownload）。 */
    public static int busyCount() {
        try { return CNManualRedownload.runningCount(); }
        catch (Throwable t) { return 0; }
    }

    /**
     * 重下一个包。<b>调用方必须先自己弹确认框</b>——这里不弹，因为确认框是界面。
     *
     * <p>会顺带把页面钉在资源页（{@code setStayOnPage(true)}，由
     * {@code CNManualRedownload.request} 自己做）：重下期间被引擎带进游戏，
     * 下载会在半路失去浮层的进度反馈。
     */
    public static void redownload(int index) {
        try {
            CNManualRedownload.request(RestClient.getCurrentActivity(), index);
        } catch (Throwable t) {
            CNLog.w(TAG, "重下 index=" + index + " 失败: " + t);
        }
    }

    // ══ 日志 ═════════════════════════════════════════════════════════

    /**
     * 把启动日志打包并走系统分享。返回打好的文件，失败返回 {@code null}。
     *
     * <p>先 {@link CNLog#flushNow()}：攒着的 logcat 行不落盘的话，导出的包会比
     * 实际少一段，而少的那段往往正是刚出问题的那段。
     */
    /**
     * F-R5-01：chooser 是否没能起来（Runnable 在主线程跑完才可知，shareLog 本体
     * 异步返回，靠这个包级标志把失败传回 {@code ShareLogResult}）。每次 shareLog
     * 开头复位。
     */
    static volatile boolean shareChooserFailed;

    public static File shareLog(Activity act) {
        try {
            shareChooserFailed = false;   // 复位放最前：本次调用的结果不带上一次的残留
            if (act == null) return null;
            CNLog.flushNow();
            File out = CNLogBundle.write(act, CNLog.logDirPath());
            if (out == null) return null;
            // 编译 classpath 没有 androidx，用自带的只读 provider 临时授权
            // （只开 cacheDir/share/，见 CNLogShareProvider）。
            Uri uri = Uri.parse("content://" + CNLogShareProvider.AUTHORITY
                    + "/" + Uri.encode(out.getName()));
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // F-R4-01：startActivity 从后台线程起 Activity 在个别 OEM/版本上有
            // 不确定性——shareLog 被设计成可在后台线程调（flush+打包不能卡 UI），
            // 但起 chooser 这一步要回主线程。已在主线程就直发，否则 post 回去。
            final Intent chooser = Intent.createChooser(send, "分享日志");
            final Activity a = act;
            final Runnable launch = new Runnable() {
                @Override public void run() {
                    try {
                        a.startActivity(chooser);
                    } catch (Throwable t) {
                        // F-R5-01：失败如实上报，别让「日志包好了」骗过玩家——
                        // chooser 没起来，包是白打的。
                        CNLog.w(TAG, "起分享 chooser 失败: " + t);
                        shareChooserFailed = true;
                    }
                }
            };
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                launch.run();
            } else {
                a.runOnUiThread(launch);
            }
            return out;
        } catch (Throwable t) {
            CNLog.w(TAG, "分享日志失败: " + t);
            return null;
        }
    }

    /** 悬浮窗权限（API 23+ 要用户手动授予）。低版本恒为 true。 */
    public static boolean canDrawOverlays(Context ctx) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 23) return true;
            return android.provider.Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 跳到系统的悬浮窗授权页。拉不起来返回 false，由调用方提示手动去设置里找。 */
    public static boolean requestOverlayPermission(Activity act) {
        try {
            if (act == null || android.os.Build.VERSION.SDK_INT < 23) return false;
            Intent i = new Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
            return true;
        } catch (Throwable t) {
            CNLog.w(TAG, "拉不起悬浮窗授权页: " + t);
            return false;
        }
    }

    // ══ native ═══════════════════════════════════════════════════════
    //
    // 两个都在 JNI_OnLoad 里经 RegisterNatives 绑定（MagiaLegacy.cpp）。
    // 绑不上时调用抛 UnsatisfiedLinkError，上面每个调用点都接住了。

    /** 包里烧的调试总闸。 */
    private static native boolean nativeDebugOverlayEnabled();

    /** native 侧开关全表，扁平三元组 {名字, 说明, 当前是否生效}。 */
    private static native String[] nativeDebugFlagTable();

    // ---- JVM 回归测试入口 ----
    // 测试跑在没有 native 库的 JVM 上，总闸恒为「关」，所以要能强制置位。
    public static void setAllowedForTest(boolean value) {
        allowedCache = Boolean.valueOf(value);
    }
    public static void resetForTest() {
        allowedCache = null;
        active = false;
        warnedNoNative = false;
    }
    /**
     * 总闸的缓存现状：{@code null} = 还没问到过（下次会重试）。
     *
     * <p>专门给「问不到时不缓存」那条用例。这个区别在真机上的表现是
     * 「悬浮窗永远不出现」，靠人眼复查发现不了——只能靠钉这一条。
     */
    public static Boolean cachedForTest() { return allowedCache; }
    public static int hudMaxNamesForTest() { return HUD_MAX_NAMES; }
    public static Set<String> newSetForTest() { return new HashSet<String>(); }
    /** 不过滤（P7）的全表：给「维护者手动开的开关仍出现在 activeFlags」用例用。 */
    public static String[][] rawFlagTableForTest() { return flagTable(false); }
    /** P7 过滤名单的只读校验口：名单长什么样由本类说了算。 */
    public static boolean panelHidesForTest(String name) { return PANEL_HIDDEN.contains(name); }
}
