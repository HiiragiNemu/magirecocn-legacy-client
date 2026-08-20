package io.kamihama.magianative;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 热更包的事务化应用：解压到暂存区 → 备份旧文件 → 换入 → 出错整体回滚。
 *
 * <h3>要解决的问题</h3>
 *
 * 原本热更是「下载 → 直接解压覆盖活动目录树」。
 * 那种做法是**逐条目往活动目录树上写**的，中途失败（磁盘满、
 * 进程被杀、断电）就留下一棵新旧混杂的树。
 *
 * <p>版本号确实是解压成功后才写的，所以下次启动会重下重解——**但那是「以后能
 * 自愈」，不是「现在没坏」**。在下一次成功解压之前，游戏跑的是半更新的前端：
 * 一个 JS 换了、依赖它的另一个没换，直接白屏。而白屏之后玩家往往就不再启动了，
 * 自愈的机会也就没了。
 *
 * <h3>做法</h3>
 *
 * <pre>
 *   &lt;files&gt;/.cnv_tx/&lt;tag&gt;/
 *       stage/      解压产物（先全部落到这里，不碰活动树）
 *       backup/     被覆盖/被删除的旧文件，按相对路径原样镜像
 *       journal     提交计划：每行 "&lt;0|1&gt;&lt;+|-&gt;\t&lt;相对路径&gt;"
 *                   第 1 位：动手之前活动树上是否已有它（决定要不要备份）
 *                   第 2 位：'+' 从 stage 换入，'-' 只删不写（孤儿清理）
 *       COMMITTED   提交完成标记
 *   &lt;files&gt;/.cnv_manifest/&lt;tag&gt;.list
 *                   上一轮实际下发的完整文件清单，一行一个相对路径。
 *                   放在事务目录之外——那个目录提交完就整个删掉了。
 * </pre>
 *
 * 四个阶段：
 * <ol>
 *   <li><b>列清单</b>——{@link #listEntries} 只读 zip 的<b>中央目录</b>，在写第一个
 *       字节之前就拿到包内全部路径：非法路径当场整包拒收，也才有可能和上一轮的
 *       清单做差集算出<b>孤儿</b>（上一版下发过、这一版没有了的文件）。</li>
 *   <li><b>暂存</b>——解压进 {@code stage/}。这一步失败不会碰到活动树一个字节。</li>
 *   <li><b>计划</b>——遍历 {@code stage/} 列出全部相对路径，连同「活动树上是否
 *       已有同名文件」写成 journal，<b>fsync 一次</b>后才进入下一阶段。</li>
 *   <li><b>提交</b>——逐个：旧文件 rename 进 {@code backup/}，暂存文件 rename 到位。
 *       全部做完写 {@code COMMITTED} 并 fsync。</li>
 * </ol>
 *
 * <h3>为什么 journal 一次写完再 fsync，而不是逐条 fsync</h3>
 *
 * 逐条 fsync 是最直观的写法，但一个热更包有近千个文件，在手机上就是近千次
 * fsync——光这一项就能让「应用更新」卡十几秒，玩家会以为死机。
 *
 * <p>把**完整计划**在动手之前一次写完再 fsync，能拿到同样的保证：journal 的
 * 持久化严格早于任何一次 rename，所以只要有 rename 落了盘，journal 一定也在。
 * 恢复时按计划逐条判断即可，无需知道当时进行到哪一条：
 *
 * <ul>
 *   <li>backup 里有 → 不管是「备份完还没换入」还是「已经换入」，一律删掉活动树
 *       上的、把 backup 挪回去；</li>
 *   <li>backup 里没有、且计划记的是「原本有」→ 这条还没轮到，活动树上是旧文件，
 *       不动；</li>
 *   <li>backup 里没有、且计划记的是「原本没有」→ 是本次新增的文件，删掉。</li>
 * </ul>
 *
 * 孤儿那类（{@code '-'}）落在第一条：它一定是「原本有」，备份走了就靠 backup 挪回来，
 * 还没轮到就什么都没动过。所以回滚逻辑一个字都不用为它单开分支。
 *
 * <h3>为什么要清理孤儿</h3>
 *
 * 解压是**只写不删**的。把一个文件从包里拿掉，只是「以后不再更新它」——设备上那份
 * 会永远留着，而且因为 {@code WebViewImpl$WebViewClientImpl.shouldInterceptRequest}
 * 是本地优先且忽略 {@code ?<md5>} 查询串，它会**永远盖住服务端的版本**。
 * 历史篇入口就是这么丢的：某一版把一个页面 CSS 放进过包里，那份快照缺了按钮的规则，
 * 后来即使从包里移除也无济于事。把孤儿在同一个事务里删掉，请求就回落到服务端，
 * 等于恢复成「我们从没碰过这个文件」。
 *
 * <p>删除范围由 {@code cleanupPrefixes} 的白名单限死——热更包和安装器的十几个大包
 * 写的是同一棵树，共用的子树里「这一版没有它」不等于「不该有它」。
 * 首次启用时没有上一轮清单，什么都不删：<b>这套机制防的是以后再犯，补不了以前的账。</b>
 *
 * 文件 fsync 共两次（journal 一次、COMMITTED 一次）；此外对 journal /
 * COMMITTED / 清单所在的目录、以及提交阶段动过的每个目录各做一次<b>目录
 * fsync</b>（F-B-06：fsync 文件管不到它的目录项，掉电时可能出现「rename
 * 生效了但 journal 不在」，恰好击穿上面的恢复前提）。不支持 fsync 目录的
 * 文件系统降级为记日志，不判事务失败。
 *
 * <h3>崩溃后的方向</h3>
 *
 * {@link #recover(File)} 在热更检查开机时先跑一遍：
 * 有 {@code COMMITTED} 就<b>向前滚</b>（内容是完整的，清掉事务目录即可），
 * 没有就<b>向后滚</b>。版本号是在 {@link #apply} 返回之后才写的，所以
 * 「提交完成但版本号没来得及写」只会导致下次重下重应用一遍同样的内容——
 * 幂等覆盖，无害。方向永远偏安全的那一边。
 *
 * <h3>只用于热更</h3>
 *
 * 安装器那条路（{@code cn_base_*.zip} 等十几个包，解压后可能上 GB）
 * <b>不走这里</b>，仍旧直接解压：事务要额外一份暂存 + 一份备份，大包扛不住手机
 * 的存储空间；而且安装器本来就是逐文件写 marker 的，半装状态下次启动会接着装，
 * 不存在「新旧混杂还看不出来」的问题。热更包只有台词与前端脚本两个，体积小，
 * 才付得起这个代价。
 */
public final class CNHotUpdateTx {

    private static final String TAG = "MagiaCNHotUpdate";

    /** 事务工作区的目录名。放在解压根之下，保证与活动树同一个文件系统——
     *  跨文件系统时 {@link File#renameTo} 会失败，整套换入就退化成复制。 */
    static final String TX_DIR = ".cnv_tx";

    /** 每个 tag 上一次实际落盘的文件清单，用来算「这次没了的文件」。
     *  不放在 {@link #TX_DIR} 下面——那个目录提交完就整个删掉了。 */
    static final String MANIFEST_DIR = ".cnv_manifest";

    private static final String STAGE     = "stage";
    private static final String BACKUP    = "backup";
    private static final String JOURNAL   = "journal";
    private static final String COMMITTED = "COMMITTED";

    private CNHotUpdateTx() {}

    // ==================================================================
    // 应用
    // ==================================================================

    /**
     * 事务化地把 {@code archive} 应用到 {@code root}。
     *
     * <p>返回即代表成功。抛异常代表失败，且<b>活动树已经回滚到动手之前的状态</b>
     * （回滚本身也失败时会在日志里明说，并把事务目录留着等下次启动继续恢复）。
     *
     * @param archive 已经过 size/md5 校验的热更包
     * @param root    解压根，即 {@code <files>/}
     * @param tag     事务名，用于区分同时存在的多个包（如 {@code scenario} / {@code js}）
     */
    public static void apply(File archive, File root, String tag) throws IOException {
        if (archive == null || !archive.isFile()) {
            throw new IOException("热更包不存在: " + archive);
        }
        if (root == null || (!root.isDirectory() && !root.mkdirs() && !root.isDirectory())) {
            throw new IOException("建不出解压根: " + root);
        }
        if (CNDebugFlags.isOn(CNDebugFlags.FAIL_HOTUPDATE_APPLY)) {
            // 在建暂存目录之前就抛：调用方按「应用失败」处理，
            // 下次启动 recover() 会看到（没有）journal 并原样通过——
            // 想验真正的中途回滚，把这个注入点往 commit 之前挪。
            throw new IOException("[DEBUG] failHotUpdateApply 注入的失败");
        }
        File tx     = txDir(root, tag);
        File stage  = new File(tx, STAGE);
        File backup = new File(tx, BACKUP);
        File journal = new File(tx, JOURNAL);

        // 上一轮留下的残骸先按恢复流程处理掉，别把它的 backup 当成本轮的
        if (tx.exists()) {
            CNLog.w(TAG, "[" + tag + "] 发现上一轮未收尾的事务，先恢复");
            // F-034：恢复失败（回滚/清理没能彻底清除）时，旧 journal/backup 是
            // 唯一恢复材料。绝不能把新事务的 stage/backup/journal 写进未恢复的
            // 旧命名空间，否则上一轮的恢复材料被当场销毁、且新事务建立在
            // 语义不明的半恢复状态上——fail-closed，拒绝开始。
            if (!recoverOne(root, tx) || tx.exists()) {
                throw new IOException("上一轮事务未能完全恢复，拒绝开始新事务: " + tx);
            }
        }
        if (!tx.mkdirs() && !tx.isDirectory()) {
            throw new IOException("建不出事务目录: " + tx);
        }

        try {
            // ---- 阶段零：先读 zip 的中央目录，拿到完整文件清单 ----
            // 一个字节都还没写就能知道这个包要动哪些文件：非法路径当场拒收，
            // 也才有可能在解压之前把「上一版有、这一版没有」的孤儿算出来。
            List<String> declared = listEntries(archive);
            CNLog.i(TAG, "[" + tag + "] 包内清单 " + declared.size() + " 个文件");

            // ---- 阶段一：解压到暂存区。全程不碰活动树 ----
            if (!stage.mkdirs() && !stage.isDirectory()) {
                throw new IOException("建不出暂存目录: " + stage);
            }
            // 与首次安装器、离线导入同一套解压事务（2026-08-13 收敛到一份实现）。
            // 暂存区是本次事务专用的新目录，状态文件放它旁边即可；解压失败会把
            // 整个暂存区丢掉，所以这里的断点续解压只在同一轮内有意义。
            File stageState = new File(stage.getPath() + ".extract.tx");
            try {
                CNArchiveInstallTx.extract(archive, stage, stageState, null, null);
            } finally {
                CNArchiveInstallTx.clearState(stageState);
            }

            // ---- 阶段二：列计划、写 journal、fsync ----
            List<String> rels = new ArrayList<String>();
            collect(stage, "", rels);
            if (rels.isEmpty()) throw new IOException("热更包解压后没有任何文件");

            // 落盘结果必须与中央目录声明的完全一致。对不上说明要么解压器写了
            // 清单外的东西，要么清单里的东西没落盘——两种都不该带着往下走。
            Set<String> declaredSet = new HashSet<String>(declared);
            for (int i = 0; i < rels.size(); i++) {
                if (!declaredSet.contains(rels.get(i))) {
                    throw new IOException("解压出了清单之外的文件: " + rels.get(i));
                }
            }
            if (rels.size() != declaredSet.size()) {
                throw new IOException("清单 " + declaredSet.size() + " 个文件，实际解压出 "
                        + rels.size() + " 个");
            }

            // ---- 孤儿：上一版下发过、这一版没有了 ----
            List<String> orphans = findOrphans(root, tag, declaredSet);

            List<Entry> plan = new ArrayList<Entry>(rels.size() + orphans.size());
            StringBuilder sb = new StringBuilder((rels.size() + orphans.size()) * 48);
            // 孤儿排在写入**之前**。顺序不是随意的：某个路径这一版从「文件」变成
            // 「目录」（或反过来）时，两种排法差别很大。
            //   例：上一版有文件 magica/js/x，这一版是目录 magica/js/x/y.js。
            //   先写：ensureParent 要把 x 当目录建，而它是个文件 → 整笔事务失败。
            //   先删：x 作为孤儿被移进 backup，路腾出来了，再写就顺理成章。
            // 反向（目录变文件）同理：先把目录里的孤儿逐个搬走，剩下空目录再被
            // 当作「原本有」备份掉；若先写，父目录已被整个搬走，孤儿那一步就找
            // 不到源文件而失败。
            for (int i = 0; i < orphans.size(); i++) {
                // 孤儿一定是活动树上现存的文件（findOrphans 已经 stat 过），
                // 所以 existed 恒为 1：它会被备份走，回滚时原样挪回来。
                plan.add(new Entry(orphans.get(i), true, true));
                sb.append('1').append('-').append('\t').append(orphans.get(i)).append('\n');
            }
            for (int i = 0; i < rels.size(); i++) {
                String rel = rels.get(i);
                boolean existed = new File(root, rel).exists();
                plan.add(new Entry(rel, existed, false));
                sb.append(existed ? '1' : '0').append('+').append('\t').append(rel).append('\n');
            }
            writeSynced(journal, sb.toString());
            // F-B-06：上面 fsync 的是 journal 的**内容**，而「journal 这个目录项
            // 存在」是另一回事——ext4/f2fs 上目录项要单独 fsync 父目录才落盘。
            // 不补这一下，掉电时序「journal 创建 → fsync 内容 → rename 落盘 →
            // 掉电」恢复后会变成 rename 生效而 journal 不存在，rollback 会误判
            // 「还没进提交阶段」直接删事务目录，把回滚材料（backup）一并销毁。
            syncDir(journal.getParentFile());
            CNLog.i(TAG, "[" + tag + "] 提交计划已落盘：写入 " + rels.size() + " 个（覆盖 "
                    + countExisting(plan) + " 个），清理孤儿 " + orphans.size() + " 个");

            // ---- 阶段三：提交 ----
            // F-B-06：提交阶段的 rename 改的全是目录项，逐个 fsync 文件管不到。
            // 把动过的父目录（活动树侧与 backup 镜像侧）收集起来，提交后逐个
            // fsync——恢复语义依赖「rename 与 journal/COMMITTED 的相对持久顺序」，
            // 目录项不落盘，这个顺序在掉电后就没有意义。目录数量是几十量级，
            // 一次干净目录的 fsync 近乎零成本，付得起。
            Set<File> dirtyDirs = new LinkedHashSet<File>();
            commit(root, stage, backup, plan, tag, dirtyDirs);
            for (File d : dirtyDirs) syncDir(d);
            writeSynced(new File(tx, COMMITTED), "ok\n");
            // COMMITTED 自己的目录项同理：recover() 靠「COMMITTED 在不在」决定
            // 向前滚还是向后滚，这个标志位的目录项必须落盘。
            syncDir(tx);
            CNLog.i(TAG, "[" + tag + "] 提交完成");
            // 清单在提交之后写。中间崩掉的话下一轮拿到的是**上一版**的清单，
            // 算出来的孤儿只会更少（漏删），不会多删——失败方向永远偏安全。
            writeManifest(root, tag, declared);
        } catch (Throwable t) {
            CNLog.e(TAG, "[" + tag + "] 应用失败，开始回滚", t);
            boolean rolled = rollback(root, tx, journal);
            if (rolled) {
                deleteTree(tx);
                pruneTxBase(root);
                CNLog.i(TAG, "[" + tag + "] 已回滚到更新前的状态");
            } else {
                // 回滚没做干净：事务目录必须留着，下次启动 recover() 继续
                CNLog.e(TAG, "[" + tag + "] 回滚未能完成，事务目录保留待下次启动恢复: " + tx);
            }
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException("应用热更包失败: " + t, t);
        }
        // 提交成功，工作区没用了。删不掉也不算失败——里面有 COMMITTED，
        // 下次启动的 recover() 会认出这是「已完成」并向前滚。
        if (!deleteTree(tx)) {
            CNLog.w(TAG, "[" + tag + "] 事务目录清理不干净: " + tx);
        }
        pruneTxBase(root);
    }

    /**
     * 事务目录删掉之后，若 {@code .cnv_tx/} 已经空了就把它也删掉。
     *
     * <p>不这么做的话，装完一次热更就会在 {@code <files>/} 下永久留一个空目录，
     * 而 {@link #recover} 是以「{@code .cnv_tx/} 存在」为线索的，留着只会让每次
     * 启动都多走一遍恢复流程、日志里也多一行没意义的告警。
     *
     * <p>两个包目前是**顺序**应用的（见 {@code CNHotUpdateCheck} 的解压循环），
     * 所以不存在「A 删掉父目录时 B 正在用」的竞争；将来若改成并行，
     * 这里最坏也只是 B 的 {@code mkdirs()} 重建一次。
     */
    private static void pruneTxBase(File root) {
        try {
            File base = new File(root, TX_DIR);
            String[] left = base.list();
            if (left != null && left.length == 0) base.delete();
        } catch (Throwable ignore) {}
    }

    /** 计划里的一条：相对路径 + 活动树上原本是否已有同名文件。 */
    private static final class Entry {
        final String  rel;
        final boolean existed;   // 动手之前活动树上是否已有它（决定要不要备份）
        final boolean remove;    // true = 只删不写（孤儿清理），没有对应的 stage 文件
        Entry(String rel, boolean existed, boolean remove) {
            this.rel = rel; this.existed = existed; this.remove = remove;
        }
    }

    private static int countExisting(List<Entry> plan) {
        int n = 0;
        for (int i = 0; i < plan.size(); i++) if (plan.get(i).existed) n++;
        return n;
    }

    /**
     * 逐个换入：旧文件进 backup，暂存文件到位。任何一步失败都往上抛，由调用方回滚。
     *
     * <p>{@code dirtyDirs} 收集本次 rename 动过的父目录（F-B-06），调用方在提交
     * 完成后对它们逐个 fsync——见 apply 阶段三与 syncDir 的注释。传 null 表示不收集。
     */
    private static void commit(File root, File stage, File backup,
                               List<Entry> plan, String tag, Set<File> dirtyDirs) throws IOException {
        for (int i = 0; i < plan.size(); i++) {
            Entry en = plan.get(i);
            File live = new File(root, en.rel);
            File from = new File(stage, en.rel);
            if (en.remove && !live.exists()) {
                // 从 findOrphans 那次 stat 到现在，这个文件可能已经不在了
                // （前面某条孤儿把它的父目录搬走了，或者外部动过）。没什么可删的，
                // 跳过即可：journal 记的是 existed=1、backup 里没有，回滚时正好
                // 落在「这条还没轮到，不动」那一支，语义仍然自洽。
                continue;
            }
            if (en.existed) {
                File to = new File(backup, en.rel);
                ensureParent(to);
                move(live, to);
                if (dirtyDirs != null) {
                    // 旧文件从活动树的这个目录里消失、出现在 backup 镜像里——
                    // 两个目录的目录项都变了。
                    File lp = live.getParentFile();
                    File tp = to.getParentFile();
                    if (lp != null) dirtyDirs.add(lp);
                    if (tp != null) dirtyDirs.add(tp);
                }
            }
            if (en.remove) continue;   // 孤儿：备份完就没了，没有 stage 文件要换入
            ensureParent(live);
            move(from, live);
            if (dirtyDirs != null) {
                File lp = live.getParentFile();
                if (lp != null) dirtyDirs.add(lp);
            }
        }
    }

    // ==================================================================
    // 清单：读 zip 中央目录 / 记录本轮下发了什么 / 算孤儿
    // ==================================================================

    /**
     * 只读 zip 的<b>中央目录</b>，返回包内全部文件的相对路径（目录条目不算）。
     *
     * <p>不解压、不解码任何一个字节：{@link ZipFile} 打开时读的就是中央目录，
     * 遍历它是 O(条目数)。所以这一步可以放在动手之前，用来：
     *
     * <ul>
     *   <li>把非法路径挡在<b>写第一个字节之前</b>——绝对路径、{@code ..} 穿越、
     *       写进事务工作区，任何一条命中就整包拒收；</li>
     *   <li>拿到「这一版有哪些文件」，才能和上一版的清单做差集算出孤儿；</li>
     *   <li>解压完拿它和实际落盘的结果对账（见 {@link #apply}）。</li>
     * </ul>
     *
     * <p>路径统一成 {@code /} 分隔、去掉末尾斜杠。重复条目直接拒收——同名条目
     * 出现两次时「哪一份赢」取决于解压顺序，那是不该带进事务里的不确定性。
     */
    public static List<String> listEntries(File archive) throws IOException {
        if (archive == null || !archive.isFile()) {
            throw new IOException("热更包不存在: " + archive);
        }
        List<String> out = new ArrayList<String>();
        Set<String>  seen = new HashSet<String>();
        ZipFile zip = new ZipFile(archive);
        try {
            Enumeration<? extends ZipEntry> es = zip.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory() || name.endsWith("/")) continue;
                // F-079：判据收敛到 recordPathProblem——这里原先自己写了一套
                // （空名/绝对路径/冒号/..），既漏了控制字符与 . 段，又与
                // isSafeManifestEntry 那套各说各话。同一批路径要经过包内清单、
                // journal、manifest 三道关，三道关判据不同就等于没判。
                // 拒收发生在**一个字节都还没写**的时候：整包不应用，游戏保持
                // 上一版内容，失败方向是安全的。
                String problem = recordPathProblem(name);
                if (problem != null) {
                    throw new ZipException("包内路径不安全（" + problem + "）: "
                            + escapeForLog(name));
                }
                if (name.equals(TX_DIR) || name.startsWith(TX_DIR + "/")) {
                    throw new ZipException("热更包试图写入事务工作区: " + name);
                }
                if (name.equals(MANIFEST_DIR) || name.startsWith(MANIFEST_DIR + "/")) {
                    throw new ZipException("热更包试图写入清单目录: " + name);
                }
                if (!seen.add(name)) {
                    throw new ZipException("包内有重复条目: " + name);
                }
                out.add(name);
            }
        } finally {
            closeQuietly(zip);
        }
        if (out.isEmpty()) throw new ZipException("包里没有任何文件条目: " + archive);
        return out;
    }

    /**
     * 允许清理孤儿的路径前缀，按 tag 区分。<b>这是个白名单，宁可漏删不可错删。</b>
     *
     * <h3>为什么必须限定前缀</h3>
     *
     * 热更包和安装器的十几个大包写的是**同一棵树**。某个路径同时出现在两边时，
     * 「热更包这一版没有它」不代表「设备上不该有它」——很可能是安装包给的。
     * 把它当孤儿删掉，游戏就回落到向服务端取原版，等于把汉化悄悄退了。
     *
     * <h3>各前缀的依据（实测线上各包的路径集合）</h3>
     *
     * <ul>
     *   <li>{@code js} 包：{@code magica/js/}、{@code magica/template/}、
     *       {@code magica/css/}、{@code magica/fonts/} 只有热更包会写；
     *       而 {@code magica/resource/} 与 {@code cn_magica_resource.zip}
     *       （9547 项，全部在这个前缀下）**重叠**，所以<b>不放进白名单</b>。</li>
     *   <li>{@code scenario} 包：{@code madomagi/resource/scenario/json/} 是
     *       {@code cn_scenario_update.zip} 独占；{@code cn_scenario_img.zip}
     *       全部落在 {@code .../scenario/img/} 下，两者路径交集为 0。</li>
     * </ul>
     *
     * 加新前缀之前，先把线上所有包的路径集合拉下来做一次交集验证。
     */
    private static String[] cleanupPrefixes(String tag) {
        if ("js".equals(tag)) {
            return new String[] { "magica/js/", "magica/template/",
                                  "magica/css/", "magica/fonts/" };
        }
        if ("scenario".equals(tag)) {
            return new String[] { "madomagi/resource/scenario/json/" };
        }
        return new String[0];   // 不认识的 tag 一律不清理
    }

    private static File manifestFile(File root, String tag) {
        return new File(new File(root, MANIFEST_DIR), tag + ".list");
    }

    /** 把本轮下发的完整清单写下来，供下一轮算孤儿。一行一个相对路径。 */
    private static void writeManifest(File root, String tag, List<String> rels) {
        try {
            StringBuilder sb = new StringBuilder(rels.size() * 40);
            for (int i = 0; i < rels.size(); i++) sb.append(rels.get(i)).append('\n');
            File f = manifestFile(root, tag);
            ensureParent(f);
            writeSynced(f, sb.toString());
            // F-B-06：清单的目录项也要落盘。它丢了不致命（下一轮只是不清理
            // 孤儿，失败方向偏安全），但目录 fsync 近乎免费，顺手关上窗口。
            syncDir(f.getParentFile());
        } catch (Throwable t) {
            // 写不下来只影响下一轮的孤儿计算（会漏删），不影响这次更新的正确性
            CNLog.w(TAG, "[" + tag + "] 清单写入失败，下一轮不会清理孤儿", t);
        }
    }

    /** 读上一轮的清单。没有（首次启用、或上次没写成）就返回空。 */
    private static List<String> readManifest(File root, String tag) {
        List<String> out = new ArrayList<String>();
        File f = manifestFile(root, tag);
        if (!f.isFile()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), "UTF-8"), 65536);
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() > 0) out.add(line);
            }
        } catch (Throwable t) {
            CNLog.w(TAG, "[" + tag + "] 清单读取失败，本轮不清理孤儿", t);
            out.clear();
        } finally {
            closeQuietly(r);
        }
        return out;
    }

    /**
     * 孤儿 = 上一轮下发过、这一轮没有了、且现在还躺在活动树上的文件。
     *
     * <p>热更的解压是**只写不删**的：从包里拿掉一个文件，只是「以后不再更新它」，
     * 设备上那份会永远留着，而且因为
     * {@code WebViewImpl$WebViewClientImpl.shouldInterceptRequest} 是本地优先，
     * 它会**永远盖住服务端的版本**。历史篇入口就是这么丢的：某一版把一个页面
     * CSS 放进过包里，那份快照缺了按钮的规则，后来即使从包里移除也无济于事。
     *
     * <p>所以这里把它们找出来，在同一个事务里删掉——删掉之后请求就回落到服务端，
     * 也就恢复成了「我们从没碰过这个文件」的状态。
     *
     * <p>首次启用这套机制时没有上一轮清单，返回空：<b>已经中招的设备不会被这一步
     * 自动救回来</b>，那种只能靠把服务端现役内容原样发一遍去覆盖。这套机制是防
     * 以后再犯，不是补以前的账。
     */
    private static List<String> findOrphans(File root, String tag, Set<String> current) {
        List<String> out = new ArrayList<String>();
        String[] prefixes = cleanupPrefixes(tag);
        if (prefixes.length == 0) return out;
        List<String> prev = readManifest(root, tag);
        // F-B-05：清单文件（.cnv_manifest/<tag>.list）躺在解压根下，本身没有
        // 任何完整性保护——能往 <files>/ 写文件的任何路径（例如 native 解压
        // 曾经的 Zip Slip，F-B-01）都能种下一份假清单。而原先的过滤只有
        // startsWith 前缀白名单：「magica/js/../../../databases/x」同样以合法
        // 前缀开头，new File(root, rel) 解析后落在 <files>/ 之外，一旦进删除
        // 计划就会被 rename 进 backup、提交后随事务目录一起删掉——「只写不删」
        // 的热更语义被升级成跨目录定向删除（可删安装器状态、数据库）。
        // 孤儿删除必须保守：每条目先做与补丁 01（cnExtract is_safe_entry_name）
        // 同语的规范化校验，再做 canonical 前缀复核，非法条目跳过并记 WARN。
        final String rootPrefix;
        try {
            rootPrefix = root.getCanonicalPath() + File.separator;
        } catch (Throwable t) {
            // 连基准路径都解析不出就做不了 canonical 复核——宁可本轮一个孤儿
            // 都不删（漏删只是老文件多留一轮），也不在没有复核的情况下动删除。
            CNLog.w(TAG, "[" + tag + "] 解压根 canonical 解析失败，本轮跳过孤儿清理", t);
            return out;
        }
        for (int i = 0; i < prev.size(); i++) {
            String rel = prev.get(i);
            if (current.contains(rel)) continue;
            boolean allowed = false;
            for (int j = 0; j < prefixes.length; j++) {
                if (rel.startsWith(prefixes[j])) { allowed = true; break; }
            }
            if (!allowed) continue;
            if (!isSafeManifestEntry(rel)) {
                CNLog.w(TAG, "[" + tag + "] 清单含非法路径条目，跳过: " + escapeForLog(rel));
                continue;
            }
            File live = new File(root, rel);
            try {
                // canonical 复核：逐段字符串校验管不住「每段都合法、解析后却在
                // 根外」的情况（活动树里混入符号链接时）。删除范围的最终判据
                // 以解析后的真实路径为准。
                if (!live.getCanonicalPath().startsWith(rootPrefix)) {
                    CNLog.w(TAG, "[" + tag + "] 清单条目解析后落在解压根外，跳过: " + escapeForLog(rel));
                    continue;
                }
            } catch (Throwable t) {
                CNLog.w(TAG, "[" + tag + "] 清单条目 canonical 解析失败，跳过: " + escapeForLog(rel), t);
                continue;
            }
            if (live.isFile()) out.add(rel);
        }
        if (!out.isEmpty()) {
            CNLog.w(TAG, "[" + tag + "] 上一版下发过、这一版没有的文件 " + out.size()
                    + " 个，将在本次事务中一并删除（首个: " + out.get(0) + "）");
        }
        return out;
    }

    // ==================================================================
    // 恢复
    // ==================================================================

    /**
     * 开机恢复：扫 {@code <root>/.cnv_tx/} 下所有残留事务，逐个处理。
     *
     * <p>在热更检查真正开始之前调用一次。没有残留时什么都不做，代价只有一次
     * 目录 stat。
     */
    public static void recover(File root) {
        try {
            File base = new File(root, TX_DIR);
            File[] txs = base.listFiles();
            if (txs == null || txs.length == 0) {
                if (base.exists()) deleteTree(base);
                return;
            }
            CNLog.w(TAG, "发现 " + txs.length + " 个未收尾的热更事务，开始恢复");
            for (int i = 0; i < txs.length; i++) {
                if (txs[i].isDirectory()) recoverOne(root, txs[i]);
                else deleteQuietly(txs[i]);
            }
            File[] left = base.listFiles();
            if (left == null || left.length == 0) deleteTree(base);
        } catch (Throwable t) {
            // 恢复失败不能拖垮启动：最坏情况是活动树保持半更新，
            // 下一轮热更会重新下载并再次尝试
            CNLog.e(TAG, "热更事务恢复异常", t);
        }
    }

    /**
     * 处理单个残留事务目录：有 COMMITTED 向前滚，否则向后滚。
     *
     * @return true = 事务目录已被彻底清除（可安全复用 tx 名字开始新事务）；
     *         false = 清理失败，调用方必须 fail-closed（F-034）。
     */
    private static boolean recoverOne(File root, File tx) {
        String tag = tx.getName();
        if (new File(tx, COMMITTED).isFile()) {
            // 内容已经完整换入，只是没来得及清理。版本号可能没写上——
            // 那只会导致下次重下重应用同样的内容，幂等，无害。
            CNLog.i(TAG, "[" + tag + "] 事务已提交完成，清理工作区");
            boolean clean = deleteTree(tx);
            if (!clean) CNLog.e(TAG, "[" + tag + "] 已提交事务目录清理失败: " + tx);
            return clean;
        }
        CNLog.w(TAG, "[" + tag + "] 事务未提交完成，回滚");
        if (rollback(root, tx, new File(tx, JOURNAL))) {
            boolean clean = deleteTree(tx);
            if (clean) CNLog.i(TAG, "[" + tag + "] 已回滚");
            else CNLog.e(TAG, "[" + tag + "] 回滚后工作区清理失败: " + tx);
            return clean;
        }
        CNLog.e(TAG, "[" + tag + "] 回滚未能完成，工作区保留: " + tx);
        return false;
    }

    /**
     * 按 journal 回滚。返回 true 表示每一条都处理干净了。
     *
     * <p>journal 不存在说明还没进入提交阶段（或连计划都没写完），活动树没被碰过，
     * 直接算回滚成功。
     */
    private static boolean rollback(File root, File tx, File journal) {
        if (!journal.isFile()) return true;
        File backup = new File(tx, BACKUP);
        boolean clean = true;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(journal), "UTF-8"), 65536);
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() < 3) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                boolean existed = line.charAt(0) == '1';
                String rel = line.substring(tab + 1);
                // F-044：journal 条目路径必须通过校验——损坏/旧版 Zip Slip/基础包/
                // 调试环境可能留下伪造 journal，`../` 或绝对路径可让恢复流程移动/
                // 覆盖/删除事务根之外的应用数据。非法条目跳过并记失败（不静默忽略）。
                if (!isSafeManifestEntry(rel)) {
                    CNLog.e(TAG, "journal 条目非法，拒绝处理（防事务根外覆盖/删除）: " + escapeForLog(rel));
                    clean = false;
                    continue;
                }
                File live = new File(root, rel);
                File bak  = new File(backup, rel);
                try {
                    // 用 exists 而不是 isFile：活动树上那个位置原本可能是**目录**
                    // （包把某个目录换成了同名文件）。备份时整个目录被 rename 走了，
                    // 这里若只认 isFile 就会跳过，那个目录就永远留在 backup 里丢掉了。
                    if (bak.exists()) {
                        // 备份过 → 不管换入没换入，一律用备份盖回去
                        if (live.exists() && !deleteTree(live)) {
                            throw new IOException("删不掉 " + live);
                        }
                        ensureParent(live);
                        move(bak, live);
                    } else if (!existed) {
                        // 本次新增的文件：可能已经换入，删掉即可
                        if (live.exists() && !live.delete()) {
                            throw new IOException("删不掉新增文件 " + live);
                        }
                    }
                    // 剩下一种：备份没有、原本就有 —— 这条还没轮到，活动树是旧的，不动
                } catch (Throwable t) {
                    clean = false;
                    CNLog.e(TAG, "回滚单条失败: " + rel, t);
                }
            }
        } catch (Throwable t) {
            CNLog.e(TAG, "读 journal 失败，无法回滚: " + journal, t);
            return false;
        } finally {
            closeQuietly(r);
        }
        return clean;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /**
     * 清单条目的路径校验（F-B-05）。判据本体在 {@link #recordPathProblem}——
     * 包内清单、journal、manifest 三处共用同一份，别在这里再长出第二套。
     *
     * <p>这只是第一道字符串闸，调用方还须做 canonical 前缀复核——字符串
     * 合法不代表解析后仍在解压根内（符号链接）。
     */
    private static boolean isSafeManifestEntry(String rel) {
        return recordPathProblem(rel) == null;
    }

    /**
     * 记录安全的相对路径判据（F-079）。<b>包内清单、journal、manifest、暂存、
     * backup、活动树全部只认这一份判据</b>——多一份就多一次漂移的机会。
     *
     * <p>返回 {@code null} 表示合格；否则返回一句「哪儿不合格」，供调用方拼进
     * 异常与日志。写成「返回问题」而不是 boolean，是因为这些路径来自 ZIP 中央
     * 目录，出问题时最需要知道的恰恰是**踩了哪一条规则**。
     *
     * <h3>为什么光挡 {@code ..} 和绝对路径不够</h3>
     *
     * 这套事务把相对路径<b>逐行</b>写进 journal 与 manifest，一行一条记录。于是：
     *
     * <ul>
     *   <li><b>控制字符</b>（尤其 LF/CR）能把一条逻辑记录拆成多行。文件名里带一个
     *       换行，journal 里就凭空多出一条伪记录——而恢复流程照着 journal 走，
     *       伪记录指向哪儿它就动哪儿。TAB 还能伪造字段分隔（journal 正是
     *       {@code 标志\t路径} 这个格式）。CR/LF 进日志则能伪造多行诊断。</li>
     *   <li><b>{@code .} 段与空段</b>制造字符串别名：{@code a/b}、{@code a/./b}、
     *       {@code a//b} 在记录层是三个字符串，在文件系统层是同一个对象。备份／
     *       换入／回滚全部按字符串索引，同一个文件经两个别名进计划，顺序就不再
     *       确定；孤儿差集也会因此漏删或误删。</li>
     * </ul>
     *
     * <p>规则全过之后路径已经是规范形式（无空段、无 {@code .}），所以不需要再做
     * 一次「规范化」——那一步本身正是别名的来源。
     *
     * <p>本判据只管<b>记录格式与字符串别名</b>。canonical 根约束与符号链接边界是
     * 另一件事，仍由 {@code findOrphans} / {@code recover} 里的 canonical 复核负责
     * （F-044），两者不能互相替代。
     */
    public static String recordPathProblem(String rel) {
        if (rel == null || rel.length() == 0) return "空路径";
        if (rel.charAt(0) == '/') return "绝对路径";
        if (rel.indexOf('\\') >= 0) return "含反斜杠";
        if (rel.indexOf(':') >= 0) return "含盘符/协议分隔符";
        for (int i = 0; i < rel.length(); i++) {
            char c = rel.charAt(i);
            if (c <= 0x1f || c == 0x7f) {
                return "含控制字符 U+" + String.format(Locale.US, "%04X", (int) c)
                        + "（第 " + i + " 个字符）";
            }
        }
        String[] segs = rel.split("/", -1);
        for (int i = 0; i < segs.length; i++) {
            if (segs[i].length() == 0) return "有空目录段";
            if (".".equals(segs[i]))   return "有 . 段";
            if ("..".equals(segs[i]))  return "有 .. 段";
        }
        return null;
    }

    /**
     * 把路径转成能安全写进日志的形式：控制字符换成 {@code \\uXXXX}，超长截断。
     *
     * <p>非法路径<b>必然</b>会被记进日志（那正是要看的东西），而它很可能正是因为
     * 带 CR/LF 才非法——原样打出去等于让它自己往日志里写行。
     */
    public static String escapeForLog(String rel) {
        if (rel == null) return "<null>";
        int n = Math.min(rel.length(), 200);
        StringBuilder sb = new StringBuilder(n + 16);
        for (int i = 0; i < n; i++) {
            char c = rel.charAt(i);
            if (c <= 0x1f || c == 0x7f) {
                sb.append(String.format(Locale.US, "\\u%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        if (rel.length() > n) sb.append("…(共 ").append(rel.length()).append(" 字符)");
        return sb.toString();
    }

    /** F-B-06：目录 fsync 的全仓唯一实现挪在 {@link CNArchiveInstallTx#syncDir}
     *  （解压状态文件也需要它），这里只是转调，别复制第二份——两份迟早漂。 */
    private static void syncDir(File dir) {
        CNArchiveInstallTx.syncDir(dir);
    }

    static File txDir(File root, String tag) {
        return new File(new File(root, TX_DIR), tag);
    }

    /** 递归收集 {@code dir} 下所有**文件**的相对路径（用 '/' 分隔）。 */
    private static void collect(File dir, String prefix, List<String> out) throws IOException {
        Deque<Object[]> stack = new ArrayDeque<Object[]>();
        stack.push(new Object[] { dir, prefix });
        while (!stack.isEmpty()) {
            Object[] cur = stack.pop();
            File d = (File) cur[0];
            String p = (String) cur[1];
            File[] kids = d.listFiles();
            if (kids == null) throw new IOException("列不出目录: " + d);
            for (int i = 0; i < kids.length; i++) {
                File k = kids[i];
                String rel = p.isEmpty() ? k.getName() : p + "/" + k.getName();
                if (k.isDirectory()) stack.push(new Object[] { k, rel });
                else out.add(rel);
            }
        }
    }

    /**
     * 把 {@code from} 挪到 {@code to}。同一文件系统内是 rename（原子且不耗 IO）；
     * rename 失败时退回「复制 + 删源」，这样即使将来暂存区被挪到别的挂载点也还能用。
     */
    private static void move(File from, File to) throws IOException {
        if (to.exists() && !deleteTree(to)) {
            throw new IOException("删不掉已存在的目标 " + to);
        }
        if (from.renameTo(to)) return;
        // rename 对目录也有效，退路（复制）却只对文件成立。走到这里说明既跨了
        // 文件系统又碰上目录——与其半途而废留下混合状态，不如直接失败去回滚。
        if (from.isDirectory()) {
            throw new IOException("目录跨文件系统搬不动: " + from + " -> " + to);
        }
        copy(from, to);
        if (!from.delete()) {
            CNLog.w(TAG, "复制成功但删不掉源文件: " + from);
        }
    }

    private static void copy(File from, File to) throws IOException {
        InputStream  in  = null;
        OutputStream out = null;
        try {
            in  = new BufferedInputStream(new FileInputStream(from), 65536);
            FileOutputStream fos = new FileOutputStream(to);
            out = fos;
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            out.flush();
            fos.getFD().sync();
        } finally {
            closeQuietly(out);
            closeQuietly(in);
        }
    }

    /** 写文件并 fsync。事务里只有 journal 与 COMMITTED 走这里，共两次。 */
    private static void writeSynced(File f, String content) throws IOException {
        ensureParent(f);
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
            fos.getFD().sync();
        } finally {
            closeQuietly(fos);
        }
    }

    private static void ensureParent(File f) throws IOException {
        File p = f.getParentFile();
        if (p != null && !p.isDirectory() && !p.mkdirs() && !p.isDirectory()) {
            throw new IOException("建不出目录: " + p);
        }
    }

    /** 递归删除。返回 true 表示删干净了。 */
    private static boolean deleteTree(File f) {
        if (f == null || !f.exists()) return true;
        boolean ok = true;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) ok &= deleteTree(kids[i]);
            }
        }
        return f.delete() && ok;
    }

    private static void deleteQuietly(File f) {
        try { if (f != null) f.delete(); } catch (Throwable ignore) {}
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Throwable ignore) {}
        }
    }
}
