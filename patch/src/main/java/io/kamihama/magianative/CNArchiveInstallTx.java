package io.kamihama.magianative;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** 崩溃可恢复的基础资源解压事务（按 checkpoint 断点续解）。 */
public final class CNArchiveInstallTx {
    private static final String TAG = "CNArchiveInstallTx";
    private static final String SCHEMA = "CNV-EXTRACT-1";
    private static final int CHECKPOINT_ENTRIES = 32;
    private static final long CHECKPOINT_BYTES = 64L * 1024L * 1024L;
    private static final long CHECKPOINT_NS = 1_000_000_000L;

    public interface Cancel { boolean isCancelled(); }
    public interface Progress {
        void onProgress(int doneEntries, int totalEntries, long doneBytes, long totalBytes);
    }

    public static final class CancelledException extends IOException {
        private static final long serialVersionUID = 1L;
        CancelledException(String message) { super(message); }
    }

    public static final class InstallIOException extends IOException {
        private static final long serialVersionUID = 1L;
        InstallIOException(String message, Throwable cause) { super(message, cause); }
        InstallIOException(String message) { super(message); }
    }

    private static final class State {
        String fingerprint;
        int next;
    }

    private CNArchiveInstallTx() {}

    /** 本轮解压实际写出去的字节数。回归测试用它区分「写之前拦下」与「写完再拒」。 */
    public static long writtenThisRunForTest() { return writtenThisRun; }

    static File stateFile(File stateRoot, String archiveName) {
        return new File(stateRoot, archiveName + ".extract.tx");
    }

    static void clearState(File stateFile) {
        deleteQuietly(stateFile);
        // F-073：候选名不再固定为 <目标>.tmp，清残留一律走 sweep。
        CNAtomicReplace.sweep(stateFile);
    }

    public static void extract(File archive, File root, File stateFile,
                        Cancel cancel, Progress progress) throws IOException {
        if (archive == null || !archive.isFile()) {
            throw new InstallIOException("Archive is missing: " + archive);
        }
        if (root == null || (!root.isDirectory() && !root.mkdirs() && !root.isDirectory())) {
            throw new InstallIOException("Cannot create extraction root: " + root);
        }

        // 用内置 libarchive（libarchive JNI）校验结构并解压，替代 java.util.zip.ZipFile：资源包含
        // 「冗余 ZIP64」，老设备 ZipFile 可能打不开（见 CNZipTool 的说明）。
        // 这里不再逐条目续传——libarchive 整包解压，中断则整包重解（放弃 stateFile
        // 的逐条目断点，换来对 ZIP64 的完整兼容）。
        // isAvailable 含 --version 真探测：exec 被 SELinux / 16KB 页拦截的
        // 设备会落到下面的 ZipFile 回退，而不是把「二进制起不来」误报成
        // 「zip 结构非法」。
        if (CNZipTool.isAvailable()) {
            extractWithBsdtar(archive, root, cancel, progress);
            return;
        }

        // libarchive 不可用（异常环境）时回退旧路径。仍用 ZipFile——虽然老设备可能
        // 打不开，但总比完全不解压好（结构校验失败总比误拒好）。
        extractWithZipFile(archive, root, stateFile, cancel, progress);
    }

