package io.kamihama.magianative;

import android.app.Activity;

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
 * <p>这里把选择逻辑移进 Java（可测、可读），{@code RestClient.smali} 只留一行
 * 委托。选法：
 * <ol>
 *   <li>遍历 {@code mActivities} 的全部记录（{@code ArrayMap} 实现
 *       {@link Map}，按值迭代即可），优先选 {@code paused/stopped/hideForNow}
 *       全为 {@code false}、且 {@code activity} 未 {@code isFinishing()/
 *       isDestroyed()} 的记录——这才是「最可能在当前任务栈顶层」的实例；</li>
 *   <li>没有符合条件的就退回第一条记录（与原实现一致，保证调用方总有兜底）；</li>
 *   <li>反射任何一步失败返回 {@code null}（与原实现一致）。</li>
 * </ol>
 *
 * <p>故意不用 {@link android.util.ArrayMap} 的编译期类型：它是隐藏路径反射拿到的
 * 对象，直接用 {@code java.util.Map} 接口迭代即可，避免对具体实现的编译期依赖。
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

            Activity fallback = null;
            for (Object record : activities.values()) {
                if (record == null) continue;
                Activity act = extractActivity(record);
                if (act == null) continue;
                if (fallback == null) fallback = act;
                if (!recordBool(record, "paused") && !recordBool(record, "stopped")
                        && !recordBool(record, "hideForNow")
                        && !act.isFinishing() && !act.isDestroyed()) {
                    return act;
                }
            }
            return fallback;
        } catch (Throwable t) {
            try { android.util.Log.e(TAG, "getCurrentActivity failed: " + t); } catch (Throwable ignore) {}
            return null;
        }
    }

    /** 从 ActivityClientRecord 反射取 {@code activity} 字段，非 Activity 返回 null。 */
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

    /** 反射读 record 的 boolean 字段；读不到按 false 处理（保守，不误杀可交互实例）。 */
    private static boolean recordBool(Object record, String name) {
        try {
            Field f = record.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return Boolean.TRUE.equals(f.get(record));
        } catch (Throwable t) {
            return false;
        }
    }
}
