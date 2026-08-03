package io.kamihama.magianative;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 「下次启动去打教程战斗」这个意愿的状态：标记文件的读写，以及「自动询问
 * 只问一次」的记忆；外加给 native 侧用的「隐藏/恢复前端界面」入口。
 *
 * <h3>为什么需要</h3>
 *
 * 复刻服对任何账号都下发「已通关」的存档，玩家进游戏直接落到主页，教程战斗
 * 不会自己触发。想打得有个入口。
 *
 * <h3>分工</h3>
 *
 * 本类只管「标记在不在」。真正去触发的是 <b>native 侧</b>
 * （magia-native/src/MagiaLegacy.cpp）：拦引擎首个「进主页」命令
 * （{@code web::SceneCommand::pushSceneTop}），命中标记就改调
 * {@code pushScenePrologue}——从 native 压序章场景，放得出战斗、放不出剧情，
 * 而这正是本版要的行为（只触发教程战斗）。标记由 native 在消费时删除，
 * <b>一次性</b>。
 *
 * <p>序章场景是 native 图层，画在 GL 层上；主界面 WebView 盖在它上面会把它
 * 变成背景。所以 native 在序章图层构造/析构时经 JNI 调本类的
 * {@link #setGameUiVisible(boolean)} 把前端界面藏起来/放回来。
 *
 * <p>询问的界面在 {@link CNCNDownloadUI}：它拥有浮层的调色板与模态框样式，
 * 这个询问就用那一套，不另起一个长得不一样的对话框。
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

    private static final String TAG = "MagiaCNScene0";

    /**
     * 「进主页时改走教程战斗」的标记。放在安装标记的同一个目录：资源装完时该目录
     * 必定存在，不必额外建。
     *
     * <p>文件名沿用 cn_force_tutorial.flag 没改——早先 native 侧读的是这个名字，
     * 改名只会让升级上来的设备留下一个永远没人读的残留文件。
     */
    private static final String FORCE_TUTORIAL_FLAG =
            "/data/data/io.kamihama.totentanz/files/madomagi/magica/cn_force_tutorial.flag";

    private static final String PREFS_NAME = "cnv_tutorial";
    /** 自动询问是否已经问过（无论当时答的是什么）。 */
    private static final String KEY_ASKED  = "prompt_answered";

    private CNTutorialPrompt() {}

    // ==================================================================
    // 前端界面可见性（native 序章场景期间使用）
    // ==================================================================

    /**
     * 切换前端界面（主界面 WebView）的可见性。由 native 在序章图层构造/析构时
     * 经 JNI 调用：序章是画在 GL 层上的 native 图层，主界面 WebView 盖在它
     * 上面会把它变成背景，所以序章开始时藏起来、结束时放回来。
     *
     * <p>任何线程都可调（native 的 hook 跑在游戏线程上），内部切到 UI 线程。
     * 绝不抛异常——native 侧没有处理 Java 异常的余地。
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

    /**
     * 递归找 WebView 并设置可见性，返回命中个数。
     *
     * <p>判据是 {@code instanceof android.webkit.WebView}，不是类名前缀。
     * 第一版按 {@code org.cocos2dx.lib.Cocos2dxWebView} 匹配，真机日志给出的
     * 是「命中 0 个 WebView」——包里确实有 cocos 那套，但游戏主界面用的是
     * {@code jp.f4samurai.web.WebViewImpl}（同样继承 android.webkit.WebView，
     * 由 jp.f4samurai.web.WebViewHelper 管理）。认基类就两者通吃，将来换实现
     * 也不会再漏。
     */
    private static int applyVisibility(View v, boolean visible) {
        int hit = 0;
        if (v instanceof android.webkit.WebView) {
            v.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            CNLog.i(TAG, (visible ? "显示" : "隐藏") + " " + v.getClass().getName());
            // 命中即返回：WebView 内部还有一堆子 View，没必要再往下钻
            return 1;
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

    /** 标记是否已就位（= 下一次「进主页」会被 native 改成教程战斗）。 */
    public static boolean isArmed() {
        try { return new File(FORCE_TUTORIAL_FLAG).isFile(); }
        catch (Throwable t) { return false; }
    }

    /**
     * 按选择落地标记。
     *
     * @param on true 写出标记（进主页时打教程战斗），false 清除标记（正常留在主页）
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
                CNLog.e(TAG, "建不出目录 " + dir + "，教程战斗标记写不了");
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
            CNLog.i(TAG, "已写出教程战斗标记：" + f.getAbsolutePath());
        } catch (Throwable t) {
            CNLog.e(TAG, "写教程战斗标记失败", t);
        }
    }

    /** 清掉标记：玩家选「否」。正常触发路径下标记由 native 消费时删除。 */
    private static void clearFlag() {
        try {
            File f = new File(FORCE_TUTORIAL_FLAG);
            if (f.exists() && f.delete()) CNLog.i(TAG, "已清除教程战斗标记");
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
