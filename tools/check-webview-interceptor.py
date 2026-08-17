#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验基础 APK 里 WebView 拦截链的形状，与 CNWebProxy 的假设逐条对齐。

## 为什么需要它

`CNWebProxy` 整个建立在**别人写的**那段代码之上——`smali/jp/f4samurai/web/` 是
基础客户端 的 `classes.dex`，不是本仓库的产物。我们只是在运行时把它的
`WebViewClient` 包了一层。这意味着一堆事实是**前提而非选择**：

  · 拿 WebView 实例靠反射 `WebViewHelper.sWebView`；
  · 代理只在「本地文件没命中」之后才轮到，靠的是拦截器未命中时
    `invoke-super` 返回 null；
  · 游戏的 API 请求能落到我们手里，靠的是拦截器对 `api/` 开头的路径不处理；
  · CSS 冻结那个不可逆的坑，根源是拦截器把 `?<md5>` 查询串丢掉。

这些事实此前**没有任何东西守着**。铁律 1 说不要手改 smali，但「不该做」和
「做了会被发现」是两回事——真被改了（或将来换基础 APK），症状会是运行时一个
静默失效，而不是构建失败。这个脚本把它们变成可核验的。

## 它不做什么

不校验补丁层代码的**逻辑**——那有 javac / d8 / 单元测试管。但安全判据的
**存在性**在这里钉住：WebView 本地资源拦截的判据 2026-08 起收进 Java 补丁类
`CNWebLocalFiles`（旧 smali 实现出过 F-E-01 查询串路径穿越、F-E-02 任意源
读本地文件、F-E-03 每请求全量 logcat 完整 URL），本脚本同时守两侧形状——
smali 侧只许剩一行调用，Java 侧的四道闸一条都不许少。

用法：
    python3 tools/check-webview-interceptor.py
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 这几个文件在**重建树**里，不在仓库里（2026-08-14 起原包派生文件已从仓库删除）。
# 树根取环境变量 TREE，CI 里由重建那一步设好；本地先跑 baseline.py apply。
TREE_ROOT = os.environ.get("TREE") or ROOT
WEB = os.path.join(TREE_ROOT, "smali", "jp", "f4samurai", "web")

HELPER      = os.path.join(WEB, "WebViewHelper.smali")
HELPER_RUN  = os.path.join(WEB, "WebViewHelper$1.smali")
IMPL        = os.path.join(WEB, "WebViewImpl.smali")
INTERCEPTOR = os.path.join(WEB, "WebViewImpl$WebViewClientImpl.smali")

findings = []
checked = 0


def read(path):
    if not os.path.isfile(path):
        findings.append("缺文件: " + os.path.relpath(path, ROOT))
        return ""
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def need(text, pattern, why, where, regex=False):
    """text 里必须出现 pattern，否则记一条 finding。"""
    global checked
    checked += 1
    hit = re.search(pattern, text) if regex else (pattern in text)
    if not hit:
        findings.append("%s\n      期望在 %s 里找到: %s" % (why, where, pattern))
    return bool(hit)


def must_not(text, pattern, why, where, regex=False):
    """text 里必须不出现 pattern，否则记一条 finding（安全判据的反向守卫）。"""
    global checked
    checked += 1
    hit = re.search(pattern, text) if regex else (pattern in text)
    if hit:
        findings.append("%s\n      不得在 %s 里出现: %s" % (why, where, pattern))
    return not hit


