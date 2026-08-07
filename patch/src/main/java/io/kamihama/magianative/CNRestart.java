package io.kamihama.magianative;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

import java.io.File;

/** Android 14/15-safe in-app restart using an independent foreground trampoline. */
public final class CNRestart {
    private static final String TAG = "MagiaCNRestart";
    static final String READY_FILE = "cn_restart_trampoline_ready.flag";
    private static final long READY_TIMEOUT_MS = 2500L;
    private static final long READY_POLL_MS = 40L;

    private CNRestart() {}

    /** Toast -> countdown -> restart. Returns false if the old process was intentionally kept alive. */
    public static boolean restartWithNotice(String toastText, long countdownMs) {
        try {
            final Activity act = RestClient.getCurrentActivity();
            if (act != null) {
                act.runOnUiThread(new ToastRunnable(act, toastText));
            } else {
                CNLog.w(TAG, "取不到 Activity，无法保证前台 trampoline；取消自杀式重启");
                return false;
            }
            CNLog.i(TAG, "restart strategy=foreground separate-process trampoline countdown=" + countdownMs);
            Thread.sleep(countdownMs);
            return restartNow();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            CNLog.e(TAG, "重启流程出错，保留当前进程", t);
            return false;
        }
    }

    private static final class ToastRunnable implements Runnable {
        private final Context ctx;
        private final String msg;
        ToastRunnable(Context ctx, String msg) { this.ctx = ctx; this.msg = msg; }
        @Override public void run() {
            try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show(); }
            catch (Throwable ignore) {}
        }
    }

    /**
     * Start :cnrestart while the game Activity is still visible. The trampoline writes READY_FILE
     * from onResume. Only after observing that handshake do we kill this process.
     */
    public static boolean restartNow() {
        final Activity act = RestClient.getCurrentActivity();
        if (act == null) {
            CNLog.e(TAG, "restart aborted: current Activity is null; old process kept alive");
            return false;
        }
        final File ready = new File(act.getFilesDir(), READY_FILE);
        try { if (ready.exists() && !ready.delete()) CNLog.w(TAG, "旧 restart ready 标记删不掉"); }
        catch (Throwable ignore) {}

        final java.util.concurrent.CountDownLatch launchPosted =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean launchOk =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            act.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(act, CNRestartActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                        act.startActivity(i);
                        launchOk.set(true);
                        CNLog.i(TAG, "restart trampoline startActivity issued from visible game Activity");
                    } catch (Throwable t) {
                        CNLog.e(TAG, "restart trampoline launch failed", t);
                    } finally {
                        launchPosted.countDown();
                    }
                }
            });
            if (!launchPosted.await(1200L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    || !launchOk.get()) {
                CNLog.e(TAG, "restart trampoline launch was not confirmed; old process kept alive");
                return false;
            }
        } catch (Throwable t) {
            CNLog.e(TAG, "restart trampoline dispatch failed; old process kept alive", t);
            return false;
        }

        long deadline = android.os.SystemClock.uptimeMillis() + READY_TIMEOUT_MS;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (ready.isFile()) {
                CNLog.i(TAG, "restart trampoline foreground handshake confirmed; killing old pid="
                        + Process.myPid());
                try { CNLog.flushNow(); } catch (Throwable ignore) {}
                Process.killProcess(Process.myPid());
                return true; // normally unreachable
            }
            try { Thread.sleep(READY_POLL_MS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
        }
        CNLog.e(TAG, "restart trampoline did not reach onResume within " + READY_TIMEOUT_MS
                + "ms; old process kept alive");
        return false;
    }
}
