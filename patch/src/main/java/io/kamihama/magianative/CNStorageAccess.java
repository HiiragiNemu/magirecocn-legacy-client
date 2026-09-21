package io.kamihama.magianative;

import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

/** 仅由玩家手动打开；正常应用目录下载不以此权限作为启动条件。 */
public final class CNStorageAccess {
    private CNStorageAccess() {}

    public static boolean granted(Activity act) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return Build.VERSION.SDK_INT < 23 || act.checkSelfPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    public static String status(Activity act) {
        return granted(act) ? "文件权限：已开启" : "文件权限：设置";
    }

    public static void open(Activity act) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + act.getPackageName()));
                try { act.startActivity(intent); }
                catch (android.content.ActivityNotFoundException missing) {
                    act.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else if (Build.VERSION.SDK_INT >= 23 && !granted(act)) {
                act.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2409);
            } else {
                act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + act.getPackageName())));
            }
        } catch (RuntimeException failure) {
            CNLog.w("文件权限", "系统权限页打开失败: " + failure);
            Toast.makeText(act, "请在系统设置的应用权限中手动开启文件访问权限", Toast.LENGTH_LONG).show();
        }
    }
}
