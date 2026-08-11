package io.kamihama.magianative;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 离线包注入的 trampoline Activity：玩家手动选择（网盘等）下载好的 zip，
 * 拷贝到私有离线区，用 16MB 分块清单（manifest.json）校验后标记「离线就位」。
 *
 * <p>这是分块校验的**兜底**：玩家在「下载反复失败 / 服务器不可达」时，可以从
 * 网盘下载官方 zip（文件名与 FILE_NAMES 匹配，如 cn_base_03.zip），导入后
 * 该文件跳过网络下载。校验用 {@link ChunkManifest}（与在线下载一致），
 * 不匹配即拒收——防损坏/被篡改的包。
 *
 * <p>流程：{@link #requestImport(Activity, String)} 触发本 Activity →
 * ACTION_GET_CONTENT 文件选择器 → {@link #onActivityResult} 拷贝到私有目录 →
 * 分块校验 → 写离线标记。结果通过静态回调通知 UI。
 */
public final class CNOfflineImportActivity extends Activity {
    private static final String TAG = "CNOfflineImport";
    private static final int REQ_PICK = 7001;

    /** 待导入的文件名（如 cn_base_03.zip），Activity 启动前设置。 */
    private static volatile String pendingName;

    /** 导入结果回调：success=是否校验通过，name=文件名，err=失败原因（成功时 null）。 */
    public interface Callback {
        void onResult(boolean success, String name, String err);
    }
    private static volatile Callback callback;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        final String name = pendingName;
        if (name == null) {
            finish();
            return;
        }
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("application/zip");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(pick, REQ_PICK);
        } catch (Throwable t) {
            CNLog.e(TAG, "无法启动文件选择器: " + t, t);
            notifyResult(false, name, "无法打开文件选择器");
            finish();
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        final String name = pendingName;
        if (req != REQ_PICK || name == null) {
            notifyResult(false, name == null ? "" : name, "选择被取消");
            finish();
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) {
            notifyResult(false, name, "选择被取消");
            finish();
            return;
        }
        final Uri uri = data.getData();
        final Activity self = this;
        // 拷贝 + 校验是 IO 操作，丢到后台线程，避免阻塞 UI。
        // 用静态嵌套类 + 构造参数，避免匿名类带 this$0 触发 d8 陷阱（铁律 4）。
        new Thread(new ImportTask(self, uri, name), "cnv-offline-import").start();
    }

    /** 后台导入任务：拷贝 + 分块校验 + 通知结果。静态嵌套类，无 this$0。 */
    private static final class ImportTask implements Runnable {
        private final Activity act;
        private final Uri       uri;
        private final String    name;
        ImportTask(Activity a, Uri u, String n) { this.act = a; this.uri = u; this.name = n; }
        @Override public void run() {
            String err = null;
            try {
                File imported = CNOfflineImport.importZip(act, uri, name);
                if (imported != null) {
                    notifyResult(true, name, null);
                    finishSelf(act);
                    return;
                }
                err = "校验未通过（可能与官方包不一致）";
            } catch (Throwable t) {
                CNLog.e(TAG, "离线导入失败: " + t, t);
                err = "导入失败: " + t.getMessage();
            }
            notifyResult(false, name, err);
            finishSelf(act);
        }
    }

    private static void finishSelf(final Activity a) {
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() { a.finish(); }
            });
        } catch (Throwable ignore) {}
    }

    private static void notifyResult(final boolean ok, final String name, final String err) {
        final Callback cb = callback;
        callback = null;
        if (cb == null) return;
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() { cb.onResult(ok, name, err); }
            });
        } catch (Throwable ignore) {}
    }

    /** 请求导入某文件。返回 true 表示已启动选择器。 */
    public static boolean requestImport(Activity act, String name, Callback cb) {
        if (act == null || name == null) return false;
        pendingName = name;
        callback = cb;
        try {
            Intent it = new Intent(act, CNOfflineImportActivity.class);
            act.startActivity(it);
            return true;
        } catch (Throwable t) {
            CNLog.e(TAG, "启动导入 Activity 失败: " + t, t);
            pendingName = null;
            callback = null;
            return false;
        }
    }
}
