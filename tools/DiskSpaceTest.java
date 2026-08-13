import io.kamihama.magianative.CNDiskSpace;

import java.io.File;
import java.io.IOException;

/**
 * 「空间不足」判据的回归测试。
 *
 * <p>钉的是<b>分类</b>而不是数字：这套东西唯一的职责，是让磁盘满不再伪装成网络
 * 故障。认错了就会重演那三层后果——无辜线路被冷却、四次重试白烧、玩家对着「重试」
 * 点到天荒地老（见 CNDiskSpace 的类注释）。
 */
public class DiskSpaceTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    public static void main(String[] args) throws Exception {
        // ── [1] ENOSPC 的各种长相都要认出来 ──────────────────────────
        // ART 抛的是 ErrnoException 包在 IOException 里，errno 常量在编译
        // classpath 上够不着，只能按文本认。文本是 libcore/bionic 里写死的。
        check("[1a] 认得 ART 的 ENOSPC 原文", CNDiskSpace.isOutOfSpace(
                new IOException("write failed: ENOSPC (No space left on device)")));
        check("[1b] 认得小写的 no space left", CNDiskSpace.isOutOfSpace(
                new IOException("java.io.IOException: no space left on device")));
        check("[1c] 认得 not enough space", CNDiskSpace.isOutOfSpace(
                new IOException("Not enough space")));
        check("[1d] 认得 disk full", CNDiskSpace.isOutOfSpace(
                new IOException("Disk full")));

        // 真实调用链里它总是包着好几层：解压那条路是
        // InstallIOException("Cannot write extraction temp: …", ErrnoException)
        check("[1e] 认得包了两层的 cause", CNDiskSpace.isOutOfSpace(
                new IOException("Cannot write extraction temp: /x/y",
                        new IOException("write failed: ENOSPC (No space left on device)"))));

        // ── [2] 不能误伤真正的网络故障 ──────────────────────────────
        // 反了的代价同样实在：网络故障被当成磁盘满，就再也不换线、不重试了。
        check("[2a] 超时不算没空间", !CNDiskSpace.isOutOfSpace(
                new IOException("timeout")));
        check("[2b] 分块校验失败不算没空间", !CNDiskSpace.isOutOfSpace(
                new IOException("分块校验失败 block=0 offset=0 期望=abc 实得=def")));
        check("[2c] Range 响应越界不算没空间", !CNDiskSpace.isOutOfSpace(
                new IOException("Range 响应越界: 100 > 64")));
        check("[2d] null 不炸", !CNDiskSpace.isOutOfSpace(null));
        check("[2e] 没有 message 不炸", !CNDiskSpace.isOutOfSpace(new IOException()));

        // 自环的 cause 不能把判据绕死
        SelfLoop loop = new SelfLoop("绕圈");
        check("[2f] 自环 cause 不死循环", !CNDiskSpace.isOutOfSpace(loop));

        // ── [3] require：够就放行，不够就抛 ────────────────────────
        File tmp = File.createTempFile("cnv-space", ".bin");
        tmp.deleteOnExit();

        boolean threw = false;
        try {
            // 要一个绝不可能有的量（8 EiB 级），必须抛
            CNDiskSpace.require(tmp, Long.MAX_VALUE / 4, "测试包");
        } catch (CNDiskSpace.NotEnoughSpace e) {
            threw = true;
            check("[3a] 抛出的异常带得出还差多少", e.needBytes > 0 && e.freeBytes >= 0);
            check("[3b] 话里有「存储空间不足」和文件名",
                    e.getMessage().contains("存储空间不足")
                            && e.getMessage().contains("测试包"));
        }
        check("[3c] 明显放不下时会抛", threw);

        // 0 字节和负数一律放行——调用方算出来的「还需要多少」可能已经是负的
        // （断点比声明的还长），那不是「空间不足」。
        boolean ok0 = true;
        try {
            CNDiskSpace.require(tmp, 0L, "零");
            CNDiskSpace.require(tmp, -1L, "负");
        } catch (Throwable t) { ok0 = false; }
        check("[3d] 需求 <= 0 时放行", ok0);

        // 读不到可用空间时**放行**：宁可照旧在写的时候失败，也不能因为读不到
        // 一个数字就把玩家挡在门外。
        boolean okUnknown = true;
        try {
            CNDiskSpace.require(new File("/proc/definitely/not/here/x.bin"),
                    1024L, "取不到");
        } catch (Throwable t) { okUnknown = false; }
        check("[3e] 取不到可用空间时放行", okUnknown);

        // ── [4] 自己抛的异常也要被 isOutOfSpace 认出来 ──────────────
        // 上层有两个入口：一个是预检抛的 NotEnoughSpace，一个是写到一半冒出来的
        // ENOSPC。两条都得走同一个「不怪线路」的分支。
        boolean selfRecognised = false;
        try {
            CNDiskSpace.require(tmp, Long.MAX_VALUE / 4, "自认");
        } catch (CNDiskSpace.NotEnoughSpace e) {
            selfRecognised = CNDiskSpace.isOutOfSpace(e);
        }
        check("[4a] NotEnoughSpace 自身也被认作没空间", selfRecognised);

        // ── [5] 话术：数字要看得懂 ──────────────────────────────────
        check("[5a] 字节", "512 B".equals(CNDiskSpace.human(512L)));
        check("[5b] KB", "1.0 KB".equals(CNDiskSpace.human(1024L)));
        check("[5c] MB", "1.0 MB".equals(CNDiskSpace.human(1024L * 1024L)));
        // 03 的真实大小：1422289288 B = 1.32 GiB。用 GiB 而不是 GB 是因为
        // 玩家在系统「存储」页看到的也是这个口径，两边对得上才有参考价值。
        check("[5d] GB", "1.3 GB".equals(CNDiskSpace.human(1422289288L)));
        check("[5e] 负数不装懂", "未知".equals(CNDiskSpace.human(-1L)));

        String s = CNDiskSpace.shortfall("cn_base_03.zip", 1422289288L, 100L * 1024L * 1024L);
        check("[5f] 短缺说明里三个数都在",
                s.contains("cn_base_03.zip") && s.contains("还需要")
                        && s.contains("当前可用") && s.contains("还差"));

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    /** cause 指向自己，用来证明遍历不会死循环。 */
    private static final class SelfLoop extends IOException {
        private static final long serialVersionUID = 1L;
        SelfLoop(String m) { super(m); }
        @Override public synchronized Throwable getCause() { return this; }
    }
}
