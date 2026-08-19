package io.kamihama.magianative;

import android.app.Activity;
import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * {@code RestClient.getCurrentActivity()} 的健壮实现（F-053）。
 *
 * <p>基线里 {@code RestClient.getCurrentActivity()} 反射读
 * {@code ActivityThread.mActivities} 后只检查非空，就无条件取
 * {@code valueAt(0)} 对应记录的 {@code activity} 字段返回。{@code ArrayMap}
 * 第 0 项不是「当前前台 Activity」的合同；进程里同时存在主 Activity、离线导入
 * trampoline、计费代理与重启 trampoline 时，调用方可能拿到隐藏/旧实例，把 UI
 * 挂进死视图树，或从错误 task 发起重启与文件选择。
 *
 * <p>这里把选择逻辑移进 Java（可读可测），{@code RestClient.smali} 只留一行委托。
 * 选法：
 * <ol>
 *   <li>遍历 {@code mActivities} 的全部记录（{@code ArrayMap} 实现 {@link Map}，
 *       按值迭代即可），只把<b>还活着</b>的 Activity（未 {@code isFinishing()/
 *       isDestroyed()}，见 {@link #live(Activity)}）当候选；</li>
 *   <li>在前台判定里，{@code paused/stopped} 必须<b>明确读到 {@code false}</b>
 *       才算可交互——反射失败返回 {@code null}，绝不拿 {@code false} 冒充（把可能
 *       的后台实例当 live）；{@code hideForNow} 只在显式 {@code true} 时排除
 *       （部分版本没有该辅助字段）；</li>
 *   <li>没有任何可交互记录时退回<b>第一个存活</b>的 Activity，不退回最早插入的
 *       死对象；完全没有存活候选返回 {@code null}（与原实现反射失败一致）。</li>
 * </ol>
 */
public final class CNRestClientActivity {
    private static final String TAG = "MagiaClientJNI"; // 与原 smali 的 tag 一致

    private CNRestClientActivity() {}

    public static Activity getCurrentActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentActivityThread");
            Object thread = m.invoke((Object) null);
            if (thread == null) return null;
            Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object raw = f.get(thread);
            if (!(raw instanceof Map<?, ?>)) return null;
            Map<?, ?> activities = (Map<?, ?>) raw;
            if (activities.isEmpty()) return null;

            // F-053 follow-up：fallback 只取「活的」Activity——没有任何前台候选时
            // 退回第一个存活实例，而不是最早插入的（可能已 finishing/destroyed）对象。
            Activity fallback = null;
            for (Object record : activities.values()) {
                if (record == null) continue;
                Activity act = extractActivity(record);
                if (!live(act)) continue;
                if (fallback == null) fallback = act;
                // 生命周期字段必须明确读到 false 才叫前台；反射失败返回 null，
                // 不拿 false 冒充（残余二）。hideForNow 只在显式 true 时排除。
                Boolean paused = recordBool(record, "paused");
                Boolean stopped = recordBool(record, "stopped");
                if (Boolean.FALSE.equals(paused) && Boolean.FALSE.equals(stopped)
                        && !Boolean.TRUE.equals(recordBool(record, "hideForNow"))) {
                    return act;
                }
            }
            return fallback;
        } catch (Throwable t) {
            try { android.util.Log.e(TAG, "getCurrentActivity failed: " + t); } catch (Throwable ignore) {}
            return null;
        }
    }

    /** 从 ActivityClientRecord 反射取 {@code activity} 字段。 */
    private static Activity extractActivity(Object record) {
        try {
            Field f = record.getClass().getDeclaredField("activity");
            f.setAccessible(true);
            Object a = f.get(record);
            return (a instanceof Activity) ? (Activity) a : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读 ActivityClientRecord 的布尔字段；读不到返回 null，不伪造生命周期状态。 */
    private static Boolean recordBool(Object record, String name) {
        try {
            Field f = record.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return Boolean.valueOf(f.getBoolean(record));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只把「还活着」的 Activity 当候选：未 finishing，且（API 17+）未 destroyed。 */
    private static boolean live(Activity act) {
        if (act == null || act.isFinishing()) return false;
        return Build.VERSION.SDK_INT < 17 || !act.isDestroyed();
    }
}
