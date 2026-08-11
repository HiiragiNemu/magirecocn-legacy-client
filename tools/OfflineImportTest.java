import io.kamihama.magianative.CNChunkedDownload;
import io.kamihama.magianative.CNOfflineImport;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 离线包注入的核心校验逻辑测试（不涉及 Android Context）。
 *
 * 验证 CNOfflineImport.verifyChunks：
 *  - 正确文件（按 16MB 块算的指纹与清单一致）→ 通过
 *  - 内容被篡改 → 拒收
 *  - 大小不符 → 拒收
 */
public class OfflineImportTest {
    static int pass = 0, fail = 0;

    static void check(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  ✓ " + name + " — " + detail); }
        else      { fail++; System.out.println("  ✗ " + name + " — " + detail); }
    }

    /** 按 chunkSize 切块算每块 md5。 */
    static List<String> chunkMd5s(byte[] data, int chunk) throws Exception {
        List<String> out = new ArrayList<String>();
        for (int off = 0; off < data.length; off += chunk) {
            int len = Math.min(chunk, data.length - off);
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(data, off, len);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
            out.add(sb.toString());
        }
        return out;
    }

    static File writeTmp(String name, byte[] data) throws Exception {
        File f = File.createTempFile(name, ".zip");
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
        out.close();
        return f;
    }

    public static void main(String[] args) throws Exception {
        // 构造 40MB 随机数据（3 个 16MB 块 + 余数）
        byte[] data = new byte[40 * 1024 * 1024];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31 + 7);

        int chunk = 16 * 1024 * 1024;
        List<String> real = chunkMd5s(data, chunk);
        CNChunkedDownload.ChunkHashes good =
                new CNChunkedDownload.ChunkHashes(chunk, data.length, real);

        System.out.println("\n[1] 正确文件 → 校验通过");
        File f1 = writeTmp("offline_ok", data);
        check("verifyChunks 通过", CNOfflineImport.verifyChunks(f1, good), "40MB 3块");

        System.out.println("\n[2] 内容篡改（改中间一块）→ 拒收");
        byte[] badData = data.clone();
        badData[20 * 1024 * 1024] ^= 0x5A;   // 翻转中间块一个字节
        File f2 = writeTmp("offline_bad", badData);
        check("verifyChunks 拒收", !CNOfflineImport.verifyChunks(f2, good), "篡改中间块");

        System.out.println("\n[3] 大小不符 → 拒收");
        byte[] shortData = Arrays.copyOf(data, data.length - 100);
        File f3 = writeTmp("offline_short", shortData);
        // 清单 total 与文件长度不一致：importZip 会在 verifyChunks 前拦下
        // （这里直接测 verifyChunks 对长度不匹配的行为：循环结束 off != total）
        check("长度不符拒收", !CNOfflineImport.verifyChunks(f3, good), "短 100 字节");

        System.out.println("\n[4] 空清单 → 拒收");
        CNChunkedDownload.ChunkHashes none =
                new CNChunkedDownload.ChunkHashes(chunk, data.length, null);
        check("空清单拒收", !CNOfflineImport.verifyChunks(f1, none), "count=0");

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
