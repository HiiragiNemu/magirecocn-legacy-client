package io.kamihama.magianative;

import java.io.File;
import java.io.FileInputStream;

/**
 * 应用私有目录解析器。
 *
 * <h3>为什么存在</h3>
 *
 * 补丁层与 native 历史上全部硬编码 {@code /data/data/<pkg>}。这个路径在
 * Android 4.2+ 设备上只是指向 {@code /data/user/0} 的兼容软链——正规设备
 * 都有，但非标准容器 / 深度定制 ROM 可能没建这个软链。真碰到时游戏引擎
 * 没事（Cocos 走 {@code Context.getFilesDir()}），而补丁层会全瘫：
 * FINAL_FLAG 永远读不到 → 每次启动都判定「未安装」→ 反复全量重下。
 *
 * <h3>算法</h3>
 *
 * 读 {@code /proc/self/cmdline} 拿包名（不依赖任何 Context，native 最早
 * 期触发的 Java 调用也能用），再按 {@code /data/user/0/<pkg>} →
 * {@code /data/data/<pkg>} 的顺序探测真实存在的目录。两边都探测不到时
 * 沿用历史路径，让错误暴露在原位而不是换一个更隐蔽的地方。
 *
 * <p><b>native 侧（MagiaLegacy.cpp）用的是同一套算法</b>——Java 和 native
 * 共享一批 flag 文件（安装标记、引擎闸门、序章标记），两边必须解析出
 * 同一个目录，改算法时两边一起改。
 */
public final class CNPaths {

    private CNPaths() {}

    private static volatile String privDir;

    /** 应用私有目录（如 {@code /data/user/0/io.kamihama.totentanz}）。 */
    public static String privDir() {
        String d = privDir;
        if (d == null) {
            d = resolve();
            privDir = d;
        }
        return d;
    }

    /** 应用 files 目录（{@link #privDir()} + {@code /files}）。 */
    public static String filesDir() {
        return privDir() + "/files";
    }

    private static String resolve() {
        String pkg = packageName();
        // /data/user/0 是 4.2+ 多用户设备上的真实目录，优先；/data/data 是
        // 兼容软链，兜底。只探测目录存在性，应用对自己的私有目录必有权限。
        String[] candidates = {
                "/data/user/0/" + pkg,
                "/data/data/" + pkg,
        };
        for (String c : candidates) {
            try {
                if (new File(c).isDirectory()) return c;
            } catch (Throwable ignore) {}
        }
        // 极端环境两个都探测不到：沿用历史路径，错误暴露在原位。
        return "/data/data/" + pkg;
    }

    /**
     * 从 {@code /proc/self/cmdline} 读进程名（= 包名）。读不到就回本仓库
     * 唯一服务的死值。
     */
    private static String packageName() {
        String pkg = null;
        try {
            FileInputStream in = new FileInputStream("/proc/self/cmdline");
            try {
                byte[] buf = new byte[128];
                int n = in.read(buf);
                if (n > 0) {
                    int end = 0;
                    while (end < n && buf[end] != 0) end++;
                    pkg = new String(buf, 0, end, "UTF-8").trim();
                }
            } finally {
                in.close();
            }
        } catch (Throwable ignore) {}
        if (pkg == null || pkg.isEmpty() || pkg.indexOf('/') >= 0) {
            pkg = "io.kamihama.totentanz";
        }
        return pkg;
    }
}
