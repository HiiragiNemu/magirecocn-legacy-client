package io.kamihama.magianative;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.WeakHashMap;

/**
 * 下载浮层的最低风险可达性补丁。
 *
 * <p>不改原布局树：在 decorView 右下角加一个“字/↔”小入口。面板提供字体缩放、
 * 左右位置和上下位置两个滑动条；当系统大字体把内容挤出屏幕时，玩家可缩放文字或
 * 平移整个下载浮层查看被裁掉的区域。控制条挂在 decorView 上，不跟随浮层移动。
 */
public final class CNDownloadUiAssist {
    private static final String TAG = "cn-download-ui-assist";
    private static final String PREFS = "cnv_bootstrap_ui_assist";
    private static final String PREF_SCALE = "font_scale_pct";
    private static final String PREF_X = "pan_x";
    private static final String PREF_Y = "pan_y";

    private static final WeakHashMap<TextView, Float> BASE_TEXT_PX =
            new WeakHashMap<TextView, Float>();

    private static View controlRoot;
    private static View attachedOverlay;
    private static LinearLayout panel;
    private static SeekBar seekX;
    private static SeekBar seekY;
    private static TextView scaleLabel;
    private static SharedPreferences prefs;
    private static int scalePct = 100;
    private static int panX = 100;
    private static int panY = 100;

    private static final Runnable INSTALL = new InstallTask();

    private static final class InstallTask implements Runnable {
        @Override public void run() { installOnMain(); }
    }

    private CNDownloadUiAssist() {}

    /** 可从任意线程调用；Android/JVM stub 异常由调用方隔离，不影响下载。 */
    public static void ensureInstalled() {
        // JVM 回归测试与浮层尚未创建的启动窗口直接返回，不触碰 Android stub。
        if (CNCNDownloadUI.overlayView == null || CNCNDownloadUI.decorView == null) return;
        Looper main = Looper.getMainLooper();
        if (Looper.myLooper() == main) {
            installOnMain();
            return;
        }
        Handler h = CNCNDownloadUI.uiHandler;
        if (h == null) h = new Handler(main);
        h.post(INSTALL);
    }

