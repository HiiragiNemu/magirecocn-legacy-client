#!/usr/bin/env python3
"""核对 native 里「代理钩子的实现」与「实际安装的钩子」是否一致。

## 为什么需要它

代理这条线换了四次路线、停用过三次，而**停用的做法是把 H(...) 安装调用整个删掉、
实现函数原样留着**。于是文件里同时存在两类长得一模一样的函数：

    urlConfigApiNew        真的在跑
    urlConfigWebNew        没人调，纯遗留
    setURI_hook            没人调，纯遗留
    hostServiceHook 等      没人调，且已被判死刑（v3 真机 UAF）

1760 行的文件里代理段占了约 900 行，其中大半是这类死代码。谁读到都会默认它在
生效——这已经导致过一次误判（拿旧包的日志推断当前行为）。

本脚本把「哪些实现存在」和「哪些真的被安装」摆在一起，并要求每个**未安装**的
实现都带有 `【已停用` 标记。这样：

  · 有人删掉某个 H(...) 却忘了加标记 → 失败，提醒他写清停用原因；
  · 有人重新启用某个钩子却忘了删标记 → 失败，提醒他更新注释；
  · 有人新写一个钩子实现却没接上 H(...) → 失败，挡住「写了但没生效」。

判据只看源码，不需要设备也不需要构建。

用法：python3 tools/check-proxy-hooks.py
"""

import re
import sys

SRC = "magia-native/src/MagiaLegacy.cpp"

# 代理相关的钩子实现函数名。新增钩子时加进来。
# （只列代理段的；i18n / font / 教程那些各有自己的约束，不在本脚本管辖内。）
PROXY_HOOKS = [
    "urlConfigApiNew",
    "urlConfigWebNew",
    "urlConfigChatNew",
    "urlConfigWebObserve",
    "setURI_hook",
    "webViewLoadURL_hook",
    "webViewImplLoadURL_hook",
    "hostServiceHook",
    "submitHook",
    "submitBodyHook",
]

DISABLED_MARK = "【已停用"

# 这两个 getter 进入旧版 Cocos/OpenSSL。2026-08-21 真机 A/B 证明一旦把它们
# 改写到代理，所有战斗都会以 0x140920E3 / 336142563 失败；它们只能只读观测。
NATIVE_DIRECT_HOOKS = ("urlConfigApiNew", "urlConfigChatNew")


def function_body(text, name):
    """返回静态函数的完整函数体；用括号深度而不是跨函数正则。"""
    m = re.search(r"^static\s+[^\n;]*\b%s\s*\([^;]*?\)\s*\{" % re.escape(name),
                  text, re.M | re.S)
    if not m:
        return None
    start = m.end() - 1
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return None


def validate(text):
    problems = []

    for name in NATIVE_DIRECT_HOOKS:
        body = function_body(text, name)
        if body is None:
            problems.append("找不到 %s 的完整函数体。" % name)
            continue
        if "endpointObserveOnly(" not in body:
            problems.append("%s 必须调用 endpointObserveOnly，确保 native 端点直连。" % name)
        if "endpointRewrite(" in body:
            problems.append("%s 禁止调用 endpointRewrite；这会复发战斗错误 336142563。" % name)

    # 主动探测必须留痕。2026-08-27 三份真机日志里 [proxy] 只有 web 那两行，
    # api/chat 一条都没有，而钩子明明装上了——「没有输出」当时同时对应「没跑到」
    # 「getter 返回 null」「读到了但被去重咽了」三种事实，指向的下一步完全相反，
    # 而日志里长得一模一样。汇总行是这段探测唯一的存在证明，删了就退回那种沉默。
    probe = function_body(text, "probeEndpointSlots")
    if probe is None:
        problems.append("找不到 probeEndpointSlots 的完整函数体。")
    else:
        if "主动探测第" not in probe:
            problems.append(
                "probeEndpointSlots 必须打一行汇总（读到几个槽位、其中几个非空）："
                "没有它，「没跑到」与「跑了但读不到」在日志里分不开。")
        for var in ("apiRead", "chatRead", "apiNonEmpty", "chatNonEmpty"):
            if var not in probe:
                problems.append("probeEndpointSlots 的汇总缺 %s 计数。" % var)

    return problems


def main():
    try:
        text = open(SRC, encoding="utf-8").read()
    except OSError as e:
        print("读不到 %s: %s" % (SRC, e), file=sys.stderr)
        return 2

    # 被 H(...) 安装的钩子：形如  (void*)urlConfigApiNew,
    installed = set(re.findall(r"\(void\s*\*\)\s*([A-Za-z_][A-Za-z0-9_]*)", text))

    problems = validate(text)
    live, dead = [], []

    for name in PROXY_HOOKS:
        # 实现是否存在（函数定义，不算前向声明与调用）
        defined = re.search(r"^static\s+[^\n;]*\b%s\s*\(" % re.escape(name),
                            text, re.M) is not None
        if not defined:
            problems.append("清单里有 %s，但源码里找不到它的定义——改名了还是删了？"
                            "请同步更新本脚本的 PROXY_HOOKS。" % name)
            continue

        if name in installed:
            live.append(name)
            # 装着的钩子不该带停用标记，否则注释在骗人
            if marked_disabled(text, name):
                problems.append(
                    "%s 已被 H(...) 安装，注释里却还写着「已停用」——"
                    "重新启用时请把标记删掉，否则下一个人会以为它没生效。" % name)
        else:
            dead.append(name)
            if not marked_disabled(text, name):
                problems.append(
                    "%s 有实现但没有任何 H(...) 安装它，且附近没有「%s」标记。\n"
                    "      停用一个钩子时请在它上方写明：停用于哪个提交、"
                    "证据是什么、重新启用前要先解决什么。\n"
                    "      代理这条线换过四次路线，没有这些标记就分不清"
                    "「还在跑」和「早废了」。" % (name, DISABLED_MARK))

    if problems:
        print("✘ 代理钩子核对未通过：", file=sys.stderr)
        for p in problems:
            print("  · " + p, file=sys.stderr)
        return 1

    print("✔ 代理钩子核对通过")
    print("    实际安装 %d 个：%s" % (len(live), ", ".join(live) or "（无）"))
    print("    保留但停用 %d 个：%s" % (len(dead), ", ".join(dead) or "（无）"))
    return 0


def marked_disabled(text, name):
    """标记必须在**紧邻函数定义的那段连续注释**里。

    不用「往上 N 行」的窗口：代理段里这些函数是挨着写的，窗口会让上一个函数的
    停用标记误伤下一个函数——实测 urlConfigChatNew（活着的）就被上面 urlConfigWebNew
    的标记盖到过，脚本反而报错。改成从定义那行往上吃连续的 `//` 注释行，遇到
    空行或代码就停，谁的注释归谁。
    """
    m = re.search(r"^static\s+[^\n;]*\b%s\s*\(" % re.escape(name), text, re.M)
    if not m:
        return False
    lines = text[:m.start()].splitlines()
    block = []
    for line in reversed(lines):
        s = line.strip()
        if s.startswith("//"):
            block.append(s)
            continue
        break          # 空行或代码：注释块到此为止
    return any(DISABLED_MARK in l for l in block)


if __name__ == "__main__":
    sys.exit(main())
