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
 * 读 {@code /proc/self/cmdline} 拿包名、{@code /proc/self/status} 拿 uid
 * （都不依赖 Context，native 最早期触发的 Java 调用也能用），按
 * {@code /data/user/<用户号>/<pkg>} → {@code /data/data/<pkg>} →
 * {@code /data/user/0/<pkg>} 的顺序探测，<b>取第一个可写的</b>。
 *
 * <h3>🔴 用户号不能写死 0</h3>
 *
 * 用户号 = {@code uid / 100000}（Android 的 {@code UserHandle.PER_USER_RANGE}）。
 * 主用户是 0，但<b>工作资料 / 系统分身 / 厂商应用多开不是</b>——真机上见过
 * 10 和 999。
 *
 * <p>写死 0 的坑比「路径不存在」更隐蔽：分身进程去 stat
 * {@code /data/user/0/<pkg>} 时，{@code /data}、{@code /data/user}、
 * {@code /data/user/0} 一路都是 711，<b>stat 会成功</b>——于是解析器高高兴兴
 * 返回了<b>主用户</b>的目录，而分身进程（另一个 uid）对它没有任何读写权限。
 * 表现就是补丁层各种「写了没生效 / 读不到自己刚写的东西」，且哪一层都不报错。
 *
 * <h3>🔴 判据是「可写」，不是「存在」</h3>
 *
 * 正因为上面那条，探测<b>必须</b>用 {@code canWrite()} 而不是 {@code exists()}
 * / {@code isDirectory()}。别人的目录照样「存在」，但那对我们毫无用处——
 * 我们要的是一个能落 flag 的地方，不是一个能看见的地方。
 *
 * <p><b>native 侧（MagiaLegacy.cpp）用的是同一套算法</b>——Java 和 native
 * 共享一批 flag 文件（安装标记、引擎闸门、序章标记），两边必须解析出
 * 同一个目录，改算法时两边一起改。native 那边 uid 直接 {@code getuid()}，
 * 拿到的是同一个数。
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
        String[] candidates = candidatesFor(pkg, userId());
        for (int i = 0; i < candidates.length; i++) {
            try {
                File f = new File(candidates[i]);
                // 「可写」而不是「存在」——理由见类注释那两条 🔴。
                if (f.isDirectory() && f.canWrite()) return candidates[i];
            } catch (Throwable ignore) {}
        }
        // 一个可写的都没有：退回按本进程用户号拼出来的那个。它是「本该正确」
        // 的路径，让错误暴露在原位，而不是换去一个更隐蔽的地方。
        return candidates[0];
    }

    /**
     * 候选路径，按「最可能是本进程真正的私有目录」排序。抽成纯函数是为了
     * 能直接测——真机上凑不齐工作资料/分身/多开这几种环境。
     */
    public static String[] candidatesFor(String pkg, int user) {
        return new String[] {
                // 本进程所属用户的目录。主用户时它就是 /data/user/0/<pkg>。
                "/data/user/" + user + "/" + pkg,
                // 每个应用的挂载命名空间里，/data/data 指向自己那一份；
                // 老设备上它是指向 /data/user/0 的兼容软链。
                "/data/data/" + pkg,
                // 历史路径兜底。放最后：写死 0 在分身进程里会指到别人家。
                "/data/user/0/" + pkg,
        };
    }

    /**
     * 本进程的 Android 用户号 = {@code uid / 100000}。
     *
     * <p>读 {@code /proc/self/status} 而不是 {@code android.os.Process.myUid()}：
     * 与 {@link #packageName()} 同一种取法，不依赖 Context、不依赖 android
     * 框架类，JVM 上也跑得通（本类被 {@code CNDebugFlags} 的静态初始化用到，
     * 抛异常会连累整个类加载）。native 侧 {@code getuid()} 拿到的是同一个数。
     *
     * <p>读不到一律回 0：那是绝大多数设备的真实值，且后面还有可写性探测兜底。
     */
    public static int userId() {
        try {
            return userIdFromStatus(readSmall("/proc/self/status"));
        } catch (Throwable ignore) {
            return 0;
        }
    }

    /** {@link #userId()} 的纯函数部分：从 status 正文里抠出用户号。 */
    public static int userIdFromStatus(String status) {
        if (status == null) return 0;
        String[] lines = status.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith("Uid:")) continue;
            // 形如 "Uid:\t10999\t10999\t10999\t10999"，四个都是同一个 uid，
            // 取第一个（real uid）。
            String[] parts = line.substring(4).trim().split("\\s+");
            if (parts.length == 0 || parts[0].isEmpty()) return 0;
            try {
                long uid = Long.parseLong(parts[0]);
                if (uid < 0) return 0;
                return (int) (uid / 100000L);   // UserHandle.PER_USER_RANGE
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static String readSmall(String path) throws java.io.IOException {
        FileInputStream in = new FileInputStream(path);
        try {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } finally {
            in.close();
        }
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
        // F-038：/proc/self/cmdline 在次级进程（如 :cnrestart）中是
        // <package>:<suffix>，应用数据目录仍以基础包名命名。后缀不得拼进
        // /data/user/<id>/... 路径。
        if (pkg != null) {
            int colon = pkg.indexOf(':');
            if (colon >= 0) pkg = pkg.substring(0, colon);
        }
        if (pkg == null || pkg.isEmpty() || pkg.indexOf('/') >= 0) {
            pkg = "io.kamihama.totentanz";
        }
        return pkg;
    }
}