def main():
    helper = read(HELPER)
    runner = read(HELPER_RUN)
    impl   = read(IMPL)
    icept  = read(INTERCEPTOR)
    # 判据在 Java 补丁侧（仓库正文，不在重建树里）：它的安全校验被悄悄拿掉
    # 必须是构建失败，而不是运行时的静默放行。
    jfiles = read(os.path.join(ROOT, "patch", "src", "main", "java",
                               "io", "kamihama", "magianative",
                               "CNWebLocalFiles.java"))
    if findings:
        report()
        return

    # ── 1. 取 WebView 实例的那个字段 ────────────────────────────────
    # CNWebProxy.findWebView() 就是反射它。名字或类型一变，代理静默装不上。
    need(helper,
         ".field private static sWebView:Ljp/f4samurai/web/WebViewImpl;",
         "CNWebProxy 靠反射 WebViewHelper.sWebView 取 WebView 实例",
         "WebViewHelper.smali")

    # ── 2. tag 会被覆盖，所以**不能**用 findViewWithTag 找 ──────────
    # WebViewImpl 构造函数里 setTag("WebViewImpl")，看着像给外人留的门；
    # 但 createWebView 紧接着 setTag("WebView") 把它盖掉了。第一版 CNWebProxy
    # 正是照构造函数写的 findViewWithTag("WebViewImpl")，真机上等满 180 秒也
    # 找不到。这两条钉在这里，是为了让「别再用 tag」有据可查。
    need(impl, 'const-string v0, "WebViewImpl"',
         "构造函数里那个 tag（会被下面覆盖，仅作记录）", "WebViewImpl.smali")
    need(runner, 'const-string v1, "WebView"',
         "createWebView 会把 tag 覆盖成 \"WebView\" —— 所以不能靠 tag 找 WebView",
         "WebViewHelper$1.smali")

    # ── 3. 两个 shouldInterceptRequest 重载与它们的关系 ──────────────
    # Delegating 只覆盖 WebResourceRequest 那个重载并转交给原对象；
    # 原对象内部再转调 String 重载。这条链断了就会双重处理或漏处理。
    need(icept,
         ".method public shouldInterceptRequest(Landroid/webkit/WebView;"
         "Landroid/webkit/WebResourceRequest;)Landroid/webkit/WebResourceResponse;",
         "WebResourceRequest 重载必须存在（Delegating 覆盖的就是它）",
         "拦截器")
    need(icept,
         ".method public shouldInterceptRequest(Landroid/webkit/WebView;"
         "Ljava/lang/String;)Landroid/webkit/WebResourceResponse;",
         "String 重载必须存在", "拦截器")
    need(icept,
         r"invoke-virtual \{p0, p1, v0\}, Ljp/f4samurai/web/WebViewImpl\$WebViewClientImpl;"
         r"->shouldInterceptRequest\(Landroid/webkit/WebView;Ljava/lang/String;\)",
         "request 重载必须转调 String 重载（否则包一层会漏掉一半请求）",
         "拦截器", regex=True)

    # ── 4. smali 只剩一行调用：判据全部在 CNWebLocalFiles（Java 侧）────────
    # 安全判据放进 Java 补丁类才能被 javac/d8/单测/评审守住；smali 里留逻辑
    # 的旧实现出过 F-E-01（查询串携 ../ 穿越读私有目录）与 F-E-02（任意源
    # 页面读 <files>/magica/）。这两条的守卫就是下面的 must_not：判据一旦
    # 回流 smali，构建当场失败。
    need(icept,
         r"invoke-static \{p2\}, Lio/kamihama/magianative/CNWebLocalFiles;"
         r"->intercept\(Ljava/lang/String;\)Landroid/webkit/WebResourceResponse;",
         "smali 必须只剩一行调用 CNWebLocalFiles.intercept（判据全在 Java 侧）",
         "拦截器", regex=True)
    must_not(icept, 'const-string v0, "/magica/"',
             "F-E-01/F-E-02 守卫：字符串 contains(\"/magica/\") 判据必须留在"
             " Java 侧（结构化解析），不得回流 smali", "拦截器")
    must_not(icept, "MagiaHook-URL",
             "F-E-03 守卫：每请求全量 logcat 完整 URL 已废弃（敏感查询参数"
             "进系统日志 + 高频 I/O），不得恢复", "拦截器")
    must_not(icept, "Ljava/io/FileInputStream;",
             "文件 I/O 必须在 Java 侧（canonical 校验之后），smali 不得直接开文件",
             "拦截器")

    # ── 5. Java 侧安全判据的存在性守卫 ──────────────────────────────
    # 判据既然在补丁类里，「被悄悄拿掉」就必须变成构建失败，而不是运行时的
    # 静默放行。逐条钉住四道闸与两条既有语义。
    need(jfiles, 'class CNWebLocalFiles',
         "本地资源判据类必须存在", "CNWebLocalFiles.java")
    need(jfiles, "GAME_HOST_SUFFIXES",
         "闸 2：host 必须属于游戏域后缀白名单或项目自有域——F-E-02 的屏障",
         "CNWebLocalFiles.java")
    need(jfiles, 'startsWith(MAGIC_PREFIX)',
         "闸 3：只对 path 分量做 /magica/ 前缀匹配，查询串不再命中——F-E-01 的屏障",
         "CNWebLocalFiles.java")
    need(jfiles, 'getCanonicalPath()',
         "闸 4：canonical 复核，连符号链接逃逸一起拒",
         "CNWebLocalFiles.java")
    need(jfiles, '".."',
         "闸 4：点段拒绝（../ 与 %2e%2e 解码后的原形）", "CNWebLocalFiles.java")
    need(jfiles, 'API_PREFIX',
         "api/ 前缀不接管——游戏 API 因此才会落到 CNWebProxy 手里",
         "CNWebLocalFiles.java")
    need(jfiles, "uri.getPath()",
         "查询串剥离由 Uri.getPath() 天然承担：?<md5> 不参与文件名"
         "（CSS 冻结取舍的语义保留，见 README）", "CNWebLocalFiles.java")
    need(jfiles, "CNPaths.filesDir()",
         "本地根目录经 CNPaths 解析（/data/data 只是兼容软链，不能硬编码）",
         "CNWebLocalFiles.java")
    need(jfiles, "new WebResourceResponse(",
         "命中本地时构造响应（CNWebProxy 的 orig 返回非 null 即此路）",
         "CNWebLocalFiles.java")

    # ── 9. 未命中回落 super —— 返回 null，代理才有机会接手 ───────────
    need(icept,
         r"invoke-super \{p0, p1, p2\}, Landroid/webkit/WebViewClient;"
         r"->shouldInterceptRequest\(Landroid/webkit/WebView;Ljava/lang/String;\)",
         "未命中本地时回落 super（返回 null），CNWebProxy 正是接在这之后",
         "拦截器", regex=True)

    report()


def report():
    if findings:
        print("✘ WebView 拦截链与 CNWebProxy 的假设对不上（%d 项）：" % len(findings))
        for i, f in enumerate(findings, 1):
            print("  %d. %s" % (i, f))
        print()
        print("  这几个文件是**基础客户端 的产物**，正常情况下不该变。")
        print("  若确实换了基础 APK，需同步复核 CNWebProxy 的假设再改本脚本。")
        sys.exit(1)
    print("✔ WebView 拦截链核对通过（%d 项断言，CNWebProxy 的假设全部成立）" % checked)


if __name__ == "__main__":
    main()
