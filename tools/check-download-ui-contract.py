#!/usr/bin/env python3
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
manifest = Path("AndroidManifest.xml").read_text(encoding="utf-8")

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
        and "CNHotUpdateCheck.metaForSlot(slot)" in downloader
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
    "默认100%内容宽度等于视口": "int contentBaseWidth = Math.max(1" in ui
        and "scalePct <= 100 ? viewport" in assist,
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
    "解压膨胀比上限保持 200x 且两处一致":
        "EXTRACT_MAX_RATIO = 200L" in downloader
        and "totalBytes / archive.length() > 200L" in extract_tx,
    # 依据要跟着阈值走：只留一个数字，下一个人还是会照「接近 1」去拍。
    "膨胀比阈值旁边留着实测表":
        "2.11x" in downloader and "cn_base_03.zip" in downloader
        and "2.11x" in extract_tx,
    # COLOR_* 全是无初始值的 static int，默认 0 = #00000000 全透明。调试悬浮窗
    # 反射读它们取色，读到 0 就把文字画成透明——面板上开关名、说明、「已激活」
    # 标签全消失，只剩硬编码白色的主按钮还在，且时有时无（取决于这次启动有没有
    # 建过下载浮层）。两道：类加载时兜底 + 取色时把 alpha=0 当「没取到」。
    # 「重下」胶囊与左右分界线拖动已按维护者要求整体撤回（2026-08-13）。下面两条是
    # **反向**断言：谁再把它们加回来，CI 当场红。撤回的理由不是实现有 bug，是维护者
    # 不要这两个特性——判据因此钉在「不存在」，而不是「实现得对不对」。
    "不再有单包重下胶囊":
        "installReloads" not in assist and "TAG_RELOAD" not in assist
        and "ReloadClick" not in assist,
    "不再有左右分界线拖动":
        "SplitDrag" not in assist and "TAG_SPLIT" not in assist
        and "splitPct" not in assist
        and "0.38f" in ui and "0.62f" in ui,
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
    "原包自带的悬浮窗权限必须保留": "SYSTEM_ALERT_WINDOW" in manifest,
    "不主动申请全盘存储权限": "MANAGE_EXTERNAL_STORAGE" not in manifest,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("✓ " if ok else "✗ ") + name)
if failed:
    raise SystemExit("下载/热更新/UI 合同失败: " + "；".join(failed))
