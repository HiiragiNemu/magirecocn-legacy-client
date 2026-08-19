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

    // F-065：requestReserved 是「请求预留 → picker → 后台导入 → 最终结果」全程的
    // 唯一所有者标志。pendingName 只承载文件名（选完即清，防 Activity 重建重弹），
    // 不再兼作占用判据；占用判据统一看 requestReserved || isImporting()。
    private static volatile boolean requestReserved;

    /** 导入结果回调：success=是否校验通过，name=文件名，err=失败原因（成功时 null）。 */
    public interface Callback {
        void onResult(boolean success, String name, String err);

        /** 进度回调。verifying=true 表示已进入分块校验阶段。 */
        void onProgress(long done, long total, boolean verifying);
    }

    /** 结果回调。 */
    private static volatile Callback callback;
    // F-037：pendingName/callback 的同一同步边界。isImporting 要等文件选完才置位，
    // 请求阶段的「检查+预留」不加锁会被两次快速点击同时穿过、互相覆盖。
    private static final Object REQUEST_LOCK = new Object();

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
        // 用完即清 pendingName。留着的话，下一次这个 Activity 因配置变化等原因
        // 重建时，onCreate 会拿这个陈旧的名字再弹一次文件选择器（F-037 同锁释放）。
        // 注意：这里只清 pendingName、**不清 requestReserved**——后台导入还没完成，
        // 交接窗口必须继续保持占用，否则第二次点击会启动第二个 picker（F-065）。
        synchronized (REQUEST_LOCK) { pendingName = null; }
        // 拷贝 + 校验是 IO 操作，丢到后台线程，避免阻塞 UI。
        // 用静态嵌套类 + 构造参数，避免匿名类带 this$0 触发 d8 陷阱（铁律 4）。
        try {
            new Thread(new ImportTask(self, uri, name), "cnv-offline-import").start();
        } catch (Throwable t) {
            // F-065：线程启动失败（资源耗尽等）必须走同一释放路径，否则
            // reservation/callback 遗留，本进程永久拒绝后续导入。
            CNLog.e(TAG, "启动导入任务失败: " + t, t);
            notifyResult(false, name, "无法启动导入任务");
        }
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
                // Progress 必须用静态嵌套类（铁律 4）：run() 是实例方法，在里面
                // new 匿名类会带 this$0、让 d8 崩。静态嵌套类 + 构造参数传值。
                File imported = CNOfflineImport.importZip(
                        act, uri, name, new ImportProgress());
                if (imported != null) {
                    notifyResult(true, name, null);
                    finishSelf(act);
                    return;
                }
                if (CNOfflineImport.isImporting()) {
                    err = "已有导入正在进行，请等它完成";
                } else {
                    err = "校验未通过（可能与官方包不一致）";
                }
            } catch (Throwable t) {
                CNLog.e(TAG, "离线导入失败: " + t, t);
                err = "导入失败: " + t.getMessage();
            }
            notifyResult(false, name, err);
            finishSelf(act);
        }
    }

    /**
     * {@link CNOfflineImport.Progress} 的静态实现：把进度转发到静态
     * {@link #notifyProgress}。静态方法无 this$0，符合铁律 4。
     */
    private static final class ImportProgress implements CNOfflineImport.Progress {
        ImportProgress() {}
        @Override public void onProgress(long done, long total, boolean verifying) {
            notifyProgress(done, total, verifying);
        }
    }

    private static void finishSelf(final Activity a) {
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() { a.finish(); }
            });
        } catch (Throwable ignore) {}
    }

    private static void notifyProgress(final long done, final long total,
                                       final boolean verifying) {
        final Callback cb = callback;
        if (cb == null) return;
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() { cb.onProgress(done, total, verifying); }
            });
        } catch (Throwable ignore) {}
    }

    /** 取消/失败/完成共用的最终释放：抓取旧回调并清空全部 reservation 状态。 */
    private static void releaseReservation() {
        synchronized (REQUEST_LOCK) {
            callback = null;
            pendingName = null;
            requestReserved = false;
        }
    }

    private static void notifyResult(final boolean ok, final String name, final String err) {
        final Callback cb;
        synchronized (REQUEST_LOCK) {
            // F-065：一次性释放——cancel/picker 失败/导入完成都要清 pendingName 和
            // requestReserved。旧实现只清 callback，cancel 后 pendingName 残留让
            // requestImport 永久拒绝后续导入。
            cb = callback;
            callback = null;
            pendingName = null;
            requestReserved = false;
        }
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
        // F-037：请求阶段的重入保护——isImporting 要等文件选完才置位，两次快速
        // 点击/并发调用可同时通过。pendingName != null 表示已有一个请求在等用户
        // 选文件，拒绝后来的（否则后一次会覆盖前一次的 pendingName/callback）。
        synchronized (REQUEST_LOCK) {
            // F-065：占用判据只看 requestReserved（全程）|| isImporting，不依赖
            // pendingName 的双重含义。
            if (requestReserved || CNOfflineImport.isImporting()) {
                CNLog.w(TAG, "拒绝重复导入（已有请求/导入在进行）: " + name);
                if (cb != null) {
                    try {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() {
                                cb.onResult(false, name, "已有导入正在进行，请等它完成");
                            }
                        });
                    } catch (Throwable ignore) {}
                }
                return false;
            }
            requestReserved = true;
            pendingName = name;
            callback = cb;
        }
        try {
            Intent it = new Intent(act, CNOfflineImportActivity.class);
            act.startActivity(it);
            return true;
        } catch (Throwable t) {
            CNLog.e(TAG, "启动导入 Activity 失败: " + t, t);
            // F-065：走同一释放路径，不残留 reservation。
            releaseReservation();
            return false;
        }
    }
}
