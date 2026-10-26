"""极简 TLS 反向代理：把发往 jzsz.uestc.edu.cn 的请求转给真实服务器，并顺手保存打卡票据。

为什么不用 mitmproxy 自带的 reverse 模式：
1. mitmproxy 12 没有 DNS 覆盖选项，hosts 把域名指向本机后，上游也会被解析成本机 → 自己连自己；
2. 反向模式按上游地址生成证书，客户端会因证书主机名不匹配而拒绝。

因此：证书用 mitmproxy 的 CA 为真实域名签发（客户端已信任该 CA），
上游固定连真实 IP，Host 头保持真实域名。

用法：
    python mini_tls_proxy.py            # 监听 127.0.0.1:443
    python mini_tls_proxy.py --port 8443
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import gzip
import http.client
import http.server
import json
import os
import re
import ssl
import sys
import threading
import zlib

HOSTNAME = "jzsz.uestc.edu.cn"
UPSTREAM_IP = "222.197.166.2"
UPSTREAM_PORT = 443

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFDIR = os.path.join(os.path.expanduser("~"), ".mitmproxy")
CERT_FILE = os.path.join(BASE_DIR, "server-cert.pem")
KEY_FILE = os.path.join(BASE_DIR, "server-key.pem")
TICKET_FILE = os.path.join(BASE_DIR, "ticket.json")
CAPTURE_LOG = os.path.join(BASE_DIR, "capture.log")

TICKET_PATH = re.compile(r"/api/auth/checkin-ticket")
TICKET_PATTERN = re.compile(r"^[A-Za-z0-9_-]{43}$")
# 兜底：打卡页 URL 里就带着票据和会话（小程序领取票据后拼进 webview 地址）
TICKET_IN_URL = re.compile(r"checkinTicket=([A-Za-z0-9_-]{43})")
SESSION_IN_URL = re.compile(r"sessionId=([0-9a-zA-Z\-]{8,64})")


def decompress(payload: bytes, encoding: str) -> bytes:
    """按 Content-Encoding 解压：mitmproxy/微信都会协商 gzip。"""
    encoding = (encoding or "").lower()
    try:
        if "gzip" in encoding:
            return gzip.decompress(payload)
        if "deflate" in encoding:
            return zlib.decompress(payload)
    except (OSError, zlib.error):
        return payload
    return payload

HOP_BY_HOP = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade",
}


def log(message: str) -> None:
    line = f"{dt.datetime.now():%Y-%m-%d %H:%M:%S} {message}"
    print(line, flush=True)
    try:
        with open(CAPTURE_LOG, "a", encoding="utf-8") as handle:
            handle.write(line + "\n")
    except OSError:
        pass


def ensure_certificate() -> None:
    """用 mitmproxy 的 CA 为真实域名签发证书（已存在则复用）。"""
    if os.path.exists(CERT_FILE) and os.path.exists(KEY_FILE):
        return
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization
    from mitmproxy import certs

    store = certs.CertStore.from_store(CONFDIR, "mitmproxy", 2048)
    # 必须带 SAN：现代客户端（Chromium / WMPF）只认 subjectAltName，没有 SAN 会直接拒绝握手
    entry = store.get_cert(HOSTNAME, [x509.DNSName(HOSTNAME)])
    with open(CERT_FILE, "wb") as handle:
        handle.write(entry.cert.to_pem())
    key = entry.privatekey
    pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    with open(KEY_FILE, "wb") as handle:
        handle.write(pem)
    log(f"已生成 {HOSTNAME} 的证书（由 mitmproxy CA 签发）")


def write_ticket(ticket: str, expires_at, user_agent: str, cookie: str, session: str) -> None:
    if not TICKET_PATTERN.match(ticket):
        log(f"票据格式不符（{len(ticket)} 字符），忽略")
        return
    record = {
        "ticket": ticket,
        "expiresAt": expires_at,
        "capturedAt": int(dt.datetime.now().timestamp() * 1000),
        "userAgent": user_agent,
        # 提交打卡要用同一个小程序会话，否则服务端会判「登录无效」
        "sessionId": session,
        "session": cookie,
    }
    tmp = TICKET_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as handle:
        json.dump(record, handle, ensure_ascii=False)
    os.replace(tmp, TICKET_FILE)
    log(f"票据已保存：{ticket[:12]}…（{len(ticket)} 字符，sessionId={session or '无'}）")


def save_ticket(payload: bytes, content_encoding: str, user_agent: str, cookie: str) -> None:
    body = decompress(payload, content_encoding)
    try:
        data = json.loads(body.decode("utf-8", "replace"))
    except json.JSONDecodeError:
        log(f"取票响应不是 JSON（{len(body)} 字节），等页面 URL 兜底提取")
        return
    inner = data.get("data") if isinstance(data, dict) else None
    ticket = (inner or {}).get("ticket") if isinstance(inner, dict) else None
    if not ticket:
        log(f"取票响应里没有票据（code={data.get('code') if isinstance(data, dict) else '?'}）")
        return
    write_ticket(ticket, (inner or {}).get("expiresAt"), user_agent, cookie, "")


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "CheckinRelay/1.0"

    def log_message(self, fmt: str, *args) -> None:  # noqa: A003
        return

    def _forward(self) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None

        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_BY_HOP}
        headers["Host"] = HOSTNAME

        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        connection = http.client.HTTPSConnection(UPSTREAM_IP, UPSTREAM_PORT, context=context, timeout=30)
        try:
            connection.request(self.command, self.path, body=body, headers=headers)
            response = connection.getresponse()
            payload = response.read()
        except Exception as exc:  # noqa: BLE001
            log(f"转发失败 {self.command} {self.path}: {exc}")
            self.send_error(502, "upstream failed")
            return
        finally:
            connection.close()

        self.send_response(response.status)
        for key, value in response.getheaders():
            if key.lower() not in HOP_BY_HOP and key.lower() != "content-length":
                self.send_header(key, value)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

        log(f"{self.command} {self.path} → {response.status} ({len(payload)} 字节)")
        cookie = self.headers.get("Cookie", "")
        user_agent = self.headers.get("User-Agent", "")
        if TICKET_PATH.search(self.path):
            save_ticket(payload, response.getheader("Content-Encoding"), user_agent, cookie)

        # 兜底：打卡页地址里会带 checkinTicket 与 sessionId
        ticket_match = TICKET_IN_URL.search(self.path)
        if ticket_match:
            session_match = SESSION_IN_URL.search(self.path)
            write_ticket(
                ticket_match.group(1),
                None,
                user_agent,
                cookie,
                session_match.group(1) if session_match else "",
            )

    do_GET = _forward
    do_POST = _forward
    do_PUT = _forward
    do_DELETE = _forward


def serve(port: int) -> int:
    ensure_certificate()
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(CERT_FILE, KEY_FILE)
    context.set_alpn_protocols(["http/1.1"])

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    httpd.socket = context.wrap_socket(httpd.socket, server_side=True)
    log(f"监听 127.0.0.1:{port}，转发 {HOSTNAME} → {UPSTREAM_IP}:{UPSTREAM_PORT}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        log("已停止")
    finally:
        httpd.server_close()
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="jzsz.uestc.edu.cn 的极简 TLS 反向代理")
    parser.add_argument("--port", type=int, default=443)
    args = parser.parse_args()
    return serve(args.port)


if __name__ == "__main__":
    sys.exit(main())
