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

不校验补丁层自己的代码——那有 javac / d8 / 单元测试管。这里只盯**输入**。

用法：
    python3 tools/check-webview-interceptor.py
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(ROOT, "smali", "jp", "f4samurai", "web")

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


def main():
    helper = read(HELPER)
    runner = read(HELPER_RUN)
    impl   = read(IMPL)
    icept  = read(INTERCEPTOR)
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

    # ── 4. 路径网关：/magica/ ────────────────────────────────────────
    need(icept, 'const-string v0, "/magica/"',
         "拦截只对 /magica/ 生效", "拦截器")

    # ── 5. api/ 被排除 —— 游戏 API 因此才会落到 CNWebProxy 手里 ──────
    need(icept, 'const-string v2, "api/"',
         "拦截器对 api/ 开头的路径不处理，这是 API 请求能被代理接手的前提",
         "拦截器")

    # ── 6. ?<md5> 被丢掉 —— CSS 冻结那个不可逆坑的根源 ──────────────
    need(icept, 'const-string v2, "?"',
         "查询串被丢弃：本地文件一旦存在，服务端改版本号也不会生效"
         "（CSS 冻结陷阱的根源，见 README）", "拦截器")

    # ── 7. 本地根目录 ───────────────────────────────────────────────
    need(icept, 'const-string v2, "/data/data/io.kamihama.totentanz/files/magica/"',
         "本地文件根目录（热更包正是解压到这里）", "拦截器")

    # ── 8. 本地优先：exists() → 构造 WebResourceResponse ─────────────
    need(icept, "invoke-virtual {v3}, Ljava/io/File;->exists()Z",
         "本地文件存在性判断", "拦截器")
    need(icept, "new-instance v6, Landroid/webkit/WebResourceResponse;",
         "命中本地时直接构造响应（CNWebProxy 的 orig 返回非 null 即此路）",
         "拦截器")

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
