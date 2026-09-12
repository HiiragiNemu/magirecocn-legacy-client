#!/usr/bin/env python3
"""守卫 i18n 查表路径的两条合同：缓冲不共享、一次翻译只用一个快照。

## 一、译文缓冲不能是共享的

### 这条守卫在防什么

两个文本钩子都要先把译文拷进一块自己的缓冲，再把指针交给引擎——引擎在
`old()` / `initLabelOld()` 返回前会一直读它，所以缓冲必须活到那之后。原先这两块
缓冲写成了 `static thread_local`：生命周期确实够，但**同一个线程重入时两层会撞在
同一块缓冲上**。

重入不是假设。反汇编确认（arm64 `0x12a9bd0`）：

    cocos2d::MenuItemLabel::setString(&s)
        └─ 0x12a9c14  blr  ← 把收到的 string 指针**原样**转给内层 Label 的虚
                            setString，而那个地址正是我们钩着的

于是外层 `engineLookup(text, zh)` 把译文写进 `zh`、把 `zh.c_str()` 交给引擎之后，
内层再次进入本函数时，`text` 指向的正是 `zh` 内部，而 `engineLookup` 的 `out`
又是**同一个** `zh`。译文本身一旦也是表里的 key，内层那句 `out = it->second`
就在改写外层指针正指着的缓冲，长度一变就重新分配 → 外层指针当场悬空。

今天没炸的唯一理由是「译文又是 key」这种自指条目大概不存在——那是运气，不是设计。
局部变量的生命周期和 `static thread_local` 一模一样（都活到函数结束，而引擎调用
就在函数内部），所以 `static` 从来不是正确性需要的，只是想省一次构造；而它换来的
是一整类 use-after-free。

### 同时钉住「拷贝」本身

`engineLookup` 必须把译文**拷进** out，不能退回返回 `&it->second`——那是指向表
内部的指针，另一线程一重载表就悬空（558efd5 修的就是这个）。两条判据是一件事的
两面：缓冲要独立，内容要自有。

## 二、一次翻译只能用一个快照

精确查找与前缀查找原先各自 `engineI18nSnapshot()` 一次，于是「一句文案的一次
翻译」跨了两个快照。这不只是白锁一次 `g_engineI18nMutex`（那把锁被 GL 线程、
网络线程和重载路径共用），更要紧的是它**违反了这张表整个设计的前提**——558efd5
把表做成不可变快照，靠的正是「读者一次性取走快照，在整个使用期间持有它」。

两次取的中间要是落进一次热重载，同一句文案的精确规则来自旧表、前缀规则来自新表，
得出的结果两个版本都没写过，而且完全无法复现。表随 `cn_js_update.zip` 下发、
每 3 秒查一次指纹，这个窗口是真实存在的。

所以快照一律由调用方取一份、传给两级查找；两个查找函数自己**不许**再取。
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _guardlib import code, body   # noqa: E402

CPP = Path("magia-native/src/MagiaLegacy.cpp")
src = code(CPP.read_text(encoding="utf-8"))

def last_body(signature):
    """取**最后**一处同名声明的函数体。

    `initLabelNew` 有前向声明（以 `;` 收尾）在先，定义在后。`body()` 从第一处
    匹配起找下一个 `{`，在这里恰好还是能落到定义上——但那是运气：中间只要多出
    一个带花括号的声明（`using`、初始化列表、另一个函数），它就会取到别人的身上。
    从最后一处找起，前向声明再多也不影响。
    """
    i = src.rfind(signature)
    return body(src[i:], signature) if i >= 0 else ""


trampoline = last_body("static void setStringTrampoline(")
init_label = last_body("static void initLabelNew(")
ttf_initial = last_body("static bool translateTtfInitialText(")
ttf_cfg = last_body("static void* createWithTtfCfgNew(")
ttf_str = last_body("static void* createWithTtfStrNew(")
lookup = body(src, "static bool engineLookup(")
prefix = body(src, "static bool enginePrefixLookup(")

# 「本函数里有没有 static/thread_local 的 std::string」——只看方法体，别看全文：
# 别的地方（比如观测去重表）用静态容器是正当的。
SHARED = re.compile(r"(?:static|thread_local)[^;\n]*\bstd::string\b")

SNAP = "engineI18nSnapshot()"

checks = {
    # ── 二、一个快照 ──
    "engineLookup 的快照由调用方传入（自己不取）":
        bool(lookup) and SNAP not in lookup
        and "const EngineI18nPtr& t" in src[src.find("static bool engineLookup("):
                                            src.find("static bool engineLookup(") + 200],
    "enginePrefixLookup 的快照由调用方传入（自己不取）":
        bool(prefix) and SNAP not in prefix,
    "setStringTrampoline 整次翻译只取一份快照":
        bool(trampoline) and trampoline.count(SNAP) == 1,
    "initLabelNew 整次翻译只取一份快照":
        bool(init_label) and init_label.count(SNAP) == 1,
    "createWithTTF 初始文案整次翻译只取一份快照":
        bool(ttf_initial) and ttf_initial.count(SNAP) == 1,
    # ── 一、缓冲不共享 ──
    "setStringTrampoline 的译文缓冲是局部的（不是 static/thread_local）":
        bool(trampoline) and not SHARED.search(trampoline),
    "setStringTrampoline 仍然有一块自己的 std::string 缓冲（不是改回指表内部）":
        bool(trampoline) and "std::string zh;" in trampoline,
    "initLabelNew 的译文缓冲是局部的（不是 static/thread_local）":
        bool(init_label) and not SHARED.search(init_label),
    "initLabelNew 仍然有一块自己的 std::string 缓冲":
        bool(init_label) and "std::string combined;" in init_label,
    "createWithTTF 初始文案 helper 同时覆盖精确与前缀查找":
        bool(ttf_initial) and "engineLookup(t, text, translated)" in ttf_initial
        and "enginePrefixLookup(t, v.data, v.size, translated)" in ttf_initial,
    "createWithTTF 初始文案受 noI18nSetString 统一控制":
        bool(ttf_initial) and "g_dbgNoI18nSetString" in ttf_initial,
    "createWithTTF(cfg) 的译文与伪 string 都是调用期局部变量":
        bool(ttf_cfg) and not SHARED.search(ttf_cfg)
        and "std::string translated;" in ttf_cfg and "FakeNdkStr fk;" in ttf_cfg
        and "createWithTtfCfgOld(cfg, &fk, h, i)" in ttf_cfg,
    "createWithTTF(str) 的译文与伪 string 都是调用期局部变量":
        bool(ttf_str) and not SHARED.search(ttf_str)
        and "std::string translated;" in ttf_str and "FakeNdkStr fk;" in ttf_str
        and "createWithTtfStrOld(&fk, font, size, dims, h, v)" in ttf_str,
    "engineLookup 把译文**拷进** out，不返回指向表内部的指针":
        bool(lookup) and "out = it->second;" in lookup
        and "&it->second" not in lookup,
    "enginePrefixLookup 同样拷贝，不交出表内指针":
        bool(prefix) and "out = rule.second;" in prefix
        and "&rule.second" not in prefix,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS " if ok else "FAIL ") + name)
if failed:
    raise SystemExit("i18n buffer alias contract failed: " + ", ".join(failed))
