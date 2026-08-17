package io.kamihama.magianative;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 内置 libarchive 的 JNI 封装：进程内解压 zip，替代 exec bsdtar 二进制。
 *
 * <h3>为什么从 exec 二进制改成 JNI</h3>
 *
 * 资源包含「冗余 ZIP64」（普通 EOCD 自洽却多挂 zip64 EOCD），老设备
 * {@code java.util.zip.ZipFile} 可能打不开；而「从 filesDir exec 二进制」在
 * Android 10+ 又撞 SELinux W^X 闸（{@code app_data_file} 无 execute 权限，
 * execve 返回 EACCES），16KB 页设备还要求 ELF 段 16KB 对齐。
 *
 * <p>本方案把 libarchive 编成标准 {@code native library}（{@code libarchive.so}，
 * 放 {@code lib/<abi>/}），经 {@link System#loadLibrary} 由 linker 加载——落点在
 * 只读的 nativeLibraryDir，完全绕开 W^X / 16KB 页。解压逻辑跑在进程内，
 * 通过 JNI 调 {@code archive_read_*} 系列 API，进度/取消由 Java 回调。
 *
 * <h3>公共接口（{@link CNArchiveInstallTx} 依赖）</h3>
 *
 * {@link #isAvailable()} / {@link #list(File)} / {@link #isValid(File)} /
 * {@link #extract(File, File, EntryTable, ExtractProgress)} 签名与原先一致，
 * 只是内部实现从「exec bsdtar + 解析输出」换成「JNI 调用」。
 */
public final class CNZipTool {
    private static final String TAG = "CNZipTool";

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initDone;
    private static volatile boolean available;
    /** 解压忙门：同一时刻只允许一个解压。 */
    private static final AtomicBoolean busy = new AtomicBoolean(false);

    private CNZipTool() {}

    // ── JNI 原生方法（libarchive.so）────────────────────────────────────
    /** 列出 zip 条目未压缩大小表。null = 结构不可解析。 */
    private static native long[] cnList(String zipPath);
    /** 结构校验：能读出一个条目即可解析。 */
    private static native boolean cnIsValid(String zipPath);
    /** 解压到 destPath，逐条目回调 progress.onProgress。返回 false = 已取消。 */
    private static native boolean cnExtract(String zipPath, String destPath,
                                            JniProgress progress);

    /** JNI 进度回调：返回 false 表示取消解压。 */
    public interface JniProgress {
        boolean onProgress(int entriesDone, long bytesDone);
    }

    /**
     * libarchive 是否可用。二进制由 {@link System#loadLibrary} 加载
     * （libarchive.so 在 lib/ 下），加载失败（如 ABI 不匹配、OEM 移除）返回
     * false，调用方回退 ZipFile 路径。与 exec 版的 probeExec 不同：JNI 加载
     * 天然没有「exec 被 SELinux/16KB 页拦截」的问题，只需确认库能加载。
     */
    public static boolean isAvailable() {
        if (initDone) return available;
        synchronized (INIT_LOCK) {
            if (initDone) return available;
            boolean ok = false;
            try {
                System.loadLibrary("archive");
                ok = true;
            } catch (Throwable t) {
                CNLog.w(TAG, "libarchive 加载失败（回退 ZipFile）: " + t);
                ok = false;
            }
            available = ok;
            initDone = true;
            return available;
        }
    }

    // -----------------------------------------------------------------
    // 列表（结构校验 + 条目大小表）
    // -----------------------------------------------------------------

    /**
     * {@code cnList} 的解析结果：条目数、总未压缩大小、按解压顺序逐个条目的
     * 未压缩大小。JNI 从 {@code archive_entry_size()} 拿精确值。
     */
    public static final class EntryTable {
        public final int count;
        public final long totalBytes;
        final long[] sizes;
        /** JNI 路径始终精确，恒为 true（原 exec 版解析失败才为 false）。 */
        final boolean sizesReliable;

        EntryTable(long[] sizes, boolean sizesReliable) {
            this.count = sizes.length;
            // 求和必须防溢出：archive_entry_size() 读的是 ZIP 中央目录里的
            // **声明**尺寸，攻击者可以把条目声明成接近 Long.MAX_VALUE 的值让
            // sum 溢出。溢出回绕成**负数**时，下游「total <= 0 → 按 zip 体积
            // 3x 保守预检」的兜底恰好能接住；真正危险的是回绕成**小的正值**——
            // 既过比例闸又过 >0 检查，被直接当成磁盘需求量以极小值放行，炸弹
            // 整份通过。所以这里显式检测溢出并把 totalBytes 置 -1 交给保守
            // 兜底，而不是靠回绕后的符号碰运气。对未知尺寸（-1）条目不参与
            // 求和并把大小表降级为不可靠，让调用方走保守兜底。
            long sum = 0L;
            boolean reliable = sizesReliable;
            boolean overflow = false;
            for (long s : sizes) {
                if (s < 0L) { reliable = false; continue; }
                if (s > Long.MAX_VALUE - sum) { overflow = true; reliable = false; break; }
                sum += s;
            }
            // 溢出时 totalBytes 置 -1：CNArchiveInstallTx 对 total <= 0 有
            // 「按 zip 体积 3x」的保守预检兜底，不会拿溢出值放行。
            this.totalBytes = overflow ? -1L : sum;
            this.sizes = sizes;
            this.sizesReliable = reliable;
        }
    }

    /**
     * 列出 zip 的条目大小表。JNI 用 {@code archive_read_*} 走中央目录，不扫
     * 数据区，大文件也是秒出。返回 null = 结构不可解析。
     */
    public static EntryTable list(File zip) {
        if (zip == null || !zip.isFile() || !isAvailable()) return null;
        try {
            long[] sizes = cnList(zip.getAbsolutePath());
            if (sizes == null || sizes.length == 0) return null;
            return new EntryTable(sizes, true);
        } catch (Throwable t) {
            CNLog.w(TAG, "JNI 列表失败: " + t);
            return null;
        }
    }

    /** 校验 zip 结构是否合法：JNI 能读出一个条目即视为可解析。 */
    public static boolean isValid(File zip) {
        if (zip == null || !zip.isFile() || !isAvailable()) return false;
        try {
            return cnIsValid(zip.getAbsolutePath());
        } catch (Throwable t) {
            CNLog.w(TAG, "JNI 结构校验失败: " + t);
            return false;
        }
    }

    // -----------------------------------------------------------------
    // 解压（JNI + 进度回调）
    // -----------------------------------------------------------------

    /** 解压进度。{@code doneBytes} 由 JNI 按实际解压字节累加，精确。 */
    public interface ExtractProgress {
        void onProgress(int doneEntries, long doneBytes);
    }

    /**
     * 解压取消判据（F-B-04）。原先 JNI 进度回调恒返 true、注释把取消推给
     * 「外层」处理，而外层（{@code CNArchiveInstallTx.extractWithBsdtar}）
     * 实际上没有处理——主路径整包解压期间「重下」请求被静默吞掉，解压完
     * 上层照常写完成标记。现在回调每次被 native 调用时轮询本接口，返回
     * false 即让 cnExtract 中断（native 早已支持，取消粒度为条目边界，
     * 与 ZipFile 回退路径逐条目查 cancel 一致）。
     */
    public interface ExtractCancel { boolean isCancelled(); }

    /**
     * 解压 zip 到目标目录。JNI 在进程内逐条目解压，每解完一个条目回调
     * {@code cb.onProgress(entriesDone, bytesDone)}。调用方需自行预检磁盘空间。
     *
     * <p>与 exec 版不同：JNI 是同步阻塞调用，会占用调用线程直到解压完成。
     * {@link CNArchiveInstallTx} 在后台线程调它，不阻塞 UI。
     *
     * @param table {@link #list} 的结果（可为 null，则进度只有条目数）
     * @return 是否成功（false = 解压失败或已取消）
     */
    public static boolean extract(File zip, File dest, EntryTable table,
                                  ExtractProgress cb) {
        return extract(zip, dest, table, cb, null);
    }

    /**
     * 同 {@link #extract(File, File, EntryTable, ExtractProgress)}，额外接
     * 取消判据（F-B-04）：{@code cancel} 报取消时 JNI 回调返回 false，native
     * 在条目边界中断，本方法返回 false——由调用方（再查一次 cancel）区分
     * 「因取消而 false」与「解压失败而 false」。
     */
    public static boolean extract(File zip, File dest, EntryTable table,
                                  ExtractProgress cb, final ExtractCancel cancel) {
        if (zip == null || dest == null || !zip.isFile() || !dest.isDirectory()) {
            return false;
        }
        if (!isAvailable()) return false;
        if (!busy.compareAndSet(false, true)) {
            CNLog.w(TAG, "已有解压在进行，拒绝并发: " + zip.getName());
            return false;
        }
        try {
            // ── 补丁 19 · 闸一（写出前）：Java 层 Zip Slip 预扫 ────────────
            // cnExtract 跑在预编译 libarchive.so 里，而它的源码
            // （magia-native/src/archive_jni.cpp）**不在 CI 编译目标内**——
            // 源码侧的 Zip Slip / 吞错修复（补丁 01/02）在 CI 重建并替换
            // shipped 二进制之前到不了设备。设备上的即时防线由这里保证：
            // 恶意包先在 Java 层被拒，根本不进 native。
            // expect==null 表示 ZipFile 打不开（老设备 + 冗余 ZIP64，正是
            // 当年上 JNI 的场景）——fail-open 放行并留痕，不能反过来禁用；
            // 此时闸二一并跳过（没有条目表可核对），残余风险见方法注释。
            Map<String, Long> expect;
            try {
                expect = scanJdkEntries(zip);
            } catch (SecurityException se) {
                CNLog.e(TAG, "拒绝解压：ZIP 含非法条目名（Zip Slip 防护）: "
                        + zip.getName() + " : " + se.getMessage());
                return false;
            } catch (Throwable t) {
                CNLog.w(TAG, "JDK 条目预扫不可用，fail-open 交给 native"
                        + "（本机暂无 Zip Slip 防线）: " + zip.getName() + " : " + t);
                expect = null;
            }
            final long zipLen = zip.length();
            final ExtractProgress p = cb;
            // 写时炸弹闸（补丁 03）：native 闸二不可达（libarchive 是预编译二进制、
            // CI 不重建）期间的设备侧防线，判据镜像 archive_jni.cpp 的「累计写出
            // > zip 体积×200 且已过 256MB 即中止」。预检读的中央目录**声明**尺寸
            // 可以撒谎，真实写出字节数才是硬证据。返回 false 让 cnExtract 停手，
            // 调用方（extractWithBsdtar）把 !ok 按「包损坏」拒绝。
            // 恒建 JniProgress：p==null 时也要跑炸弹闸，回调开销由 native 节流。
            JniProgress jp = new JniProgress() {
                @Override public boolean onProgress(int doneEntries, long bytesDone) {
                    if (zipLen > 0L && bytesDone > 256L * 1024 * 1024
                            && bytesDone > zipLen * 200L) {
                        CNLog.w(TAG, "解压膨胀比超限中止（zip 炸弹?）file="
                                + zip.getName() + " written=" + bytesDone
                                + " zip=" + zipLen);
                        return false;
                    }
                    if (p != null) p.onProgress(doneEntries, bytesDone);
                    // false = 请 native 中断解压（F-B-04）；炸弹闸命中返回 false 同上。
                    return cancel == null || !cancel.isCancelled();
                }
            };
            boolean ok = cnExtract(zip.getAbsolutePath(), dest.getAbsolutePath(), jp);
            // ── 补丁 19 · 闸二（写出后）：应产出文件的尺寸核对 ──────────
            // shipped 旧二进制会把读写错误（ENOSPC 截断、CRC 失败、fopen 失败）
            // 静默吞掉照报成功；只核对 isFile 的存在性抓不住「存在但截断」的
            // 文件（isFile() 照样 true、照写完成标记）。核对**尺寸**把截断也
            // 抓出来——缺文件或尺寸不符即按解压失败上报，由上层重下/回退，
            // 绝不写完成标记。数万条目 stat 为秒级，相对解压本身可忽略。
            // 炸弹闸命中的路径 ok==false，不会走到这里。
            if (ok && expect != null) {
                for (Map.Entry<String, Long> en : expect.entrySet()) {
                    File f = new File(dest, en.getKey());
                    long want = en.getValue().longValue();
                    if (!f.isFile() || (want >= 0L && f.length() != want)) {
                        CNLog.e(TAG, "解压后文件缺失或尺寸不符（native 吞错/截断?），"
                                + "按失败上报: " + en.getKey() + " want=" + want
                                + " @ " + zip.getName());
                        return false;
                    }
                }
            }
            return ok;
        } catch (Throwable t) {
            CNLog.w(TAG, "JNI 解压失败: " + t);
            return false;
        } finally {
            busy.set(false);
        }
    }

    // -----------------------------------------------------------------
    // Java 层兜底闸（补丁 19）
    // -----------------------------------------------------------------

    /**
     * JDK 预扫：返回 zip 应产出的唯一常规文件名 → 声明尺寸 的映射（相对路径，
     * 目录条目与重名条目已归并——native 对同名条目后写覆盖先写，产出数按唯一
     * 名计）。
     *
     * <p>不做类型区分：ZIP 中央目录不存文件类型，普通文件/符号链接/其它特殊
     * 条目在 {@link ZipEntry} 看来一样。目录条目靠 {@code name.endsWith("/")}
     * 识别——「无结尾斜杠的目录条目」（畸形包）会被当成文件加入 expect，native
     * 按 AE_IFDIR 建目录后闸二 isFile 报 false。自产包无此形态，畸形包误报是
     * fail-closed 方向，可接受。
     *
     * @return 文件名 → 声明尺寸（未知尺寸为 -1）；仅 ZipFile 能正常枚举时返回
     * @throws SecurityException 发现 Zip Slip 条目（绝对路径 / 逐段 ".."
     *         / 反斜杠 / 空名），调用方必须拒解压
     * @throws IOException ZipFile 打不开（老设备冗余 ZIP64 等），调用方
     *         fail-open 放行 native 路径
     */
    private static Map<String, Long> scanJdkEntries(File zip) throws IOException {
        ZipFile zf = new ZipFile(zip);
        try {
            Map<String, Long> out = new HashMap<String, Long>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (!isSafeEntryNameJdk(name)) {
                    throw new SecurityException("非法条目名: " + abbrev(name));
                }
                if (e.isDirectory() || name.endsWith("/")) continue;
                // 非 UTF-8 条目名被 ZipFile 解码成 U+FFFD 替换字符，与 native 写盘的
                // 原始字节名对不上——闸二会误报「文件缺失」。跳过：防误报优先，
                // 这类条目本就不该出现在自产包里。
                if (name.indexOf('�') >= 0) continue;
                out.put(name, e.getSize());
            }
            return out;
        } finally {
            try { zf.close(); } catch (Throwable ignore) {}
        }
    }

    /**
     * 与 native is_safe_entry_name（archive_jni.cpp，补丁 01）同语的条目名
     * 校验：拒绝绝对路径（'/' 开头）、任何按 '/' 切分后恰好等于 ".." 的段、
     * 反斜杠、空名。只做逐段相等判断——"a..b"、"./x" 这类正常名字不受影响
     * （"." 段在 Linux 下原地不动，无穿越能力，放行）。
     */
    private static boolean isSafeEntryNameJdk(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.charAt(0) == '/') return false;
        int seg = 0;
        for (int i = 0; i <= name.length(); i++) {
            char c = i < name.length() ? name.charAt(i) : '/';
            if (c == '\\') return false;
            if (c == '/') {
                if (i - seg == 2 && name.charAt(seg) == '.' && name.charAt(seg + 1) == '.') {
                    return false;
                }
                seg = i + 1;
            }
        }
        return true;
    }

    /** 日志防注入：截断过长/含控制字符的条目名。 */
    private static String abbrev(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(Math.min(s.length(), 120));
        for (int i = 0; i < s.length() && b.length() < 120; i++) {
            char c = s.charAt(i);
            b.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        if (s.length() > 120) b.append("…");
        return b.toString();
    }
}
