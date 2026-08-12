#!/usr/bin/env python3
"""一次性源码迁移：把“停留本页”接入真实的安装/热更收尾路径。"""
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"{label}: expected baseline not found")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


hot = Path("patch/src/main/java/io/kamihama/magianative/CNHotUpdateCheck.java")
replace_once(
    hot,
    '            CNCNDownloadUI.updateSimple("已是最新",\n'
    '                    "即将进入游戏；点按浮层（如「教程」胶囊播序章）可稍作停留", 0);',
    '            CNCNDownloadUI.updateSimple("已是最新",\n'
    '                    "检查已完成。可点「停留本页」继续查看，或等待进入游戏。", 0);',
    "hot-update linger text",
)
replace_once(
    hot,
    "        awaitPlayerWindow();\n"
    "        awaitConfigSettled();\n"
    "        // running 要在浮层收掉之前清掉：之后再点胶囊（浮层还在的最后一刻）",
    "        awaitPlayerWindow();\n"
    "        awaitConfigSettled();\n"
    "        // 配置到位的短等待期间玩家仍可能点‘停留本页’；收浮层前再做一次\n"
    "        // 无上限的显式停留闸。只有玩家自己点‘进入游戏’才释放。\n"
    "        awaitExplicitStayRelease();\n"
    "        // running 要在浮层收掉之前清掉：之后再点胶囊（浮层还在的最后一刻）",
    "hot-update completion path",
)
replace_once(
    hot,
    "                long now = android.os.SystemClock.uptimeMillis();\n"
    "                long lastTouch = CNCNDownloadUI.lastInteractionMs();",
    "                long now = android.os.SystemClock.uptimeMillis();\n"
    "                // ‘停留本页’是玩家明确选择，不受 120 秒自动窗口上限约束。\n"
    "                // 这里直接阻断收浮层与进入游戏，而不是隐藏后重新盖回浮层。\n"
    "                if (CNDownloadUiAssist.shouldStayOnPage()) {\n"
    "                    Thread.sleep(100);\n"
    "                    continue;\n"
    "                }\n"
    "                long lastTouch = CNCNDownloadUI.lastInteractionMs();",
    "awaitPlayerWindow loop",
)
replace_once(
    hot,
    "    /** 收浮层前等 config.json 到位，只等「还在加载」这一种状态。 */\n"
    "    private static void awaitConfigSettled() {",
    "    /** 显式停留没有自动超时；按钮切回‘进入游戏’后才继续完成启动。 */\n"
    "    private static void awaitExplicitStayRelease() {\n"
    "        try {\n"
    "            while (CNDownloadUiAssist.shouldStayOnPage()) Thread.sleep(100L);\n"
    "        } catch (InterruptedException ie) {\n"
    "            Thread.currentThread().interrupt();\n"
    "        }\n"
    "    }\n\n"
    "    /** 收浮层前等 config.json 到位，只等「还在加载」这一种状态。 */\n"
    "    private static void awaitConfigSettled() {",
    "explicit stay helper",
)

installer = Path("patch/src/main/java/io/kamihama/magianative/CNDownloaderFix.java")
replace_once(
    installer,
    "            awaitTutorialChoice();\n"
    "            CNCNDownloadUI.hide();",
    "            awaitTutorialChoice();\n"
    "            // 首次安装与手动单包重下载完成后也尊重‘停留本页’。资源已经提交，\n"
    "            // 但在玩家点‘进入游戏’前不收浮层、不执行必须的进程重启。\n"
    "            try {\n"
    "                while (CNDownloadUiAssist.shouldStayOnPage()) Thread.sleep(100L);\n"
    "            } catch (InterruptedException ie) {\n"
    "                Thread.currentThread().interrupt();\n"
    "            }\n"
    "            CNCNDownloadUI.hide();",
    "installer completion path",
)
