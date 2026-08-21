#!/usr/bin/env python3
"""守卫 CNBootWatchdog 的几条不变量。

这些条件每一条都对应一次真机上会出事的改法，不是风格约束：

* 看门狗排在前端超时**之后** —— 排前面就等于把一次还有希望的请求掀掉；
* 序章期间不武装 —— 序章全程 native 每 250ms 把 WebView 按回隐藏，
  不让开就会在序章正中间重载页面；
* 只开一枪 —— 重载完还不行就该让前端的错误弹窗接手，再刷下去玩家连报错都看不到；
* 判据三个条件齐全 —— 只看 VISIBLE 不看尺寸，会把 0×0 的 WebView 判成「起来了」，
  而 0×0 恰恰就是玩家报的那块黑屏。
"""

import re
from pathlib import Path

JAVA = Path("patch/src/main/java/io/kamihama/magianative")

wd = (JAVA / "CNBootWatchdog.java").read_text(encoding="utf-8")
ui = (JAVA / "CNCNDownloadUI.java").read_text(encoding="utf-8")
flags = (JAVA / "CNDebugFlags.java").read_text(encoding="utf-8")
proxy = (JAVA / "CNWebProxy.java").read_text(encoding="utf-8")


def body(src: str, signature: str) -> str:
    """按花括号配平取出一个方法体；取不到返回空串（让断言失败而不是抛异常）。"""
    i = src.find(signature)
    if i < 0:
        return ""
    j = src.find("{", i)
    if j < 0:
        return ""
    depth = 0
    for k in range(j, len(src)):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                return src[j : k + 1]
    return ""


arm = body(wd, "public static void arm()")
front = body(wd, "static boolean frontEndUp()")

# 截止时间必须由前端超时推导，且严格更大。写死一个字面量也可能碰巧更大，
# 但那样下次前端改 18E4 时这里不会跟着动——所以要求的是「推导关系」本身。
deadline_derived = bool(
    re.search(
        r"DEADLINE_MS\s*=\s*FRONTEND_TOPPAGE_TIMEOUT_MS\s*\+\s*(\d+)L",
        wd,
    )
)
deadline_margin = re.search(
    r"DEADLINE_MS\s*=\s*FRONTEND_TOPPAGE_TIMEOUT_MS\s*\+\s*(\d+)L", wd
)

checks = {
    "浮层撤下时武装（且只在那一处）":
        ui.count("CNBootWatchdog.arm();") == 1,
    "截止时间由前端 TopPage 超时推导，不是写死的字面量":
        deadline_derived,
    "截止时间严格晚于前端自己的超时":
        bool(deadline_margin) and int(deadline_margin.group(1)) > 0,
    "前端超时常量标了它的真实出处（不在本仓库）":
        "前端那一侧" in wd and "18E4" in wd,
    "序章期间不武装":
        "CNTutorialPrompt.isArmed()" in arm,
    "有 skipBootWatchdog 逃生开关":
        "CNDebugFlags.SKIP_BOOT_WATCHDOG" in arm
        and 'SKIP_BOOT_WATCHDOG  = "skipBootWatchdog"' in flags,
    "逃生开关登记进了 KNOWN 表（否则日志里不列、玩家不知道有它）":
        "{ SKIP_BOOT_WATCHDOG," in flags,
    "一个进程只武装一次":
        "ARMED.compareAndSet(false, true)" in arm,
    "一个进程只重载一次（真正的 reload 调用全类仅一处）":
        # 用带接收者和分号的形式，避免把日志里那句 "已下发 WebView.reload()" 数进来
        wd.count("wv.reload();") == 1,
    "判据同时要求 VISIBLE 与非零尺寸":
        "getVisibility() != View.VISIBLE" in front
        and "getWidth() > 0" in front
        and "getHeight() > 0" in front,
    "判据读不出来时判为「没起来」，不误判成功":
        "return false;" in front.split("catch (Throwable t)")[-1],
    "武装路径整体吞异常，不把浮层收尾拖挂":
        "catch (Throwable t)" in arm,
    "WebView 只通过 CNWebProxy 的单一出口取":
        "CNWebProxy.currentWebView()" in wd
        and "WebViewHelper" not in wd,
    "sWebView 的反射全仓库只有一处":
        proxy.count('getDeclaredField("sWebView")') == 1
        and sum(
            p.read_text(encoding="utf-8").count('"sWebView"')
            for p in JAVA.glob("*.java")
        ) == 1,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS " if ok else "FAIL ") + name)
if failed:
    raise SystemExit("boot watchdog contract failed: " + ", ".join(failed))
