package io.kamihama.magianative;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.WeakHashMap;

/**
 * 下载浮层内部的显示、停留和单包重下载入口。
 *
 * <p>所有可见控件都挂在 {@link CNCNDownloadUI#overlayView} 内；关闭下载页即一起销毁。
 * 本类绝不向 decorView 添加悬浮面板，也绝不平移整个下载浮层或游戏画面。
 */
public final class CNDownloadUiAssist {
    /** buildOverlay 给中央内容和两条真实滚动容器使用的稳定标签。 */
    public static final String TAG_CONTENT_ROOT = "cn-download-content-root";
    public static final String TAG_H_SCROLL = "cn-download-content-hscroll";
    public static final String TAG_V_SCROLL = "cn-download-content-vscroll";
    /**
     * 资源行里那个「文字进度」。它必须是该行的<b>最后一个</b>孩子——右端与下面
     * 整宽进度条的右端对齐是原版的视觉基准，见 {@code CNCNDownloadUI.rebuildSlots}。
     * 「重下」按插到它前面，不能追加到它后面。
     */
    public static final String TAG_SLOT_INFO = "cn-download-slot-info";

    private static final String LEGACY_TAG = "cn-download-ui-assist";
    private static final String TAG_STAY = "cn-download-stay";
    private static final String TAG_DISPLAY = "cn-download-display";
    private static final String TAG_RELOAD = "cn-download-reload-";
    private static final String TAG_SPLIT = "cn-download-split";
    private static final String PREFS = "cnv_bootstrap_ui_assist";
    private static final String PREF_SCALE = "font_scale_pct";
    private static final String PREF_SPLIT = "split_left_pct";

    /**
     * 左右分界线可拖到的范围与默认值（左列占比 %）。
     *
     * <p>默认 38 = `CNCNDownloadUI` 建左右两列时给的 0.38f / 0.62f，不改原设计，
     * 只是让它可调。上下限留得比较紧：两边都还要放得下东西——左列是 Logo + 署名，
     * 右列是 15 个槽位行，谁被压到 20% 以下都只剩省略号，那种「调得动但没法用」
     * 的自由度不如不给。
     */
    private static final int SPLIT_MIN = 20;
    private static final int SPLIT_MAX = 70;
    private static final int SPLIT_DEFAULT = 38;
    private static final String OLD_LINGER =
            "即将进入游戏；点按浮层（如「教程」胶囊播序章）可稍作停留";
    private static final String NEW_LINGER =
            "检查已完成。可查看日志或管理资源；需要停留请使用“停留本页”。";

    private static final Object STAY_LOCK = new Object();
    private static final WeakHashMap<TextView, Float> BASE_TEXT_PX =
            new WeakHashMap<TextView, Float>();
    /** 图片的原始 {宽, 高}（px）。与 BASE_TEXT_PX 同理：永远从基准重算。 */
    private static final WeakHashMap<ImageView, int[]> BASE_IMAGE_PX =
            new WeakHashMap<ImageView, int[]>();

    /**
     * 本页布局的参考宽度（dp）。{@link #suggestedScale} 拿它当 100% 的基准。
     * 560 是返工前内容区宽度下限用的数，也就是这套布局当初排版时的目标宽度。
     */
    private static final float DESIGN_WIDTH_DP = 560f;

    private static View attachedOverlay;
    private static View contentRoot;
    private static HorizontalScrollView hScroll;
    private static ScrollView vScroll;
    private static TextView stayChip;
    private static TextView displayChip;
    private static TextView scaleLabel;
    private static SeekBar scaleSeek;
    private static FrameLayout confirmModal;
    private static FrameLayout displayModal;
    private static View splitHandle;
    private static SharedPreferences prefs;
    private static int scalePct = 100;
    private static int splitPct = SPLIT_DEFAULT;
    private static int baseContentWidth;
    private static volatile boolean stayRequested;
    private static volatile boolean leaveRequested;
    private static volatile Thread stayThread;

    private static final Runnable INSTALL = new InstallTask();
    private static final Runnable APPLY = new ApplyTask();
    private static final Runnable DETACH = new DetachTask();

    private CNDownloadUiAssist() {}

    /** 可从任意线程调用；浮层尚未创建时直接返回。 */
    public static void ensureInstalled() {
        if (CNCNDownloadUI.overlayView == null || CNCNDownloadUI.decorView == null) return;
        try {
            Looper main = Looper.getMainLooper();
            if (Looper.myLooper() == main) installOnMain();
            else {
                Handler h = CNCNDownloadUI.uiHandler;
                if (h == null) h = new Handler(main);
                h.post(INSTALL);
            }
        } catch (Throwable t) {
            try { CNLog.w("界面", "内嵌显示控件安装失败: " + t); } catch (Throwable ignore) {}
        }
    }

    public static boolean shouldStayOnPage() {
        return stayRequested;
    }

    /** “进入游戏”是一次性动作；热更新停留窗口消费后自动清掉。 */
    public static boolean consumeLeaveRequest() {
        synchronized (STAY_LOCK) {
            if (!leaveRequested) return false;
            leaveRequested = false;
            return true;
        }
    }

    /** 下载 UI 自己的模态框也必须阻止自动收页。 */
    public static boolean isModalOpen() {
        return confirmModal != null || displayModal != null;
    }

    /** 包内调用：教程“否”、单包重下和顶部胶囊共用同一停留状态。 */
    public static void setStayOnPage(boolean stay) {
        synchronized (STAY_LOCK) {
            stayRequested = stay;
            leaveRequested = !stay;
            STAY_LOCK.notifyAll();
        }
        if (stay) startStayWatchdog();
        else stopStayWatchdog();
        postInstall();
    }