    /** 用内置 libarchive（libarchive JNI）整包解压（主路径）。 */
    private static void extractWithBsdtar(File archive, File root,
                                          Cancel cancel, Progress progress)
            throws IOException {
        if (cancel != null && cancel.isCancelled()) {
            throw new CancelledException("Extraction cancelled before start");
        }
        // JNI 一次列表同时完成结构校验和「按解压顺序的条目大小表」：libarchive 的
        // zip 读取器走中央目录，列表不扫数据区，1.4GB 的包也是秒出。
        CNZipTool.EntryTable table = CNZipTool.list(archive);
        if (table == null || table.count <= 0) {
            throw corrupt("Cannot open ZIP central directory: " + archive, null);
        }
        // 磁盘预检用列表给出的**精确**总未压缩大小，替代原先 zip 体积 3x 的
        // 保守估计——03 的解压预留从 4.3GB 降到真实的 2.9GB，存储紧张的设备
        // 不再被误拒。zip-bomb 比例闸保留：自产包膨胀比 ~2x，声明总量超过
        // zip 体积 50 倍只可能是包坏了（原 ZipFile 路径也有同样的闸，见
        // EXTRACT_MAX_RATIO 注释）。
        long total = table.totalBytes;
        if (table.sizesReliable && total > Math.max(archive.length(), 1L) * 50L) {
            throw corrupt("Declared uncompressed size suspicious: " + archive
                    + " zip=" + archive.length() + " declared=" + total, null);
        }
        if (!table.sizesReliable || total <= 0L) {
            total = archive.length() * 3L;   // 大小表不全时的保守兜底
        }
        CNDiskSpace.require(root, total, archive.getName() + " 解压");
        final CNZipTool.EntryTable t = table;
        final long totalF = total;
        final Progress progressF = progress;   // 匿名类捕获用（显式 final，兼容老 source 级别）
        if (progress != null) progress.onProgress(0, t.count, 0L, totalF);
        CNLog.i(TAG, "extract-start(file) file=" + archive.getName()
                + " via=libarchive entries=" + t.count);
        // 进度节流：03 有 11408 个条目，逐条回调会刷爆 UI 线程——
        // 每 32 条目或 200ms 才上报一次，最后一次由下方满格回调补。
        final int[] lastEntries = {0};
        final long[] lastNs = {0L};
        CNZipTool.ExtractProgress sink = progress == null ? null
                : new CNZipTool.ExtractProgress() {
            @Override public void onProgress(int doneEntries, long doneBytes) {
                long now = System.nanoTime();
                if (doneEntries < t.count
                        && doneEntries - lastEntries[0] < 32
                        && now - lastNs[0] < 200_000_000L) return;
                lastEntries[0] = doneEntries;
                lastNs[0] = now;
                long bytes = t.sizesReliable ? doneBytes
                        : totalF * Math.min(doneEntries, t.count) / t.count;
                progressF.onProgress(Math.min(doneEntries, t.count), t.count,
                        Math.min(bytes, totalF), totalF);
            }
        };
        // F-B-04：主路径原先全程不响应取消——JNI 进度回调恒返 true，2.79GB
        // 的 03 解压几十秒到几分钟期间，玩家点的「重下」被静默吞掉，解压完
        // 上层照常 writeMarker，FORCE_REDOWNLOAD 语义被破坏（回退路径本来
        // 会在条目间抛 CancelledException）。现在把 cancel 一路接进 JNI 回调：
        // native 每解完一个条目调一次，返回 false 即中断（cnExtract 早已支持）。
        final Cancel cancelF = cancel;
        boolean ok = CNZipTool.extract(archive, root, table, sink,
                cancel == null ? null : new CNZipTool.ExtractCancel() {
                    @Override public boolean isCancelled() {
                        return cancelF.isCancelled();
                    }
                });
        if (!ok) {
            // false 有两种来源：玩家取消（回调中断）与解压本身失败，必须区分。
            // 取消要走 CancelledException——上层（CNDownloaderFix 主引擎/aria2）
            // 据此转 ResetRequired 清断点重下，**不会**写 marker；若错当成普通
            // 失败抛 corrupt，取消语义就又被吞回去了。
            if (cancel != null && cancel.isCancelled()) {
                throw new CancelledException("Extraction cancelled during libarchive extract");
            }
            throw corrupt("libarchive 解压失败: " + archive.getName(), null);
        }
        if (progress != null) progress.onProgress(t.count, t.count, totalF, totalF);
        CNLog.i(TAG, "extract-complete file=" + archive.getName() + " via=libarchive");
    }

