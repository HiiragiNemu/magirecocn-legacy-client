import io.kamihama.magianative.CNLocalStore;
import io.kamihama.magianative.CNWebStateBridge;

import java.io.File;

/**
 * CNLocalStore 的判据测试。
 *
 * <p>重点不在「能存能取」——那是最容易对的部分。重点在三处**安全与耐久**边界：
 * 命名空间白名单（它挡的是路径穿越）、内容与容量闸、以及「写失败时旧内容必须
 * 保持原样」。这三条错了都不会立刻显形：前两条是安全洞，第三条是玩家某天发现
 * 编队没了而日志里什么都没有。
 */
public class LocalStoreTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    /** 耐久断言的哨兵内容：任何一次「被拒」之后它都必须原封不动。 */
    private static final String SENTINEL = "{\"sentinel\":true}";

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                            "cn-localstate-test-" + System.nanoTime());
        System.setProperty("cn.localstate.dir", dir.getAbsolutePath());

        System.out.println("== 基本读写 ==");
        check("未写入时读到 null", CNLocalStore.read("deck") == null);
        check("写入一个对象", CNLocalStore.write("deck", "{\"11\":{\"deckType\":11}}"));
        check("读回同一份", "{\"11\":{\"deckType\":11}}".equals(CNLocalStore.read("deck")));

        // 绕过内存镜像再读一次：验的是「真的落盘了」，不是「缓存里还在」。
        CNLocalStore.invalidateCacheForTest();
        check("清掉内存镜像后仍读得到（确实落了盘）",
                "{\"11\":{\"deckType\":11}}".equals(CNLocalStore.read("deck")));

        check("落盘文件就在约定位置", new File(dir, "deck.json").isFile());

        check("覆盖写", CNLocalStore.write("deck", "{\"24\":{\"deckType\":24}}"));
        CNLocalStore.invalidateCacheForTest();
        check("覆盖写之后读到的是新内容",
                "{\"24\":{\"deckType\":24}}".equals(CNLocalStore.read("deck")));

        // 耐久断言用一个专用命名空间：下面几组会往 "deck" 上写各种东西，
        // 拿它当哨兵的话，测出来的是测试自己的写入顺序，不是被测代码的行为。
        check("哨兵写入", CNLocalStore.write("keep", SENTINEL));

        System.out.println("== 命名空间白名单（路径穿越）==");
        // 这一组是安全断言：ns 直接来自页面 JS，会被拼进文件路径。
        String[] bad = {
            "../evil", "..", ".", "a/b", "a\\b", "deck.json", "DECK", "a b",
            "a\u0000b", "", null, "中文",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",   // 33 字符，超长
        };
        boolean allRejected = true;
        for (int i = 0; i < bad.length; i++) {
            if (CNLocalStore.write(bad[i], "{}")) { allRejected = false;
                System.out.println("    ! 竟然接受了: " + bad[i]); }
            if (CNLocalStore.read(bad[i]) != null) { allRejected = false;
                System.out.println("    ! 竟然读得到: " + bad[i]); }
        }
        check("所有非法命名空间一律被拒", allRejected);

        // 逃逸真的没发生。判据不是「目录里正好几个文件」——那会跟着测试自己的
        // 写入顺序变，改一行断言就红。真正要钉的是：目录里**每一个**文件名都长
        // 成 <合法命名空间>.json 的样子，一个都不例外。
        File[] onDisk = dir.listFiles();
        boolean allWellFormed = onDisk != null;
        if (onDisk != null) for (int i = 0; i < onDisk.length; i++) {
            String name = onDisk[i].getName();
            if (!name.endsWith(".json") || !onDisk[i].isFile()) { allWellFormed = false;
                System.out.println("    ! 目录里出现了非预期文件: " + name); continue; }
            String ns = name.substring(0, name.length() - ".json".length());
            for (int j = 0; j < ns.length(); j++) {
                char c = ns.charAt(j);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
                if (!ok) { allWellFormed = false;
                    System.out.println("    ! 落盘文件名不合白名单: " + name); break; }
            }
        }
        check("非法命名空间没有在盘上留下任何文件", allWellFormed);
        check("上一级目录没有被写出 evil 文件",
                !new File(dir.getParentFile(), "evil.json").exists());

        String[] good = { "deck", "sheet", "a", "a-b_c", "x0123456789",
                          "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" };   // 32 字符，刚好到顶
        boolean allAccepted = true;
        for (int i = 0; i < good.length; i++) {
            if (!CNLocalStore.write(good[i], "{}")) { allAccepted = false;
                System.out.println("    ! 竟然拒绝了: " + good[i]); }
        }
        check("合法命名空间全部接受", allAccepted);

        System.out.println("== 内容闸 ==");
        check("拒绝非 JSON", !CNLocalStore.write("deck", "不是 json"));
        check("拒绝半截 JSON", !CNLocalStore.write("deck", "{\"a\":"));
        check("拒绝裸标量", !CNLocalStore.write("deck", "123"));
        check("拒绝裸字符串", !CNLocalStore.write("deck", "\"hello\""));
        check("拒绝 null 内容", !CNLocalStore.write("deck", null));
        check("接受 JSON 数组", CNLocalStore.write("arr", "[1,2,3]"));

        // 被拒之后盘上的旧内容必须原封不动——这是 CNAtomicReplace 的语义，
        // 也是本类「不是缓存」那条判据的落点。
        check("拒绝非 JSON 时不动哨兵", !CNLocalStore.write("keep", "不是 json"));
        CNLocalStore.invalidateCacheForTest();
        check("写被拒之后旧内容保持原样", SENTINEL.equals(CNLocalStore.read("keep")));

        System.out.println("== 容量闸 ==");
        StringBuilder big = new StringBuilder("{\"k\":\"");
        for (int i = 0; i < 520 * 1024; i++) big.append('x');
        big.append("\"}");
        check("拒绝超过 512KB 的内容", !CNLocalStore.write("keep", big.toString()));
        CNLocalStore.invalidateCacheForTest();
        check("超限被拒之后旧内容仍保持原样", SENTINEL.equals(CNLocalStore.read("keep")));

        // 汉字在 UTF-8 下 3 字节。按 length() 量会以为没超，按字节量才对。
        StringBuilder cjk = new StringBuilder("{\"k\":\"");
        for (int i = 0; i < 200 * 1024; i++) cjk.append('中');   // 200K 字符 = 600KB
        cjk.append("\"}");
        check("按字节而不是按字符数判上限（汉字）", !CNLocalStore.write("cjk", cjk.toString()));

        System.out.println("== 枚举与清除 ==");
        String[] all = CNLocalStore.namespaces();
        boolean sorted = true;
        for (int i = 1; i < all.length; i++) if (all[i - 1].compareTo(all[i]) > 0) sorted = false;
        check("namespaces 升序", sorted);
        boolean hasDeck = false;
        for (int i = 0; i < all.length; i++) if ("deck".equals(all[i])) hasDeck = true;
        check("namespaces 列出已写入的命名空间", hasDeck);

        check("清除返回成功", CNLocalStore.clear("deck"));
        CNLocalStore.invalidateCacheForTest();
        check("清除后读到 null", CNLocalStore.read("deck") == null);
        check("清除后文件确实没了", !new File(dir, "deck.json").exists());
        check("清除一个本来就不存在的也算成功", CNLocalStore.clear("deck"));
        check("清除非法命名空间被拒", !CNLocalStore.clear("../evil"));

        System.out.println("== JS 桥 ==");
        CNWebStateBridge b = CNWebStateBridge.instance();
        CNWebStateBridge.resetThrottleForTest();
        CNLocalStore.clear("bridge");
        check("桥写入", b.set("bridge", "{\"a\":1}"));
        check("桥读回", "{\"a\":1}".equals(b.get("bridge")));
        check("桥拒绝非法命名空间", !b.set("../evil", "{}"));
        check("桥读非法命名空间返回 null", b.get("../evil") == null);
        check("桥 list 是 JSON 数组", b.list().startsWith("["));
        check("桥删除", b.remove("bridge"));
        check("桥删除后读到 null", b.get("bridge") == null);

        // 限速：挡的是页面在死循环里狂写。写坏 flash 是不可逆的，
        // 所以这条闸即使「正常玩家永远撞不到」也必须在。
        CNWebStateBridge.resetThrottleForTest();
        int limit = CNWebStateBridge.maxWritesPerWindowForTest();
        int accepted = 0;
        for (int i = 0; i < limit * 2; i++) {
            if (b.set("throttle", "{\"i\":" + i + "}")) accepted++;
        }
        check("写限速在阈值处生效（接受 " + accepted + " / 上限 " + limit + "）", accepted == limit);
        check("限速期间读不受影响", b.get("throttle") != null);
        CNWebStateBridge.resetThrottleForTest();
        check("窗口重置后又能写", b.set("throttle", "{\"i\":-1}"));

        // ⚠ 这一组必须排在最后：它会把命名空间写到上限，之后任何**新建**都会
        // 被拒。排在前面的话，后面每一组都在跟这条闸较劲，测出来的全是假失败。
        System.out.println("== 命名空间总数上限（必须最后跑）==");
        // 已有若干个，补到 64 之后再新建应当被拒；改既有的仍要放行。
        for (int i = 0; i < 80; i++) CNLocalStore.write("ns" + i, "{}");
        int n = CNLocalStore.namespaces().length;
        check("命名空间总数被压在 64 以内（实际 " + n + "）", n <= 64);
        String survivor = CNLocalStore.namespaces()[0];
        check("写满之后仍能修改既有命名空间（不是砖头）",
                CNLocalStore.write(survivor, "{\"changed\":true}"));


        System.out.println();
        System.out.println("LocalStoreTest: " + pass + " 通过, " + fail + " 失败");
        deleteTree(dir);
        if (fail > 0) System.exit(1);
    }

    private static void deleteTree(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (int i = 0; i < kids.length; i++) deleteTree(kids[i]);
        f.delete();
    }
}
