#!/usr/bin/env python3
"""按引擎自带 OpenSSL 的能力包络，起一个本地 TLS 端点并自测。

## 这个脚本要回答的问题

「能不能让 native 引擎跟一个我们自己起的本地服务端说话」——这是「服务端逻辑
自己做」这条路线**唯一的技术死穴**。之前一直以为死穴是信任链（要么拿到私钥、
要么改二进制），2026-08-22 从 libmadomagi_native.so 里挖出来的结论是反的：

    ┌ 内置 CA bundle ······················ 没有
    ├ OPENSSLDIR ·························· /Users/sugano_ai/build-script-for-library/
    │                                       openssl/output/android/{abi}/
    │                                       —— 打包机路径，设备上不存在
    ├ SSL_CTX_set_verify ·················· 全库 0 次调用
    ├ SSL_CTX_load_verify_locations ······· 全库 0 次调用
    ├ SSL_CTX_set_cert_verify_callback ···· 全库 0 次调用
    └ SSL_set_verify ······················ 2 次，但都是
                                            SSL_get_verify_mode() 取出再原样传回
                                            （只换回调，不改模式）

SSL_CTX 由 SSL_CTX_new() 新建，OpenSSL 对新 CTX 的默认验证模式是
SSL_VERIFY_NONE，而全库没有任何地方改过它。建连点在
`http2::Http2SessionManager::run`（arm64 0xa00434）：

    0xa00484  boost::asio::ssl::context::context(method=0xf)   ← 0xf = tlsv12
    0xa0048c  context::set_default_verify_paths()              ← 挂了个不存在的目录
    0xa00498  nghttp2::asio_http2::client::configure_tls_context()  ← 只设 ALPN
              （没有 set_verify_mode）

`rfc2818_verification` 回调确实装了（0x112a6cc），但在 SSL_VERIFY_NONE 下
OpenSSL 会调用它、然后**忽略它的结论**，握手照样继续。

**所以自签名证书它照收不误。** 那次 0x140920E3（SSL3_GET_SERVER_HELLO /
SSL_R_PARSE_TLSEXT）不是信任问题，是**协议代差**：引擎带的是 OpenSSL 1.0.2s
（2019-05-28），全库 0 处 TLS 1.3 痕迹，而代理那端是现代栈。

## 于是「墙」变成了一张配置清单

本脚本把那张清单固化成可执行的东西：起一个只说 TLS 1.2 的自签名端点，再用一个
**刻意退化到 1.0.2 能力**的客户端去连，把协商结果逐条核对。清单从二进制实测：

    TLS 版本   仅 TLSv1.2（引擎无 TLS1.3，且 ctx 明确建成 tlsv12）
    EC 曲线    仅 prime256v1 / secp384r1 / secp521r1
               ⚠ 绝不能协商到 X25519：引擎里 X25519 符号数为 0（1.1.0 才有）
    扩展       不得出现 extended_master_secret（1.1.0 才有，引擎串数为 0）
    签名算法   ServerKeyExchange 不得用 RSA-PSS（1.1.1 才有）。这条是跑出来的：
               现代 openssl 客户端默认提 rsa_pss_rsae_*，服务端就用 PSS 签；
               把客户端 sigalgs 限成 1.0.2 的集合后才回落到 PKCS1。服务端本身
               配得对，但只有忠实的客户端才逼得出正确分支——所以探针不限定
               sigalgs 的话，给的是**假绿灯**。
    ALPN       必须协商出 h2（引擎走 nghttp2，协商不到就不是 HTTP/2）
    证书       RSA-2048 / SHA-256 自签名即可——反正它不验

## ⚠ 这个脚本证明不了什么

它证明的是「服务端这面按 1.0.2 包络配得出来，且能被一个受限客户端连上」。
它**不能**证明引擎真的连得上——那需要在真机上把 UrlConfig::api 指过来跑一次。
本机没有 qemu-user，也没有 aarch64 运行时，跑不了引擎自带的那份 OpenSSL。

所以：绿灯 = 服务端配置没问题，可以上真机验；红灯 = 连这一关都没过，别上真机了。
"""

import argparse
import http.server
import shutil
import ssl
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

# ── 从 libmadomagi_native.so 实测出来的包络 ────────────────────────
ENGINE_OPENSSL = "OpenSSL 1.0.2s  28 May 2019"
# 1.0.2s 有、且现代 OpenSSL 也还留着的交集。顺序即优先级。
CIPHERS = ":".join([
    "ECDHE-RSA-AES128-GCM-SHA256",
    "ECDHE-RSA-AES256-GCM-SHA384",
    "ECDHE-RSA-AES128-SHA256",
    "AES128-GCM-SHA256",          # 纯 RSA 密钥交换兜底
])
# 引擎里没有 X25519（符号数 0），只认这三条 NIST 曲线
CURVES = ["prime256v1", "secp384r1", "secp521r1"]
ALPN = ["h2"]


def make_self_signed(dirpath: Path, cn: str):
    """RSA-2048 / SHA-256 自签名。引擎不验证，所以这里只求它是 1.0.2 解析得动的形状。"""
    key = dirpath / "key.pem"
    crt = dirpath / "cert.pem"
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256",
         "-days", "365", "-nodes",
         "-keyout", str(key), "-out", str(crt),
         "-subj", f"/CN={cn}",
         "-addext", f"subjectAltName=DNS:{cn},DNS:localhost,IP:127.0.0.1"],
        check=True, capture_output=True,
    )
    return key, crt


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):                      # noqa: N802
        body = b'{"ok":true}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):             # 静音：握手才是重点
        pass


