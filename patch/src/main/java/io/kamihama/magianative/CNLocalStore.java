package io.kamihama.magianative;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端本地状态存储：按命名空间存一份 JSON，落盘在应用私有目录下。
 *
 * <h3>为什么需要它</h3>
 *
 * Totentanz 服务端是<b>无状态</b>的——彻底到打完一场战斗、结算界面的星数都不会变。
 * 也就是说 {@code /magica/api/userDeck/save} 这类「写」接口，请求发出去、罐头响应
 * 回来，服务端<b>一个字节都不记</b>。
 *
 * <p>这条事实决定了本类的存在，也决定了它的形状：
 *
 * <ul>
 *   <li><b>不能靠重放请求恢复状态。</b>把 {@code userDeck/save} 的 payload 存下来、
 *       下次开机再 POST 一遍——对无状态服务端毫无意义，回来的还是那份罐头。
 *       所以存档只能在客户端，服务端那侧没有任何东西可以指望。</li>
 *   <li><b>它不是缓存。</b>缓存丢了可以回源重建；这里丢了就是玩家的编队没了，
 *       没有第二份。所以写一律走 {@link CNAtomicReplace}（建父目录 → 写候选 →
 *       fsync → rename 换入），绝不用「先删再写」那种两步之间被杀就什么都不剩的
 *       写法。</li>
 * </ul>
 *
 * <h3>存在哪里，以及为什么不在 {@code files/} 下</h3>
 *
 * 落盘目录是 {@link CNPaths#privDir()}{@code + "/cn-state"}，<b>刻意避开
 * {@code <files>/}</b>。
 *
 * <p>理由是 {@link CNWebLocalFiles}：{@code <files>/magica/} 整个子树都是「WebView
 * 拿 URL 就能读到」的供给区。虽然那边有四道闸（scheme / host / path 前缀 /
 * canonical 复核）把范围锁死在 {@code <files>/magica/} 之内，但把玩家存档放在
 * 与之相邻的位置，等于把「那四道闸有没有洞」和「存档会不会被页面读走」绑成同一个
 * 问题。放在 {@code files/} 外面，这两件事就彻底无关——**页面侧唯一的入口只剩
 * {@link CNWebStateBridge}，而那个入口是我们自己写的、逐条校验过的。**
 *
 * <h3>命名空间是安全边界，不是命名习惯</h3>
 *
 * {@code ns} 直接来自页面 JS，会被拼进文件路径。所以 {@link #isValidNamespace}
 * 用的是<b>白名单</b>（只认 {@code [a-z0-9_-]}，长度 1..32），而不是「黑名单挡
 * {@code ../}」——黑名单要穷举所有编码变体（{@code %2e%2e}、反斜杠、NUL 截断……），
 * 穷举不完。白名单只需要回答「这个字符在不在表里」，没有变体可言。
 *
 * <p>同源的判据见 {@link CNWebLocalFiles} 的类注释：那边也是拿掉 contains 换成
 * 逐项校验之后才关上路径穿越的。
 *
 * <h3>三条容量与格式的闸</h3>
 *
 * <ol>
 *   <li><b>单命名空间 {@value #MAX_BYTES} 字节封顶</b>——页面 JS 有权写这里，
 *       不封顶就等于把「页面能不能把玩家磁盘写满」交给页面自己决定；</li>
 *   <li><b>必须是合法 JSON 的对象或数组</b>——存进去的东西下次是要解析回来用的，
 *       在入口就拒掉半截 JSON，比等到读的时候再发现「文件坏了但不知道什么时候坏的」
 *       要好；</li>
 *   <li><b>命名空间总数 {@value #MAX_NAMESPACES} 封顶</b>——挡的是「拿随机 ns 刷出
 *       几万个小文件」，那是把目录本身撑爆，与单文件大小无关。</li>
 * </ol>
 *
 * <h3>异常一律不外抛</h3>
 *
 * 全部公开方法都吞异常并返回失败值（{@code null} / {@code false}）。调用方是
 * {@link CNWebStateBridge}，而它是 {@code @JavascriptInterface}——异常逃进
 * WebView 的 JS 桥只会变成一句没有上下文的 JS 错误，查不动。这里就地记日志，
 * 保留判据。
 *
 * @see CNWebStateBridge 页面侧的唯一入口
 * @see CNAtomicReplace 原子写
 */
public final class CNLocalStore {

    private static final String TAG = "MagiaCNLocalStore";

    /** 落盘目录名，挂在 {@link CNPaths#privDir()} 下（**不在 {@code files/} 里**）。 */
    private static final String DIR_NAME = "cn-state";

    /** 单个命名空间的字节上限。编队存档实测个位数 KB，512KB 是宽到不会误伤的量级。 */
    private static final int MAX_BYTES = 512 * 1024;

    /** 命名空间总数上限。 */
    private static final int MAX_NAMESPACES = 64;

    /** 命名空间长度上限。 */
    private static final int MAX_NS_LEN = 32;

    /**
     * 内存镜像的总字节上限。
     *
     * <p>单份 512KB × 64 个命名空间 = 最坏 32MB 常驻——对一个把「省内存」当命的
     * 老设备客户端来说，这个数字大到能自己把自己 OOM 掉，而且是页面 JS 单方面
     * 就能造出来的。上限撞到就<b>整份清空</b>镜像（不是 LRU）：这里要的是一个
     * 「绝不会悄悄长大」的保证，而不是命中率——清空之后下次读回一次磁盘就是了，
     * 代价是一次几 KB 的 I/O。
     */
    private static final int MAX_CACHE_BYTES = 256 * 1024;

    /**
     * 内存镜像：{@code ns -> JSON 文本}。
     *
     * <p>页面每次加载都会把整份状态读回去（覆盖响应要用），不缓存就是每次页面
     * 切换都打一轮磁盘。写是<b>写穿</b>的——先落盘成功才更新镜像，所以镜像里
     * 出现过的内容一定已经在盘上，不存在「内存说有、盘上没有」的窗口。
     */
    private static final Map<String, String> CACHE = new HashMap<String, String>();

    /** 所有读写共用一把锁：写穿要求「落盘 + 更新镜像」对读者是一个原子步。 */
    private static final Object LOCK = new Object();

    /** {@link #CACHE} 里当前存了多少字节（按 UTF-16 的 length 估，不必精确）。 */
    private static int cachedBytes;

    private CNLocalStore() {}

    // ── 命名空间校验 ────────────────────────────────────────────────

    /**
     * 命名空间白名单校验：只认小写字母、数字、下划线、连字符，长度 1..{@value #MAX_NS_LEN}。
     *
     * <p>刻意<b>不</b>接受点号——即使 {@code "."} 单独出现无害，允许它就得再去论证
     * {@code ".."}、{@code "a..b"}、{@code "..%2f"} 各自安不安全。不收这个字符，
     * 这一整类问题就不存在。
     */
    static boolean isValidNamespace(String ns) {
        if (ns == null) return false;
        int n = ns.length();
        if (n < 1 || n > MAX_NS_LEN) return false;
        for (int i = 0; i < n; i++) {
            char c = ns.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                      || (c >= '0' && c <= '9')
                      || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /** 命名空间对应的文件。仅在 {@link #isValidNamespace} 通过后调用。 */
    static File fileFor(String ns) {
        return new File(dir(), ns + ".json");
    }

    /**
     * 落盘目录。
     *
     * <p>测试可用系统属性 {@code cn.localstate.dir} 覆盖（Android 上该属性不存在，
     * 生产路径不受影响）——与 {@link Aria2EngineFailover} 的
     * {@code aria2.failover.dir} 同一套做法。
     */
    static File dir() {
        String over = System.getProperty("cn.localstate.dir");
        return over != null ? new File(over) : new File(CNPaths.privDir(), DIR_NAME);
    }

    // ── 读 ──────────────────────────────────────────────────────────

    /**
     * 读一个命名空间的 JSON 文本。
     *
     * @return JSON 文本；命名空间非法、不存在或读失败时返回 {@code null}
     */
    public static String read(String ns) {
        if (!isValidNamespace(ns)) {
            CNLog.w(TAG, "读取被拒：命名空间非法 " + describe(ns));
            return null;
        }
        synchronized (LOCK) {
            String hit = CACHE.get(ns);
            if (hit != null) return hit;
            String text = readFile(fileFor(ns));
            if (text != null) cachePut(ns, text);
            return text;
        }
    }

    /** 从盘上读一个文件，失败返回 null。不做格式校验——校验在写入口做过了。 */
    private static String readFile(File f) {
        InputStream in = null;
        try {
            if (!f.isFile()) return null;
            long len = f.length();
            // 盘上的文件理论上都过过写入口的闸，但盘可能被别的东西动过。
            // 读这侧再挡一次，免得一个被撑大的文件把内存吃了。
            if (len > MAX_BYTES) {
                CNLog.w(TAG, "读取被拒：" + f.getName() + " 超过上限（" + len + " 字节）");
                return null;
            }
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            CNLog.w(TAG, "读取失败 " + f + " : " + t);
            return null;
        } finally {
            CNIo.closeQuietly(in);
        }
    }

    // ── 写 ──────────────────────────────────────────────────────────

    /**
     * 写一个命名空间。内容必须是合法的 JSON 对象或数组。
     *
     * @return 是否写成功。失败时盘上的旧内容<b>保持原样</b>（{@link CNAtomicReplace} 的语义）
     */
    public static boolean write(String ns, String json) {
        if (!isValidNamespace(ns)) {
            CNLog.w(TAG, "写入被拒：命名空间非法 " + describe(ns));
            return false;
        }
        if (json == null) {
            CNLog.w(TAG, "写入被拒：内容为 null（清空请用 clear）ns=" + ns);
            return false;
        }
        // 先量字节数再谈别的：UTF-8 下一个汉字 3 字节，按 length() 量会漏。
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            CNLog.w(TAG, "写入被拒：ns=" + ns + " 内容 " + bytes + " 字节，超过上限 " + MAX_BYTES);
            return false;
        }
        if (!isJsonContainer(json)) {
            CNLog.w(TAG, "写入被拒：ns=" + ns + " 内容不是合法的 JSON 对象或数组");
            return false;
        }
        synchronized (LOCK) {
            // 命名空间数封顶只拦「新增」，已存在的照常可以改——不然一旦写满，
            // 连修改既有编队都做不了，那是把限流做成了砖头。
            if (!CACHE.containsKey(ns) && !fileFor(ns).isFile() && countNamespaces() >= MAX_NAMESPACES) {
                CNLog.w(TAG, "写入被拒：命名空间已达上限 " + MAX_NAMESPACES + "，ns=" + ns);
                return false;
            }
            try {
                CNAtomicReplace.writeText(fileFor(ns), json);
                cachePut(ns, json);      // 写穿：落盘成功之后才更新镜像
                return true;
            } catch (Throwable t) {
                CNLog.w(TAG, "写入失败 ns=" + ns + " : " + t);
                return false;
            }
        }
    }

    /**
     * 内容是否是合法的 JSON 对象或数组。
     *
     * <p>刻意只认这两种而不接受裸标量：存这里的东西都是结构化状态，一个裸
     * {@code "123"} 出现在这里只可能是调用方拼错了，早拒早暴露。
     */
    private static boolean isJsonContainer(String json) {
        try {
            Object v = new JSONTokener(json).nextValue();
            return v instanceof JSONObject || v instanceof JSONArray;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── 清除与枚举 ──────────────────────────────────────────────────

    /**
     * 删掉一个命名空间。
     *
     * @return 是否删成功（本来就不存在也算成功——调用方要的是「删完之后它不在」）
     */
    public static boolean clear(String ns) {
        if (!isValidNamespace(ns)) {
            CNLog.w(TAG, "清除被拒：命名空间非法 " + describe(ns));
            return false;
        }
        synchronized (LOCK) {
            cacheRemove(ns);
            File f = fileFor(ns);
            try {
                // 顺手扫掉可能残留的候选文件，否则下次 stage 会与它们撞名。
                CNAtomicReplace.sweep(f);
                if (!f.exists()) return true;
                if (f.delete()) return true;
                CNLog.w(TAG, "清除失败（delete 返回 false）ns=" + ns);
                return false;
            } catch (Throwable t) {
                CNLog.w(TAG, "清除失败 ns=" + ns + " : " + t);
                return false;
            }
        }
    }

    /** 已存在的命名空间，按名字升序。读不到目录时返回空数组。 */
    public static String[] namespaces() {
        synchronized (LOCK) {
            String[] out = listNamespaces();
            Arrays.sort(out);
            return out;
        }
    }

    /** 目录里现有的命名空间（不排序）。调用方须持有 {@link #LOCK}。 */
    private static String[] listNamespaces() {
        try {
            File[] fs = dir().listFiles();
            if (fs == null) return new String[0];
            int n = 0;
            String[] tmp = new String[fs.length];
            for (int i = 0; i < fs.length; i++) {
                String name = fs[i].getName();
                if (!fs[i].isFile() || !name.endsWith(".json")) continue;
                String ns = name.substring(0, name.length() - ".json".length());
                // 目录里出现过不合白名单的名字，说明有别的东西写过这里。
                // 不报错，但也不把它当成我们的命名空间列出去。
                if (isValidNamespace(ns)) tmp[n++] = ns;
            }
            String[] out = new String[n];
            System.arraycopy(tmp, 0, out, 0, n);
            return out;
        } catch (Throwable t) {
            CNLog.w(TAG, "枚举命名空间失败: " + t);
            return new String[0];
        }
    }

    /** 现有命名空间数量。调用方须持有 {@link #LOCK}。 */
    private static int countNamespaces() {
        return listNamespaces().length;
    }

    // ── 杂项 ────────────────────────────────────────────────────────

    /**
     * 把一个来路不明的命名空间渲染成能进日志的样子。
     *
     * <p>不直接把原串拼进日志：它来自页面 JS，可能带换行或控制字符，直接拼会把
     * 一行日志撑成假的多行，之后按行解析日志的东西全部对不上。
     */
    private static String describe(String ns) {
        if (ns == null) return "<null>";
        StringBuilder sb = new StringBuilder(ns.length() + 8);
        sb.append('"');
        for (int i = 0; i < ns.length() && i < MAX_NS_LEN + 8; i++) {
            char c = ns.charAt(i);
            if (c >= 0x20 && c < 0x7f) sb.append(c);
            else sb.append('?');
        }
        sb.append('"').append("(len=").append(ns.length()).append(')');
        return sb.toString();
    }

    /**
     * 放进镜像，并维护总字节数。撑破上限就整份清空再放。
     *
     * <p>调用方须持有 {@link #LOCK}。
     */
    private static void cachePut(String ns, String text) {
        cacheRemove(ns);
        int add = text.length();
        if (cachedBytes + add > MAX_CACHE_BYTES) {
            CACHE.clear();
            cachedBytes = 0;
            // 单份就超上限的，干脆不进镜像：放进去等于每次写都触发一次全清，
            // 镜像反而变成负担。它照样在盘上，读的时候现读。
            if (add > MAX_CACHE_BYTES) return;
        }
        CACHE.put(ns, text);
        cachedBytes += add;
    }

    /** 从镜像里摘掉，并维护总字节数。调用方须持有 {@link #LOCK}。 */
    private static void cacheRemove(String ns) {
        String old = CACHE.remove(ns);
        if (old != null) cachedBytes -= old.length();
        if (cachedBytes < 0) cachedBytes = 0;
    }

    /** 只给测试用：镜像总量上限。 */
    public static int maxCacheBytesForTest() { return MAX_CACHE_BYTES; }

    /** 只给测试用：当前镜像占了多少字节。 */
    public static int cachedBytesForTest() {
        synchronized (LOCK) { return cachedBytes; }
    }

    /** 只给测试用：丢掉内存镜像，强制下次读走磁盘。 */
    public static void invalidateCacheForTest() {
        synchronized (LOCK) {
            CACHE.clear();
            cachedBytes = 0;
        }
    }
}
