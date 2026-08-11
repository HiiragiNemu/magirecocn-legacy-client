import io.kamihama.magianative.CNChunkedDownload;
import io.kamihama.magianative.CNChunkedDownload.ChunkHashes;
import java.util.Arrays;

/**
 * 16MB 分块哈希清单的边界逻辑测试（不涉及网络）。
 *
 * 验证 ChunkHashes 的块对齐/区间指纹选择：
 *  - 块边界严格按文件 offset（chunkSize 整数倍）对齐
 *  - hashFor(start,end) 只在区间落在单个块内时返回指纹
 *  - 跨块区间返回 null（无法用单指纹）
 */
public class ChunkHashTest {
    static int pass = 0, fail = 0;

    static void check(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  ✓ " + name + " — " + detail); }
        else      { fail++; System.out.println("  ✗ " + name + " — " + detail); }
    }

    public static void main(String[] args) {
        System.out.println("\n[1] 100MB 文件按 16MB 分块 → 7 块");
        ChunkHashes h = new ChunkHashes(16L*1024*1024, 100L*1024*1024,
                Arrays.asList("a","b","c","d","e","f","g"));
        check("块数=7", h.count == 7, "count=" + h.count);
        check("块0 offset 0", "a".equals(h.hashFor(0, 16L*1024*1024)), "block0");
        check("块1 offset 16M", "b".equals(h.hashFor(16L*1024*1024, 32L*1024*1024)), "block1");

        System.out.println("\n[2] 块内子区间（分片从块中段开始）");
        check("块0 中段", "a".equals(h.hashFor(1024, 8L*1024*1024)), "mid-block");
        check("块0 尾部到块边界", "a".equals(h.hashFor(15L*1024*1024, 16L*1024*1024)), "tail-to-boundary");

        System.out.println("\n[3] 跨块区间 → null（无法单指纹）");
        check("跨块0→1", h.hashFor(15L*1024*1024, 17L*1024*1024) == null, "cross blocks");
        check("跨块到文件尾", h.hashFor(95L*1024*1024, 100L*1024*1024) == null, "cross to end");

        System.out.println("\n[4] 最后一块不足 16MB（余数）");
        ChunkHashes tail = new ChunkHashes(16L*1024*1024, 16L*1024*1024 + 100,
                Arrays.asList("full", "tiny"));
        check("最后一块", "tiny".equals(tail.hashFor(16L*1024*1024, 16L*1024*1024+100)),
                "tail block size=100");

        System.out.println("\n[5] 空清单 → null（未启用分块校验）");
        ChunkHashes none = new ChunkHashes(16L*1024*1024, 100L*1024*1024, null);
        check("无清单", none.hashFor(0, 16L*1024*1024) == null, "empty");

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
