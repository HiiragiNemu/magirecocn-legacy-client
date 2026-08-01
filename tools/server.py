#!/usr/bin/env python3
"""支持 Range 的测试用 HTTP 服务器。

通过查询参数控制异常行为，用来验证下载器的断点续传与错误处理：
  ?truncate=N  只发 N 字节就断流（模拟服务端提前关连接）
  ?etag=XXX    用指定的 ETag 覆盖默认值（模拟服务端换了文件）
  ?norange=1   忽略 Range 头，整份重发（模拟不支持 Range 的服务端）
  ?over=N      比请求的区间多发 N 字节（模拟越界响应）
"""
import hashlib
import http.server
import socketserver
import sys
import threading
import urllib.parse

PAYLOAD = None
ETAG = '"v1-abc"'
TRUNCATE = 0


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _q(self):
        return urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)

    def do_HEAD(self):
        q = self._q()
        etag = q.get("etag", [ETAG])[0]
        self.send_response(200)
        self.send_header("Content-Length", str(len(PAYLOAD)))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", etag)
        self.end_headers()

    def do_GET(self):
        global ETAG
        # 控制端点：切换服务端默认 ETag，用于模拟「同一 URL、文件被换掉」
        if urllib.parse.urlparse(self.path).path == "/settruncate":
            global TRUNCATE
            TRUNCATE = int(urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get("v",["0"])[0])
            self.send_response(200); self.send_header("Content-Length","2"); self.end_headers()
            self.wfile.write(b"ok"); return
        if urllib.parse.urlparse(self.path).path == "/setetag":
            ETAG = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get("v", ['"v2"'])[0]
            self.send_response(200); self.send_header("Content-Length","2"); self.end_headers()
            self.wfile.write(b"ok"); return
        q = self._q()
        etag = q.get("etag", [ETAG])[0]
        truncate = int(q.get("truncate", [TRUNCATE])[0])
        over = int(q.get("over", [0])[0])
        norange = q.get("norange", ["0"])[0] == "1"
        total = len(PAYLOAD)
        rng = self.headers.get("Range")

        if rng and not norange:
            # bytes=start-end
            spec = rng.split("=", 1)[1]
            s, _, e = spec.partition("-")
            start = int(s)
            end = int(e) if e else total - 1
            if start >= total:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % total)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            end = min(end + over, total - 1)
            body = PAYLOAD[start:end + 1]
            self.send_response(206)
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, total))
            self.send_header("Content-Length", str(len(body)))
            self.send_header("ETag", etag)
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
        else:
            body = PAYLOAD
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("ETag", etag)
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()

        if truncate > 0:
            # 只发一部分然后强行断开，Content-Length 仍然声称是完整长度
            try:
                self.wfile.write(body[:truncate])
                self.wfile.flush()
            except Exception:
                pass
            self.close_connection = True
            try:
                self.connection.close()
            except Exception:
                pass
            return
        try:
            self.wfile.write(body)
        except Exception:
            pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    size = int(sys.argv[1]) if len(sys.argv) > 1 else 2 * 1024 * 1024
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 877
    # 可复现的伪随机内容
    h = hashlib.sha256(b"magireco").digest()
    buf = bytearray()
    while len(buf) < size:
        h = hashlib.sha256(h).digest()
        buf += h
    PAYLOAD = bytes(buf[:size])
    with open("payload.bin", "wb") as f:
        f.write(PAYLOAD)
    print("payload sha256", hashlib.sha256(PAYLOAD).hexdigest(), "size", len(PAYLOAD), flush=True)
    srv = Server(("127.0.0.1", port), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print("READY", flush=True)
    try:
        while True:
            threading.Event().wait(3600)
    except KeyboardInterrupt:
        pass