def build_server_context(key: Path, crt: Path) -> ssl.SSLContext:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    # 只说 TLS 1.2。这是整张清单里最要紧的一条：现代栈默认会谈到 1.3，
    # 而 1.3 的 ServerHello 里那些扩展正是 1.0.2 解析不动、报
    # SSL_R_PARSE_TLSEXT 的东西。
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.maximum_version = ssl.TLSVersion.TLSv1_2
    ctx.set_ciphers(CIPHERS)
    # 曲线：Python 只暴露单条 set_ecdh_curve，取 1.0.2 与现代都认的 P-256。
    ctx.set_ecdh_curve("prime256v1")
    ctx.set_alpn_protocols(ALPN)
    ctx.load_cert_chain(certfile=str(crt), keyfile=str(key))
    return ctx


# 1.0.2s 会提议的签名算法。⚠ 少了这一条，探针就是**假绿灯**：
# 现代 openssl 客户端默认会提 rsa_pss_rsae_*，服务端于是用 RSA-PSS 签
# ServerKeyExchange——而 TLS 里的 RSA-PSS 是 OpenSSL 1.1.1 才有的，1.0.2 不认。
# 实测：不限定时协商出 "Peer signature type: RSA-PSS"，限定后回落到 "RSA"（PKCS1）。
# 也就是说服务端本身是配得对的，但只有忠实的客户端才逼得出正确分支。
SIGALGS_102 = "RSA+SHA256:RSA+SHA384:RSA+SHA1"


def probe(host: str, port: int) -> dict:
    """用一个刻意退化到 1.0.2 能力的客户端去连，回报协商结果。

    退化的四件事必须齐：不许 TLS1.3、不许 X25519、只用 1.0.2 有的 cipher、
    只提 1.0.2 有的 sigalgs。少一件，这个探针就会用现代能力连上去，绿灯没有意义。

    ⚠ 不能加 -brief：那个模式**不打印 ALPN 行**，会把「协商成功」误判成
    「没协商出来」。这一条是实测撞出来的。
    """
    out = subprocess.run(
        ["openssl", "s_client", "-connect", f"{host}:{port}",
         "-servername", host,
         "-tls1_2",                       # 不许谈到 1.3
         "-cipher", CIPHERS,
         "-groups", "P-256:P-384:P-521",  # 不许 X25519
         "-sigalgs", SIGALGS_102,         # 不许 RSA-PSS
         "-alpn", "h2"],
        input=b"", capture_output=True, timeout=30,
    )
    text = (out.stdout or b"").decode("utf-8", "replace") + \
           (out.stderr or b"").decode("utf-8", "replace")
    info = {"raw": text}
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("Protocol  :"):
            info["version"] = s.split(":", 1)[1].strip()
        elif s.startswith("Cipher    :"):
            info["cipher"] = s.split(":", 1)[1].strip()
        elif s.startswith("ALPN protocol:"):
            info["alpn"] = s.split(":", 1)[1].strip()
        elif s.startswith("Server Temp Key:"):
            info["group"] = s.split(":", 1)[1].strip()
        elif s.startswith("Peer signature type:"):
            info["sigtype"] = s.split(":", 1)[1].strip()
    return info


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=0, help="0 = 随机端口")
    ap.add_argument("--serve", action="store_true",
                    help="只起服务端并一直跑（给真机连），不自测")
    args = ap.parse_args()

    if not shutil.which("openssl"):
        print("✘ 找不到 openssl 命令，无法生成证书/自测")
        return 2

    tmp = Path(tempfile.mkdtemp(prefix="tls12probe-"))
    key, crt = make_self_signed(tmp, args.host)
    ctx = build_server_context(key, crt)

    srv = http.server.ThreadingHTTPServer((args.host, args.port), Handler)
    srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    port = srv.socket.getsockname()[1]
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()

    print(f"引擎 OpenSSL : {ENGINE_OPENSSL}")
    print(f"本地端点     : https://{args.host}:{port}")
    print(f"证书         : {crt}")
    if args.serve:
        print("\n服务端已起，Ctrl-C 结束。真机验证步骤见脚本头注释。")
        try:
            t.join()
        except KeyboardInterrupt:
            pass
        return 0

    info = probe(args.host, port)
    checks = {
        "握手成功":                 bool(info.get("version")),
        "协议版本是 TLSv1.2":       info.get("version") == "TLSv1.2",
        "ALPN 协商出 h2":           info.get("alpn") in ("h2", "h2 "),
        "cipher 在 1.0.2 也有":     info.get("cipher", "") in CIPHERS.split(":"),
        "临时密钥没落到 X25519":     "X25519" not in info.get("group", ""),
        # 见 SIGALGS_102 的注释：PSS 是 1.1.1 才有的，落到 PSS 就等于真机必崩
        "签名没落到 RSA-PSS":       "PSS" not in info.get("sigtype", ""),
    }
    for name, ok in checks.items():
        print(("PASS " if ok else "FAIL ") + name)
    print(f"     实测：version={info.get('version')} cipher={info.get('cipher')}")
    print(f"           alpn={info.get('alpn')} group={info.get('group')} "
          f"sig={info.get('sigtype')}")

    srv.shutdown()
    failed = [k for k, v in checks.items() if not v]
    if failed:
        print("\n✘ 服务端配置就没过关，别上真机：" + ", ".join(failed))
        print(info.get("raw", "")[:1200])
        return 1
    print("\n✔ 服务端按 1.0.2 包络配得出来，且受限客户端连得上。")
    print("  ⚠ 这不等于引擎连得上——最终证明必须在真机上把 UrlConfig::api")
    print("    指到这个端点跑一次。本机没有 qemu-user，跑不了引擎那份 OpenSSL。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
