#!/usr/bin/env python3
from pathlib import Path

ui = Path("patch/src/main/java/io/kamihama/magianative/CNCNDownloadUI.java").read_text(encoding="utf-8")
assist = Path("patch/src/main/java/io/kamihama/magianative/CNDownloadUiAssist.java").read_text(encoding="utf-8")
hot = Path("patch/src/main/java/io/kamihama/magianative/CNHotUpdateCheck.java").read_text(encoding="utf-8")
downloader = Path("patch/src/main/java/io/kamihama/magianative/CNDownloaderFix.java").read_text(encoding="utf-8")
manual = Path("patch/src/main/java/io/kamihama/magianative/CNManualRedownload.java").read_text(encoding="utf-8")

checks = {
    "不再向 decorView 添加独立显示控件": "decor.addView(dock" not in assist and "decor.addView(panel" not in assist,
    "不再平移整个下载浮层": "setTranslationX((panX" not in assist and "setTranslationY((panY" not in assist,
    "中央内容有底部横向滚动容器": "TAG_H_SCROLL" in ui and "HorizontalScrollView mainScroll" in ui,
    "文件列表有右侧纵向滚动条": "TAG_V_SCROLL" in ui and "setScrollbarFadingEnabled(false)" in ui,
    "关闭浮层会清理显示控件状态": "CNDownloadUiAssist.onOverlayDetached()" in ui,
    "热更新停留窗口读取手动停留状态": "CNDownloadUiAssist.shouldStayOnPage()" in hot,
    "热更新支持显式进入游戏": "CNDownloadUiAssist.consumeLeaveRequest()" in hot,
    "首次安装收尾尊重停留按钮": "CNDownloadUiAssist.awaitReleaseIfRequested()" in downloader,
    "教程选择否留在资源页": "CNDownloadUiAssist.setStayOnPage(true)" in ui,
    "每个文件可触发指定重下载": "CNManualRedownload.request(act, index)" in assist,
    "热更新包重下载会重置版本状态": "resetHotUpdateVersion" in manual and "scenario_version" in manual and "js_version" in manual,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("✓ " if ok else "✗ ") + name)
if failed:
    raise SystemExit("下载 UI 合同失败: " + "；".join(failed))