    /** 首次安装收尾调用：玩家点了“停留本页”时一直等到其点“进入游戏”。 */
    public static void awaitReleaseIfRequested() {
        synchronized (STAY_LOCK) {
            while (stayRequested) {
                try {
                    STAY_LOCK.wait(250L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 浮层关闭前调用，终止所有引用和看门狗，保证控件绝不进入游戏主界面。 */
    public static void onOverlayDetached() {
        synchronized (STAY_LOCK) {
            stayRequested = false;
            leaveRequested = false;
            STAY_LOCK.notifyAll();
        }
        stopStayWatchdog();
        try {
            Handler h = CNCNDownloadUI.uiHandler;
            if (h != null) {
                h.removeCallbacks(INSTALL);
                h.removeCallbacks(APPLY);
            }
            if (Looper.myLooper() == Looper.getMainLooper()) detachOnMain();
            else if (h != null) h.post(DETACH);
            else detachRefs();
        } catch (Throwable t) {
            detachRefs();
        }
    }

    private static final class InstallTask implements Runnable {
        @Override public void run() { installOnMain(); }
    }

    private static final class ApplyTask implements Runnable {
        @Override public void run() {
            if (attachedOverlay != null && attachedOverlay == CNCNDownloadUI.overlayView
                    && CNCNDownloadUI.isShowing) {
                applyScale();
                styleScrollbars();
                refreshReloadStates();
            }
        }
    }

    private static final class DetachTask implements Runnable {
        @Override public void run() { detachOnMain(); }
    }

    private static void installOnMain() {
        FrameLayout overlay = CNCNDownloadUI.overlayView;
        if (overlay == null) return;
        removeLegacy();
        overlay.setTranslationX(0f);
        overlay.setTranslationY(0f);

        if (attachedOverlay != overlay) {
            attachedOverlay = overlay;
            contentRoot = overlay.findViewWithTag(TAG_CONTENT_ROOT);
            View hs = overlay.findViewWithTag(TAG_H_SCROLL);
            View vs = overlay.findViewWithTag(TAG_V_SCROLL);
            hScroll = hs instanceof HorizontalScrollView ? (HorizontalScrollView) hs : null;
            vScroll = vs instanceof ScrollView ? (ScrollView) vs : null;
            stayChip = null;
            displayChip = null;
            confirmModal = null;
            displayModal = null;
            splitHandle = null;
            baseContentWidth = 0;
            BASE_TEXT_PX.clear();
            loadPrefs(overlay.getContext());
        }

        replaceLinger(overlay);
        installStay(overlay);
        installDisplay(overlay);
        installReloads(overlay);
        installSplit();
        refreshReloadStates();
        styleStay();
        styleDisplay();
        styleScrollbars();
        applyScale();
        try { CNManualRedownload.recoverCompletedRequest(); } catch (Throwable ignore) {}

        Handler h = CNCNDownloadUI.uiHandler;
        if (h != null) {
            h.removeCallbacks(APPLY);
            h.postDelayed(APPLY, 120L);
            h.postDelayed(APPLY, 800L);
            h.postDelayed(APPLY, 1800L);
        }
        if (stayRequested) startStayWatchdog();
    }

    private static void loadPrefs(Context context) {
        if (prefs == null && context != null) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        if (prefs != null) {
            // 没调过时用按分辨率算的建议值；调过一次之后一律以玩家的选择为准。
            // 用 -1 而不是 100 当「没调过」的哨兵：100 是合法选择，分不出
            // 「玩家特意选了 100%」和「从没动过」。
            int saved = prefs.getInt(PREF_SCALE, -1);
            scalePct = saved < 0 ? suggestedScale(context) : clamp(saved, 75, 150);
        }
        // 分开写而不是并进上一行：字号那条一个字都不动，改分界线时不该连带
        // 出现在它的 diff 里。
        if (prefs != null) {
            splitPct = clamp(prefs.getInt(PREF_SPLIT, SPLIT_DEFAULT),
                    SPLIT_MIN, SPLIT_MAX);
        }
    }

    private static void detachOnMain() {
        removeLegacy();
        View overlay = attachedOverlay;
        if (overlay != null) {
            try {
                overlay.setTranslationX(0f);
                overlay.setTranslationY(0f);
            } catch (Throwable ignore) {}
        }
        detachRefs();
    }

    private static void detachRefs() {
        attachedOverlay = null;
        contentRoot = null;
        hScroll = null;
        vScroll = null;
        stayChip = null;
        displayChip = null;
        scaleLabel = null;
        scaleSeek = null;
        confirmModal = null;
        displayModal = null;
        splitHandle = null;
        baseContentWidth = 0;
        BASE_TEXT_PX.clear();
        BASE_IMAGE_PX.clear();
    }

    private static void removeLegacy() {
        try {
            ViewGroup decor = CNCNDownloadUI.decorView;
            if (decor == null) return;
            while (true) {
                View old = decor.findViewWithTag(LEGACY_TAG);
                if (old == null || !(old.getParent() instanceof ViewGroup)) break;
                ((ViewGroup) old.getParent()).removeView(old);
                CNLog.i("界面", "已移除旧版独立字/平移悬浮控件");
            }
        } catch (Throwable ignore) {}
    }

    private static void replaceLinger(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            CharSequence text = t.getText();
            if (text != null && OLD_LINGER.contentEquals(text)) t.setText(NEW_LINGER);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) replaceLinger(g.getChildAt(i));
        }
    }

    private static LinearLayout findTopLeftRow(View root) {
        TextView log = findText(root, "LOG");
        return log != null && log.getParent() instanceof LinearLayout
                ? (LinearLayout) log.getParent() : null;
    }

    /**
     * 调试悬浮窗<b>真的挂在屏幕上</b>时，浮层不再重复摆同一颗按钮。
     *
     * <p>判据用 {@link CNDebugBridge#isActive()}（真挂上了）而不是
     * 「允许挂」：本体没实现、权限没授予、挂载抛异常——任何一种情况下这些
     * 按钮都得原样留着。「停留」和「重下」是玩家卡住时自救的手段，不能因为
     * 另一处入口<b>可能</b>存在就先撤掉。
     */
    private static boolean overlayTookOver() {
        try { return CNDebugBridge.isActive(); }
        catch (Throwable t) { return false; }
    }

    private static void dropChip(LinearLayout row, String tag) {
        View old = row.findViewWithTag(tag);
        if (old != null) row.removeView(old);
    }

    private static void installStay(View root) {
        LinearLayout row = findTopLeftRow(root);
        if (row == null) return;
        if (overlayTookOver()) {
            dropChip(row, TAG_STAY);
            stayChip = null;
            return;
        }
        View existing = row.findViewWithTag(TAG_STAY);
        if (existing instanceof TextView) {
            stayChip = (TextView) existing;
            return;
        }
        TextView chip = createTopChip(row, TAG_STAY);
        chip.setOnClickListener(new StayClick());
        row.addView(chip, topChipLp(chip));
        stayChip = chip;
    }

    private static void installDisplay(View root) {
        LinearLayout row = findTopLeftRow(root);
        if (row == null) return;
        View existing = row.findViewWithTag(TAG_DISPLAY);
        if (existing instanceof TextView) {
            displayChip = (TextView) existing;
            return;
        }
        TextView chip = createTopChip(row, TAG_DISPLAY);
        chip.setOnClickListener(new DisplayClick());
        row.addView(chip, topChipLp(chip));
        displayChip = chip;
    }

    private static TextView createTopChip(LinearLayout row, String tag) {
        TextView template = null;
        for (int i = row.getChildCount() - 1; i >= 0; i--) {
            if (row.getChildAt(i) instanceof TextView) {
                template = (TextView) row.getChildAt(i);
                break;
            }
        }
        TextView chip = new TextView(row.getContext());
        chip.setTag(tag);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                template == null ? sp(chip, 11f) : template.getTextSize());
        chip.setTypeface(template == null ? Typeface.DEFAULT_BOLD : template.getTypeface(),
                Typeface.BOLD);
        int px = template == null ? dp(chip, 12) : template.getPaddingLeft();
        int py = template == null ? dp(chip, 6) : template.getPaddingTop();
        chip.setPadding(px, py, px, py);
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    private static LinearLayout.LayoutParams topChipLp(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(v, 8);
        return lp;
    }

    private static final class StayClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            Activity act = RestClient.getCurrentActivity();
            if (stayRequested && CNManualRedownload.handleLeaveRequest(act)) return;
            boolean next = !stayRequested;
            setStayOnPage(next);
            CNCNDownloadUI.noteInteraction();
            if (next) {
                CNCNDownloadUI.toast(act, "已停留；点“进入游戏”再离开资源页");
            } else if (CNHotUpdateCheck.isRunning() || CNDownloaderFix.isInstalling()) {
                CNCNDownloadUI.toast(act, "将在当前任务安全收尾后进入游戏");
            } else {
                CNCNDownloadUI.hide();
            }
        }
    }

    private static final class DisplayClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            Activity act = RestClient.getCurrentActivity();
            openDisplay(act);
            CNCNDownloadUI.noteInteraction();
        }
    }

    private static void styleStay() {
        TextView v = stayChip;
        if (v == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(v, 20));
        if (stayRequested) {
            v.setText("进入游戏");
            v.setTextColor(0xFFFFFFFF);
            bg.setColor(color("COLOR_ACCENT", 0xFFD63384));
        } else {
            v.setText("停留本页");
            v.setTextColor(color("COLOR_SUB", 0xFF6E5276));
            bg.setColor(0x00000000);
            bg.setStroke(dp(v, 1), color("COLOR_GLASS_STK", 0x55B53C8C));
        }
        v.setBackground(bg);
    }

    private static void styleDisplay() {
        TextView v = displayChip;
        if (v == null) return;
        v.setText("Aa " + scalePct + "%");
        v.setTextColor(color("COLOR_ACCENT2", 0xFF9C5BC2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00000000);
        bg.setCornerRadius(dp(v, 20));
        bg.setStroke(dp(v, 1), color("COLOR_ACCENT2", 0xFF9C5BC2));
        v.setBackground(bg);
    }

    /**
     * 把“重下”直接放进原资源行，不另造资源管理悬浮窗。
     *
     * <p>调试悬浮窗挂上时改由它接管，这里把已经加过的收掉——判据见
     * {@link #overlayTookOver()}。
     */
    private static void installReloads(View root) {
        String[] names = CNCNDownloadUI.FILE_NAMES;
        if (names == null) return;
        boolean tookOver = overlayTookOver();
        for (int i = 0; i < names.length; i++) {
            TextView name = findText(root, (i + 1) + ". " + names[i]);
            if (name == null || !(name.getParent() instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) name.getParent();
            String tag = TAG_RELOAD + i;
            if (tookOver) { dropChip(row, tag); continue; }
            if (row.findViewWithTag(tag) != null) continue;
            TextView b = new TextView(row.getContext());
            b.setTag(tag);
            b.setText("重下");
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(b, 8), dp(b, 2), dp(b, 8), dp(b, 2));
            b.setTextColor(color("COLOR_ACCENT2", 0xFF9C5BC2));
            b.setClickable(true);
            b.setFocusable(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x00000000);
            bg.setCornerRadius(dp(b, 12));
            bg.setStroke(dp(b, 1), color("COLOR_ACCENT2", 0xFF9C5BC2));
            b.setBackground(bg);
            b.setOnClickListener(new ReloadClick(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(b, 6);
            // 插到「文字进度」之前。追加到行尾等于把文字进度往左顶，它的右端就
            // 和下面那条整宽进度条错开了——原版这两条右边界是对齐的（TAG_SLOT_INFO）。
            View info = row.findViewWithTag(TAG_SLOT_INFO);
            int at = info != null ? row.indexOfChild(info) : row.getChildCount();
            if (at < 0) at = row.getChildCount();
            row.addView(b, at, lp);
        }
    }



    private static void refreshReloadStates() {
        View root = attachedOverlay;
        if (root == null) return;
        String[] names = CNCNDownloadUI.FILE_NAMES;
        for (int i = 0; names != null && i < names.length; i++) {
            View found = root.findViewWithTag(TAG_RELOAD + i);
            if (!(found instanceof TextView)) continue;
            TextView b = (TextView) found;
            boolean manual = CNManualRedownload.isRunning(i);
            boolean active = CNCNDownloadUI.fileStatus != null
                    && i < CNCNDownloadUI.fileStatus.length
                    && CNCNDownloadUI.fileStatus[i] == CNCNDownloadUI.ST_RUNNING;
            b.setText((manual || active) ? "重下" : "重下");
            b.setAlpha(1.0f);
        }
    }

    private static final class ReloadClick implements View.OnClickListener {
        private final int index;
        ReloadClick(int index) { this.index = index; }
        @Override public void onClick(View v) {
            Activity act = RestClient.getCurrentActivity();
            CNCNDownloadUI.noteInteraction();
            openConfirm(act, index);
        }
    }

    private static void openConfirm(Activity act, int index) {
        FrameLayout host = CNCNDownloadUI.overlayView;
        String[] names = CNCNDownloadUI.FILE_NAMES;
        if (act == null || host == null || names == null || index < 0 || index >= names.length) return;
        closeConfirm();

        FrameLayout modal = new FrameLayout(act);
        modal.setBackgroundColor(color("COLOR_DIM", 0x88000000));
        modal.setClickable(true);
        modal.setFocusable(true);
        modal.setOnClickListener(new CloseConfirmClick());

        LinearLayout panel = dialogPanel(act);
        panel.setOnClickListener(new ConsumeClick());
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                adaptiveDialogWidth(panel), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelLp.leftMargin = panelLp.rightMargin = dp(panel, 20);
        modal.addView(panel, panelLp);

        TextView title = text(act, "重新下载资源包", 16f,
                color("COLOR_ACCENT", 0xFFD63384));
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        panel.addView(title, rowLp(title, 0, 10));

        int[] status = CNCNDownloadUI.fileStatus;
boolean activeNow = status != null && index < status.length
        && status[index] == CNCNDownloadUI.ST_RUNNING;
String extra = activeNow
        ? "\n\n该文件当前正在处理。确认后会停止本文件当前传输、清除本文件断点并从头重下；其它文件不受影响。"
        : (index < 2
            ? "\n\n该包属于热更新通道；安装器完成后还会按版本清单补齐最新版本。"
            : "");
        TextView msg = text(act,
                "只重新下载：\n" + names[index]
                + "\n\n不要求其它资源已经下载齐全；不同 ZIP 可以同时下载。"
                + "旧 marker 与当前可用内容会保留到新包校验并提交成功。" + extra,
                13f, color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B));
        msg.setLineSpacing(dp(msg, 2), 1f);
        panel.addView(msg, rowLp(msg, 0, 18));

        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        panel.addView(buttons, rowLp(buttons, 0, 0));
        TextView cancel = dialogButton(act, "取消", false);
        TextView yes = dialogButton(act, "确认重下", true);
        cancel.setOnClickListener(new CloseConfirmClick());
        yes.setOnClickListener(new ConfirmReloadClick(act, index));
        buttons.addView(cancel);
        LinearLayout.LayoutParams yesLp = topChipLp(yes);
        yesLp.leftMargin = dp(yes, 10);
        buttons.addView(yes, yesLp);

        host.addView(modal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        confirmModal = modal;
    }

    private static final class ConfirmReloadClick implements View.OnClickListener {
        private final Activity act;
        private final int index;
        ConfirmReloadClick(Activity act, int index) { this.act = act; this.index = index; }
        @Override public void onClick(View v) {
            closeConfirm();
            setStayOnPage(true);
            CNManualRedownload.request(act, index);
        }
    }

    private static void openDisplay(Activity act) {
        FrameLayout host = CNCNDownloadUI.overlayView;
        if (act == null || host == null) return;
        closeDisplay();

        FrameLayout modal = new FrameLayout(act);
        modal.setBackgroundColor(color("COLOR_DIM", 0x88000000));
        modal.setClickable(true);
        modal.setFocusable(true);
        modal.setOnClickListener(new CloseDisplayClick());

        LinearLayout panel = dialogPanel(act);
        panel.setOnClickListener(new ConsumeClick());
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                adaptiveDialogWidth(panel), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelLp.leftMargin = panelLp.rightMargin = dp(panel, 20);
        modal.addView(panel, panelLp);

        TextView title = text(act, "显示大小", 16f,
                color("COLOR_ACCENT", 0xFFD63384));
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        panel.addView(title, rowLp(title, 0, 8));

        TextView explain = text(act,
                "只调整下载页中央内容，图片会跟着一起缩放。面板本身的左右边距按屏幕"
                + "分辨率固定，不随字号或分界线变动；文字放大后可用右侧纵向滚动条和"
                + "底部横向滚动条查看超出部分，游戏画面不会移动。",
                12.5f, color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B));
        explain.setLineSpacing(dp(explain, 2), 1f);
        panel.addView(explain, rowLp(explain, 0, 12));

        scaleLabel = text(act, "字体 " + scalePct + "%", 14f,
                color("COLOR_ACCENT2", 0xFF9C5BC2));
        scaleLabel.setGravity(Gravity.CENTER);
        scaleLabel.setTypeface(scaleLabel.getTypeface(), Typeface.BOLD);
        panel.addView(scaleLabel, rowLp(scaleLabel, 0, 4));

        scaleSeek = new SeekBar(act);
        scaleSeek.setMax(75);
        scaleSeek.setProgress(scalePct - 75);
        if (Build.VERSION.SDK_INT >= 21) {
            int accent = color("COLOR_ACCENT", 0xFFD63384);
            scaleSeek.setProgressTintList(ColorStateList.valueOf(accent));
            scaleSeek.setThumbTintList(ColorStateList.valueOf(accent));
            scaleSeek.setProgressBackgroundTintList(
                    ColorStateList.valueOf(color("COLOR_BAR_BG", 0x335B4661)));
        }
        scaleSeek.setOnSeekBarChangeListener(new ScaleSeekListener());
        panel.addView(scaleSeek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(scaleSeek, 42)));

        LinearLayout controls = new LinearLayout(act);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        panel.addView(controls, rowLp(controls, 4, 12));
        TextView minus = dialogButton(act, "A−", false);
        // 「恢复」给的是**按这台设备算出来的**建议值，不是死的 100%。
        // 100% 只对参考宽度那种屏幕才是对的；大屏上恢复到 100% 等于恢复成一行蚂蚁。
        int suggest = suggestedScale(act);
        TextView reset = dialogButton(act, "推荐 " + suggest + "%", false);
        TextView plus = dialogButton(act, "A+", false);
        minus.setOnClickListener(new ScaleStepClick(-5));
        reset.setOnClickListener(new ScaleResetClick());
        plus.setOnClickListener(new ScaleStepClick(5));
        controls.addView(minus);
        LinearLayout.LayoutParams resetLp = topChipLp(reset);
        resetLp.leftMargin = dp(reset, 8);
        controls.addView(reset, resetLp);
        LinearLayout.LayoutParams plusLp = topChipLp(plus);
        plusLp.leftMargin = dp(plus, 8);
        controls.addView(plus, plusLp);

        TextView done = dialogButton(act, "完成", true);
        done.setOnClickListener(new CloseDisplayClick());
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panel.addView(done, doneLp);

        host.addView(modal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        displayModal = modal;
    }

    private static final class ScaleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (fromUser) setScale(progress + 75);
        }
        @Override public void onStartTrackingTouch(SeekBar bar) {}
        @Override public void onStopTrackingTouch(SeekBar bar) {}
    }

    private static final class ScaleStepClick implements View.OnClickListener {
        private final int delta;
        ScaleStepClick(int delta) { this.delta = delta; }
        @Override public void onClick(View v) { setScale(scalePct + delta); }
    }

    private static final class ScaleResetClick implements View.OnClickListener {
        @Override public void onClick(View v) { setScale(suggestedScale(v.getContext())); }
    }

    private static void setScale(int value) {
        scalePct = clamp(value, 75, 150);
        if (prefs != null) prefs.edit().putInt(PREF_SCALE, scalePct).apply();
        if (scaleSeek != null && scaleSeek.getProgress() != scalePct - 75) {
            scaleSeek.setProgress(scalePct - 75);
        }
        if (scaleLabel != null) scaleLabel.setText("字体 " + scalePct + "%");
        styleDisplay();
        applyScale();
        CNCNDownloadUI.noteInteraction();
    }

    /**
     * 内容区的基准宽度（px）。<b>只由屏幕分辨率算，绝不读任何 getWidth()。</b>
     *
     * <h3>为什么这条是硬规矩</h3>
     *
     * 读测量宽度会形成反馈环：这一帧按测得的宽度设了新宽度，下一帧再去测，
     * 又算出更大的值。真机上的表现是「反复拖左右分界线，左右越变越长」
     * （2026-08-13）——而且它只在反复操作后才显形，一次两次看不出来。
     *
     * <p>权威值由 {@code CNCNDownloadUI.buildOverlay} 用
     * {@code widthPixels − 左右边距} 算出并存下；这里只在浮层还没建起来时
     * 用同一个公式兜底。两处必须同一个式子。
     */
    private static int contentWidthPx(Context ctx) {
        int w = CNCNDownloadUI.contentBaseWidthPx;
        if (w > 0) return w;
        if (ctx == null) return 1;
        // 兜底：与 buildOverlay 的 mainLp 左右边距一致（各 14+14dp）
        android.util.DisplayMetrics m = ctx.getResources().getDisplayMetrics();
        return Math.max(1, m.widthPixels - Math.round(56 * m.density));
    }

    /**
     * 按设备分辨率推荐一个字号。
     *
     * <p>依据是「内容区有多少 dp 宽」：本页布局是照约 560dp 排的（那也是返工前
     * 内容区宽度下限用的数），屏幕比它宽就该按比例把字放大，窄就缩小——这样字
     * 在画面里占的**比例**是恒定的，而不是在大屏上变成一行蚂蚁。
     *
     * <p>只是<b>建议</b>：玩家没自己调过时拿它当默认值，调过之后一律以玩家的
     * 选择为准。默默覆盖玩家的设置比给个烂默认值更糟。
     */
    static int suggestedScale(Context ctx) {
        try {
            if (ctx == null) return 100;
            float density = ctx.getResources().getDisplayMetrics().density;
            if (density <= 0f) return 100;
            return suggestFromDp(contentWidthPx(ctx) / density);
        } catch (Throwable t) {
            return 100;
        }
    }

    /**
     * {@link #suggestedScale} 的纯算术部分：内容区有多少 dp 宽 → 建议百分比。
     * 抽出来是为了能在 JVM 上直接测——那边 {@code getResources()} 是桩。
     */
    static int suggestFromDp(float dpWidth) {
        if (!(dpWidth > 0f)) return 100;
        return clamp(Math.round(dpWidth / DESIGN_WIDTH_DP * 100f), 75, 150);
    }

    private static void applyScale() {
        View root = contentRoot;
        if (root == null) return;
        applyContentScale(root);

        int viewport = contentWidthPx(root.getContext());
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        // The unscaled layout always equals the real viewport. Horizontal scrolling is a
        // fallback only for zoom >100% or genuinely narrow windows; 75/100% must never start
        // with half the UI off-screen.
        baseContentWidth = viewport;
        int width = scalePct <= 100 ? viewport
                : Math.max(viewport, Math.round(viewport * scalePct / 100f));
        if (lp != null && lp.width != width) {
            lp.width = width;
            root.setLayoutParams(lp);
        }
        root.requestLayout();
    }

    /**
     * 按当前字号缩放内容：文字<b>和图片一起</b>。
     *
     * <p>原先只缩文字，Logo 和图标保持原尺寸——字放到 150% 时图还是原来那么大，
     * 看着就是别扭（2026-08-13 反馈）。图片按同一个百分比缩放它的 LayoutParams，
     * 与文字同步。
     *
     * <p>两个基准表都缓存<b>第一次见到的</b>尺寸，之后每次都从基准重算而不是在
     * 当前值上乘——否则反复调字号会指数级放大，和上面那个宽度反馈环是同一类错。
     */
    private static void applyContentScale(View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            Float base = BASE_TEXT_PX.get(tv);
            if (base == null) {
                base = Float.valueOf(tv.getTextSize());
                BASE_TEXT_PX.put(tv, base);
            }
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    base.floatValue() * scalePct / 100f);
        } else if (v instanceof ImageView) {
            scaleImage((ImageView) v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyContentScale(g.getChildAt(i));
        }
    }

    /** 图片跟着字号走。只动固定尺寸的那一维，MATCH_PARENT / WRAP_CONTENT 不碰。 */
    private static void scaleImage(ImageView iv) {
        try {
            ViewGroup.LayoutParams lp = iv.getLayoutParams();
            if (lp == null) return;
            int[] base = BASE_IMAGE_PX.get(iv);
            if (base == null) {
                base = new int[] { lp.width, lp.height };
                BASE_IMAGE_PX.put(iv, base);
            }
            boolean changed = false;
            // 负数是 MATCH_PARENT(-1) / WRAP_CONTENT(-2)，那两种由父布局说了算，
            // 乘一下只会得出别的负数，把布局搞坏。
            if (base[0] > 0) {
                int w = Math.max(1, Math.round(base[0] * scalePct / 100f));
                if (lp.width != w) { lp.width = w; changed = true; }
            }
            if (base[1] > 0) {
                int h = Math.max(1, Math.round(base[1] * scalePct / 100f));
                if (lp.height != h) { lp.height = h; changed = true; }
            }
            if (changed) iv.setLayoutParams(lp);
        } catch (Throwable ignore) {}
    }

    private static void styleScrollbars() {
        HorizontalScrollView hs = hScroll;
        ScrollView vs = vScroll;
        if (hs != null) {
            // 溢出判据同样不读测量宽度：内容宽度是 applyScale 按分辨率设定的，
            // 所以「有没有溢出」等价于「字号有没有超过 100%」。
            boolean overflow = contentRoot != null && scalePct > 100;
            hs.setHorizontalScrollBarEnabled(overflow);
            hs.setScrollbarFadingEnabled(false);
            if (!overflow) hs.scrollTo(0, 0);
            hs.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
            hs.setClipToPadding(false);
            hs.setPadding(hs.getPaddingLeft(), hs.getPaddingTop(), hs.getPaddingRight(), dp(hs, 5));
            if (Build.VERSION.SDK_INT >= 29) {
                hs.setHorizontalScrollbarThumbDrawable(scrollThumb(hs));
                hs.setHorizontalScrollbarTrackDrawable(scrollTrack(hs));
            }
        }
        if (vs != null) {
            vs.setVerticalScrollBarEnabled(true);
            vs.setScrollbarFadingEnabled(false);
            vs.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
            vs.setClipToPadding(false);
            vs.setPadding(vs.getPaddingLeft(), vs.getPaddingTop(), dp(vs, 5), vs.getPaddingBottom());
            if (Build.VERSION.SDK_INT >= 29) {
                vs.setVerticalScrollbarThumbDrawable(scrollThumb(vs));
                vs.setVerticalScrollbarTrackDrawable(scrollTrack(vs));
            }
        }
    }

    private static GradientDrawable scrollThumb(View v) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color("COLOR_ACCENT", 0xFFD63384));
        d.setCornerRadius(dp(v, 6));
        d.setSize(dp(v, 6), dp(v, 6));
        return d;
    }

    private static GradientDrawable scrollTrack(View v) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color("COLOR_BAR_BG", 0x335B4661));
        d.setCornerRadius(dp(v, 6));
        d.setSize(dp(v, 5), dp(v, 5));
        return d;
    }



    // ══ 左右分界线：长按进入调节，横向拖动改占比 ═══════════════════════
    //
    // `CNCNDownloadUI` 建的 mainRow 是 [左列 0.38f][右列 0.62f]，两列之间原本
    // 连条线都没有。这里在中间插一根**可拖**的把手，改的只是两侧的 weight，
    // 不动任何一列的内部布局——所以对面怎么返工那两列都不受影响。
    //
    // 为什么是「长按之后才拖」而不是直接拖：把手正好落在 mainScroll
    // （HorizontalScrollView）里，字号放大到 >100% 时那层是要横向滚动的。
    // 直接拖会和滚动抢同一个手势，误触率高得离谱。长按先确认意图，再
    // requestDisallowInterceptTouchEvent 把手势从滚动那里要过来。

    private static void installSplit() {
        LinearLayout row = splitRow();
        if (row == null) return;

        View existing = row.findViewWithTag(TAG_SPLIT);
        if (existing != null) {
            splitHandle = existing;
            styleSplit(false);
            applySplit();
            return;
        }
        if (row.getChildCount() < 2) return;      // 还没建出两列

        View handle = new View(row.getContext());
        handle.setTag(TAG_SPLIT);
        handle.setLongClickable(true);            // 没有这个收不到长按
        SplitDrag drag = new SplitDrag();
        handle.setOnLongClickListener(drag);
        handle.setOnTouchListener(drag);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(handle, 14), ViewGroup.LayoutParams.MATCH_PARENT);
        row.addView(handle, 1, lp);               // 夹在两列中间
        splitHandle = handle;
        styleSplit(false);
        applySplit();
    }

    /** mainRow：横向的那个内容根。形状不对（对面改了布局）一律返回 null。 */
    private static LinearLayout splitRow() {
        View cr = contentRoot;
        if (!(cr instanceof LinearLayout)) return null;
        LinearLayout row = (LinearLayout) cr;
        return row.getOrientation() == LinearLayout.HORIZONTAL ? row : null;
    }

    /**
     * 把当前占比写进两侧的 weight。
     *
     * <p>只在「两列都是按 weight 排的」时才动手：谁哪天把某一列改成固定宽度，
     * 这里就该什么都不做，而不是把它的宽度清零——那会直接让半个界面消失。
     */
    private static void applySplit() {
        LinearLayout row = splitRow();
        if (row == null || row.getChildCount() < 3) return;
        View left = row.getChildAt(0);
        View right = row.getChildAt(2);
        if (!weighted(left) || !weighted(right)) return;
        setWeight(left, splitPct / 100f);
        setWeight(right, (100 - splitPct) / 100f);
        row.requestLayout();
    }

    private static boolean weighted(View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        return lp instanceof LinearLayout.LayoutParams
                && ((LinearLayout.LayoutParams) lp).weight > 0f;
    }

    private static void setWeight(View v, float weight) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.weight = weight;
        lp.width = 0;
        v.setLayoutParams(lp);
    }

    /** 平时是一根淡描边线；进入调节模式后加粗并换成强调色。 */
    private static void styleSplit(boolean active) {
        View v = splitHandle;
        if (v == null) return;
        GradientDrawable line = new GradientDrawable();
        line.setColor(active ? color("COLOR_ACCENT", 0xFFD63384)
                             : color("COLOR_CARD_STK", 0x33B53C8C));
        line.setCornerRadius(dp(v, 2));
        int inset = active ? dp(v, 5) : dp(v, 6);   // 14dp 把手 → 4dp / 2dp 可见线
        v.setBackground(new InsetDrawable(line, inset, dp(v, 6), inset, dp(v, 6)));
    }

    private static void setSplit(int value) {
        splitPct = clamp(value, SPLIT_MIN, SPLIT_MAX);
        applySplit();
    }

    /**
     * 长按进调节、拖动改占比。
     *
     * <p>static 嵌套类——匿名/非静态内部类会带合成字段 this$0，d8 撞上直接 NPE
     * （CLAUDE.md 铁律 4，CI 的 check-d8-pitfalls.py 会拦）。
     */
    private static final class SplitDrag
            implements View.OnTouchListener, View.OnLongClickListener {
        private boolean dragging;
        private float startX;
        private int startPct;
        private long lastTapAt;

        @Override public boolean onLongClick(View v) {
            dragging = true;
            startPct = splitPct;
            // 把手势从 HorizontalScrollView 手里要过来，否则一横向移动就被它吃掉
            grabGesture(v, true);
            try { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); }
            catch (Throwable ignore) {}
            styleSplit(true);
            CNCNDownloadUI.noteInteraction();
            CNCNDownloadUI.toast(RestClient.getCurrentActivity(),
                    "左右拖动调整分界；松手保存，双击复位");
            return true;
        }

        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX();
                    startPct = splitPct;
                    dragging = false;
                    return false;            // 交给 View 自己去产生长按
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) return false;
                    LinearLayout row = splitRow();
                    // 同样不读 row.getWidth()：那是被 applyScale 设过的值，
                    // 拿它做换算就是把反馈环接进了手势里。
                    int w = row == null ? 0 : contentWidthPx(row.getContext());
                    if (w > 0) {
                        float delta = e.getRawX() - startX;
                        setSplit(startPct + Math.round(delta * 100f / w));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) {
                        if (e.getActionMasked() == MotionEvent.ACTION_UP) checkDoubleTap(v, e);
                        return false;
                    }
                    dragging = false;
                    grabGesture(v, false);
                    styleSplit(false);
                    persistSplit();
                    CNCNDownloadUI.noteInteraction();
                    return true;
                default:
                    return false;
            }
        }

        /**
         * 双击分界线复位到默认。
         *
         * <p>复位入口放在分界线自己身上，而不是借「显示大小」弹窗的地儿：那个
         * 弹窗是字号的，不该塞进别的功能。双击也不跟长按抢——长按走的是
         * {@code dragging} 那条路，到不了这里。
         */
        private void checkDoubleTap(View v, MotionEvent e) {
            int slop = android.view.ViewConfiguration.get(v.getContext())
                    .getScaledTouchSlop();
            long now = android.os.SystemClock.uptimeMillis();
            boolean moved = Math.abs(e.getRawX() - startX) > slop;
            if (!moved && lastTapAt != 0L
                    && now - lastTapAt <= android.view.ViewConfiguration.getDoubleTapTimeout()) {
                lastTapAt = 0L;
                setSplit(SPLIT_DEFAULT);
                persistSplit();
                try { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); }
                catch (Throwable ignore) {}
                CNCNDownloadUI.noteInteraction();
                CNCNDownloadUI.toast(RestClient.getCurrentActivity(),
                        "分界已复位到 " + SPLIT_DEFAULT + "%");
            } else {
                lastTapAt = moved ? 0L : now;
            }
        }
    }

    private static void persistSplit() {
        if (prefs != null) prefs.edit().putInt(PREF_SPLIT, splitPct).apply();
    }

    /**
     * 拖动期间把手势从祖先的滚动容器手里要过来（松手时还回去）。
     *
     * <p>两个把手都在 `HorizontalScrollView` / `ScrollView` 里，不要过来的话
     * 一横向移动就被滚动吃掉，长按之后根本拖不动。
     */
    private static void grabGesture(View v, boolean grab) {
        try {
            android.view.ViewParent p = v.getParent();
            if (p != null) p.requestDisallowInterceptTouchEvent(grab);
        } catch (Throwable ignore) {}
    }

    /** 弹窗宽度永远不超过当前逻辑屏幕减 40dp，覆盖窄屏、分屏和高 DPI。 */
    private static int adaptiveDialogWidth(View v) {
        int available = v.getResources().getDisplayMetrics().widthPixels - dp(v, 40);
        return Math.max(1, Math.min(dp(v, 380), available));
    }

    private static LinearLayout dialogPanel(Activity act) {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(panel, 22), dp(panel, 20), dp(panel, 22), dp(panel, 18));
        panel.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color("COLOR_LOG_PANEL_BG", 0xFFFFFFFF));
        bg.setCornerRadius(dp(panel, 16));
        bg.setStroke(dp(panel, 1), color("COLOR_CARD_STK", 0x33B53C8C));
        panel.setBackground(bg);
        return panel;
    }

    private static TextView dialogButton(Activity act, String label, boolean primary) {
        TextView v = text(act, label, 12f, primary ? 0xFFFFFFFF
                : color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B));
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(v, 18), dp(v, 8), dp(v, 18), dp(v, 8));
        v.setClickable(true);
        v.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(v, 10));
        if (primary) bg.setColor(color("COLOR_ACCENT", 0xFFD63384));
        else {
            bg.setColor(0x00000000);
            bg.setStroke(dp(v, 1), color("COLOR_CARD_STK", 0x33B53C8C));
        }
        v.setBackground(bg);
        return v;
    }

    private static final class ConsumeClick implements View.OnClickListener {
        @Override public void onClick(View v) {}
    }

    private static final class CloseConfirmClick implements View.OnClickListener {
        @Override public void onClick(View v) { closeConfirm(); }
    }

    private static final class CloseDisplayClick implements View.OnClickListener {
        @Override public void onClick(View v) { closeDisplay(); }
    }

    private static void closeConfirm() {
        FrameLayout m = confirmModal;
        confirmModal = null;
        if (m != null && m.getParent() instanceof ViewGroup) {
            ((ViewGroup) m.getParent()).removeView(m);
        }
        CNCNDownloadUI.noteInteraction();
    }

    private static void closeDisplay() {
        FrameLayout m = displayModal;
        displayModal = null;
        scaleLabel = null;
        scaleSeek = null;
        if (m != null && m.getParent() instanceof ViewGroup) {
            ((ViewGroup) m.getParent()).removeView(m);
        }
        CNCNDownloadUI.noteInteraction();
    }

    private static void startStayWatchdog() {
        Thread current = stayThread;
        if (current != null && current.isAlive()) return;
        synchronized (CNDownloadUiAssist.class) {
            current = stayThread;
            if (current != null && current.isAlive()) return;
            Thread t = new Thread(new StayLoop(), "cnv-overlay-stay");
            t.setDaemon(true);
            stayThread = t;
            t.start();
        }
    }

    private static void stopStayWatchdog() {
        Thread t = stayThread;
        stayThread = null;
        if (t != null) t.interrupt();
    }

    private static final class StayLoop implements Runnable {
        @Override public void run() {
            while (stayRequested) {
                try {
                    FrameLayout overlay = CNCNDownloadUI.overlayView;
                    if (!CNCNDownloadUI.isShowing || overlay == null) return;
                    Activity act = RestClient.getCurrentActivity();
                    if (act != null && overlay.getParent() == null) {
                        CNCNDownloadUI.ensureVisible(act);
                    }
                    ensureInstalled();
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    try { Thread.sleep(500L); }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private static void postInstall() {
        try {
            Handler h = CNCNDownloadUI.uiHandler;
            if (h == null) h = new Handler(Looper.getMainLooper());
            h.post(INSTALL);
        } catch (Throwable ignore) {}
    }

    private static TextView findText(View v, String expected) {
        if (v instanceof TextView) {
            CharSequence s = ((TextView) v).getText();
            if (s != null && expected.contentEquals(s)) return (TextView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = findText(g.getChildAt(i), expected);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView text(Activity act, String value, float size, int color) {
        TextView v = new TextView(act);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        v.setTextColor(color);
        return v;
    }

    private static LinearLayout.LayoutParams rowLp(View v, int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(v, top);
        lp.bottomMargin = dp(v, bottom);
        return lp;
    }

    private static int color(String name, int fallback) {
        try {
            Field f = CNCNDownloadUI.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static int dp(View v, int value) {
        return (int) (value * v.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static float sp(View v, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                v.getResources().getDisplayMetrics());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- JVM 回归测试入口 ----
    // 拖动本身要真机，但**夹紧范围**是纯算术，而它恰恰是拖坏界面的唯一途径：
    // 越界一格，某一列就变成一条只剩省略号的缝。
    /**
     * 建浮层时左列该占的 weight。
     *
     * <p><b>浮层必须<i>建出来就是</i>玩家调好的比例</b>，而不是先按硬编码的
     * 38/62 建好、再由 {@link #applySplit()} 改回去。原先 {@code buildOverlay}
     * 里写死 {@code 0.38f / 0.62f}，于是每次重建浮层（切主题、看门狗发现浮层
     * 掉出视图树而重建）都先闪回默认比例；而 installSplit 要等 ensureInstalled
     * 那一轮才跑，中间这段就是玩家看到的「刷新后比例被重置」（2026-08-13 反馈）。
     *
     * <p>参数带 Context 是因为这可能是本进程第一次碰它——prefs 还没打开过时
     * 得先把玩家存的值读进来，否则又是一个「默认值假装成玩家的选择」。
     */
    public static float leftWeight(Context ctx) {
        loadPrefs(ctx);
        return splitPct / 100f;
    }

    /** 见 {@link #leftWeight(Context)}。两个加起来恒为 1。 */
    public static float rightWeight(Context ctx) {
        return 1f - leftWeight(ctx);
    }

    public static int splitPctForTest() { return splitPct; }
    public static void setSplitForTest(int value) { setSplit(value); }
    public static int splitDefaultForTest() { return SPLIT_DEFAULT; }
    public static int splitMinForTest() { return SPLIT_MIN; }
    public static int splitMaxForTest() { return SPLIT_MAX; }
    public static int suggestFromDpForTest(float dpWidth) { return suggestFromDp(dpWidth); }
    public static float designWidthDpForTest() { return DESIGN_WIDTH_DP; }
}
