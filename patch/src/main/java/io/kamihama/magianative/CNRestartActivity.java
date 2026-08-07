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
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { launchMain(); }
        }, RELAUNCH_DELAY_MS);
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
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
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
            }, 1000L);
        }
    }
}
