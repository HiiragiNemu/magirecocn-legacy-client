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