    /** 旧路径：ZipFile 逐条目解压（libarchive 不可用时的回退）。 */
    private static void extractWithZipFile(File archive, File root, File stateFile,
                                           Cancel cancel, Progress progress)
            throws IOException {
        File stateParent = stateFile == null ? null : stateFile.getParentFile();
        if (stateParent != null && !stateParent.isDirectory()
                && !stateParent.mkdirs() && !stateParent.isDirectory()) {
            throw new InstallIOException("Cannot create extraction state directory: " + stateParent);
        }

        ZipFile zip;
        try {
            zip = new ZipFile(archive);
        } catch (IOException e) {
            throw corrupt("Cannot open ZIP central directory: " + archive, e);
        }

        try {
            ArrayList<ZipEntry> entries = new ArrayList<ZipEntry>();
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            // F-081：中央目录声明的 getSize() 是**外部输入**。裸累加一旦回绕成
            // 负数或小正数，下面那道膨胀比闸（totalBytes / archive.length()）和
            // 磁盘预检就都会被绕过——一个损坏/恶意的包反而比正常包更容易过关。
            // 溢出直接整包拒收：解压还没开始，失败方向是安全的。
            long totalBytes = 0L;
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                entries.add(entry);
                if (!entry.isDirectory() && entry.getSize() > 0) {
                    // 不用 Math.addExact：那是 API 24 才有的，minSdk 21 上会
                    // NoSuchMethodError（CLAUDE.md 技术约束）。先验余量比较等价。
                    if (entry.getSize() > Long.MAX_VALUE - totalBytes) {
                        throw new ZipException("中央目录声明的解压后总量溢出 64 位: "
                                + entry.getName());
                    }
                    totalBytes += entry.getSize();
                }
            }
            if (entries.isEmpty()) throw new ZipException("Archive contains no entries: " + archive);
            // 第一道：按中央目录**声明**的未压缩总量看比例，一个字节都还没写就能拒。
            if (totalBytes >= EXTRACT_MIN_BYTES_BEFORE_RATIO
                    && archive.length() > 0
                    && totalBytes / archive.length() > EXTRACT_MAX_RATIO) {
                throw new ZipException("Archive expansion ratio is too high: "
                        + totalBytes + " / " + archive.length());
            }

            String fingerprint = fingerprint(archive, entries);
            State prior = readState(stateFile);
            int next = 0;
            String rootCanonical = root.getCanonicalPath();
            String prefix = rootCanonical + File.separator;
            if (prior != null && fingerprint.equals(prior.fingerprint)
                    && prior.next >= 0 && prior.next <= entries.size()) {
                next = prior.next;
                // F-058：state 里的 next 只证明「上次推进到了这里」，不证明前 next
                // 个输出现在还在/尺寸还对——掉电可能「state 已持久化而目录项丢了」。
                // 续解前从 0 复核到 next-1：缺文件或尺寸不符就回退到那个条目重解
                // （CRC 复核要整读，存在性 + 尺寸已能拦住目录项丢失/半截这类重排）。
                for (int i = 0; i < next; i++) {
                    ZipEntry e = entries.get(i);
                    if (e.isDirectory()) continue;
                    File out = safeTarget(root, rootCanonical, prefix, e.getName());
                    long expected = e.getSize();
                    if (!out.isFile() || out.length() != expected) {
                        CNLog.w(TAG, "extract-resume-rollback file=" + archive.getName()
                                + " entry=" + i + " (" + e.getName() + ") 缺失或尺寸不符，从此重解");
                        next = i;
                        break;
                    }
                }
                CNLog.i(TAG, "extract-resume-accept file=" + archive.getName()
                        + " entries=" + next + "/" + entries.size());
            } else {
                clearState(stateFile);
            }

            // F-081：doneBytes 同样来自声明尺寸。它只用于「还差多少」，所以这里
            // 用饱和累加而不是拒收——已解出来的部分溢出并不能说明包有问题，而
            // totalBytes 那一关已经把真正离谱的包挡住了。
            long doneBytes = 0L;
            for (int i = 0; i < next; i++) {
                ZipEntry e = entries.get(i);
                if (!e.isDirectory() && e.getSize() > 0) {
                    doneBytes = CNDiskSpace.saturatedAdd(doneBytes, e.getSize());
                }
            }
            // 饱和相减：doneBytes > totalBytes（声明前后不一致）时旧写法会得到负数，
            // 而 require() 对 <= 0 按设计放行——预检静默失效。
            CNDiskSpace.require(root, CNDiskSpace.saturatedSub(totalBytes, doneBytes),
                    archive.getName() + " 解压");
            if (progress != null) progress.onProgress(next, entries.size(), doneBytes, totalBytes);
            CNLog.i(TAG, "extract-start file=" + archive.getName() + " entries="
                    + entries.size() + " resume=" + next);

            writtenThisRun = 0L;
            int sinceCheckpoint = 0;
            long bytesSinceCheckpoint = 0L;
            long lastCheckpointNs = System.nanoTime();
            // F-058：本 checkpoint 窗口里写过文件的输出目录；推进 state 前先 sync
            // 目录项——否则 rename 已落地、state 也写了，但目录项还只在页缓存里，
            // 掉电重排会「state 说 done、文件没了」。每个目录每窗口一次 fsync，
            // 摊到整包解压是可控成本。
            java.util.LinkedHashSet<File> dirtyDirs = new java.util.LinkedHashSet<File>();

            for (int i = next; i < entries.size(); i++) {
                if (cancel != null && cancel.isCancelled()) {
                    saveState(stateFile, fingerprint, i);
                    throw new CancelledException("Extraction restart requested at entry " + i);
                }
                ZipEntry entry = entries.get(i);
                File out = safeTarget(root, rootCanonical, prefix, entry.getName());
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs() && !out.isDirectory()) {
                        throw new InstallIOException("Cannot create directory " + out);
                    }
                } else {
                    writeEntry(zip, entry, out, cancel, archive.length());
                    File parent = out.getParentFile();
                    if (parent != null) dirtyDirs.add(parent);
                    if (entry.getSize() > 0) {
                        doneBytes += entry.getSize();
                        bytesSinceCheckpoint += entry.getSize();
                    }
                }

