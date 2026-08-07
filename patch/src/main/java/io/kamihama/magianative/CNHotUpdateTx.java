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
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 热更包的事务化应用：解压到暂存区 → 备份旧文件 → 换入 → 出错整体回滚。
 *
 * <h3>要解决的问题</h3>
 *
 * 原本热更是「下载 → {@link CNDownloaderFix#extractChecked} 直接解压覆盖」。
 * {@code extractChecked} 是**逐条目往活动目录树上写**的，中途失败（磁盘满、
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
 * 总共两次 fsync（journal 一次、COMMITTED 一次）。
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
        File tx     = txDir(root, tag);
        File stage  = new File(tx, STAGE);
        File backup = new File(tx, BACKUP);
        File journal = new File(tx, JOURNAL);

        // 上一轮留下的残骸先按恢复流程处理掉，别把它的 backup 当成本轮的
        if (tx.exists()) {
            CNLog.w(TAG, "[" + tag + "] 发现上一轮未收尾的事务，先恢复");
            recoverOne(root, tx);
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
            CNDownloaderFix.extractChecked(archive, stage);

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
            for (int i = 0; i < rels.size(); i++) {
                String rel = rels.get(i);
                boolean existed = new File(root, rel).exists();
                plan.add(new Entry(rel, existed, false));
                sb.append(existed ? '1' : '0').append('+').append('\t').append(rel).append('\n');
            }
            for (int i = 0; i < orphans.size(); i++) {
                // 孤儿一定是活动树上现存的文件（findOrphans 已经 stat 过），
                // 所以 existed 恒为 1：它会被备份走，回滚时原样挪回来。
                plan.add(new Entry(orphans.get(i), true, true));
                sb.append('1').append('-').append('\t').append(orphans.get(i)).append('\n');
            }
            writeSynced(journal, sb.toString());
            CNLog.i(TAG, "[" + tag + "] 提交计划已落盘：写入 " + rels.size() + " 个（覆盖 "
                    + countExisting(plan) + " 个），清理孤儿 " + orphans.size() + " 个");

            // ---- 阶段三：提交 ----
            commit(root, stage, backup, plan, tag);
            writeSynced(new File(tx, COMMITTED), "ok\n");
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

    /** 逐个换入：旧文件进 backup，暂存文件到位。任何一步失败都往上抛，由调用方回滚。 */
    private static void commit(File root, File stage, File backup,
                               List<Entry> plan, String tag) throws IOException {
        for (int i = 0; i < plan.size(); i++) {
            Entry en = plan.get(i);
            File live = new File(root, en.rel);
            File from = new File(stage, en.rel);
            if (en.existed) {
                File to = new File(backup, en.rel);
                ensureParent(to);
                move(live, to);
            }
            if (en.remove) continue;   // 孤儿：备份完就没了，没有 stage 文件要换入
            ensureParent(live);
            move(from, live);
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
                if (name.length() == 0) {
                    throw new ZipException("包内有空文件名");
                }
                if (name.startsWith("/")) {
                    throw new ZipException("包内有绝对路径: " + name);
                }
                if (name.indexOf(':') >= 0) {
                    throw new ZipException("包内路径含盘符/协议分隔符: " + name);
                }
                String[] segs = name.split("/", -1);
                for (int i = 0; i < segs.length; i++) {
                    if (segs[i].equals("..")) {
                        throw new ZipException("包内路径试图向上穿越: " + name);
                    }
                    if (segs[i].length() == 0 && i != segs.length - 1) {
                        throw new ZipException("包内路径有空目录段: " + name);
                    }
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
        for (int i = 0; i < prev.size(); i++) {
            String rel = prev.get(i);
            if (current.contains(rel)) continue;
            boolean allowed = false;
            for (int j = 0; j < prefixes.length; j++) {
                if (rel.startsWith(prefixes[j])) { allowed = true; break; }
            }
            if (!allowed) continue;
            File live = new File(root, rel);
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

    /** 处理单个残留事务目录：有 COMMITTED 向前滚，否则向后滚。 */
    private static void recoverOne(File root, File tx) {
        String tag = tx.getName();
        if (new File(tx, COMMITTED).isFile()) {
            // 内容已经完整换入，只是没来得及清理。版本号可能没写上——
            // 那只会导致下次重下重应用同样的内容，幂等，无害。
            CNLog.i(TAG, "[" + tag + "] 事务已提交完成，清理工作区");
            deleteTree(tx);
            return;
        }
        CNLog.w(TAG, "[" + tag + "] 事务未提交完成，回滚");
        if (rollback(root, tx, new File(tx, JOURNAL))) {
            deleteTree(tx);
            CNLog.i(TAG, "[" + tag + "] 已回滚");
        } else {
            CNLog.e(TAG, "[" + tag + "] 回滚未能完成，工作区保留: " + tx);
        }
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
