package io.kamihama.magianative;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 强制新手教程的状态：标记文件的读写，以及「自动询问只问一次」的记忆。
 *
 * <h3>为什么需要这个功能</h3>
 *
 * 复刻服对任何账号都下发「已通关」的存档，引擎的正常流程<b>永远</b>不会播新手
 * 教程——即使是第一次玩的人也直接落到主页。想看序章只能靠外力。
 *
 * <h3>分工</h3>
 *
 * 本类只管「标记在不在」。真正改场景的是 native 侧：{@code libMagiaLegacy.so}
 * 拦下引擎首个「进主页」命令（{@code web::SceneCommand::pushSceneTop}），看到
 * 标记就改调 {@code pushScenePrologue} 进序章。标记<b>一次性消费</b>——native
 * 读到就立刻删除，所以序章放完回主页时不会被再打回去。
 *
 * <p>询问的界面在 {@link CNCNDownloadUI}：它拥有浮层的调色板与模态框样式，
 * 教程询问就用那一套，不另起一个长得不一样的对话框。
 *
 * <h3>两个触发口</h3>
 * <ul>
 *   <li><b>自动询问</b>：只在「首次安装跑完、完成标记落盘」那一瞬间弹一次，
 *       答过就记住，之后再也不问。见 {@link CNDownloaderFix} 的收尾。</li>
 *   <li><b>教程胶囊</b>：常驻浮层左上角，任何时候都能点开重新选择——
 *       自动询问已经答过之后，这是唯一的入口。</li>
 * </ul>
 */
public final class CNTutorialPrompt {

    private static final String TAG = "MagiaCNTutorial";

    /**
     * 强制教程标记。⚠ 必须与 {@code MagiaLegacy.cpp} 的
     * {@code FORCE_TUTORIAL_FLAG_PATH} 逐字一致。
     *
     * <p>放在安装标记的同一个目录：资源装完时该目录必定存在，不必额外建。
     */
    private static final String FORCE_TUTORIAL_FLAG =
            "/data/data/io.kamihama.totentanz/files/madomagi/magica/cn_force_tutorial.flag";

    private static final String PREFS_NAME = "cnv_tutorial";
    /** 自动询问是否已经问过（无论当时答的是什么）。 */
    private static final String KEY_ASKED  = "prompt_answered";

    private CNTutorialPrompt() {}

    // ==================================================================
    // 序章期间隐藏前端界面
    // ==================================================================

    /**
     * 序章开始/结束时由 native 经 JNI 调用，隐藏/恢复前端界面。
     *
     * <h3>为什么需要</h3>
     *
     * 游戏主界面是 {@code org.cocos2dx.lib.Cocos2dxWebView}——一个普通的
     * Android View，<b>盖在 GL SurfaceView 之上</b>。引擎的场景图层都画在 GL 里，
     * 在它下面。
     *
     * <p>正常走剧情时，是前端 JS 自己发起跳转、顺手把自己藏起来的。我们是从
     * native 直接压场景，前端根本不知道发生了什么，于是主界面照旧盖在最上层，
     * 序章就成了它的背景——真机上看到的正是这个。
     *
     * <p>所以由我们代劳：序章图层构造时把 WebView 藏起来，析构时放回来。
     *
     * <p>用类名匹配而不是 {@code instanceof}：{@code Cocos2dxWebView} 在
     * smali_classes2 里，补丁源码编译期看不到它，也不该为此建个桩。
     *
     * @param visible true 恢复显示，false 隐藏
     */
    public static void setGameUiVisible(final boolean visible) {
        try {
            final Activity act = RestClient.getCurrentActivity();
            if (act == null) {
                CNLog.w(TAG, "取不到 Activity，无法切换前端界面可见性");
                return;
            }
            act.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        View root = act.getWindow() == null
                                ? null : act.getWindow().peekDecorView();
                        if (root == null) return;
                        int n = applyVisibility(root, visible);
                        CNLog.i(TAG, (visible ? "恢复" : "隐藏") + "前端界面，命中 "
                                + n + " 个 WebView");
                    } catch (Throwable t) {
                        CNLog.e(TAG, "切换前端界面可见性失败", t);
                    }
                }
            });
        } catch (Throwable t) {
            CNLog.e(TAG, "setGameUiVisible 失败", t);
        }
    }

    /** 递归找 Cocos2dxWebView 并设置可见性，返回命中个数。 */
    private static int applyVisibility(View v, boolean visible) {
        int hit = 0;
        String cn = v.getClass().getName();
        if (cn.startsWith("org.cocos2dx.lib.Cocos2dxWebView")) {
            v.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            hit++;
            // 命中即返回：WebView 内部还有一堆子 View，没必要再往下钻
            return hit;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                hit += applyVisibility(g.getChildAt(i), visible);
            }
        }
        return hit;
    }

    // ==================================================================
    // 标记
    // ==================================================================

    /** 标记是否已就位（= 下次进主页时会被改成进序章）。 */
    public static boolean isArmed() {
        try { return new File(FORCE_TUTORIAL_FLAG).isFile(); }
        catch (Throwable t) { return false; }
    }

    /**
     * 按选择落地标记。
     *
     * @param on true 写出标记（进序章），false 清除标记（正常进主页）
     * @return 落地后的实际状态，便于调用方据此刷新界面
     */
    public static boolean set(boolean on) {
        if (on) writeFlag(); else clearFlag();
        return isArmed();
    }

    private static void writeFlag() {
        try {
            File f = new File(FORCE_TUTORIAL_FLAG);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                CNLog.e(TAG, "建不出目录 " + dir + "，强制教程标记写不了");
                return;
            }
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write(("force:" + System.currentTimeMillis() + "\n").getBytes("UTF-8"));
                fos.flush();
                fos.getFD().sync();   // 紧接着可能就重启了，别让它还在页缓存里
            } finally {
                try { fos.close(); } catch (Throwable ignore) {}
            }
            CNLog.i(TAG, "已写出强制教程标记：" + f.getAbsolutePath());
        } catch (Throwable t) {
            CNLog.e(TAG, "写强制教程标记失败", t);
        }
    }

    /** 清掉可能残留的标记，免得上次没消费干净的标记误触发。 */
    private static void clearFlag() {
        try {
            File f = new File(FORCE_TUTORIAL_FLAG);
            if (f.exists() && f.delete()) CNLog.i(TAG, "已清除强制教程标记");
        } catch (Throwable ignore) {}
    }

    // ==================================================================
    // 「已问过」的记忆
    // ==================================================================

    /** 自动询问是否已经问过。取不到 Context 时保守地当作没问过。 */
    public static boolean askedOnce() {
        try {
            SharedPreferences p = prefs();
            return p != null && p.getBoolean(KEY_ASKED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void markAsked() {
        try {
            SharedPreferences p = prefs();
            if (p == null) {
                CNLog.e(TAG, "拿不到 Context，「已问过」没能落盘，下次安装完还会问");
                return;
            }
            // commit 而非 apply：答完 3 秒后就重启，异步落盘可能没写完就被杀。
            p.edit().putBoolean(KEY_ASKED, true).commit();
        } catch (Throwable t) {
            CNLog.e(TAG, "记录「已问过」失败", t);
        }
    }

    private static SharedPreferences prefs() {
        Context ctx = appContext();
        return ctx == null ? null : ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 补丁类不由框架实例化，只能反射取 Application Context（与原包同一手法）。 */
    private static Context appContext() {
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Object thread = cls.getMethod("currentActivityThread").invoke(null);
            return (Context) cls.getMethod("getApplication").invoke(thread);
        } catch (Throwable t) {
            return null;
        }
    }
}
