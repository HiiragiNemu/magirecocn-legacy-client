package io.kamihama.magianative;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;

/** Visible, transparent restart trampoline living in the independent :cnrestart process. */
public final class CNRestartActivity extends Activity {
    private static final String TAG = "MagiaCNRestart";
    private static final long RELAUNCH_DELAY_MS = 700L;
    private boolean scheduled;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { overridePendingTransition(0, 0); } catch (Throwable ignore) {}
        CNLog.i(TAG, "trampoline onCreate pid=" + android.os.Process.myPid());
    }

    @Override protected void onResume() {
        super.onResume();
        CNLog.i(TAG, "trampoline onResume: foreground/visible confirmed pid="
                + android.os.Process.myPid());
        writeReadyFlag();
        if (scheduled) return;
        scheduled = true;
        new Handler(Looper.getMainLooper()).postDelayed(new LaunchTask(this), RELAUNCH_DELAY_MS);
    }

    /**
     * 具名静态类而非匿名类。<b>这里是真的必须。</b>
     *
     * <p>CLAUDE.md 铁律 4：当前 d8 撞上带合成字段 {@code this$0} 的类会直接崩
     * （{@code NullPointerException: Cannot invoke "String.length()"}，只报类名、
     * 没有行号）。而 {@code onResume()} / {@code launchMain()} 都是**实例**方法，
     * 写在里头的匿名类 javac 一定会塞 {@code this$0} 进去——所以这两处非改不可。
     *
     * <p>反过来，**静态**方法里的匿名类不带 {@code this$0}，完全没问题：本仓库
     * 另外 32 个匿名类全在静态方法里，一个都不用动。判据是 this$0，不是「匿不匿名」。
     * javac 一律能过，只在 dex 阶段现形，所以 CI 里有
     * {@code tools/check-d8-pitfalls.py} 在 d8 之前先拦一道。
     */
    private static final class LaunchTask implements Runnable {
        private final CNRestartActivity act;
        LaunchTask(CNRestartActivity a) { this.act = a; }
        @Override public void run() { act.launchMain(); }
    }

    /** 同上，重试那一次用。 */
    private static final class RetryTask implements Runnable {
        private final CNRestartActivity act;
        RetryTask(CNRestartActivity a) { this.act = a; }
        @Override public void run() { act.launchRetry(); }
    }

    private void writeReadyFlag() {
        File f = new File(getFilesDir(), CNRestart.READY_FILE);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f, false);
            out.write(("pid=" + android.os.Process.myPid() + "\n").getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
            CNLog.i(TAG, "trampoline ready flag committed: " + f.getAbsolutePath());
        } catch (Throwable t) {
            CNLog.e(TAG, "trampoline ready flag write failed", t);
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignore) {}
        }
    }

    private void launchMain() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch == null) throw new IllegalStateException("package launch intent is null");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            CNLog.i(TAG, "trampoline launching main Activity from visible :cnrestart process");
            startActivity(launch);
            try { overridePendingTransition(0, 0); } catch (Throwable ignore) {}
            new File(getFilesDir(), CNRestart.READY_FILE).delete();
            finish();
        } catch (Throwable t) {
            // Stay visible instead of disappearing to launcher; retry once after 1s.
            CNLog.e(TAG, "trampoline main launch failed; retrying once", t);
            new Handler(Looper.getMainLooper()).postDelayed(new RetryTask(this), 1000L);
        }
    }

    /** 重试一次拉起主 Activity；仍失败就把跳板留在前台，不要悄悄退回桌面。 */
    private void launchRetry() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch == null) throw new IllegalStateException("package launch intent is null");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(launch);
            new File(getFilesDir(), CNRestart.READY_FILE).delete();
            finish();
        } catch (Throwable t2) {
            CNLog.e(TAG, "trampoline retry failed; leaving trampoline visible", t2);
        }
    }
}
