#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
给 ProxyFetchTest 用的可控 HTTP 服务器。

每个路径对应一种响应形态——那些在真机上才会遇到、读代码看不出来的形态：
条件请求的 304、跨协议跳转、上游 5xx、gzip、分块传输、中途断流。

它同时扮演两个角色：
  · 「代理」——路径前缀是 /stream/<host>/<其余>，把 <host> 剥掉后按其余部分作答，
    这样 CNWebProxy.rewriteWith 拼出来的 URL 能直接打到这里；
  · 「上游」——直连时用的也是同一个进程，省得起两个。

用法：
    python3 tools/proxy-test-server.py 8791
"""

import gzip
import io
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

JS_BODY = b"console.log('hello from proxy test');\n" * 40
ETAG = '"deadbeef"'


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass   # 安静点，测试输出已经够多了

    # ---- 工具 ----------------------------------------------------------

    def _route(self):
        """把 /stream/<host>/<rest> 归一化成 /<rest>；不带前缀的原样返回。"""
        p = self.path
        if p.startswith("/stream/"):
            rest = p[len("/stream/"):]
            slash = rest.find("/")
            return rest[slash:] if slash >= 0 else "/"
        return p

    def _send(self, code, body=b"", headers=None, reason=None):
        self.send_response(code, reason)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        if body or code not in (204, 304):
            self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    # ---- 各种响应形态 --------------------------------------------------

    def do_GET(self):
        r = self._route()

        if r == "/ok":
            self._send(200, JS_BODY,
                       {"Content-Type": "application/javascript; charset=utf-8",
                        "X-Marker": "plain"})

        elif r == "/gzip":
            # 只在客户端明确要 gzip 时压。HttpURLConnection 自己会加
            # Accept-Encoding: gzip 并透明解压——这正是要验的那条路。
            if "gzip" in (self.headers.get("Accept-Encoding") or ""):
                buf = io.BytesIO()
                with gzip.GzipFile(fileobj=buf, mode="wb") as g:
                    g.write(JS_BODY)
                gz = buf.getvalue()
                self._send(200, gz,
                           {"Content-Type": "application/javascript",
                            "Content-Encoding": "gzip",
                            "X-Marker": "gzipped"})
            else:
                self._send(200, JS_BODY,
                           {"Content-Type": "application/javascript",
                            "X-Marker": "plain-fallback"})

        elif r == "/conditional":
            # 带 If-None-Match 就回 304（空体）——这是「空文件喂给 WebView」那个坑
            if self.headers.get("If-None-Match") == ETAG:
                self._send(304, b"", {"ETag": ETAG})
            else:
                self._send(200, JS_BODY,
                           {"Content-Type": "application/javascript", "ETag": ETAG})

        elif r == "/redirect":
            # 跨协议跳转，HttpURLConnection 不会跟
            self._send(301, b"", {"Location": "http://example.invalid/moved"})

        elif r == "/err500":
            self._send(500, b"upstream boom", {"Content-Type": "text/plain"})

        elif r == "/err404":
            self._send(404, b"nope", {"Content-Type": "text/plain"})

        elif r == "/chunked":
            # 不给 Content-Length，走分块
            self.send_response(200)
            self.send_header("Content-Type", "text/css")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            for i in range(4):
                piece = b"/* chunk %d */\n" % i
                self.wfile.write(b"%X\r\n%s\r\n" % (len(piece), piece))
            self.wfile.write(b"0\r\n\r\n")

        elif r == "/echo":
            # 把收到的请求头原样回来，用来验哪些头被转发了
            got = {k.lower(): v for k, v in self.headers.items()}
            body = json.dumps(got, ensure_ascii=False).encode("utf-8")
            self._send(200, body, {"Content-Type": "application/json"})

        elif r == "/noctype":
            # 不给 Content-Type，验按扩展名兜底
            self._send(200, JS_BODY, {})

        else:
            self._send(404, b"unknown route")


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8791
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    srv.daemon_threads = True
    print("proxy-test-server 就绪 http://127.0.0.1:%d" % port, flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
