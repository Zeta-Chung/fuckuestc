"""mitmproxy 插件：抓取学校签发的 checkinTicket 与配对的 SESSION。

用法：
    mitmdump -s ticket_saver.py --listen-port 8080
（或 mitmweb -s ticket_saver.py --listen-port 8080）

命中 POST /platform-app/api/auth/checkin-ticket 时，把
{ticket, expiresAt, session, userAgent, capturedAt} 原子写入 ticket.json，
并追加一行日志到 capture.log。

注意：ticket.json 含 SESSION 与票据，等同账号凭据，不要外传或提交到仓库。
"""
from __future__ import annotations

import json
import os
import pathlib
import tempfile
import time

from mitmproxy import http

TARGET_PATH = "/platform-app/api/auth/checkin-ticket"
TARGET_HOST = "jzsz.uestc.edu.cn"
TICKET_LENGTH = 43
BASE_DIR = pathlib.Path(__file__).resolve().parent
OUT_FILE = pathlib.Path(os.environ.get("CHECKIN_TICKET_OUT", BASE_DIR / "ticket.json"))
LOG_FILE = pathlib.Path(os.environ.get("CHECKIN_CAPTURE_LOG", BASE_DIR / "capture.log"))


def log(message: str) -> None:
    line = f"{time.strftime('%Y-%m-%d %H:%M:%S')} {message}"
    print(f"[ticket] {message}", flush=True)
    try:
        with LOG_FILE.open("a", encoding="utf-8") as handle:
            handle.write(line + "\n")
    except Exception:  # noqa: BLE001
        pass


def session_from(flow: http.HTTPFlow) -> str:
    try:
        value = flow.request.cookies.get("SESSION", "")
    except Exception:  # noqa: BLE001
        value = ""
    if value:
        return value
    raw = flow.request.headers.get("Cookie", "")
    for part in raw.split(";"):
        name, _, val = part.strip().partition("=")
        if name.upper() == "SESSION":
            return val
    return ""


def atomic_write(path: pathlib.Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle = tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=str(path.parent), delete=False)
    try:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.flush()
        os.fsync(handle.fileno())
    finally:
        handle.close()
    os.replace(handle.name, path)


class TicketSaver:
    def response(self, flow: http.HTTPFlow) -> None:
        if TARGET_HOST not in flow.request.pretty_host:
            return
        if flow.request.path.split("?")[0] != TARGET_PATH:
            return
        if flow.response is None:
            return
        try:
            body = json.loads(flow.response.get_text() or "{}")
        except Exception:  # noqa: BLE001
            log("取票响应不是 JSON，已忽略")
            return
        data = body.get("data") or {}
        ticket = (data.get("ticket") or "").strip()
        if not ticket:
            log(f"取票响应没有 ticket（msg={body.get('msg') or '无'}）")
            return
        if len(ticket) != TICKET_LENGTH:
            log(f"票据长度异常（{len(ticket)} 字符），仍按原样保存")
        record = {
            "ticket": ticket,
            "expiresAt": data.get("expiresAt"),
            "session": session_from(flow),
            "userAgent": flow.request.headers.get("User-Agent", ""),
            "capturedAt": int(time.time() * 1000),
        }
        atomic_write(OUT_FILE, record)
        try:
            remaining = (int(record["expiresAt"]) - record["capturedAt"]) // 1000
        except Exception:  # noqa: BLE001
            remaining = -1
        log(f"已保存 {OUT_FILE.name}：票据 {ticket[:12]}…，剩余 {remaining} 秒，session={'有' if record['session'] else '缺失'}")


addons = [TicketSaver()]