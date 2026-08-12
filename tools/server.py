#!/usr/bin/env python3
"""支持 Range 的测试用 HTTP 服务器。

通过查询参数控制异常行为，用来验证下载器的断点续传与错误处理：
  ?truncate=N       只发 N 字节就断流（模拟服务端提前关连接）
  ?etag=XXX         用指定的 ETag 覆盖默认值（模拟服务端换了文件）
  ?norange=1        忽略 Range 头，整份重发（模拟不支持 Range 的服务端）
  ?over=N           响应头仍声明请求区间，但正文额外多写 N 字节（模拟越界 body）
  ?oldtotal=N       206 的 Content-Range 里总长度谎报为 N
  ?corrupt_block=N  把第 N 个 block_size 分块中的一个字节翻转
  ?block_size=N     corrupt_block 使用的块长
  ?slow_others_ms=N 不含 corrupt_block 的 Range 延迟 N 毫秒

控制/观测端点：
  /settruncate?v=N  修改默认截断长度
  /setetag?v=XXX     修改默认 ETag
  /resetstats        清空 Range 请求与最大并发统计
  /stats             返回 JSON 统计
"""
import hashlib
import http.server
import json
import socketserver
import sys
import threading
import time
import urllib.parse

PAYLOAD = None
ETAG = '"v1-abc"'
TRUNCATE = 0
STATS_LOCK = threading.Lock()
ACTIVE = 0
MAX_ACTIVE = 0
RANGE_REQUESTS = []


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _url(self):
        return urllib.parse.urlparse(self.path)

    def _q(self):
        return urllib.parse.parse_qs(self._url().query)

    def _ok(self, body=b"ok", content_type="text/plain"):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_HEAD(self):
        q = self._q()
        etag = q.get("etag", [ETAG])[0]
        self.send_response(200)
        self.send_header("Content-Length", str(len(PAYLOAD)))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", etag)
        self.end_headers()

    def do_GET(self):
        global ETAG, TRUNCATE, ACTIVE, MAX_ACTIVE, RANGE_REQUESTS
        path = self._url().path

        if path == "/settruncate":
            TRUNCATE = int(self._q().get("v", ["0"])[0])
            self._ok()
            return
        if path == "/setetag":
            ETAG = self._q().get("v", ['"v2"'])[0]
            self._ok()
            return
        if path == "/resetstats":
            with STATS_LOCK:
                MAX_ACTIVE = ACTIVE
                RANGE_REQUESTS = []
            self._ok()
            return
        if path == "/stats":
            with STATS_LOCK:
                data = {
                    "active": ACTIVE,
                    "max_active": MAX_ACTIVE,
                    "requests": list(RANGE_REQUESTS),
                }
            self._ok(json.dumps(data, separators=(",", ":")).encode("utf-8"),
                     "application/json")
            return

        q = self._q()
        etag = q.get("etag", [ETAG])[0]
        truncate = int(q.get("truncate", [TRUNCATE])[0])
        over = int(q.get("over", [0])[0])
        oldtotal = int(q.get("oldtotal", [0])[0])
        norange = q.get("norange", ["0"])[0] == "1"
        corrupt_block = int(q.get("corrupt_block", ["-1"])[0])
        block_size = int(q.get("block_size", ["0"])[0])
        slow_others_ms = int(q.get("slow_others_ms", ["0"])[0])
        total = len(PAYLOAD)
        rng = self.headers.get("Range")

        active_recorded = False
        covers_corrupt = False
        body = None
        if rng and not norange:
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
            requested_end = min(end, total - 1)
            wire_end = min(requested_end + over, total - 1)
            body = bytearray(PAYLOAD[start:wire_end + 1])
            if corrupt_block >= 0 and block_size > 0:
                corrupt_at = corrupt_block * block_size
                covers_corrupt = start <= corrupt_at <= wire_end
                if covers_corrupt:
                    body[corrupt_at - start] ^= 0x5A

            with STATS_LOCK:
                ACTIVE += 1
                MAX_ACTIVE = max(MAX_ACTIVE, ACTIVE)
                RANGE_REQUESTS.append({
                    "start": start,
                    "end": requested_end,
                    "first_block": start // block_size if block_size > 0 else -1,
                    "last_block": requested_end // block_size if block_size > 0 else -1,
                })
            active_recorded = True
            if slow_others_ms > 0 and not covers_corrupt:
                time.sleep(slow_others_ms / 1000.0)

            self.send_response(206)
            # over 只模拟正文越界；资源身份与声明区间仍保持请求值。若把 end 也
            # 改大，严格客户端理应拒绝，那测试的是“头部造假”而不是越界 body。
            self.send_header("Content-Range", "bytes %d-%d/%d"
                             % (start, requested_end, oldtotal or total))
            self.send_header("Content-Length", str(requested_end - start + 1))
            if over > 0:
                self.send_header("Connection", "close")
            self.send_header("ETag", etag)
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
        else:
            body = bytearray(PAYLOAD)
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("ETag", etag)
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()

        try:
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
                self.wfile.flush()
                if over > 0:
                    self.close_connection = True
            except Exception:
                pass
        finally:
            if active_recorded:
                with STATS_LOCK:
                    ACTIVE -= 1


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
    print("payload sha256", hashlib.sha256(PAYLOAD).hexdigest(),
          "size", len(PAYLOAD), flush=True)
    srv = Server(("127.0.0.1", port), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print("READY", flush=True)
    try:
        while True:
            threading.Event().wait(3600)
    except KeyboardInterrupt:
        pass
