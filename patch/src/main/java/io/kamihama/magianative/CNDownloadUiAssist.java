package io.kamihama.magianative;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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

    private static final String LEGACY_TAG = "cn-download-ui-assist";
    private static final String TAG_STAY = "cn-download-stay";
    private static final String TAG_DISPLAY = "cn-download-display";
    private static final String TAG_RELOAD = "cn-download-reload-";
    private static final String PREFS = "cnv_bootstrap_ui_assist";
    private static final String PREF_SCALE = "font_scale_pct";
    private static final String OLD_LINGER =
            "即将进入游戏；点按浮层（如「教程」胶囊播序章）可稍作停留";
    private static final String NEW_LINGER =
            "检查已完成。可查看日志或管理资源；需要停留请使用“停留本页”。";

    private static final Object STAY_LOCK = new Object();
    private static final WeakHashMap<TextView, Float> BASE_TEXT_PX =
            new WeakHashMap<TextView, Float>();

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
    private static SharedPreferences prefs;
    private static int scalePct = 100;
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
            baseContentWidth = 0;
            BASE_TEXT_PX.clear();
            loadPrefs(overlay.getContext());
        }

        replaceLinger(overlay);
        installStay(overlay);
        installDisplay(overlay);
        installReloads(overlay);
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
        if (prefs != null) scalePct = clamp(prefs.getInt(PREF_SCALE, 100), 75, 150);
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
        baseContentWidth = 0;
        BASE_TEXT_PX.clear();
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

    private static void installStay(View root) {
        LinearLayout row = findTopLeftRow(root);
        if (row == null) return;
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

    /** 把“重下”直接放进原资源行，不另造资源管理悬浮窗。 */
    private static void installReloads(View root) {
        String[] names = CNCNDownloadUI.FILE_NAMES;
        if (names == null) return;
        for (int i = 0; i < names.length; i++) {
            TextView name = findText(root, (i + 1) + ". " + names[i]);
            if (name == null || !(name.getParent() instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) name.getParent();
            String tag = TAG_RELOAD + i;
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
            row.addView(b, lp);
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
                "只调整下载页中央内容。文字放大后，可用右侧纵向滚动条和底部横向滚动条查看超出部分；游戏画面不会移动。",
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
        TextView reset = dialogButton(act, "恢复 100%", false);
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
        @Override public void onClick(View v) { setScale(100); }
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

    private static void applyScale() {
        View root = contentRoot;
        if (root == null) return;
        applyTextScale(root);

        HorizontalScrollView hs = hScroll;
        int viewport = hs == null ? 0 : hs.getWidth();
        if (viewport <= 0 && attachedOverlay != null) {
            viewport = Math.max(1, attachedOverlay.getWidth() - dp(attachedOverlay, 56));
        }
        if (viewport <= 0) {
            viewport = Math.max(1, root.getResources().getDisplayMetrics().widthPixels
                    - dp(root, 56));
        }
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

    private static void applyTextScale(View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            Float base = BASE_TEXT_PX.get(tv);
            if (base == null) {
                base = Float.valueOf(tv.getTextSize());
                BASE_TEXT_PX.put(tv, base);
            }
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    base.floatValue() * scalePct / 100f);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyTextScale(g.getChildAt(i));
        }
    }

    private static void styleScrollbars() {
        HorizontalScrollView hs = hScroll;
        ScrollView vs = vScroll;
        if (hs != null) {
            boolean overflow = contentRoot != null
                    && hs.getWidth() > 0 && contentRoot.getWidth() > hs.getWidth() + dp(hs, 2);
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
}
