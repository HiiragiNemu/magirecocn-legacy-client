package io.kamihama.magianative;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.TextView;

import java.lang.reflect.Field;

/**
 * 下载浮层的内嵌辅助控件。所有控件都属于 overlayView，关闭下载页即一起销毁；
 * 不再向 decorView 塞独立悬浮窗，也不再平移整个游戏画面。
 */
public final class CNDownloadUiAssist {
    private static final String LEGACY_TAG = "cn-download-ui-assist";
    private static final String TAG_STAY = "cn-download-stay";
    private static final String TAG_RELOAD = "cn-download-reload-";
    private static final String OLD_LINGER =
            "即将进入游戏；点按浮层（如「教程」胶囊播序章）可稍作停留";
    private static final String NEW_LINGER =
            "检查已完成。可点「停留本页」继续查看，或等待进入游戏。";

    private static View attachedOverlay;
    private static TextView stayChip;
    private static FrameLayout confirmModal;
    private static volatile boolean stayRequested;
    private static volatile Thread stayThread;
    private static final Runnable INSTALL = new InstallTask();
    private static final Runnable POLISH = new PolishTask();

    private CNDownloadUiAssist() {}

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
            try { CNLog.w("界面", "内嵌辅助控件安装失败: " + t); } catch (Throwable ignore) {}
        }
    }

    public static boolean shouldStayOnPage() { return stayRequested; }

    static void setStayOnPage(boolean stay) {
        stayRequested = stay;
        if (stay) startStayWatchdog(); else stopStayWatchdog();
        postInstall();
    }

    private static final class InstallTask implements Runnable {
        @Override public void run() { installOnMain(); }
    }

    private static final class PolishTask implements Runnable {
        @Override public void run() {
            View ov = CNCNDownloadUI.overlayView;
            if (ov == null || ov != attachedOverlay || !CNCNDownloadUI.isShowing) return;
            try {
                removeLegacy();
                ov.setTranslationX(0f);
                ov.setTranslationY(0f);
                enableBars(ov);
                replaceLinger(ov);
                installStay(ov);
                installReloads(ov);
                styleStay();
            } catch (Throwable t) {
                try { CNLog.w("界面", "刷新内嵌控件失败: " + t); } catch (Throwable ignore) {}
            }
            Handler h = CNCNDownloadUI.uiHandler;
            if (h != null) h.postDelayed(POLISH, 500L);
        }
    }

    private static void installOnMain() {
        FrameLayout ov = CNCNDownloadUI.overlayView;
        if (ov == null) return;
        removeLegacy();
        try { CNManualRedownload.recoverCompletedRequest(); } catch (Throwable ignore) {}
        if (attachedOverlay != ov) {
            attachedOverlay = ov;
            stayChip = null;
            confirmModal = null;
        }
        ov.setTranslationX(0f);
        ov.setTranslationY(0f);
        enableBars(ov);
        replaceLinger(ov);
        installStay(ov);
        installReloads(ov);
        styleStay();
        Handler h = CNCNDownloadUI.uiHandler;
        if (h != null) {
            h.removeCallbacks(POLISH);
            h.postDelayed(POLISH, 500L);
        }
        if (stayRequested) startStayWatchdog();
    }

    private static void removeLegacy() {
        try {
            ViewGroup decor = CNCNDownloadUI.decorView;
            if (decor == null) return;
            View old = decor.findViewWithTag(LEGACY_TAG);
            if (old != null && old.getParent() instanceof ViewGroup) {
                ((ViewGroup) old.getParent()).removeView(old);
                CNLog.i("界面", "已移除旧版独立字/平移悬浮控件");
            }
        } catch (Throwable ignore) {}
    }

    /** 使用真实内容容器的长滚动条，而不是平移窗口。 */
    private static void enableBars(View v) {
        if (v instanceof ScrollView) {
            ScrollView s = (ScrollView) v;
            s.setVerticalScrollBarEnabled(true);
            s.setScrollbarFadingEnabled(false);
            s.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        } else if (v instanceof HorizontalScrollView) {
            HorizontalScrollView s = (HorizontalScrollView) v;
            s.setHorizontalScrollBarEnabled(true);
            s.setScrollbarFadingEnabled(false);
            s.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) enableBars(g.getChildAt(i));
        }
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

    private static void installStay(View root) {
        TextView log = findText(root, "LOG");
        if (log == null || !(log.getParent() instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) log.getParent();
        View existing = row.findViewWithTag(TAG_STAY);
        if (existing instanceof TextView) { stayChip = (TextView) existing; return; }

        TextView template = null;
        for (int i = row.getChildCount() - 1; i >= 0; i--) {
            if (row.getChildAt(i) instanceof TextView) {
                template = (TextView) row.getChildAt(i);
                break;
            }
        }
        TextView chip = new TextView(row.getContext());
        chip.setTag(TAG_STAY);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                template == null ? sp(chip, 11f) : template.getTextSize());
        chip.setTypeface(template == null ? Typeface.DEFAULT_BOLD : template.getTypeface(), Typeface.BOLD);
        int px = template == null ? dp(chip, 12) : template.getPaddingLeft();
        int py = template == null ? dp(chip, 6) : template.getPaddingTop();
        chip.setPadding(px, py, px, py);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setOnClickListener(new StayClick());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(chip, 8);
        row.addView(chip, lp);
        stayChip = chip;
    }

    private static final class StayClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            boolean next = !stayRequested;
            setStayOnPage(next);
            CNCNDownloadUI.noteInteraction();
            Activity act = RestClient.getCurrentActivity();
            if (next) CNCNDownloadUI.toast(act, "已停留；点“进入游戏”再离开资源页");
            else if (CNHotUpdateCheck.isRunning()) CNCNDownloadUI.toast(act, "将在当前检查收尾后进入游戏");
            else CNCNDownloadUI.hide();
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

    private static final class ReloadClick implements View.OnClickListener {
        private final int index;
        ReloadClick(int index) { this.index = index; }
        @Override public void onClick(View v) {
            Activity act = RestClient.getCurrentActivity();
            int[] status = CNCNDownloadUI.fileStatus;
            if (status != null && index >= 0 && index < status.length
                    && status[index] == CNCNDownloadUI.ST_RUNNING) {
                CNCNDownloadUI.toast(act, "该文件正在下载，完成后再选择重下");
                return;
            }
            setStayOnPage(true);
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

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(panel, 22), dp(panel, 20), dp(panel, 22), dp(panel, 18));
        panel.setClickable(true);
        panel.setOnClickListener(new ConsumeClick());
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(color("COLOR_LOG_PANEL_BG", 0xFFFFFFFF));
        panelBg.setCornerRadius(dp(panel, 16));
        panelBg.setStroke(dp(panel, 1), color("COLOR_CARD_STK", 0x33B53C8C));
        panel.setBackground(panelBg);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                dp(panel, 360), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelLp.leftMargin = panelLp.rightMargin = dp(panel, 20);
        modal.addView(panel, panelLp);

        TextView title = text(act, "重新下载资源包", 16f,
                color("COLOR_ACCENT", 0xFFD63384));
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        panel.addView(title, rowLp(title, 0, 10));

        TextView msg = text(act,
                "只重新下载：\n" + names[index]
                + "\n\n其他已验证资源不会删除。确认后游戏会重启到下载页，"
                + "完成该文件的下载、校验和解压后再自动重启一次。",
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
        LinearLayout.LayoutParams yesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yesLp.leftMargin = dp(yes, 10);
        buttons.addView(yes, yesLp);

        host.addView(modal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        confirmModal = modal;
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
    private static final class ConfirmReloadClick implements View.OnClickListener {
        private final Activity act; private final int index;
        ConfirmReloadClick(Activity act, int index) { this.act = act; this.index = index; }
        @Override public void onClick(View v) {
            closeConfirm();
            CNManualRedownload.request(act, index);
        }
    }

    private static void closeConfirm() {
        FrameLayout m = confirmModal;
        confirmModal = null;
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
                    Activity act = RestClient.getCurrentActivity();
                    if (act != null) {
                        FrameLayout ov = CNCNDownloadUI.overlayView;
                        if (!CNCNDownloadUI.isShowing || ov == null || ov.getParent() == null) {
                            CNCNDownloadUI.show(act);
                            CNCNDownloadUI.ensureVisible(act);
                        }
                        ensureInstalled();
                    }
                    Thread.sleep(150L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    try { Thread.sleep(300L); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
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
        } catch (Throwable t) { return fallback; }
    }

    private static int dp(View v, int value) {
        return (int) (value * v.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static float sp(View v, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                v.getResources().getDisplayMetrics());
    }
}
