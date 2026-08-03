package io.kamihama.magianative;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 「下次启动去看序章」这个意愿的状态：标记文件的读写，以及「自动询问只问一次」
 * 的记忆。
 *
 * <h3>为什么需要</h3>
 *
 * 复刻服对任何账号都下发「已通关」的存档，玩家进游戏直接落到主页，序章不会自己
 * 播。想看得有个入口。
 *
 * <h3>分工</h3>
 *
 * 本类只管「标记在不在」。真正去播的是 {@link CNScene0Nav}：等前端起来之后把
 * WebView 的 hash 设成游戏自己的第 0 章路由（{@code Scene0Top}），跟玩家自己点
 * 进去完全一样。标记<b>一次性消费</b>——确认跳进去之后就清掉。
 *
 * <p>（早先试过从 native 拦 {@code pushSceneTop} 改调 {@code pushScenePrologue}，
 * 真机上只放得出最后那场战斗、剧情文字一句都没有。原因见
 * {@link CNScene0Nav} 的类注释：序章是前端驱动的流程，native 只是播放器。）
 *
 * <p>询问的界面在 {@link CNCNDownloadUI}：它拥有浮层的调色板与模态框样式，
 * 这个询问就用那一套，不另起一个长得不一样的对话框。
 *
 * <h3>两个触发口</h3>
 * <ul>
 *   <li><b>自动询问</b>：只在「首次安装跑完、完成标记落盘」那一瞬间弹一次，
 *       答过就记住，之后再也不问。见 {@link CNDownloaderFix} 的收尾。</li>
 *   <li><b>序章胶囊</b>：常驻浮层左上角，任何时候都能点开重新选择——
 *       自动询问已经答过之后，这是唯一的入口。</li>
 * </ul>
 */
public final class CNTutorialPrompt {

    private static final String TAG = "MagiaCNScene0";

    /**
     * 「下次启动去第 0 章」的标记。放在安装标记的同一个目录：资源装完时该目录
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
    // 标记
    // ==================================================================

    /** 标记是否已就位（= 本次/下次启动进主页后会自动跳到第 0 章）。 */
    public static boolean isArmed() {
        try { return new File(FORCE_TUTORIAL_FLAG).isFile(); }
        catch (Throwable t) { return false; }
    }

    /**
     * 按选择落地标记。
     *
     * @param on true 写出标记（去第 0 章），false 清除标记（正常留在主页）
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
                CNLog.e(TAG, "建不出目录 " + dir + "，序章标记写不了");
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
            CNLog.i(TAG, "已写出序章标记：" + f.getAbsolutePath());
        } catch (Throwable t) {
            CNLog.e(TAG, "写序章标记失败", t);
        }
    }

    /** 清掉标记：玩家选「否」，或者已经成功跳进第 0 章。 */
    private static void clearFlag() {
        try {
            File f = new File(FORCE_TUTORIAL_FLAG);
            if (f.exists() && f.delete()) CNLog.i(TAG, "已清除序章标记");
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
