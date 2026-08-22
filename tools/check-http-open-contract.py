#!/usr/bin/env python3
"""守卫「建连只有一个加固入口」，以及两条有理由的例外。

## 为什么要钉

同一段建连样板原先在六个类里各写一遍，而它们已经漂移了：

    缺 setInstanceFollowRedirects(true)：CNMirrors 测速探针、CNDownloaderFix 的
                                         续传取回与 POST
    缺 setUseCaches(false)            ：CNVersionCheck

两处当时都不是活的故障——JVM 的 followRedirects 默认是 true，Android 也默认不装
HTTP 响应缓存，所以省略与显式设置等价。危险在于这份「等价」是靠平台默认恰好合我
们的意：任何一处将来调了 HttpURLConnection.setFollowRedirects(false) 或装上
HttpResponseCache，这些省略会同时变成真故障，而且各家表现还不一样。

## 两条例外必须留着

* CNAria2 的 loopback JSON-RPC —— 它**有意**把 setInstanceFollowRedirects 设成
  false：本地 RPC 不该跟任何跳转走。套统一口径正好把这条安全性质抹掉。
* CNWebProxy 的取数与测速 —— 那是透明代理，要原样转发调用方的头；强设缓存策略
  或 Accept-Encoding 会改变被代理请求的语义。

守卫连例外一起钉，是为了让「为什么这两处不统一」有个会红的落点，而不是等着谁
「顺手统一一下」。
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _guardlib import code   # noqa: E402

JAVA = Path("patch/src/main/java/io/kamihama/magianative")
srcs = {p.name: code(p.read_text(encoding="utf-8")) for p in JAVA.glob("*.java")}

ALLOWED = {"CNHttp.java", "CNAria2.java", "CNWebProxy.java"}
offenders = sorted(
    name for name, src in srcs.items()
    if ".openConnection(" in src and name not in ALLOWED
)

http = srcs.get("CNHttp.java", "")
aria2 = srcs.get("CNAria2.java", "")

# 统一入口必须把四件事都做齐；少一件就等于把漂移搬进了唯一实现
core = {
    "setConnectTimeout(connectMs)": "setConnectTimeout(connectMs)" in http,
    "setReadTimeout(readMs)": "setReadTimeout(readMs)" in http,
    "setUseCaches(false)": "setUseCaches(false)" in http,
    "setInstanceFollowRedirects(true)": "setInstanceFollowRedirects(true)" in http,
    "CNUserAgent.apply(c)": "CNUserAgent.apply(c)" in http,
}

checks = {
    "除统一入口与两条例外外，没有别处直接 openConnection":
        not offenders,
    "统一入口把五项加固做齐":
        all(core.values()),
    "统一入口能表达「绕开系统代理」与「尊重系统代理」两种语义":
        "Proxy.NO_PROXY" in http and "forceDirect" in http,
    "例外一：aria2 的 loopback RPC 仍然不跟跳转":
        "setInstanceFollowRedirects(false)" in aria2,
    "例外的理由写在统一入口的注释里（不是靠口口相传）":
        "CNAria2" in srcs.get("CNHttp.java", "")
        or "CNAria2" in (JAVA / "CNHttp.java").read_text(encoding="utf-8"),
    "超时仍由调用方传（控制面要快失败、数据面要能等，不该统一）":
        bool(re.search(r"open\s*\([^)]*int\s+connectMs\s*,\s*int\s+readMs", http)),
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS " if ok else "FAIL ") + name)
if offenders:
    print("     直接建连的文件: " + ", ".join(offenders))
for k, ok in core.items():
    if not ok:
        print("     统一入口缺: " + k)
if failed:
    raise SystemExit("http open contract failed: " + ", ".join(failed))