    private static void installOnMain() {
        final FrameLayout overlay = CNCNDownloadUI.overlayView;
        final ViewGroup decor = CNCNDownloadUI.decorView;
        if (overlay == null || decor == null) return;

        if (prefs == null) {
            prefs = decor.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            scalePct = clamp(prefs.getInt(PREF_SCALE, 100), 75, 150);
            panX = clamp(prefs.getInt(PREF_X, 100), 0, 200);
            panY = clamp(prefs.getInt(PREF_Y, 100), 0, 200);
        }

        if (controlRoot != null && controlRoot.getParent() == decor
                && attachedOverlay == overlay) {
            applyAll(overlay);
            return;
        }

        View old = decor.findViewWithTag(TAG);
        if (old != null && old.getParent() instanceof ViewGroup) {
            ((ViewGroup) old.getParent()).removeView(old);
        }
        controlRoot = null;
        attachedOverlay = overlay;
        BASE_TEXT_PX.clear();

        final Context c = decor.getContext();
        LinearLayout dock = new LinearLayout(c);
        dock.setTag(TAG);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setGravity(Gravity.RIGHT);
        dock.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
        panel.setBackground(panelBackground());
        panel.setVisibility(View.GONE);
        if (android.os.Build.VERSION.SDK_INT >= 21) panel.setElevation(dp(c, 8));

        scaleLabel = label(c, "字体 100%", 13f);
        scaleLabel.setGravity(Gravity.CENTER);
        panel.addView(scaleLabel, new LinearLayout.LayoutParams(dp(c, 230), dp(c, 30)));

        LinearLayout fontRow = new LinearLayout(c);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        fontRow.setGravity(Gravity.CENTER);
        TextView smaller = button(c, "A−");
        TextView reset = button(c, "重置");
        TextView larger = button(c, "A+");
        fontRow.addView(smaller, new LinearLayout.LayoutParams(dp(c, 68), dp(c, 38)));
        fontRow.addView(reset, new LinearLayout.LayoutParams(dp(c, 80), dp(c, 38)));
        fontRow.addView(larger, new LinearLayout.LayoutParams(dp(c, 68), dp(c, 38)));
        panel.addView(fontRow);

        panel.addView(label(c, "左右位置", 12f));
        seekX = new SeekBar(c);
        seekX.setMax(200);
        seekX.setProgress(panX);
        panel.addView(seekX, new LinearLayout.LayoutParams(dp(c, 230), dp(c, 36)));

        panel.addView(label(c, "上下位置", 12f));
        seekY = new SeekBar(c);
        seekY.setMax(200);
        seekY.setProgress(panY);
        panel.addView(seekY, new LinearLayout.LayoutParams(dp(c, 230), dp(c, 36)));

        TextView hint = label(c, "大字体显示不全时，先调字体，再拖动位置", 11f);
        hint.setGravity(Gravity.CENTER);
        panel.addView(hint, new LinearLayout.LayoutParams(dp(c, 230),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView toggle = button(c, "字 / ↔");
        toggle.setContentDescription("下载界面字体与位置调节");
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(dp(c, 72), dp(c, 40));
        toggleLp.gravity = Gravity.RIGHT;

        dock.addView(panel);
        dock.addView(toggle, toggleLp);

        toggle.setOnClickListener(new ToggleClick());
        smaller.setOnClickListener(new ScaleClick(-10));
        larger.setOnClickListener(new ScaleClick(10));
        reset.setOnClickListener(new ResetClick());
        seekX.setOnSeekBarChangeListener(new PanListener(true));
        seekY.setOnSeekBarChangeListener(new PanListener(false));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.RIGHT | Gravity.BOTTOM);
        lp.setMargins(dp(c, 8), dp(c, 8), dp(c, 12), dp(c, 12));
        decor.addView(dock, lp);
        controlRoot = dock;
        applyAll(overlay);
    }


    private static final class ToggleClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (panel != null) {
                panel.setVisibility(panel.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE);
            }
        }
    }

    private static final class ResetClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            scalePct = 100;
            panX = 100;
            panY = 100;
            if (seekX != null) seekX.setProgress(100);
            if (seekY != null) seekY.setProgress(100);
            persist();
            applyAll(attachedOverlay);
        }
    }

    private static final class ScaleClick implements View.OnClickListener {
        private final int delta;
        ScaleClick(int delta) { this.delta = delta; }
        @Override public void onClick(View v) {
            scalePct = clamp(scalePct + delta, 75, 150);
            persist();
            applyAll(attachedOverlay);
        }
    }

    private static final class PanListener implements SeekBar.OnSeekBarChangeListener {
        private final boolean horizontal;
        PanListener(boolean horizontal) { this.horizontal = horizontal; }
        @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (horizontal) panX = progress;
            else panY = progress;
            if (fromUser) persist();
            applyTranslation(attachedOverlay);
        }
        @Override public void onStartTrackingTouch(SeekBar bar) {}
        @Override public void onStopTrackingTouch(SeekBar bar) {}
    }

    private static void applyAll(View overlay) {
        if (overlay == null) return;
        applyTextScale(overlay);
        applyTranslation(overlay);
        if (scaleLabel != null) scaleLabel.setText("字体 " + scalePct + "%");
    }

    private static void applyTextScale(View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            Float base = BASE_TEXT_PX.get(tv);
            if (base == null) {
                base = Float.valueOf(tv.getTextSize());
                BASE_TEXT_PX.put(tv, base);
            }
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, base.floatValue() * scalePct / 100f);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyTextScale(g.getChildAt(i));
        }
    }

    private static void applyTranslation(View overlay) {
        if (overlay == null) return;
        int w = overlay.getWidth();
        int h = overlay.getHeight();
        if (w <= 0 && overlay.getParent() instanceof View) w = ((View) overlay.getParent()).getWidth();
        if (h <= 0 && overlay.getParent() instanceof View) h = ((View) overlay.getParent()).getHeight();
        float maxX = Math.max(dp(overlay.getContext(), 80), w * 0.35f);
        float maxY = Math.max(dp(overlay.getContext(), 80), h * 0.35f);
        overlay.setTranslationX((panX - 100) * maxX / 100f);
        overlay.setTranslationY((panY - 100) * maxY / 100f);
    }

    private static void persist() {
        if (prefs == null) return;
        prefs.edit().putInt(PREF_SCALE, scalePct)
                .putInt(PREF_X, panX).putInt(PREF_Y, panY).apply();
    }

    private static TextView label(Context c, String text, float sp) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(0xFFF7EEF8);
        v.setPadding(dp(c, 4), dp(c, 2), dp(c, 4), dp(c, 2));
        return v;
    }

    private static TextView button(Context c, String text) {
        TextView v = label(c, text, 13f);
        v.setGravity(Gravity.CENTER);
        v.setClickable(true);
        v.setFocusable(true);
        v.setBackground(buttonBackground());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3));
        v.setLayoutParams(lp);
        return v;
    }

    private static GradientDrawable panelBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xEE201428);
        d.setCornerRadius(18f);
        d.setStroke(1, 0x99FF86C6);
        return d;
    }

    private static GradientDrawable buttonBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xEEA83C82);
        d.setCornerRadius(16f);
        d.setStroke(1, 0xCCFFD4EA);
        return d;
    }

    private static int dp(Context c, int value) {
        return (int) (value * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
