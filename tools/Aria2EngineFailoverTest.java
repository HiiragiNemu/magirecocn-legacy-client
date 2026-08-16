import io.kamihama.magianative.Aria2EngineFailover;
import io.kamihama.magianative.CNAria2Lib;
import java.io.File;

/**
 * Aria2EngineFailover 状态机回归测试（dead-man's switch）。
 *
 * <p>测的是纯文件标记逻辑：初始后端、armed→换组、disarm→清零沿用、
 * 加载期失败当场换组、连续死亡到上限 giveUp、disarm 后恢复。
 *
 * <p>标记目录用系统属性 {@code aria2.failover.dir} 指到临时目录
 * （Android 上该属性不存在，不影响生产路径）。
 */
public class Aria2EngineFailoverTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    public static void main(String[] args) throws Exception {
        File dir = File.createTempFile("aria2fo", "");
        dir.delete();
        dir.mkdirs();
        System.setProperty("aria2.failover.dir", dir.getAbsolutePath());

        // [1] 无标记 → 默认 OSSL
        check("[1] 初始 pickBackend = OSSL",
                Aria2EngineFailover.pickBackend() == CNAria2Lib.Backend.OSSL);

        // [2] arm 后进程「死于非命」（不 disarm）→ 下次 pickBackend 换组 + 计一次死亡
        Aria2EngineFailover.arm();
        check("[2a] arm 后 dump 含 armed",
                Aria2EngineFailover.dump().contains("armed=true"));
        check("[2b] armed 未 disarm → pickBackend 切到 GNUTLS",
                Aria2EngineFailover.pickBackend() == CNAria2Lib.Backend.GNUTLS);
        check("[2c] 切换后 dump 含 deaths=1",
                Aria2EngineFailover.dump().contains("deaths=1"));

        // [3] 干净关停（disarm）→ 清零并沿用当前后端
        Aria2EngineFailover.disarm();
        check("[3a] disarm 后 dump 含 deaths=0",
                Aria2EngineFailover.dump().contains("deaths=0"));
        check("[3b] disarm 后 pickBackend 沿用 GNUTLS",
                Aria2EngineFailover.pickBackend() == CNAria2Lib.Backend.GNUTLS);

        // [4] 加载期失败当场换组（不走 armed 标记）
        check("[4a] shouldFallbackOnLoadError(GNUTLS) → OSSL",
                Aria2EngineFailover.shouldFallbackOnLoadError(CNAria2Lib.Backend.GNUTLS)
                        == CNAria2Lib.Backend.OSSL);
        check("[4b] 换组后 dump 含 deaths=1",
                Aria2EngineFailover.dump().contains("deaths=1"));

        // [5] 连续 armed 死亡到上限 → giveUp（防乒乓）
        for (int i = 0; i < 3; i++) {
            Aria2EngineFailover.arm();
            Aria2EngineFailover.pickBackend();
        }
        check("[5] deaths>=4 → giveUp", Aria2EngineFailover.giveUp());

        // [6] disarm 清零后不再 giveUp
        Aria2EngineFailover.disarm();
        check("[6] disarm 清零后 giveUp=false", !Aria2EngineFailover.giveUp());

        // [7] 版本变更即重置：伪造「旧版本、已 giveUp」的标记 → 读到即整体重置
        java.io.FileWriter fw = new java.io.FileWriter(new File(dir, "aria2_failover"));
        fw.write("backend=GNUTLS\narmed=1\ndeaths=9\ngen=0.0.0\n");
        fw.close();
        check("[7a] 旧版本标记读后 giveUp 被重置=false", !Aria2EngineFailover.giveUp());
        check("[7b] 重置后 pickBackend 回默认 OSSL",
                Aria2EngineFailover.pickBackend() == CNAria2Lib.Backend.OSSL);
        check("[7c] 重置后 dump 为 backend=OSSL armed=false deaths=0",
                Aria2EngineFailover.dump().contains("backend=OSSL")
                        && Aria2EngineFailover.dump().contains("armed=false")
                        && Aria2EngineFailover.dump().contains("deaths=0"));

        System.clearProperty("aria2.failover.dir");
        File[] leftover = dir.listFiles();
        if (leftover != null) { for (File f : leftover) f.delete(); }
        dir.delete();

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
