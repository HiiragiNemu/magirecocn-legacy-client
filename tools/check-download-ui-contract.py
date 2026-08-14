#!/usr/bin/env python3
import re
from pathlib import Path

ui = Path("patch/src/main/java/io/kamihama/magianative/CNCNDownloadUI.java").read_text(encoding="utf-8")
assist = Path("patch/src/main/java/io/kamihama/magianative/CNDownloadUiAssist.java").read_text(encoding="utf-8")
hot_check = Path("patch/src/main/java/io/kamihama/magianative/CNHotUpdateCheck.java").read_text(encoding="utf-8")
hot = Path("patch/src/main/java/io/kamihama/magianative/CNHotUpdate.java").read_text(encoding="utf-8")
downloader = Path("patch/src/main/java/io/kamihama/magianative/CNDownloaderFix.java").read_text(encoding="utf-8")
manual = Path("patch/src/main/java/io/kamihama/magianative/CNManualRedownload.java").read_text(encoding="utf-8")
concur = Path("patch/src/main/java/io/kamihama/magianative/CNDownloadConcurrency.java").read_text(encoding="utf-8")
mode = Path("patch/src/main/java/io/kamihama/magianative/CNDownloadMode.java").read_text(encoding="utf-8")
chunk = Path("patch/src/main/java/io/kamihama/magianative/CNChunkedDownload.java").read_text(encoding="utf-8")
log = Path("patch/src/main/java/io/kamihama/magianative/CNLog.java").read_text(encoding="utf-8")
extract_tx = Path("patch/src/main/java/io/kamihama/magianative/CNArchiveInstallTx.java").read_text(encoding="utf-8")
overlay = Path("patch/src/main/java/io/kamihama/magianative/CNDebugOverlay.java").read_text(encoding="utf-8")
zipplan = Path("patch/src/main/java/io/kamihama/magianative/CNZipPlan.java").read_text(encoding="utf-8")
offline = Path("patch/src/main/java/io/kamihama/magianative/CNOfflineImport.java").read_text(encoding="utf-8")
hot_tx = Path("patch/src/main/java/io/kamihama/magianative/CNHotUpdateTx.java").read_text(encoding="utf-8")
hud = Path("patch/src/main/java/io/kamihama/magianative/CNDebugHud.java").read_text(encoding="utf-8")
aria2 = Path("patch/src/main/java/io/kamihama/magianative/CNAria2.java").read_text(encoding="utf-8")
bgm = Path("patch/src/main/java/io/kamihama/magianative/CNBgm.java").read_text(encoding="utf-8")
bgm_gen = Path("tools/convert-bgm.py").read_text(encoding="utf-8")
# AndroidManifest.xml 本身不在仓库里了（2026-08-14 起原包派生文件由 baseline/
# 的 patchset 重建）。这里改读**补丁**，判据也随之变准：我们能负责的是「我们的
# 改动加了什么、没加什么」，至于原包那一侧写了什么，由 apk.tree_fingerprint 钉住。
manifest_patch = Path("baseline/patches/AndroidManifest.xml.patch").read_text(encoding="utf-8")
manifest_added = [l[1:] for l in manifest_patch.splitlines() if l.startswith("+") and not l.startswith("+++")]
manifest_removed = [l[1:] for l in manifest_patch.splitlines() if l.startswith("-") and not l.startswith("---")]


def code_lines(src):
    """去掉注释后的非空代码行。

    本文件的判据是「源码里写没写某段字」，而这个仓库的注释写得比代码还长，
    里头经常**原样引用**被禁掉的写法（例如「⚠ 绝不能设 setTextIsSelectable(true)」）。
    拿整份文本做 `not in` 判断的话，注释会替代码顶罪：把危险写法解释清楚的那条注释
    反而让守卫红灯。凡是「某写法必须不存在」的判据，都过这一层。
    """
    out, in_block = [], False
    for raw in src.splitlines():
        line = raw
        if in_block:
            end = line.find("*/")
            if end < 0:
                continue
            line, in_block = line[end + 2:], False
        while True:
            start = line.find("/*")
            if start < 0:
                break
            end = line.find("*/", start + 2)
            if end < 0:
                line, in_block = line[:start], True
                break
            line = line[:start] + line[end + 2:]
        slash = line.find("//")
        if slash >= 0:
            line = line[:slash]
        line = line.strip()
        if line:
            out.append(line)
    return out


def code(src):
    return "\n".join(code_lines(src))


def body(src, signature):
    """从方法签名那行起，按大括号配平取出方法体（去注释后再数括号）。

    「某方法里必须调到某一句」比「全文里有这一句」强得多：后者在方法被拆开、
    调用被挪走之后照样绿。
    """
    lines = code_lines(src)
    for i, line in enumerate(lines):
        if not line.startswith(signature):
            continue
        depth, out = 0, []
        for cur in lines[i:]:
            out.append(cur)
            depth += cur.count("{") - cur.count("}")
            if depth <= 0 and len(out) > 1:
                break
        return "\n".join(out)
    return ""


def followed_by(src, first, second):
    """`first` 之后紧跟着的下一行代码就是 `second`（中间的注释不算数）。"""
    lines = code_lines(src)
    return any(lines[i] == first and lines[i + 1] == second
               for i in range(len(lines) - 1))


