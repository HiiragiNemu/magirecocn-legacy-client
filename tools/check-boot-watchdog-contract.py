#!/usr/bin/env python3
"""守卫 CNBootWatchdog 的几条不变量。

这些条件每一条都对应一次真机上会出事的改法，不是风格约束：

* 看门狗排在前端超时**之后** —— 排前面就等于把一次还有希望的请求掀掉；
* 序章期间不武装 —— 序章全程 native 每 250ms 把 WebView 按回隐藏，
  不让开就会在序章正中间重载页面；
* 只开一枪 —— 重载完还不行就该让前端的错误弹窗接手，再刷下去玩家连报错都看不到；
* 只处理启动路由 —— 战斗、剧情和活动页即使 WebView 隐藏也已经离开启动阶段；
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
fire = body(wd, "private static void fire(")

# 截止时间必须由前端超时推导，且严格更大。写死一个字面量也可能碰巧更大，
# 但那样下次前端改这个超时时这里不会跟着动——所以要求的是「推导关系」本身。
deadline_derived = bool(
    re.search(
        r"DEADLINE_MS\s*=\s*FRONTEND_TOPPAGE_TIMEOUT_MS\s*\+\s*(\d+)L",
        wd,
    )
)
deadline_margin = re.search(
    r"DEADLINE_MS\s*=\s*FRONTEND_TOPPAGE_TIMEOUT_MS\s*\+\s*(\d+)L", wd
)

# ── 从 Java 源码里抠出四个常量，拿它们跑一遍状态机 ──────────────────
#
# 轮询那段逻辑是这个类里唯一有状态的地方，也是唯一能「编译得过、守卫全绿、
# 上真机才发现白干」的地方：判据取错一格，要么该救的黑屏不救，要么在战斗
# 中途把页面掀了。所以除了比对字面量，这里再按常量把几种典型时序跑一遍。
def const(name: str) -> int:
    m = re.search(name + r"\s*=\s*([0-9]+)L?;", wd)
    if not m:
        raise SystemExit("守卫读不到常量 " + name + "，先确认它还在不在")
    return int(m.group(1))


TICK_MS = const(r"TICK_MS")
VISIBLE_STREAK = const(r"VISIBLE_STREAK")
SAFE_STREAK = const(r"SAFE_STREAK")
DEADLINE_MS = const(r"FRONTEND_TOPPAGE_TIMEOUT_MS") + int(
    re.search(r"DEADLINE_MS\s*=\s*FRONTEND_TOPPAGE_TIMEOUT_MS\s*\+\s*(\d+)L", wd).group(1)
)


def simulate(visible_at):
    """按 Tick.run() 的逻辑跑，返回 早退 / 到点不开枪 / 开枪。"""
    streak = peak = 0
    t = 0
    while True:
        t += TICK_MS
        if visible_at(t):
            streak += 1
            peak = max(peak, streak)
        else:
            streak = 0
        if streak >= VISIBLE_STREAK:
            return "早退"
        if t >= DEADLINE_MS:
            return "到点不开枪" if peak >= SAFE_STREAK else "开枪"


T = TICK_MS
SCENARIOS = [
    # 该救的：玩家报的那块黑屏，全程一次都没露面
    ("全程不可见（0100 型黑屏）", lambda t: False, "开枪"),
    # 该救的：武装头一个 tick 撞上「引擎建好、前端还没来得及藏」的瞬间可见。
    # 这一条是 SAFE_STREAK 取 2 而不是 1 的全部理由。
    ("只有 1 个 tick 瞬间可见", lambda t: t == T, "开枪"),
    ("隔一个 tick 闪一次", lambda t: (t // T) % 2 == 0, "开枪"),
    # 不该动的：前端稳稳起来了
    ("10 秒后起来并一直在", lambda t: t >= 10 * 1000, "早退"),
    ("前端在自己超时后自愈显示", lambda t: t >= DEADLINE_MS - 20 * 1000, "早退"),
    # 不该动的：露过面之后玩家进了战斗，屏幕归引擎——这时候重载是纯破坏
    ("露 2 个 tick 就进战斗", lambda t: t in (10 * 1000, 10 * 1000 + T), "到点不开枪"),
]

sim_failures = [
    "%s：期望 %s，实到 %s" % (name, want, got)
    for name, vis, want in SCENARIOS
    for got in [simulate(vis)]
    if got != want
]

checks = {
    "浮层撤下时武装（且只在那一处）":
        ui.count("CNBootWatchdog.arm();") == 1,
    "截止时间由前端 TopPage 超时推导，不是写死的字面量":
        deadline_derived,
    "截止时间严格晚于前端自己的超时":
        bool(deadline_margin) and int(deadline_margin.group(1)) > 0,
    # 出处只说到「不在本仓库」为止（见 CONTRIBUTING 的「对外表述」一节）：
    # 具体出处不写进注释，这条断言自然也不能去钉它——钉了就等于把它写回来。
    "前端超时常量标了它的真实出处（不在本仓库）":
        "不在本仓库" in wd and "6E4" in wd,
    "序章期间不武装":
        "CNTutorialPrompt.isArmed()" in arm,
    "有 skipBootWatchdog 逃生开关":
        "CNDebugFlags.SKIP_BOOT_WATCHDOG" in arm
        and 'SKIP_BOOT_WATCHDOG  = "skipBootWatchdog"' in flags,
    "逃生开关登记进了 KNOWN 表（否则日志里不列、玩家不知道有它）":
        "{ SKIP_BOOT_WATCHDOG," in flags,
    "一个进程只武装一次":
        "ARMED.compareAndSet(false, true)" in arm,
    "WebView 存在时只重载一次（真正的 reload 调用全类仅一处）":
        # 用带接收者和分号的形式，避免把日志里那句 "已下发 WebView.reload()" 数进来
        wd.count("wv.reload();") == 1,
    "WebView 缺失时只接一次现有进程恢复链":
        wd.count("CNRestart.restartWithNotice(") == 1
        and "if (wv == null)" in fire
        and "本次放弃重载" not in fire,
    "进程恢复在具名后台 daemon 线程运行，不阻塞主线程 Handler":
        "new Thread(new Runnable()" in fire
        and "cn-boot-watchdog-restart" in fire
        and "restart.setDaemon(true)" in fire
        and "restart.start()" in fire,
    "进程恢复失败保留当前进程并写诊断":
        "进程级恢复未启动，保留当前进程与诊断日志" in fire
        and "启动进程级恢复失败，保留当前进程与诊断日志" in fire,
    "判据同时要求 VISIBLE 与非零尺寸":
        "getVisibility() != View.VISIBLE" in front
        and "getWidth() > 0" in front
        and "getHeight() > 0" in front,
    "判据读不出来时判为「没起来」，不误判成功":
        "return false;" in front.split("catch (Throwable t)")[-1],
    "武装路径捕获并记录异常，不把浮层收尾拖挂":
        "catch (Throwable t)" in arm and "武装失败" in arm,
    "WebView 只通过 CNWebProxy 的单一出口取":
        "CNWebProxy.currentWebView()" in wd
        and "WebViewHelper" not in wd,
    "成功判据要求连续多次可见，不是单次快照":
        "visibleStreak >= VISIBLE_STREAK" in wd and VISIBLE_STREAK >= 2,
    "到点只在「从没稳定露过面」时才开枪":
        "maxStreak >= SAFE_STREAK" in wd,
    "启动恢复只允许无路由或 TopPage":
        'return "#/TopPage".equals(route) || route.startsWith("#/TopPage?");' in wd
        and "static boolean bootRecoveryAllowedForUrl(String url)" in wd,
    "轮询和开枪前都对活动路由 fail-closed":
        wd.count("if (!bootRecoveryAllowedForUrl(url))") == 2
        and "当前已进入非启动路由，不重载，看门狗收工" in wd
        and "到点前已进入非启动路由，拒绝重载并收工" in wd,
    "战斗与活动路由不进入 reload 路径":
        not ("QuestBackground" in wd or "EventWitchTopPage" in wd)
        and "wv.reload();" in fire,
    "SAFE_STREAK 取值既不退化成「见过就不救」也不退化成「到点必开枪」":
        2 <= SAFE_STREAK <= VISIBLE_STREAK,
    "状态机时序模拟全部符合预期":
        not sim_failures,
    "sWebView 的反射全仓库只有一处":
        proxy.count('getDeclaredField("sWebView")') == 1
        and sum(
            p.read_text(encoding="utf-8").count('"sWebView"')
            for p in JAVA.glob("*.java")
        ) == 1,
}

failed = [name for name, ok in checks.items() if not ok]
for f in sim_failures:
    print("     时序模拟不符 " + f)
for name, ok in checks.items():
    print(("PASS " if ok else "FAIL ") + name)
if failed:
    raise SystemExit("boot watchdog contract failed: " + ", ".join(failed))