                sinceCheckpoint++;
                long now = System.nanoTime();
                boolean checkpoint = i + 1 == entries.size()
                        || sinceCheckpoint >= CHECKPOINT_ENTRIES
                        || bytesSinceCheckpoint >= CHECKPOINT_BYTES
                        || now - lastCheckpointNs >= CHECKPOINT_NS;
                if (checkpoint) {
                    for (File d : dirtyDirs) syncDir(d);
                    dirtyDirs.clear();
                    saveState(stateFile, fingerprint, i + 1);
                    sinceCheckpoint = 0;
                    bytesSinceCheckpoint = 0L;
                    lastCheckpointNs = now;
                    if (progress != null) {
                        progress.onProgress(i + 1, entries.size(), doneBytes, totalBytes);
                    }
                    CNLog.i(TAG, "extract-progress file=" + archive.getName() + " entries="
                            + (i + 1) + "/" + entries.size());
                }
            }

            clearState(stateFile);
            if (progress != null) {
                progress.onProgress(entries.size(), entries.size(), totalBytes, totalBytes);
            }
            CNLog.i(TAG, "extract-complete file=" + archive.getName()
                    + " entries=" + entries.size());
        } finally {
            try { zip.close(); } catch (Throwable ignore) {}
        }
    }

    private static File safeTarget(File root, String rootCanonical, String prefix, String name)
            throws IOException {
        File out = new File(root, name);
        String canonical = out.getCanonicalPath();
        if (!canonical.equals(rootCanonical) && !canonical.startsWith(prefix)) {
            throw new ZipException("ZIP entry escapes extraction root: " + name);
        }
        return out;
    }

    /**
     * 解压膨胀比上限与「小包不看比例」的门槛。数值与判据都来自原
     * {@code CNDownloaderFix.extractChecked}——那份实现已并入本类，这两个常量
     * 随之搬来，不是新立的。
     *
     * <p>正常包实测的膨胀比（2026-08-13，读线上各包的中央目录累加得出）：
     *
     * <pre>
     *   cn_base_03.zip     1.32 → 2.79 GiB   2.11x   ← 15 个包里最高
     *   cn_voice_01.zip    1.85 → 2.15 GiB   1.16x
     *   cn_base_02.zip     0.89 → 0.94 GiB   1.06x
     *   movie.zip          1.33 → 1.35 GiB   1.02x
     * </pre>
     *
     * <p>别按「游戏资源膨胀比接近 1」去收紧：那句话对 14 个包成立，对 03 不成立。
     * 收到 2x 以下就等于把它判成 zip 炸弹，每次装到一半整包作废重下。真实余量按
     * 最高的 03 算是约 95 倍；要动这个数，先把上面这张表重测一遍。
     */
    private static final long EXTRACT_MAX_RATIO = 200L;
    /**
     * 小包不看比例。几 KB 的包里放一个高度可压的小文件，比例很容易冲上去，
     * 但那点绝对量根本谈不上「把磁盘写满」，没必要为它中止。
     */
    private static final long EXTRACT_MIN_BYTES_BEFORE_RATIO = 256L * 1024 * 1024;

    /**
     * 本次解压已经写出去的字节数。**边写边看**用的，不是统计。
     *
     * <p>为什么必须边写边看：上面 {@code extract()} 开头那道比例检查读的是中央
     * 目录<b>声明</b>的未压缩长度，而声明是可以撒谎的——一个谎报小尺寸、实际
     * 解出几十 GB 的包能整份通过那道闸。而 {@code writeEntry} 是读到 EOF 才比
     * {@code copied != entry.getSize()}，等它报错时磁盘已经铺满了。zip 炸弹的
     * 伤害就在于「写出去」这件事本身，事后拒绝没有意义。
     *
     * <p>单线程使用：解压全程在 {@code EXTRACT_LOCK} 之内串行。
     */
    private static long writtenThisRun;

    private static void writeEntry(ZipFile zip, ZipEntry entry, File out, Cancel cancel,
                                   long archiveBytes)
            throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new InstallIOException("Cannot create directory " + parent);
        }
        File temp = new File(out.getPath() + ".cnv-install.tmp");
        File backup = new File(out.getPath() + ".cnv-install.bak");
        recoverInterruptedSwap(out, temp, backup);
        deleteQuietly(temp);

        InputStream in = null;
        FileOutputStream raw = null;
        OutputStream output = null;
        CRC32 crc = new CRC32();
        long copied = 0L;
        try {
            try {
                in = new BufferedInputStream(zip.getInputStream(entry), 65536);
            } catch (IOException e) {
                throw corrupt("Cannot open ZIP entry: " + entry.getName(), e);
            }
            try {
                raw = new FileOutputStream(temp, false);
                output = new BufferedOutputStream(raw, 65536);
            } catch (IOException e) {
                throw new InstallIOException("Cannot create extraction temp: " + temp, e);
            }
            byte[] buf = new byte[65536];
            while (true) {
                if (cancel != null && cancel.isCancelled()) {
                    throw new CancelledException("Extraction restart requested: " + entry.getName());
                }
                int n;
                try {
                    n = in.read(buf);
                } catch (IOException e) {
                    throw corrupt("Cannot inflate ZIP entry: " + entry.getName(), e);
                }
                if (n < 0) break;
                if (n == 0) continue;
                // 两道判据都在 write **之前**。写完再判等于「炸弹已经落地，
                // 事后宣布它不该落地」——zip 炸弹的伤害就是写出去这件事本身。
                //
                // ① 单条目不许超过它自己声明的长度。中央目录说多少就只收多少，
                //    谎报的那部分一个字节都不落盘。
                long declared = entry.getSize();
                // F-081：写成「余量比较」而不是 copied + n——后者在 copied 接近
                // Long.MAX_VALUE 时回绕成负数，比较反而通过。
                if (declared >= 0 && n > declared - copied) {
                    throw new ZipException("Entry longer than declared: " + entry.getName()
                            + " declared=" + declared + " atLeast=" + (copied + n));
                }
                // ② 整包累计仍要看比例：即使每条都「诚实」，条目数量本身也能堆出
                //    一个炸弹。判据与门槛沿用并入前的那份实现。
                // F-081：两处溢出。writtenThisRun + n 同上改成余量比较；
                // archiveBytes * EXTRACT_MAX_RATIO 在 archiveBytes >
                // Long.MAX_VALUE / 200 时回绕，闸门会朝任意方向失灵——改成除法
                // 形式比较，两边都不做乘法。
                long afterWrite = CNDiskSpace.saturatedAdd(writtenThisRun, n);
                if (archiveBytes > 0
                        && afterWrite > EXTRACT_MIN_BYTES_BEFORE_RATIO
                        && afterWrite / archiveBytes > EXTRACT_MAX_RATIO) {
                    throw new ZipException("解压膨胀比超限（已写 " + writtenThisRun
                            + "B，归档 " + archiveBytes + "B，上限 "
                            + EXTRACT_MAX_RATIO + "x）：" + entry.getName());
                }
                try {
                    output.write(buf, 0, n);
                } catch (IOException e) {
                    throw new InstallIOException("Cannot write extraction temp: " + temp, e);
                }
                crc.update(buf, 0, n);
                copied = CNDiskSpace.saturatedAdd(copied, n);
                writtenThisRun = afterWrite;
            }
            try {
                output.flush();
                raw.getFD().sync();
            } catch (IOException e) {
                throw new InstallIOException("Cannot sync extraction temp: " + temp, e);
            }
        } catch (Throwable t) {
            // 从写入循环里抛出来时，半截临时文件必须带走。原先只有循环**之后**
            // 那两处检查会 deleteQuietly(temp)，于是新加的即时判据一旦命中，
            // .cnv-install.tmp 就留在盘上没人管了。
            CNIo.closeQuietly(output);
            CNIo.closeQuietly(raw);
            CNIo.closeQuietly(in);
            deleteQuietly(temp);
            throw t;
        } finally {
            CNIo.closeQuietly(output);
            CNIo.closeQuietly(raw);
            CNIo.closeQuietly(in);
        }

        if (entry.getSize() >= 0 && copied != entry.getSize()) {
            deleteQuietly(temp);
            throw new ZipException("Entry size mismatch: " + entry.getName()
                    + " expected=" + entry.getSize() + " actual=" + copied);
        }
        if (entry.getCrc() >= 0 && crc.getValue() != entry.getCrc()) {
            deleteQuietly(temp);
            throw new ZipException("Entry CRC mismatch: " + entry.getName());
        }

        boolean backedUp = false;
        try {
            if (backup.exists() && !backup.delete() && backup.exists()) {
                throw new InstallIOException("Cannot clear stale backup: " + backup);
            }
            if (out.exists()) {
                if (!out.renameTo(backup)) {
                    throw new InstallIOException("Cannot back up existing file: " + out);
                }
                backedUp = true;
            }
            if (!temp.renameTo(out)) {
                throw new InstallIOException("Cannot promote extraction temp: " + temp);
            }
            deleteQuietly(backup);
        } catch (IOException e) {
            if (!out.exists() && backedUp && backup.exists()) {
                try { backup.renameTo(out); } catch (Throwable ignore) {}
            }
            deleteQuietly(temp);
            throw e;
        }
    }

    private static void recoverInterruptedSwap(File out, File temp, File backup)
            throws InstallIOException {
        if (backup.exists() && !out.exists()) {
            if (!backup.renameTo(out)) {
                throw new InstallIOException("Cannot restore interrupted extraction backup: " + out);
            }
        } else if (backup.exists()) {
            deleteQuietly(backup);
        }
        deleteQuietly(temp);
    }

    private static String fingerprint(File archive, ArrayList<ZipEntry> entries)
            throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            update(md, String.valueOf(archive.length()));
            for (int i = 0; i < entries.size(); i++) {
                ZipEntry e = entries.get(i);
                update(md, "\n");
                update(md, e.getName());
                update(md, ":" + e.getSize() + ":" + e.getCompressedSize()
                        + ":" + e.getCrc() + ":" + e.getMethod());
            }
            return hex(md.digest());
        } catch (Exception e) {
            throw new IOException("Cannot fingerprint ZIP central directory", e);
        }
    }

    private static void update(MessageDigest md, String s) throws Exception {
        md.update(s.getBytes("UTF-8"));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            sb.append(String.format(Locale.US, "%02x", bytes[i] & 0xff));
        }
        return sb.toString();
    }

    private static State readState(File file) {
        if (file == null || !file.isFile() || file.length() > 4096) return null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            if (!SCHEMA.equals(reader.readLine())) return null;
            State state = new State();
            state.fingerprint = reader.readLine();
            String next = reader.readLine();
            state.next = Integer.parseInt(next == null ? "-1" : next.trim());
            return state;
        } catch (Throwable t) {
            return null;
        } finally {
            CNIo.closeQuietly(reader);
        }
    }

    private static void saveState(File file, String fingerprint, int next)
            throws InstallIOException {
        if (file == null) return;
        File temp = CNAtomicReplace.stage(file);
        FileOutputStream raw = null;
        Writer writer = null;
        try {
            raw = new FileOutputStream(temp, false);
            writer = new OutputStreamWriter(raw, "UTF-8");
            writer.write(SCHEMA); writer.write('\n');
            writer.write(fingerprint); writer.write('\n');
            writer.write(String.valueOf(next)); writer.write('\n');
            writer.flush();
            raw.getFD().sync();
            CNIo.closeQuietly(writer); writer = null;
            CNIo.closeQuietly(raw); raw = null;
            // F-073：不再「先删再改名」。两步之间被杀，断点状态就此消失——不致命
            // （下次整包重解），但那是白烧一次几百 MB 的解压，而 rename(2) 本来就
            // 能一步换到位。
            // F-B-06：rename 落地的是**目录项**，上面对 temp 的 fsync 管不到它；
            // commit() 收尾的 syncDir 把这个掉电窗口一并关上。
            CNAtomicReplace.commit(temp, file);
        } catch (IOException e) {
            if (e instanceof InstallIOException) throw (InstallIOException) e;
            throw new InstallIOException("Cannot save extraction state: " + file, e);
        } finally {
            CNIo.closeQuietly(writer);
            CNIo.closeQuietly(raw);
            deleteQuietly(temp);
        }
    }

    /**
     * fsync 一个<b>目录</b>，让其中的目录项（创建/删除/rename）持久化（F-B-06）。
     *
     * <p>为什么需要它：ext4/f2fs 上 {@code getFD().sync()} 一个文件只保证该
     * 文件的**内容**落盘，不保证「它出现在父目录里 / 旧名字消失」这类目录项
     * 变更落盘。热更事务（{@code CNHotUpdateTx}）的崩溃恢复语义建立在
     * 「journal 的持久化严格早于任何 rename」之上——不 fsync 目录，掉电后可能
     * 出现 rename 生效而 journal/COMMITTED 的目录项丢失，恢复方向随之误判
     * （把「已提交一半」当成「没动过」，连同回滚材料一起删掉）。
     *
     * <p>实现见 {@link #syncDirOrThrow}。部分挂载（sdcardfs/FUSE/个别 OEM 内核）
     * 不支持 fsync 目录——此时<b>降级为记日志而不是判失败</b>：文件内容本身都已
     * 逐次 fsync，缺的只是「目录项 vs 掉电」这最后一个窗口，为它放弃整笔事务
     * 得不偿失。失败日志按目录限流，见 {@link #warnSyncDirFailed}。
     *
     * <p>包内可见：{@code CNHotUpdateTx} 的 journal/COMMITTED/清单目录复用
     * 同一份实现，别复制第二份。
     */
    static void syncDir(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        try {
            syncDirOrThrow(dir);
        } catch (Throwable t) {
            warnSyncDirFailed(dir, t);
        }
    }

    /**
     * {@link #syncDir} 的底层动作，失败<b>向上抛</b>。
     *
     * <p>给「目录项没确认落盘就绝不能报成功」的调用方用
     * （{@code CNOfflineImport.commitImportedFile}）。整个补丁里对目录
     * fsync 的系统调用只有这一处，别复制第二份。
     *
     * <p>{@code O_RDONLY} 打开目录再 fsync 是标准的目录项同步惯用法；精简
     * android.jar 只暴露 {@code O_RDONLY}，不依赖 {@code O_DIRECTORY}
     * （API 21 上对目录 fsync 同样有效）。
     *
     * <p>⚠ <b>不要改回 {@code new RandomAccessFile(dir, "r")}</b>。那种写法在
     * Android 上对目录<b>必定</b>抛 {@code FileNotFoundException: … EISDIR}
     * ——2026-08-21 的玩家日志里 1386 次调用无一例外全部落进降级分支，
     * 目录级持久化实际上从来没有生效过，而热更事务的崩溃恢复语义正是建立在
     * 它之上的。
     */
    static void syncDirOrThrow(File dir) throws Exception {
        java.io.FileDescriptor fd = null;
        try {
            fd = android.system.Os.open(dir.getAbsolutePath(),
                    android.system.OsConstants.O_RDONLY, 0);
            android.system.Os.fsync(fd);
        } finally {
            if (fd != null) {
                try { android.system.Os.close(fd); } catch (Throwable ignore) {}
            }
        }
    }

    /**
     * 目录 fsync 失败时限流记日志。
     *
     * <p>为什么要限流：确实有挂载（sdcardfs/FUSE/个别 OEM 内核）不支持对目录
     * fsync，那种设备上<b>每一次</b>调用都会失败。一次热更有上千次调用，不限流
     * 就是上千行同样的警告——2026-08-21 那份玩家日志里这一行占了整份的 62%，
     * 把真正要查的东西全冲没了。每个目录只说一次，总量再封顶。
     */
    private static final java.util.Set<String> SYNC_DIR_WARNED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final int SYNC_DIR_WARN_MAX = 8;

    private static void warnSyncDirFailed(File dir, Throwable t) {
        try {
            String key = dir.getPath();
            synchronized (SYNC_DIR_WARNED) {
                if (SYNC_DIR_WARNED.contains(key)) return;
                if (SYNC_DIR_WARNED.size() >= SYNC_DIR_WARN_MAX) {
                    if (SYNC_DIR_WARNED.add("\u0000capped")) {
                        CNLog.w(TAG, "目录 fsync 已失败 " + SYNC_DIR_WARN_MAX
                                + " 个目录，后续不再逐个记（该文件系统多半整体不支持）");
                    }
                    return;
                }
                SYNC_DIR_WARNED.add(key);
            }
            CNLog.w(TAG, "目录 fsync 失败（该文件系统可能不支持，掉电窗口仍在）: "
                    + dir + " : " + t);
        } catch (Throwable ignore) {
            // 记日志绝不能反过来把事务搞挂
        }
    }

    private static ZipException corrupt(String message, Throwable cause) {
        ZipException out = new ZipException(message);
        try { out.initCause(cause); } catch (Throwable ignore) {}
        return out;
    }

    private static void deleteQuietly(File file) {
        if (file == null || !file.exists()) return;
        try { file.delete(); } catch (Throwable ignore) {}
    }

}
