import io.kamihama.magianative.CNPaths;

/**
 * 私有目录解析的回归测试。
 *
 * <p>钉的是 <b>用户号不能写死 0</b> 这条：工作资料 / 系统分身 / 厂商应用多开
 * 里用户号不是 0（真机上见过 10 和 999）。这几种环境 CI 上凑不出来，所以把
 * 「uid → 用户号 → 候选路径」抽成纯函数在这里测。
 *
 * <p>为什么值得单独测：写死 0 的失败方式是<b>静默</b>的。分身进程 stat
 * {@code /data/user/0/<pkg>} 会成功（父目录一路 711），于是解析器返回主用户的
 * 目录，而本进程对它没有读写权限——补丁层每一层都「没报错但没生效」。
 * 这种 bug 只能靠钉判据来防，跑一遍是看不出来的。
 */
public class PathsTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    public static void main(String[] args) {
        // ── [1] uid → 用户号 ─────────────────────────────────────────
        check("[1a] 主用户 uid 10123 → 0", CNPaths.userIdFromStatus(status("10123")) == 0);
        check("[1b] 工作资料 uid 1010123 → 10",
                CNPaths.userIdFromStatus(status("1010123")) == 10);
        check("[1c] 分身 uid 99910123 → 999",
                CNPaths.userIdFromStatus(status("99910123")) == 999);
        check("[1d] system uid 1000 → 0", CNPaths.userIdFromStatus(status("1000")) == 0);

        // 读不到/读歪一律回 0：那是绝大多数设备的真实值，且后面还有可写性兜底。
        check("[2a] null 回 0", CNPaths.userIdFromStatus(null) == 0);
        check("[2b] 空串回 0", CNPaths.userIdFromStatus("") == 0);
        check("[2c] 没有 Uid 行回 0",
                CNPaths.userIdFromStatus("Name:\tmain\nPid:\t1234\n") == 0);
        check("[2d] Uid 值不是数字回 0",
                CNPaths.userIdFromStatus("Uid:\tabc\tabc\tabc\tabc\n") == 0);
        check("[2e] Uid 行为空回 0", CNPaths.userIdFromStatus("Uid:\n") == 0);
        check("[2f] 负数回 0", CNPaths.userIdFromStatus("Uid:\t-5\t-5\t-5\t-5\n") == 0);
        // Uid 行不一定在开头，前面还有 Name/State/Tgid 等等。
        check("[2g] Uid 行在中间也认得出",
                CNPaths.userIdFromStatus("Name:\tx\nState:\tS\nUid:\t1010001\tx\n") == 10);
        // 只取第一个（real uid）——四列在正常进程里是同一个值。
        check("[2h] 只取第一列",
                CNPaths.userIdFromStatus("Uid:\t99900001\t10\t10\t10\n") == 999);

        // ── [3] 候选路径 ────────────────────────────────────────────
        String pkg = "io.kamihama.totentanz";

        String[] main = CNPaths.candidatesFor(pkg, 0);
        check("[3a] 主用户第一候选仍是 /data/user/0",
                main[0].equals("/data/user/0/" + pkg));

        String[] clone = CNPaths.candidatesFor(pkg, 999);
        check("[3b] 分身第一候选带自己的用户号",
                clone[0].equals("/data/user/999/" + pkg));
        // 这条是这次修复的核心：写死 0 的那个路径在分身里 stat 得到但读写不了，
        // 所以它必须排在最后，绝不能是首选。
        check("[3c] 写死 0 的历史路径排在最后",
                clone[clone.length - 1].equals("/data/user/0/" + pkg));
        check("[3d] 分身的首选不等于主用户目录",
                !clone[0].equals("/data/user/0/" + pkg));

        boolean allAbsolute = true;
        for (int i = 0; i < clone.length; i++) {
            if (!clone[i].startsWith("/data/") || !clone[i].endsWith("/" + pkg)) {
                allAbsolute = false;
            }
        }
        check("[3e] 候选全是 /data 下该包的绝对路径", allAbsolute);
        check("[3f] 主用户与分身候选数一致", main.length == clone.length);

        // ── [4] 真实进程上跑一遍不炸 ────────────────────────────────
        // JVM 上 /proc/self/status 存在（Linux），拿到的用户号必然 >= 0。
        check("[4a] 真实进程用户号非负", CNPaths.userId() >= 0);
        String priv = CNPaths.privDir();
        check("[4b] privDir 非空且是绝对路径",
                priv != null && priv.startsWith("/"));
        check("[4c] filesDir 就是 privDir + /files",
                CNPaths.filesDir().equals(priv + "/files"));

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    private static String status(String uid) {
        return "Name:\tmain\nState:\tS (sleeping)\nTgid:\t1234\n"
                + "Uid:\t" + uid + "\t" + uid + "\t" + uid + "\t" + uid + "\n"
                + "Gid:\t" + uid + "\t" + uid + "\t" + uid + "\t" + uid + "\n";
    }
}
