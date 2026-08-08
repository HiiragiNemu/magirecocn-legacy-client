#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""调试开关的两条不变量：**不伸进安全判据**，且**两侧路径逐字一致**。

## 检查二：跨 JNI 的路径常量必须逐字一致

同一个文件/目录被 Java 与 native 两侧各自硬编码一份。写歪一个字符不会有任何报错
——两边各写各的文件，各读各的，谁也不抱怨：

  · `DEBUG_DIR` 不一致 → 开关建在 A 处、代码读 B 处，整表打成「关」，
    和「一个都没开」在日志里完全一样（2026-08-08 为这类现象绕了整整一轮）；
  · `cn_base_done.flag` 不一致 → 安装完成标记查不到，每次启动重跑安装器；
  · `cn_force_tutorial.flag` 不一致 → 玩家选了播序章，native 侧永远读不到；
  · `cn_overlay_active.flag` 不一致 → 浮层闸门失效，引擎在下载中途抢跑主页。

这些不变量原本只写在注释里（native 侧就有「⚠ 必须与 CNTutorialPrompt.
FORCE_TUTORIAL_FLAG 逐字一致」「已逐字节核对」这类句子）。注释挡不住改动——
逐字节核对是**人**做的，做过一次不代表下次还会做。所以钉在这里。

解析支持字符串拼接（`CNHotUpdateCheck.FINAL_FLAG = FILES_DIR + "madomagi/..."`），
解析不出来一律按失败处理：那说明声明的形状变了，需要人来看一眼，而不是悄悄跳过。

---

## 检查一：调试开关没有伸进安全判据里

## 守的是哪条规矩

`CNDebugFlags` / native 的 `g_dbg*` 是排查工具：装一次包，在设备上建/删文件就能
把某个改动关掉，用来定位「是哪一步把游戏搞挂的」。这套东西有一条硬边界：

    开关一律只能把客户端**退回更接近原包的行为**，
    绝不能关掉任何一道**安全判定**。

否则这个目录就从排查工具变成攻击面——一旦有人能写进 `<priv>/debug/`，
就能把防线一条条关掉，而那个目录恰恰是「拿到 root 就能写」的地方。

这条规矩写在 `CNDebugFlags` 的类注释和 README 里，但**文档挡不住「就加一个方便
排查」**：这类规则不钉死，迟早会被磨掉一个口子，而且是在某次赶工时悄悄磨掉的。
所以在这里变成可执行的检查。

## 判据

下面列出的每个「保护区」——整份文件或某个具体方法——的正文里**不许出现
`CNDebugFlags` 或 `g_dbg`**。保护区选的是真正构成安全边界的那几处：

  · 外链白名单（服务端被攻破后靠改配置把玩家导去任意地址，靠它挡）
  · config.json 只收 https（整条信任链的第一环）
  · 代理域名的最小粒度（防止 `com.cn` 这种一网打尽的写法）
  · 解压膨胀比上限（zip bomb）
  · 代理改写的域名判据（决定哪些主机的流量会被送去代理入口）

## 加新开关时撞上它怎么办

先回答这个问题：**打开之后，客户端是「少一个我们加的功能」，还是「少一道防线」？**

  · 前者 → 开关不该放在保护区里，把它挪到调用方（例如「不装这个 hook」而不是
    「让判据放行」）。
  · 后者 → 这个开关不该存在。

用法：
    python3 tools/check-debug-flag-boundary.py