checks = {
    "不再向 decorView 添加独立显示控件": "decor.addView(dock" not in assist and "decor.addView(panel" not in assist,
    "不再平移整个下载浮层": "setTranslationX((panX" not in assist and "setTranslationY((panY" not in assist,
    "中央内容有底部横向滚动容器": "TAG_H_SCROLL" in ui and "HorizontalScrollView mainScroll" in ui,
    "文件列表有右侧纵向滚动条": "TAG_V_SCROLL" in ui and "setScrollbarFadingEnabled(false)" in ui,
    "顶部胶囊在窄屏使用独立横向视口": "HorizontalScrollView topLeftScroll" in ui and "LinearLayout topBar" in ui,
    "确认框宽度按当前屏幕收缩": "adaptiveDialogWidth(panel)" in assist and "widthPixels - dp(v, 40)" in assist,
    "关闭浮层会清理显示控件状态": "CNDownloadUiAssist.onOverlayDetached()" in ui,
    "热更新停留窗口读取手动停留状态": "CNDownloadUiAssist.shouldStayOnPage()" in hot_check,
    "热更新支持显式进入游戏": "CNDownloadUiAssist.consumeLeaveRequest()" in hot_check,
    "首次安装收尾尊重停留按钮": "CNDownloadUiAssist.awaitReleaseIfRequested()" in downloader,
    "教程选择否留在资源页": "CNDownloadUiAssist.setStayOnPage(true)" in ui,
    "红色重试按运行通道路由": "CNManualRedownload.retry(act, index)" in ui,
    "任意 ZIP 重下不要求其它 marker": "requiresOtherMarkersForTest() { return false; }" in manual and "firstInvalidOther" not in manual,
    "不同 ZIP 可并发且同文件去重": "MAX_PARALLEL_FILES = 3" in manual and "RUNNING.compareAndSet(index, 0, 1)" in manual,
    "基础包旧 marker 保留到新包成功": "FORCE_REDOWNLOAD" in downloader and "manual-force-redownload" in downloader,
    "手动逐项补齐后可提交总完成标记": "commitFinalFlagIfComplete" in downloader and "commitFinalFlagIfComplete" in manual,
    "动态热更新不使用基础包 manifest": "usesChunkManifestForHotUpdateForTest() { return false; }" in hot and "true, null" in hot,
    # 上一条只管热更**那一轮**。首次安装器把热更两包排在下载队列最前，走的却是
    # 基础包那条路，于是照样套 manifest.json 的块指纹——而 manifest 只在基础包
    # 整批出包时重算，热更包由流水线单独重发。脱节时块指纹指向上一版，偏偏 size
    # 常常不变（同结构 ZIP 重打包尺寸一致），「清单与文件是否同一身份」那道闸照样
    # 放行，随后每一块都失败，四条线路轮完只剩红条重试（2026-08-13 真机：四个镜像
    # 众口一词给出同一实得值，只有清单对不上）。
    "首次安装器不给热更两包套基础包 manifest":
        "final boolean useManifest = usesChunkManifest(name);" in downloader
        and "useManifest ? ChunkManifest.forFile(name) : null" in downloader
        and "return !CNOfflineImport.isHotUpdateFile(name);" in downloader,
    # 去掉清单不能等于不校验：热更包的权威身份是 version json 的 size + 整包 MD5。
    # 少了这一步就只剩 ZIP 结构预检，而缓存里的旧包结构完好、照样通过——
    # 那正是「下完了却是旧台词」。
    "热更两包按 version json 身份完工校验":
        "verifyHotIdentity" in downloader
        and "CNHotUpdateCheck.metaForSlot(indexOfArchive(name))" in downloader
        and "CNHotUpdateValidate.verifyZip(archive, meta)" in downloader
        and "static CNHotUpdateValidate.VerMeta metaForSlot(int slot)" in hot_check,
    "动态热更新绑定 version-size-md5": "cnv_hot=" in hot and "hotIdentity" in hot and "verifyZip(dest, expected)" in hot,
    "版本 JSON 与 ZIP 均绕过旧 CDN 缓存": "cnv_version=" in hot_check and "Cache-Control" in hot_check and "cnv_hot=" in hot,
    "热更新校验或应用失败会把槽位标红": "markHotFailed(pkg.slot)" in hot_check,
    "部分成功不会谎报全部完成": "summaryPhaseForTest" in hot_check and 'return applied ? "部分更新完成"' in hot_check,
    "下载连接有全局八连接闸门": "CNDownloadConcurrency.acquire" in chunk and "hasQueuedWaiters" in chunk
        and 'CNDownloadConcurrency.acquire("base-single:' in downloader
        and 'CNDownloadConcurrency.acquire(' in hot,
    "活动资源树修改共用提交锁": "extractCommitLock" in downloader and "synchronized (CNDownloaderFix.extractCommitLock())" in hot_check,
    "日志只回收当前进程": '"--pid="' in log and "android.os.Process.myPid()" in log,
    # ── 宽度模型（2026-08-14 重写）──────────────────────────────────
    #
    # 这一层在宽度上翻过两次车，方向相反，判据必须把**两个**坑一起钉住：
    #
    #   第一版：读了自己马上要改的那个 View 的测量宽度 → 反馈环，
    #           「反复拖分界线，左右越变越长」；
    #   第二版：为躲开上面那条，改成 widthPixels − 边距 算死一个像素值，建浮层时
    #           算一次 → 分屏/旋转/inset/padding 任一对不上，两列就按错的总宽分家，
    #           就是「左右宽度解析有大问题」；而且之后屏幕怎么变都不重算。
    #
    # 现在的模型：**读视口（hScroll）、写内容（contentRoot）**。父子关系，父宽由
    # 再上一层决定，不受子节点影响 —— 既没有反馈环，读的又是真实测量值。
    "默认100%内容宽度交给视口而不是算出来的像素":
        "mainScroll.setFillViewport(true)" in ui
        and "mainScroll.addView(mainRow, new FrameLayout.LayoutParams(\n"
            "                ViewGroup.LayoutParams.MATCH_PARENT," in ui
        and "want = ViewGroup.LayoutParams.MATCH_PARENT;" in assist,
    # 反向判据：内容宽度不准再从屏幕分辨率推算，也不准去读被自己改的那个 View。
    "内容宽度只读视口，不读分辨率也不读自己":
        "contentBaseWidthPx" not in code(assist)
        and "contentRoot.getWidth()" not in code(assist)
        and "hs.getWidth() - hs.getPaddingLeft()" in assist,
    # 视口会变（旋转、分屏、折叠屏展开），变了要重算——上一版算一次就不管了。
    "视口变化会重算内容宽度":
        "OnLayoutChangeListener" in assist
        and "addOnLayoutChangeListener" in assist
        and "removeOnLayoutChangeListener" in assist
        and "if ((r - l) == (oldR - oldL)) return;" in assist,
    "横向滚动只在真实溢出时启用": "setHorizontalScrollBarEnabled(overflow)" in assist
        and "if (!overflow) hs.scrollTo(0, 0)" in assist,
    "进度与下载字节单调不回撤": "if (clean > progress[i])" in ui
        and "if (clean > downloaded[i])" in ui,
    "速度由有效进度而非重试流量计算": "AtomicLong usefulBytes" in chunk
        and "currentUseful - lastSpeedBytes" in chunk,
    "03完整ZIP可复用并断点续解压": "CNArchiveInstallTx.extract" in downloader
        and "extract-resume-accept" in extract_tx
        and "Do not delete a complete archive here" in downloader,
    "下载中重下会中止并从头开始": "requestActiveRestart" in downloader
        and "manual-restart-active" in downloader
        and "停止当前传输" in manual,
    # 单线程可靠模式：四处并发必须**全部**过同一个判据 CNDownloadMode.cap()。
    # 漏掉任何一处的表现都是「选了单线程但并发没降下来」——不报错、不崩，
    # 只有翻日志数连接数才发现得了，而那时玩家已经认定这个按钮没用。
    # 四处：分片工作线程、字节分段、全局连接闸门、并行文件数。
    "单线程模式覆盖分片工作线程与字节分段":
        "CNDownloadMode.cap(MAX_NETWORK_WORKERS)" in chunk
        and "CNDownloadMode.cap(MAX_BYTE_SEGMENTS)" in chunk
        and "Math.min(maxWorkers()" in chunk
        and "Math.min(maxSegments()" in chunk,
    "单线程模式覆盖热更新入口": "CNDownloadMode.cap(mirror.effectiveChunks())" in hot,
    "单线程模式覆盖全局连接闸门": "setCap" in concur and "reducePermits" in concur
        and "CNDownloadConcurrency.setCap" in mode,
    "单线程模式覆盖并行文件数": "CNDownloadMode.cap(MAX_PARALLEL_FILES)" in manual
        and "CNManualRedownload.applyMode()" in mode,
    # 断点续传的分段布局**不能**跟着模式变：改了等于把已下好的进度作废，
    # 而玩家恰恰是在「下到一半失败」时切模式的。
    "切模式不作废已有断点": "resume.segments <= MAX_BYTE_SEGMENTS" in chunk,
    # 三层来源缺一不可：调试开关（排查）、云端（全员故障）、玩家（弹窗）
    "单线程有调试开关与云端开关": "USE_SINGLE_THREAD" in mode
        and "forceSingleThread" in mode,
    "下载失败弹窗给得出单线程": "DL_SINGLE" in ui and "改用单线程下载" in ui
        and "CNDownloadMode.setPlayerChoice(true)" in ui,
    "主引擎与热更失败都会问玩家":
        "awaitDownloadFallbackChoice" in downloader
        and "awaitDownloadFallbackChoice" in hot_check,
    # 离线包导入成功后必须**立刻被消费**。离线检查在 installArchive 的开头，而
    # 安装器的 15 文件循环启动时只跑一次——不接这条线，就是「导入了没反应」
    # 和「红条一直重试」两个症状（2026-08-13 真机）。
    "离线包导入后立刻应用": "installOfflineNow" in downloader
        and "applyOfflineAsync" in ui,
    # 「重下」要删离线候选、「用刚导入的包」要留它，同一个清理函数两种语义。
    "离线即时安装不会自删离线候选": "keepOffline" in downloader
        and "cleanupArchiveDownloadState(index, true)" in downloader,
    # 原版里「文字进度」的右端与下面那条整宽进度条的右端对齐，这条竖线是整块的
    # 视觉基准。「重试」只要排在它后面，一出现就把它往左顶，右边界立刻错开
    # ——2026-08-13 真机连报两次。文字进度必须是资源行的最后一个孩子。
    "文字进度是资源行最后一个孩子":
        "info.setTag(CNDownloadUiAssist.TAG_SLOT_INFO)" in ui
        and "headRow.addView(retry, retryLp);" in ui
        and ui.index("headRow.addView(retry, retryLp);") < ui.index("headRow.addView(info, infoLp);"),
    # 磁盘满不能伪装成网络故障。ENOSPC 抛的是普通 IOException，和超时、断流走同一个
    # catch，于是：线路被 reportFailure（线上 switch_after_failures=1，一次就冷却
    # 60 秒）、四次重试逐条线路白烧、玩家对着「重试/备用引擎/单线程/离线包」四个
    # 都不解决问题的选项反复点。最容易撞上的是 cn_base_03.zip——1.3 GiB，队列里第一个
    # 真正的大包，前面五个装完空间峰值正好落在它这里。
    "磁盘满不按线路故障处理":
        "CNDiskSpace.isOutOfSpace" in downloader
        and "catch (CNDiskSpace.NotEnoughSpace e)" in downloader
        and "reportNoSpace" in downloader,
    # 预检要在**知道大小的那一刻**做，不能等写满：探针刚给出长度、以及解压前
    # 由 zip 目录累加出 totalBytes 的那两处。
    "下载与解压都先看装不装得下":
        "long peak = probe.total - partBytes(archive);" in downloader
        and "CNDiskSpace.require(archive, peak, name)" in downloader
        and "CNDiskSpace.require(root, totalBytes - doneBytes" in extract_tx,
    # 安装峰值是 ZIP + 解压后（ZIP 要留到解压成功才删），而这个比例各包差得很远：
    # cn_base_03.zip 1.32→2.79 GiB（2.11x），其余全在 1.02–1.16x。03 因此拥有 15 个包里
    # 最高的安装峰值 4.11 GiB，而进度条上只写着 1.3 GB。只按下载量预检等于把那 2.79 GiB
    # 瞒着玩家——他按 1.3 GB 去清理，然后在解压阶段翻车。
    "预检按安装峰值而不是下载量":
        "CNZipPlan.extractedBytes" in downloader
        and "peak += extract" in downloader,
    # 算不出来必须是「不知道」，不能当 0：当成小数字等于把玩家放进去再翻车。
    "解压后大小算不出时按未知放行":
        "UNKNOWN" in zipplan and "return UNKNOWN;" in zipplan
        and "extract != CNZipPlan.UNKNOWN" in downloader,
    # 膨胀比上限必须留在 200x，且两条解压路径（首次安装器 / 离线导入与热更新）
    # 用同一个数。诱惑在于「游戏资源膨胀比接近 1，收紧一点更安全」——那句话对 14 个包
    # 成立，对 cn_base_03.zip 不成立：它 2.11x（1.32→2.79 GiB），是唯一真正会膨胀的。
    # 收到 2x 以下就等于把它判成 zip 炸弹，每次装到一半整包作废重下。
    "解压膨胀比上限保持 200x":
        "EXTRACT_MAX_RATIO = 200L" in extract_tx
        and "EXTRACT_MIN_BYTES_BEFORE_RATIO = 256L * 1024 * 1024" in extract_tx,
    # 依据要跟着阈值走：只留一个数字，下一个人还是会照「接近 1」去拍。
    "膨胀比阈值旁边留着实测表":
        "2.11x" in extract_tx and "cn_base_03.zip" in extract_tx,
    # COLOR_* 全是无初始值的 static int，默认 0 = #00000000 全透明。调试悬浮窗
    # 反射读它们取色，读到 0 就把文字画成透明——面板上开关名、说明、「已激活」
    # 标签全消失，只剩硬编码白色的主按钮还在，且时有时无（取决于这次启动有没有
    # 建过下载浮层）。两道：类加载时兜底 + 取色时把 alpha=0 当「没取到」。
    # aria2 曾是条平行宇宙：线上 force_aria2=true，也就是**每个玩家的每个文件都先走
    # 它**，而主引擎那边的验收与策略它一条都不过——分块清单、热更身份、空间预检、
    # 单线程模式、逐轮换线，全绕开。排查下载问题时只盯主引擎日志，看到的根本不是
    # 玩家实际走的那条路（2026-08-13）。四条一并并线，并在此钉住。
    "aria2 连接数过单线程判据":
        "CNDownloadMode.cap(16)" in downloader
        and "CNAria2.download(url, FILE_ROOT, name," in downloader
        and "null, null, conns, null, progress, cancel)" in downloader,
    # 完整换线机制，不是简化版：逐轮 pick(attempt) + 成败都回报 CNMirrors 健康表
    # （失败记冷却、成功清计数），尝试次数与主引擎对齐好让线路表轮得完。原先固定
    # pick(1) 且从不回报——线路有多不行，健康表一无所知，主引擎回退后照样先挑它。
    "aria2 接入完整换线机制":
        "CNMirrors.pick(attempt)" in downloader
        and "tryAria2Download(CNMirrors.pick(1)" not in downloader
        and 'CNMirrors.reportFailure(mirror, "aria2 code=" + rv)' in downloader
        and "A2_MAX_ATTEMPTS = 4" in downloader
        and downloader.count("CNMirrors.reportSuccess(mirror)") >= 2,
    # 热更两包在**同一个 URL** 上被反复重发，CDN 各节点因此可能同时存在好几个版本。
    # 热更轮一直靠 cnv_hot=<version-size-md5> 把它们隔开，安装器与 aria2 这两条路
    # 却一次都没加——于是可能下到旧副本：主引擎那边校验必然不过（换线、再下、再
    # 不过，四轮 740 MiB 全白费），aria2 那边则是装上旧台词、等热更轮再下一遍。
    # 「scenario_update 特别容易下载失败」的最后一个诱因就是它。
    "热更两包按本轮身份取（三条路同一个格式）":
        "CNHotUpdate.withIdentity(mirror.urlFor(name), hotMeta)" in downloader
        and "CNHotUpdate.withIdentity(mirror.urlFor(name), a2Meta)" in downloader
        and "static String withIdentity(String url, CNHotUpdateValidate.VerMeta meta)" in hot,
    # 下哪个与校验哪个必须是**同一份** meta：分两次取会在重发的瞬间撞上不一致，
    # 表现为「刚下完就说身份不对」。
    "取包与校验复用同一份身份":
        "verifyHotIdentity(name, archive, hotMeta)" in downloader
        and "CNHotUpdateValidate.VerMeta hotMeta = useManifest ? null" in downloader,
    # 维护者决定（2026-08-13）：aria2 模式下不叠加额外内容校验，判据是 ZIP 自带的
    # 完整性。所以这里是**反向**断言——这条路上不许再冒出 manifest 块指纹或热更
    # version json 的比对。代价写在代码注释里：拦得住「没下全」，拦不住「下全了但
    # 是旧的」，后者交给随后的热更新轮按版本号发现。
    # 反向断言：aria2 那条路上不许出现 verifyHotIdentity。两处调用都在 fetchArchive
    # （主引擎的分片路径与单线程路径各一处），aria2 分支一处都不该有。
    "aria2 模式旁路额外内容校验":
        downloader.count("verifyHotIdentity(name, archive, hotMeta)") == 2
        and "verifyHotIdentity(name, archive, a2Meta)" not in downloader
        and "isAria2ArchiveUsable(archive, name)" in downloader,
    # 上一条与这一条是一对，必须一起读。
    #
    # 「旁路内容校验」是维护者的决定，前提是**传输层还认证着**。而 aria2 原先在
    # 拼不出 CA 桶时会下发 check-certificate=false，那条兜底当初的理由正是
    # 「内容层还有独立防线」——两次改动各自都说得通，合起来是：传输不认证 +
    # 内容不认证。中间人可以整包替换，而 cn_js_update.zip 装的是 WebView 里跑的
    # 前端脚本，那已经不是「资源坏了」而是在玩家设备上执行攻击者的代码。
    #
    # 判据因此钉死：aria2 里不准出现关闭证书校验的写法，拿不到 CA 桶只能返回
    # ERR_INIT 回退主引擎（那条路 OkHttp 做完整 TLS 验证，功能一点不少）。
    "aria2 绝不关闭证书校验":
        '"check-certificate"' not in code(aria2)
        and 'opt.put("ca-certificate"' in aria2
        and "return ERR_INIT;" in aria2
        and "SecureRandom" in aria2,
    "aria2 解压走同一套事务（带空间预检与逐条目校验）":
        "CNArchiveInstallTx.extract(archive, new File(INSTALL_ROOT)," in downloader
        and "a2State" in downloader,
    "aria2 路径上的空间不足也不当引擎故障":
        "catch (CNDiskSpace.NotEnoughSpace e)" in downloader
        and downloader.count("reportNoSpace") >= 4,
    # 全仓只此一套解压实现（2026-08-13 收敛）。原先 extractChecked 与
    # CNArchiveInstallTx.extract 并存，两者的膨胀比防护**时机不同**：前者边写边看，
    # 后者读到 EOF 才比 size。naive 合并会悄悄丢掉前者那道——而一个谎报未压缩长度的
    # 包正是靠它拦住的。合并时把两道都留下了，这里钉住。
    "解压实现只此一套":
        "extractChecked" not in downloader
        and "CNDownloaderFix.extractChecked" not in hot_tx,
    "膨胀比两道防护都在（声明侧 + 边写边看）":
        "totalBytes / archive.length() > EXTRACT_MAX_RATIO" in extract_tx
        and "copied + n > declared" in extract_tx
        and "writtenThisRun + n > archiveBytes * EXTRACT_MAX_RATIO" in extract_tx,
    # 判据必须在 write 之前：写完再拒等于「炸弹已经落地，事后宣布它不该落地」。
    "膨胀比判据在写出去之前":
        extract_tx.index("copied + n > declared")
            < extract_tx.index("output.write(buf, 0, n);"),
    # 离线包是玩家从网盘下了一两个 G 再手动导入的。原先任何 Throwable 都删它并
    # 回退网络下载——磁盘满也删。删完接着走网络，只会以同样的方式再失败一次，而他
    # 得从头再下一遍。只有 ZipException（包真坏）才该删。
    "离线包只在 ZIP 真坏时才删":
        "offline-zip-bad" in downloader
        and "catch (ZipException e)" in downloader
        and "保留离线包，下轮重试" in downloader,
    "离线解压走同一套事务":
        'CNArchiveInstallTx.stateFile(\n                        new File(STATE_ROOT), name + ".offline")' in downloader,
    # 导入是「再拷一份」：不预检就会拷到最后几十兆才 ENOSPC，前面几十分钟白费，
    # 半截文件还留在盘上没人删（动辄一两个 G）。
    "离线导入先预检空间且清理半截产物":
        "CNDiskSpace.require(dir, need" in offline
        and "sweepStaleTemps" in offline
        and "deleteQuietly(tmp);\n            throw t;" in offline,
    # CNLog 的「有新行了」回调是单槽位，下载浮层 LOG 面板与悬浮窗日志页都要占。
    # 后者用完置 null 的话，前者的实时刷新这一整个会话都恢复不了。
    "日志监听器离场原样归还而不是置 null":
        "public static Runnable setListener(Runnable r)" in log
        and "prevLogListener" in overlay
        and "CNLog.setListener(prevLogListener)" in overlay,
    # 玩家的选择压过云端，且**不落盘**（2026-08-13 维护者要求）。求或的写法会让
    # 云端一开玩家就再也关不掉，而坐在设备前面的是他；落盘则是「一次慢次次慢」——
    # 他为一次网络抖动选的降级路线会跟着他到永远，还没有任何地方提示他开着。
    "单线程优先级：调试 > 玩家 > 云端":
        "Boolean p = playerChoice;" in mode
        and "if (p != null) return p.booleanValue();" in mode
        and "return CNMirrors.forceSingleThread();" in mode,
    "玩家的单线程选择不落盘":
        "cn_single_thread.flag" not in mode and "FLAG_PATH" not in mode
        and "playerDecided" in mode,
    "云端不再算玩家关不掉的强制层":
        "CNMirrors.forceSingleThread()" not in mode.split("public static boolean forcedOn()")[1].split("}")[0],
    # 「重下」胶囊已按维护者要求整体撤回（2026-08-13）。这是一条**反向**断言：
    # 谁再把它加回来，CI 当场红。撤回的理由不是实现有 bug，是维护者不要这个特性
    # ——判据因此钉在「不存在」，而不是「实现得对不对」。
    "不再有单包重下胶囊":
        "installReloads" not in assist and "TAG_RELOAD" not in assist
        and "ReloadClick" not in assist,
    # 分界线拖动同日先撤后加：撤是维护者不要它，加回来也是维护者要的。判据于是
    # 从「不存在」翻回「存在且接对了」——而「接对了」有两条，都踩过：
    #   1. 长按才进拖动态，进去之后要把手势从 HorizontalScrollView 手里要过来
    #      （不要的话横向一动就被滚动吃掉，表现为「长按了也拖不动」）；
    #   2. 建浮层时的列宽直接取玩家存的比例，不能写死 0.38f/0.62f——浮层会被重建
    #      （切主题、看门狗发现它掉出视图树），写死的话每次重建都先闪回默认比例，
    #      玩家看到的就是「刷新一下比例被重置了」。
    "左右分界线可长按拖动":
        "SplitDrag" in assist and "TAG_SPLIT" in assist
        and "setOnLongClickListener" in assist
        and "requestDisallowInterceptTouchEvent" in assist,
    # 🔴 两列必须拿**精确像素**，不准靠 weight。
    #
    # 它们装在 HorizontalScrollView 里，而框架那两段凑一起会把 weight 布局毁掉：
    #   HorizontalScrollView.measureChild 无视子节点 lp.width，一律 UNSPECIFIED；
    #   LinearLayout.measureHorizontal 在父不是 EXACTLY 时，把 `width=0 + weight>0`
    #   的 lp.width **就地改写成 WRAP_CONTENT**。
    # 于是 fillViewport 之后那一遍 EXACTLY 测量里 lp.width 已经不是 0，weight 只
    # 分配「各列按内容撑开之后剩下的那点空间」——38/62 从此不成立，列宽变成
    # 「内容想要多宽 + 剩余空间的加权零头」。这个坑跨过两版宽度模型都没被修掉，
    # 因为两版都经由同一个 UNSPECIFIED。
    "两列拿精确像素宽而不是 weight":
        "private static void setExactWidth(View v, int px)" in assist
        and "lp.weight = 0f;" in assist
        and "setExactWidth(left, lw);" in assist
        and "setExactWidth(right, usable - lw);" in assist
        and "lp.weight = weight;" not in code(assist),
    "列宽换算有纯算术入口且两头都夹得住":
        "static int splitLeftPx(int content, int handleW, int pct)" in assist
        and "static int usableWidth(int content, int handleW)" in assist
        and "clamp(Math.round(usable * pct / 100f), 1, usable - 1)" in assist,
    "分界比例夹在可用范围内并落盘":
        "SPLIT_MIN = 20" in assist and "SPLIT_MAX = 70" in assist
        and "clamp(value, SPLIT_MIN, SPLIT_MAX)" in assist
        and "PREF_SPLIT" in assist,
    "浮层建出来就是玩家调好的比例":
        "CNDownloadUiAssist.leftWeight(act)" in ui
        and "CNDownloadUiAssist.rightWeight(act)" in ui
        and "0.38f" not in code(ui) and "0.62f" not in code(ui),
    "调色板在类加载时就有值":
        "static { loadPalette(false); }" in ui and "ensurePalette" in ui,
    "取色把全透明当成没取到":
        "(v >>> 24) == 0 ? fallback : v" in overlay
        and "CNCNDownloadUI.ensurePalette(act)" in overlay,
    # 悬浮窗的日志预览：原先是固定 220dp 高的裸 TextView 直接 setText——没有
    # MovementMethod 就没有内部滚动，外层 ScrollView 滚的是整页不是这个框，于是
    # 超出高度的内容既滚不到也不会随新行走，能看见的只有最早那十几行。
    # 一页只留一个滚动容器。上一版在固定 220dp 的预览框上又套了个 ScrollView，
    # 结果页面滚动容器与它抢同一个竖直手势，吸底吸的是内层、玩家看到的是外层
    # 没动（2026-08-13 反馈「内外两个滑动条相互打架，吸底依旧不管用」）。
    "悬浮窗日志页只有一个滚动容器且吸底吸它":
        "logtailscroll" not in overlay
        and '"pagescroll"' in overlay and "stickToBottom" in overlay
        and "fullScroll(View.FOCUS_DOWN)" in overlay and "isAtBottom" in overlay,
    # 解析器与下载浮层那块共用 CNLogFormat，不另写一份。
    "悬浮窗日志预览走同一个解析器":
        "CNLogFormat.parse(r.src, r.text)" in overlay
        and "CNLog.tailRows(200)" in overlay,
    # 权限引导页的宿主固定 decorView，靠布局回调持续置顶。曾按「下载浮层在就挂
    # 进浮层」选宿主，可它由挂载看门狗在 Activity 出现后几毫秒触发，那时下载浮层
    # 还没建出来——判断永远走 decorView，几百毫秒后浮层加进同一个 decorView 把它
    # 盖住，玩家看到的还是「什么都没发生」。
    "权限引导页随布局持续置顶":
        "keepGuideOnTop" in overlay
        and "OnGlobalLayoutListener" in overlay
        and "removeOnGlobalLayoutListener" in overlay,
    # 授权没有截止时间，轮询也不该有：原先 5 秒 × 120 之后彻底停下，玩家在系统
    # 设置里慢一步回来就永远等不到小球，日志里只有一句「等待超时」。
    "等待悬浮窗权限的轮询不会彻底停下":
        "PERM_POLL_SLOW_MS" in overlay and "PERM_POLL_MAX" not in overlay,
    # SYSTEM_ALERT_WINDOW 是**原包自带**的权限，不是我们加的。9688f7e7 把它连同
    # MANAGE_EXTERNAL_STORAGE 一起删掉，理由写作「移除无用的悬浮窗权限」，并在这里
    # 立了一条「不许回来」的断言——而维护者对这条改动**完全不知情**。
    #
    # 它不是无用的：调试悬浮窗（docs/DEBUG_FLOATING_WINDOW_DESIGN.md）正是靠它挂
    # WindowManager 窗口，而那个窗口要解决的恰恰是「群友不会用 Termux 改 flag」——
    # 移除 android:debuggable 就是因为这条路送不到人。删掉权限等于把唯一够得着的
    # 入口也一并堵死。
    #
    # 断言因此**反过来**：这个权限必须在。上一次它是被静默删掉的，那种改动人眼复查
    # 拦不住，所以钉在这里——谁再删，CI 当场红灯。
    # ---- 2026-08-13 的九项修复，逐条钉住判据 ----
    # 1. 「按分辨率推荐字号」原先拿 560dp 当参考、斜率取 1，于是几乎所有设备都被
    #    推到 115%–150%。而 dp = px/density，720p 低密度手机报出的 dp（797）比
    #    1080p 手机（642）还多——dp 宽度根本不是屏幕大小的代理，方向都可能是反的。
    #    参考值抬到 720dp、斜率取半、结果夹在 85–125：一个「推荐」必须真的在区分
    #    设备，而不是对所有人都喊最大值。
    "字号建议用半速率并夹在窄量程内":
        "DESIGN_WIDTH_DP = 720f" in assist
        and "SUGGEST_SLOPE = 0.5f" in assist
        and "SUGGEST_MIN = 85" in assist and "SUGGEST_MAX = 125" in assist
        and "delta * SUGGEST_SLOPE * 100f" in assist,
    # 2. 浮层总进度条比它上面那行字长出一截：文字行贴着 slotScroll 的 5dp 滚动条
    #    留白，进度条却是满宽。两者必须用同一个 inset，否则右端永远差 5dp。
    # 右端对齐的三处必须**同源**：文件列表靠右 padding 让出滚动条槽位，而它下面
    # 那行文字进度与总进度条不在同一个滚动容器里，得用同一个数做右边距才对得齐。
    # 原先三处各写 dp(act, 5)，谁改一处另外两处就错开——而错 1dp 都看得出来。
    # 顺带：5dp 比滚动条本身（6dp）还窄，条必然压在字上，所以这个数抬到了 10dp。
    "滚动条槽宽三处同源":
        ui.count("dp(act, CNDownloadUiAssist.SCROLLBAR_GUTTER_DP)") >= 4
        and "SCROLLBAR_GUTTER_DP = 10" in assist
        and "dp(hs, SCROLLBAR_GUTTER_DP)" in assist
        and "dp(vs, SCROLLBAR_GUTTER_DP)" in assist,
    "槽宽必须宽过滚动条本身":
        "d.setSize(dp(v, 6), dp(v, 6))" in assist
        and int(re.search(r"SCROLLBAR_GUTTER_DP = (\d+)", assist).group(1)) > 6,
    # 3. 热更新检查完就跳走，玩家来不及看清结果（尤其失败时）。停留窗口拉长；
    #    上限 PLAYER_WINDOW_MAX_MS 不动，手动「停留」按钮仍是唯一的无限期通道。
    "热更新结果停留时间足够看清":
        "IDLE_LINGER_MS = 9000L" in hot_check
        and "INTERACT_LINGER_MS = 12000L" in hot_check,
    # 4. 调大字号会把左右两栏撑大，再调小回不去——scrollX 停在旧内容宽度上，
    #    栏宽由权重算但滚动位置没归位。applyScale 收尾必须重新布局并复位滚动条。
    # 改字号 → 缩内容 → 重算宽度 → 复位滚动状态，四步缺一不可。少了最后一步的
    # 表现是「调大字号后两栏被撑大，再调小就回不去」：内容宽度确实缩回去了，但
    # scrollX 还停在原处，而滚动条又已按「没溢出」关掉。
    "改字号后重新布局并复位滚动状态":
        followed_by(assist, "applyContentScale(root);", "applyWidth();")
        and "styleScrollbars();" in body(assist, "private static void applyWidth()"),
    # 5. 日志预览曾用 setTextIsSelectable(true)——那会顺带装上 ArrowKeyMovementMethod，
    #    把 TextView 变成一个吃触摸的滚动器，于是它和外层页面滚动容器抢同一个竖直
    #    手势：吸底吸的是内层，玩家看到的是外层没动。一页只留一个滚动容器。
    "日志预览不自带滚动器":
        "tail.setTextIsSelectable(false)" in overlay
        and "tail.setMovementMethod(null)" in overlay
        and "setTextIsSelectable(true)" not in code(overlay),
    # 6+7. 面板重开会把内存里改了还没保存的开关冲掉（改半天关一次全没了），
    #    且总览页不提示「有未应用的开关」。两种 pending 语义不同，不能压成一个：
    #    未应用 = 内存 ≠ 磁盘（还没保存）；待重启 = 磁盘 ≠ 启动值（保存了没生效）。
    "未应用的开关改动跨面板重开保留":
        "boolean keep = dirty();" in overlay
        and "if (keep)" in overlay
        and "resetDesiredFromDisk();" in overlay,
    "总览同时提示未应用与待重启":
        "int unapplied = countUnapplied();" in overlay
        and "static int countUnapplied()" in overlay
        and "static boolean dirty()" in overlay,
    # 8. 总览页只能进分组才有重启按钮，改完开关的人在最外层找不到出口。
    "开关总览页自带应用与丢弃按钮":
        "DiscardClick" in overlay and "class ApplyClick" in overlay,
    # 9. 【设计缺陷】「调试模式已开」那行字原先也是 WindowManager 悬浮窗，于是同时
    #    被 native 总闸和「显示在其他应用上层」权限挡着。可开关本身是**读文件**生效
    #    的，不依赖悬浮窗——某人开了调试开关又回收了悬浮窗权限，开关照旧生效、提示
    #    却没了，恰好在最需要它的时候失效。改挂 decorView（应用自己的窗口，零权限），
    #    并且**不看总闸**：总闸管「能不能改开关」，「有开关正在生效就得说出来」与之无关。
    "调试提示条不要悬浮窗权限也不看总闸":
        "gatedByOverlayForTest() { return false; }" in hud
        and "decor.addView(tv, lp)" in hud
        and "WindowManager" not in code(hud)
        and "overlayGate" not in code(hud),
    "调试提示条已彻底移出悬浮窗":
        "hudView" not in code(overlay) and "createHud" not in code(overlay)
        and "refreshHud" not in code(overlay) and "CNDebugHud.refresh()" in overlay,
    # 挂载顺序也是判据的一部分：先无条件挂提示条，再去问总闸。反过来写的话
    # 「总闸问不到」这一支会顺带把提示条也吞掉，等于把缺陷原样搬了个家。
    "提示条的挂载早于并独立于总闸":
        "CNDebugHud.mount(act);" in downloader
        and downloader.index("CNDebugHud.mount(act);")
            < downloader.index("Boolean gate = CNDebugBridge.overlayGate();"),
    # BGM 胶囊的曲名：编号 → 曲名。编号在 convert-bgm.py 的 TRACKS 里**显式写死**
    # （不是按文件排序推的），所以按编号绑安全；但两张表得一样长，否则加了曲子而
    # 曲名表没跟上，界面就会悄悄少报一首的名字。
    "曲名表与曲目表同长":
        len(re.findall(r'^\s*\(\d+,\s*"', bgm_gen, re.M))
            == len(re.findall(r'^\s{12}"[^"]+",', bgm, re.M)) > 0,
    # 1 号最容易被写成「ごまかし」——那是 TV 动画 OP，同样四假名同样 TrySail，
    # 但不是手游主线主题曲。反向钉住。
    "曲名没把动画 OP 当成手游主题曲":
        '"かかわり"' in bgm and '"うつろい"' in bgm
        and "ごまかし" not in code(bgm),
    # 订正（2026-08-14）：这条原先叫「原包自带的悬浮窗权限必须保留」，是错的
    # ——看补丁就知道 SYSTEM_ALERT_WINDOW 是**我们加的**，原包没有。浮层要靠它，
    # 所以判据是「我们的补丁必须加上它」，不是「别把它删了」。
    "悬浮窗权限由我们的补丁加上":
        any("SYSTEM_ALERT_WINDOW" in l for l in manifest_added),
    "不主动申请全盘存储权限":
        not any("MANAGE_EXTERNAL_STORAGE" in l for l in manifest_added),
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("✓ " if ok else "✗ ") + name)
if failed:
    raise SystemExit("下载/热更新/UI 合同失败: " + "；".join(failed))
