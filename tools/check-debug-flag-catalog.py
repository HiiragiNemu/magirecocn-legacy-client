#!/usr/bin/env python3
"""守卫「调试开关目录三处不许各说各话」。

## 这条守卫在防什么

调试开关的名字同时出现在三个地方：

    MagiaLegacy.cpp  kDebugFlags[]        ← native 侧真正会读的
    CNDebugFlags.java  KNOWN[][]          ← Java 侧真正会读的
    CNDebugOverlay.java  分组表 + 注释表   ← 玩家在悬浮窗上看得见、拨得动的

2026-08-22 出过一次事：`noProxyEndpoint` 从 native 侧删掉了（api/chat 改为永久
只读观测），但悬浮窗那两张表没跟着删。于是面板上留着一个开关，玩家拨得动、
写得进文件，而**没有任何代码会去读它**——拨了不会发生任何事。

最坏的地方不是它没用，是它的文案写着「一直转圈、连不上时对照排查用」。也就是说
它恰好会在玩家最着急的时候被拨到，然后给出一个「试过了，没用」的错误结论。

当时全套守卫都是绿的：`check-debug-flag-boundary.py` 查的是保护区与跨 JNI 的
路径常量，不比对开关目录；`DebugOverlayTest` 用的是自己手写的合成表，不读真表。
**这个洞没有任何落点会红。** 这个脚本就是那个落点。

## 顺带钉住文档里的数字

同一次排查还发现设计文档的数字漂了很久：文档写「KNOWN 15 / kDebugFlags 19 /
全表 34 / 面板 31」，而代码里是 20 / 17 / 37 / 33。这类数字没人会主动回来改，
只能靠改开关表时必然红灯来保证。所以把它们一起钉进来——判据取自代码，文档跟着代码走。
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _guardlib import code   # noqa: E402

CPP = Path("magia-native/src/MagiaLegacy.cpp")
FLAGS = Path("patch/src/main/java/io/kamihama/magianative/CNDebugFlags.java")
OVERLAY = Path("patch/src/main/java/io/kamihama/magianative/CNDebugOverlay.java")
DOC = Path("docs/DEBUG_OVERLAY_DESIGN_PRINCIPLES.md")

cpp_src = code(CPP.read_text(encoding="utf-8"))
flags_raw = FLAGS.read_text(encoding="utf-8")
flags_src = code(flags_raw)
overlay_src = code(OVERLAY.read_text(encoding="utf-8"))
doc = DOC.read_text(encoding="utf-8")

# ── 三处名单 ────────────────────────────────────────────────
# native：{ "名字", &g_dbgXxx, "说明" }
native = re.findall(r'\{\s*"([A-Za-z0-9_]+)",\s*&g_dbg', cpp_src)

# Java：KNOWN 里放的是常量名，得先把常量解回字面量
# ⚠ 锚点不能带缩进：_guardlib.code() 剥注释时对每行做了 strip()，缩进全没了。
# 第一版写的是 `\n    \};`，于是永远匹配不上，KNOWN 静默算成 0 条——而「0 条」
# 会让「悬空开关」那条判据把 Java 侧的开关全报成悬空，噪音盖过真正的那一个。
known_block = re.search(r"KNOWN\s*=\s*\{(.*?)\n\s*\};", flags_src, re.S)
# 两种写法都要认：`{ SKIP_WEB_PROXY, "…" }`（现状，全是常量）与
# `{ "someFlag", "…" }`（直接写字面量）。只认常量的话，谁哪天图省事直接写字符串，
# 这个开关就对守卫隐形了——而「隐形」正是本脚本要消灭的东西。
entries = re.findall(r'\{\s*(?:"([A-Za-z0-9_]+)"|([A-Z_0-9]+))\s*,',
                     known_block.group(1)) if known_block else []
literals = dict(re.findall(r'String\s+([A-Z_0-9]+)\s*=\s*"([A-Za-z0-9_]+)"', flags_src))
java = [lit if lit else literals.get(const, const) for lit, const in entries]

# 面板：分组表与注释表
panel = re.findall(r'\{\s*"([A-Za-z0-9_]+)",\s*GROUP_([A-F])\s*\}', overlay_src)
panel_names = [n for n, _ in panel]
notes = re.findall(r'\{\s*"([A-Za-z0-9_]+)",\s*\n?\s*"', overlay_src)

catalog = set(native) | set(java)
dangling = sorted(set(panel_names) - catalog)
# 有意不进面板的：P7 的三个断同步开关 + 两个后加的逃生开关。
# 写死在这里是**故意**的：新增开关若忘了挂上面板，会当场变成「目录有、面板没有」
# 而红灯，逼人明确表态是漏了还是有意不给玩家。
INTENTIONALLY_OFF_PANEL = {
    "skipVersionCheck", "skipHotUpdate", "skipMirrorConfig",   # P7 断同步
    "skipBootWatchdog",                                        # 逃生开关
    # 本地状态覆盖层的逃生开关。不进面板与 skipBootWatchdog 同理：它关掉的是一个
    # 「不开就没有」的功能（编队存不住），玩家拨它只会把自己的存档停掉；真正需要
    # 它的场合是排查「编队错乱是不是覆盖层干的」，那是开发动作，不是玩家动作。
    "skipLocalState",
}
off_panel = sorted(catalog - set(panel_names))

# 注释表只该覆盖面板上的开关；面板有而注释表没有 → 玩家只看得到接口名
note_missing = sorted(set(panel_names) - set(notes))

from collections import Counter                                  # noqa: E402
by_group = Counter(g for _, g in panel)

# ── 文档里的数字 ────────────────────────────────────────────
def doc_group_counts():
    return dict(re.findall(r"^\| ([A-F]) \| [^|]+\| (\d+) \|", doc, re.M))


def doc_section_counts():
    return dict(re.findall(r"^#### ([A-F])\.[^（]*（(\d+)", doc, re.M))


def all_counts_ok(name_pattern, real):
    """文档里这个名字后面跟的**每一处**数字都得对。

    判据必须是「全部」而不是「存在一处」。同一个名字在本文里出现两次
    （§4.2 一次、§4.3 一次），只要求存在一处相符时，改错其中一处照样绿灯
    ——变异测试当场抓到了这个漏判。数字没有唯一落点，就等于没钉住。
    """
    nums = re.findall(name_pattern + r"\s*[（(]?\s*(\d+)", doc)
    return bool(nums) and all(int(n) == real for n in nums)


dg = doc_group_counts()
ds = doc_section_counts()
real_group = {g: str(by_group[g]) for g in "ABCDEF"}

# ── native 背的那份「Java 侧开关名」 ────────────────────────────────
# native 的「目录里有不认识的文件」只拿 kDebugFlags 比对时，每个 Java 侧开关都会
# 被报成打错名字。2026-08-27 的真机日志里三条假警报把唯一一条真的（tlaProbe，
# tlsProbe 打错）埋掉了，维护者据此以为探针坏了。修法是让 native 也认得 Java 的
# 名字；代价是抄了一份，会漂移——这条判据就是防漂移的那个落点。
_m = re.search(r"kJavaSideFlags\[\]\s*=\s*\{(.*?)\n\};", cpp_src, re.S)
cpp_java_side = re.findall(r'"([A-Za-z0-9_]+)"', _m.group(1)) if _m else []

checks = {
    # 少一个 → 那个开关的假警报回来；多一个（比如 Java 删了开关而这边没删）→
    # 目录里真出现这个名字时反而不报了，等于把打错名字的兜底悄悄挖掉。
    "native 认得的 Java 侧开关名 == Java 的 KNOWN 减去 kDebugFlags":
        set(cpp_java_side) == (set(java) - set(native))
        and len(cpp_java_side) == len(set(cpp_java_side)),
    "面板上没有悬空开关（拨了不会有任何代码去读的）":
        not dangling,
    "面板每一项都有白话说明（否则玩家只看得到接口名）":
        not note_missing,
    "不进面板的开关正好是那 5 个有意为之的":
        set(off_panel) == INTENTIONALLY_OFF_PANEL,
    "文档 §4.2 的分类数量与代码一致":
        dg == real_group,
    "文档 §4.3 各节标题的数量与代码一致":
        ds == real_group,
    "文档写的 CNDebugFlags.KNOWN 条数与代码一致":
        all_counts_ok(r"`CNDebugFlags\.KNOWN`", len(java)),
    "文档写的 kDebugFlags 条数与代码一致":
        all_counts_ok(r"`MagiaLegacy\.cpp kDebugFlags`", len(native)),
    "文档写的「两侧合计」与代码一致":
        ("两侧目录合计 %d 个" % len(catalog)) in doc,
    "文档写的「面板可见」与代码一致":
        ("面板可见 **%d** 个" % len(panel_names)) in doc,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS " if ok else "FAIL ") + name)

print("     native kDebugFlags=%d  CNDebugFlags.KNOWN=%d  合计=%d  面板=%d  分组=%s"
      % (len(native), len(java), len(catalog), len(panel_names),
         {g: by_group[g] for g in "ABCDEF"}))
if dangling:
    print("     悬空开关（面板有、两侧目录都没有）: " + ", ".join(dangling))
if note_missing:
    print("     缺白话说明: " + ", ".join(note_missing))
if set(off_panel) != INTENTIONALLY_OFF_PANEL:
    print("     多出来的「目录有、面板没有」: "
          + ", ".join(sorted(set(off_panel) - INTENTIONALLY_OFF_PANEL)))
    print("     名单里写着却已不存在的: "
          + ", ".join(sorted(INTENTIONALLY_OFF_PANEL - set(off_panel))))
if dg != real_group:
    print("     文档 §4.2 = %s，代码 = %s" % (dg, real_group))
if ds != real_group:
    print("     文档 §4.3 = %s，代码 = %s" % (ds, real_group))
if failed:
    raise SystemExit("debug flag catalog contract failed: " + ", ".join(failed))
