package io.kamihama.magianative;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;

/**
 * 重启本进程。
 *
 * <h3>为什么不用 {@code RestClient.restartApp()}</h3>
 *
 * 原包那个实现真机上是坏的，表现为「浮层再次出现 → 退出到桌面 → 闪一下黑屏 →
 * 退回桌面 → 并没有重启」。解码对照，两处都能对上：
 *
 * <ol>
 *   <li>它<b>开头先调一次</b> {@code checkAndApplyHotUpdate()}——那是被
 *       {@link CNHotUpdateCheck} 取代掉的旧流程，它自己会
 *       {@code CNCNDownloadUI.show()}。这就是「浮层再次出现」。</li>
 *   <li>随后 {@code finish()} → {@code sleep(500)} →
 *       {@code startActivity(launchIntent)} → {@code sleep(1000)} →
 *       {@code killProcess(myPid())}。新 Activity 起在<b>同一个进程</b>里，
 *       一秒后那一刀把它自己也砍了。这就是「闪一下黑屏又退回桌面」。</li>
 * </ol>
 *
 * <h3>这里的做法</h3>
 *
 * 先用 {@link AlarmManager} 把启动 Intent 排到 ~300ms 之后，再杀掉自己。等闹钟
 * 响时旧进程已经没了，系统会为这个 PendingIntent 新建进程——这才是真正的重启。
 *
 * <p><b>已知限制</b>：Android 10 起对后台启动 Activity 有限制，进程刚死那几秒
 * 属于宽限窗口，通常能起来；但部分厂商 ROM（尤其激进的省电策略）可能仍然拦下。
 * 所以 Toast 文案里明说了「若没有自动回来请手动打开」，而不是假定一定成功。
 */
public final class CNRestart {

    private static final String TAG = "MagiaCNRestart";

    /** 排给闹钟的延迟。要大于杀进程所需的时间，又不能久到玩家以为卡死。 */
    private static final long ALARM_DELAY_MS = 300L;

    /** PendingIntent 的 requestCode，随便取一个不与别处冲突的常量。 */
    private static final int REQ_CODE = 0x4D47_4300;

    private CNRestart() {}

    /**
     * Toast 提示 → 等 {@code countdownMs} → 重启进程。<b>会阻塞</b>，别在 UI
     * 线程上调。
     *
     * @param toastText 提示文案，由调用方按自己的上下文给
     */
    public static void restartWithNotice(String toastText, long countdownMs) {
        try {
            final Activity act = RestClient.getCurrentActivity();
            final String msg = toastText + "（若没有自动回来，请手动打开游戏）";
            if (act != null) {
                act.runOnUiThread(new ToastRunnable(act, msg));
            } else {
                CNLog.w(TAG, "取不到 Activity，重启前的提示无法显示");
            }
            CNLog.i(TAG, "将在 " + countdownMs + "ms 后重启进程");
            Thread.sleep(countdownMs);
            restartNow();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            CNLog.e(TAG, "重启流程出错", t);
        }
    }

    private static final class ToastRunnable implements Runnable {
        private final Context ctx;
        private final String  msg;
        ToastRunnable(Context ctx, String msg) { this.ctx = ctx; this.msg = msg; }
        @Override public void run() {
            try {
                android.widget.Toast.makeText(ctx, msg,
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignore) {}
        }
    }

    /** 立刻重启：排闹钟 → 杀自己。不返回（除非排闹钟就失败了）。 */
    public static void restartNow() {
        Context ctx = appContext();
        if (ctx == null) {
            CNLog.e(TAG, "拿不到 Context，无法重启");
            return;
        }
        try {
            Intent intent = ctx.getPackageManager()
                    .getLaunchIntentForPackage(ctx.getPackageName());
            if (intent == null) {
                CNLog.e(TAG, "取不到启动 Intent，无法重启");
                return;
            }
            // CLEAR_TASK：别把旧任务栈带过来，重启要的是干净的一次冷启动
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);

            int flags = PendingIntent.FLAG_CANCEL_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                // API 31 起 PendingIntent 必须显式指定可变性；23 起就支持这个标志，
                // 提前给上没有副作用。
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, REQ_CODE, intent, flags);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null || pi == null) {
                CNLog.e(TAG, "AlarmManager/PendingIntent 取不到，改为直接结束进程");
            } else {
                long at = System.currentTimeMillis() + ALARM_DELAY_MS;
                if (Build.VERSION.SDK_INT >= 19) {
                    am.setExact(AlarmManager.RTC, at, pi);
                } else {
                    am.set(AlarmManager.RTC, at, pi);
                }
                CNLog.i(TAG, "启动 Intent 已排入闹钟，" + ALARM_DELAY_MS + "ms 后触发");
            }
        } catch (Throwable t) {
            CNLog.e(TAG, "排重启闹钟失败，仍然结束进程", t);
        }
        // 日志要在杀自己之前落盘，否则最后几行留在缓冲里就没了
        try { CNLog.flushNow(); } catch (Throwable ignore) {}
        Process.killProcess(Process.myPid());
    }

    /** 补丁类不由框架实例化，只能反射取 Application Context（与原包同一手法）。 */
    private static Context appContext() {
        try {
            Activity act = RestClient.getCurrentActivity();
            if (act != null) return act.getApplicationContext();
        } catch (Throwable ignore) {}
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Object thread = cls.getMethod("currentActivityThread").invoke(null);
            return (Context) cls.getMethod("getApplication").invoke(thread);
        } catch (Throwable t) {
            return null;
        }
    }
}