"""

import os
import re
import sys

J = "patch/src/main/java/io/kamihama/magianative/"
N = "magia-native/src/MagiaLegacy.cpp"

# (文件, 方法名或 None 表示整份文件, 这块守的是什么)
PROTECTED = [
    (J + "CNSafeLink.java",      None,                 "外链白名单（整份文件）"),
    (J + "CNMirrors.java",       "normalizeBase",      "config.json 只收 https"),
    (J + "CNMirrors.java",       "isSaneProxyDomain",  "代理域名最小粒度"),
    (J + "CNDownloaderFix.java", "extractChecked",     "解压膨胀比上限（zip bomb）"),
    (J + "CNWebProxy.java",      "rewriteWith",        "代理改写的域名判据"),
    (N,                          "tryRewriteUrl",      "native 侧代理改写的域名判据"),
    (N,                          "proxySnapshot",      "native 侧代理配置快照"),
]

BANNED = re.compile(r"CNDebugFlags|g_dbg[A-Z]")


# ── 检查二用：跨 JNI 必须逐字一致的路径常量 ────────────────────────────
# (说明, [(文件, 常量名), ...])  —— 同一组里的所有常量必须解析出同一个值。
CROSS_JNI_PATHS = [
    ("调试开关目录", [
        (J + "CNDebugFlags.java",     "DEBUG_DIR"),
        (N,                           "DEBUG_DIR"),
    ]),
    ("安装完成标记", [
        (J + "CNDownloaderFix.java",  "FINAL_FLAG"),
        (J + "CNHotUpdateCheck.java", "FINAL_FLAG"),   # 这个是 FILES_DIR + "..." 拼的
        (N,                           "FLAG_PATH"),
    ]),
    ("强制序章标记", [
        (J + "CNTutorialPrompt.java", "FORCE_TUTORIAL_FLAG"),
        (N,                           "FORCE_TUTORIAL_FLAG_PATH"),
    ]),
    ("浮层活动标记", [
        (J + "CNCNDownloadUI.java",   "OVERLAY_FLAG"),
        (N,                           "OVERLAY_FLAG_PATH"),
    ]),
]

# 声明右侧一直取到分号。`[^;]` 会跨行，所以两侧那种「等号后换行再写字面量」的
# 排版也吃得下。
DECL = r"\b%s\s*=\s*([^;]*);"
# 表达式里的记号：字符串字面量（含转义）或标识符
TOKEN = re.compile(r'"((?:[^"\\]|\\.)*)"|([A-Za-z_][A-Za-z_0-9]*)')


def const_value(src, name, seen=None):
    """解析 `NAME = <字面量与标识符的 + 拼接>;`，返回字符串值；解析不出返回 None。"""
    seen = set() if seen is None else seen
    if name in seen:
        return None                      # 循环引用
    seen.add(name)
    m = re.search(DECL % re.escape(name), src)
    if not m:
        return None
    out = []
    for tok in TOKEN.finditer(m.group(1)):
        if tok.group(1) is not None:
            out.append(tok.group(1).replace('\\"', '"').replace("\\\\", "\\"))
        else:
            v = const_value(src, tok.group(2), seen)
            if v is None:
                return None              # 引用了解析不了的东西
            out.append(v)
    return "".join(out) if out else None


def check_cross_jni(problems):
    for what, refs in CROSS_JNI_PATHS:
        seen = []
        for path, name in refs:
            if not os.path.isfile(path):
                problems.append("找不到文件 %s（%s 的常量表过期了？）" % (path, what))
                continue
            v = const_value(open(path, encoding="utf-8").read(), name)
            if v is None:
                problems.append(
                    "解析不出 %s 里的常量 %s（%s）。\n"
                    "      **按失败处理**：声明的形状变了，或者常量被改名/删掉了。\n"
                    "      请确认它与同组其它几处仍然逐字一致，然后更新本脚本的\n"
                    "      CROSS_JNI_PATHS —— 不要只把这一条删掉。" % (path, name, what))
                continue
            seen.append((path, name, v))
        vals = set(v for _, _, v in seen)
        if len(vals) > 1:
            lines = "\n".join("        %s %s = %r" % (p, n, v) for p, n, v in seen)
            problems.append(
                "「%s」在各处的值不一致——两边各写各的文件、各读各的，"
                "运行时不会有任何报错：\n%s" % (what, lines))



# 怎么认「这行是定义而不是调用」：**行首必须是声明关键字**。
#
# 这条判据是被反面用例逼出来的，前两版都不行：
#   v1 不分定义与调用，于是 `if (a && tryRewriteUrl(x)) {` 被当成签名，
#      抠出一块毫不相干的区域，两个越界用例全部漏网；
#   v2 改成「滤掉带控制流的行 + 候选必须恰好一个」，又把 `normalizeBase` 这种
#      有普通调用点（`String b = normalizeBase(x);`）的方法判成了「找不到」。
# 行首关键字这条两边都过：调用点永远不会以 private/static 开头，
# 而本仓库的定义（含 C++ 那几个跨行签名的）全都以它们开头。
DEF_LINE = re.compile(
    r"^\s*(?:private|public|protected|static|final|inline|virtual)\b[^\n]*"
    r"\b%s\s*\([^\n]*$")


def method_body(src, name):
    """按花括号配平抠出方法/函数正文。抠不到 / 认不准就返回 None（由调用方报错）。

    起始花括号从**签名行的开头**往后找，不是从匹配末尾——签名行本身常以 `{`
    结尾（v1 就是从末尾找，于是抠到了下一个区块）。C++ 那几个跨行签名靠这条
    也能正确落到真正的 `{` 上。
    """
    pat = re.compile(DEF_LINE.pattern % re.escape(name), re.M)
    cands = list(pat.finditer(src))
    if len(cands) != 1:
        return None
    i = src.find("{", cands[0].start())     # 从签名行**开头**找，不是末尾
    if i < 0:
        return None
    depth, j = 0, i
    while j < len(src):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[i:j + 1]
        j += 1
    return None


def main():
    problems = []
    for path, method, what in PROTECTED:
        if not os.path.isfile(path):
            problems.append("找不到文件 %s（保护区列表过期了？）" % path)
            continue
        src = open(path, encoding="utf-8").read()
        if method is None:
            region, where = src, path
        else:
            region = method_body(src, method)
            where = "%s 的 %s()" % (path, method)
            if region is None:
                problems.append(
                    "在 %s 里找不到方法 %s —— 保护区列表与代码脱节了。\n"
                    "      改名/挪走都请同步这里，**不要**只把这一条删掉：\n"
                    "      删掉就等于悄悄取消了对「%s」的保护。" % (path, method, what))
                continue
        hit = BANNED.search(region)
        if hit:
            line = region[:hit.start()].count("\n") + 1
            problems.append(
                "%s 里出现了 %s（区域内第 %d 行）。\n"
                "      这块守的是：%s\n"
                "      调试开关**不许**伸进安全判据。先回答：打开之后客户端是\n"
                "      「少一个我们加的功能」还是「少一道防线」？前者请把开关挪到\n"
                "      调用方；后者这个开关就不该存在。"
                % (where, hit.group(0), line, what))

    check_cross_jni(problems)

    if not problems:
        print("✔ 调试开关边界检查通过（%d 个保护区）" % len(PROTECTED))
        print("✔ 跨 JNI 路径常量逐字一致（%d 组）" % len(CROSS_JNI_PATHS))
        return 0

    print("\n✘ 检查未通过（%d 项）：\n" % len(problems))
    for i, p in enumerate(problems, 1):
        print("  %d. %s\n" % (i, p))
    print("  规矩出处：CNDebugFlags 类注释 / README「调试开关目录」，"
          "以及各常量声明处的「必须与 X 逐字一致」注释\n")
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        import traceback
        traceback.print_exc()
        # 这项检查**不 fail-open**：它守的是安全边界，自己出错时宁可挡住构建
        # 让人来看，也不能悄悄放行。与那些「护栏坏了要让路」的钩子取向相反，
        # 因为放行的代价不一样。
        print("\n检查脚本自身出错——按失败处理（安全边界的检查不 fail-open）")
        sys.exit(1)
