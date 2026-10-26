#!/usr/bin/env python3
"""用 PC 微信签发的 checkinTicket 提交打卡（仅用 Python 标准库）。

默认只做只读演练：读今天状态、读允许位置、检查坐标与票据有效期，不提交。
加 --submit 才真正提交，并在提交后复查 today；每次运行追加一条 JSON 到 relay.log。

防重复提交（实测：票据是分钟级一次性凭证，重复提交会生成第二条记录）：
    * 提交前把“意图”写入 submit-intent.json，提交后清空 ticket.json 里的票据；
    * 本进程只 POST 一次；超时或未知结果一律只回查 today，绝不重发；
    * 46004 视为“结果待确认”，保留意图，下次运行只回查；
    * 若发现当天已有未确认的意图，直接跳过提交改为回查（--force 可绕过）。

退出码：
    0 已打卡 / 提交并复查成功
    1 提交了但未确认为成功
    2 配置或票据问题（含票据过期、坐标超范围）
    3 网络或会话问题

用法：
    python relay_checkin.py                 # 演练
    python relay_checkin.py --submit        # 真提交
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
BASE = "https://jzsz.uestc.edu.cn"
TODAY_URL = BASE + "/daily-checkin/api/student-checkin-record/today"
LOCATIONS_URL = BASE + "/daily-checkin/api/checkin-location/available-locations"
CHECKIN_URL = BASE + "/daily-checkin/api/student-checkin-record/checkin"
REFERER = BASE + "/daily-checkin-h5/index.html?appId=294"
DEFAULT_LOG = BASE_DIR / "relay.log"

DEFAULT_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/144.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781(0x6700143B) NetType/WIFI "
    "MiniProgramEnv/Windows WindowsWechat/WMPF WindowsWechat(0x63090a13) "
    "UnifiedPCWindowsWechat(0xf2541b18) XWEB/25297"
)

TICKET_ERROR_CODES = {
    46001: "票据缺失或无效",
    46002: "票据已过期",
    46003: "票据与当前会话不匹配",
}

# 46004：服务端未给出确定结果，绝不能重发，只能回查 today。
PENDING_CODES = {
    46004: "提交结果待确认（只回查，不重发）",
}

# 实测：服务端不返回 expiresAt，票据是分钟级（21:34 签发 → 21:35 提交成功；1.5 小时后 46002）。
DEFAULT_MAX_TICKET_AGE_SECONDS = 300
INTENT_PATH = BASE_DIR / "submit-intent.json"

EXIT_OK = 0
EXIT_UNCONFIRMED = 1
EXIT_CONFIG = 2
EXIT_NETWORK = 3


def log(message: str) -> None:
    print(message, flush=True)


def write_log(evidence: dict, log_path: Path | None, status: str, error: str = "") -> None:
    if log_path is None:
        return
    entry = {"time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "status": status}
    if error:
        entry["error"] = error
    entry.update({key: value for key, value in evidence.items() if value is not None})
    try:
        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False) + "\n")
    except Exception:  # noqa: BLE001
        pass


def die(message: str, code: int = EXIT_CONFIG, evidence: dict | None = None, log_path: Path | None = None) -> None:
    log(f"错误：{message}")
    write_log(evidence or {}, log_path, status="error", error=message)
    sys.exit(code)


def load_json(path: Path, log_path: Path | None) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        die(f"找不到文件 {path}", EXIT_CONFIG, log_path=log_path)
    except Exception as exc:  # noqa: BLE001
        die(f"读取 {path} 失败：{exc}", EXIT_CONFIG, log_path=log_path)
    return {}


def write_json_atomic(path: Path, payload: dict) -> None:
    """先写临时文件再替换，避免中途崩溃留下半个 JSON。"""
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    temporary.replace(path)


def read_intent(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return {}


def business_date_now() -> str:
    return datetime.now().strftime("%Y-%m-%d")


SESSION_ID = ""


def with_session(url: str) -> str:
    """小程序用 URL 里的 sessionId 鉴权（不是 Cookie），提交时必须带上。"""
    if not SESSION_ID:
        return url
    separator = "&" if "?" in url else "?"
    return f"{url}{separator}sessionId={urllib.parse.quote(SESSION_ID)}"


def normalize_cookie_session(session: str) -> str:
    """抓包拿到的 Cookie 可能是 `SESSION=xxx` 形式，去掉前缀避免重复拼成 SESSION=SESSION=。"""
    value = (session or "").strip()
    if value.upper().startswith("SESSION="):
        value = value[len("SESSION="):]
    return value


def build_headers(ticket: str, session: str, user_agent: str, with_body: bool) -> dict[str, str]:
    headers = {
        "User-Agent": user_agent,
        "Platform": "h5",
        "Accept": "*/*",
        "Origin": BASE,
        "Referer": REFERER,
        "X-Requested-With": "com.tencent.mm",
        "Sec-Fetch-Site": "same-origin",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Dest": "empty",
        "Cookie": f"SESSION={session}",
    }
    if ticket:
        headers["X-Checkin-Ticket"] = ticket
    if with_body:
        headers["Content-Type"] = "application/json; charset=UTF-8"
    return headers


def call(url: str, ticket: str, session: str, user_agent: str, body: dict | None = None) -> tuple[int, dict | None, str]:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    request = urllib.request.Request(
        with_session(url),
        data=data,
        headers=build_headers(ticket, session, user_agent, data is not None),
        method="POST" if data else "GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            raw = response.read().decode("utf-8", "replace")
            status = response.status
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")
        status = exc.code
    except Exception as exc:  # noqa: BLE001
        return 0, None, str(exc)
    try:
        return status, json.loads(raw), raw
    except Exception:  # noqa: BLE001
        return status, None, raw


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius = 6371000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = phi2 - phi1
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(a))


def today_status(envelope: dict | None) -> str:
    return ((envelope or {}).get("data") or {}).get("recordStatusCode") or "UNKNOWN"


def describe_today(envelope: dict | None) -> str:
    data = (envelope or {}).get("data") or {}
    parts = [f"状态={data.get('recordStatusCode') or 'UNKNOWN'}"]
    if data.get("businessDate"):
        parts.append(f"业务日期={data['businessDate']}")
    if data.get("checkinTime"):
        parts.append(f"打卡时间={data['checkinTime']}")
    if data.get("abnormalFlag") is not None:
        parts.append(f"异常标记={data['abnormalFlag']}")
    return "，".join(parts)


def main() -> int:
    parser = argparse.ArgumentParser(description="用微信签发的 checkinTicket 提交打卡")
    parser.add_argument("--ticket", default=str(BASE_DIR / "ticket.json"), help="ticket_saver.py 落盘的文件")
    parser.add_argument("--config", default=str(BASE_DIR / "relay-config.json"), help="坐标与定位字段配置")
    parser.add_argument("--log", default=str(DEFAULT_LOG), help="JSONL 证据日志路径")
    parser.add_argument("--submit", action="store_true", help="真正提交（默认只读演练）")
    parser.add_argument(
        "--status-only",
        action="store_true",
        help="只查今日状态就退出（不需要票据；已打卡返回 0，未打卡返回 1）",
    )
    parser.add_argument("--force", action="store_true", help="忽略票据过期、位置超范围与已打卡判断")
    parser.add_argument("--user-agent", default=None, help="覆盖提交使用的 UA")
    parser.add_argument(
        "--max-ticket-age",
        type=int,
        default=DEFAULT_MAX_TICKET_AGE_SECONDS,
        help="票据最大可接受签发时长（秒），超过即视为过期",
    )
    args = parser.parse_args()

    log_path = Path(args.log)
    record = load_json(Path(args.ticket), log_path)
    ticket = (record.get("ticket") or "").strip()
    session = normalize_cookie_session(record.get("session") or "")
    global SESSION_ID
    SESSION_ID = (record.get("sessionId") or "").strip()
    evidence: dict = {
        "ticket": ticket[:12],
        "session": (session[:8] + "…") if session else "",
        "sessionId": (SESSION_ID[:8] + "…") if SESSION_ID else "",
    }

    if args.status_only:
        # 先看今天是否已经打过卡：打过就不必再去等票、更不该再提交。
        if not session:
            die("ticket.json 里没有 session，无法查询今日状态", EXIT_CONFIG, evidence, log_path)
        user_agent = args.user_agent or (record.get("userAgent") or "").strip() or DEFAULT_UA
        status, today, raw = call(TODAY_URL, "", session, user_agent)
        evidence["todayHttp"] = status
        evidence["todayBefore"] = today_status(today)
        if today is None:
            log(f"今日状态查询失败：HTTP {status} · {raw[:120]}")
            write_log(evidence, log_path, status="status-error")
            return EXIT_NETWORK
        state = today_status(today)
        log(f"今日状态：{describe_today(today)}")
        write_log(evidence, log_path, status="status-only")
        return EXIT_OK if state == "CHECKED_IN" else EXIT_UNCONFIRMED

    if not ticket:
        die("ticket.json 里没有 ticket，请先运行取票抓包", EXIT_CONFIG, evidence, log_path)
    if not session:
        die("ticket.json 里没有 session，请确认抓包时带有 SESSION Cookie", EXIT_CONFIG, evidence, log_path)

    now_ms = int(time.time() * 1000)
    try:
        expires_at = int(record.get("expiresAt") or 0)
    except Exception:  # noqa: BLE001
        expires_at = 0
    if not expires_at:
        captured_at = int(record.get("capturedAt") or 0)
        expires_at = captured_at + args.max_ticket_age * 1000 if captured_at else 0
    remaining = (expires_at - now_ms) // 1000 if expires_at else -1
    evidence["ticketRemainingSeconds"] = remaining
    log(f"票据：{ticket[:12]}…（共 {len(ticket)} 字符），剩余有效期 {remaining} 秒")
    if remaining <= 0 and not args.force:
        die(
            f"票据已过期（允许签发时长 {args.max_ticket_age} 秒），请重新在小程序打开打卡页取票（或加 --force）",
            EXIT_CONFIG,
            evidence,
            log_path,
        )

    config = load_json(Path(args.config), log_path)
    latitude = float(config["latitude"])
    longitude = float(config["longitude"])
    user_agent = args.user_agent or (record.get("userAgent") or "").strip() or DEFAULT_UA
    log(f"坐标：{latitude},{longitude}")

    status, today, raw = call(TODAY_URL, ticket, session, user_agent)
    evidence["todayHttp"] = status
    evidence["todayBefore"] = today_status(today)
    log(f"只读检查：HTTP {status} · {describe_today(today) if today else raw[:120]}")
    if today is None:
        die(f"无法解析 today 响应（HTTP {status}），会话可能已失效", EXIT_NETWORK, evidence, log_path)
    if today_status(today) == "CHECKED_IN" and not args.force:
        log("今天已经打过卡，无需提交。")
        write_log(evidence, log_path, status="already-checked-in")
        return EXIT_OK

    status, locations, raw = call(LOCATIONS_URL, ticket, session, user_agent)
    if locations and isinstance(locations.get("data"), list):
        best = None
        for item in locations["data"]:
            try:
                item_lat = float(item.get("latitude"))
                item_lon = float(item.get("longitude"))
                radius = float(item.get("radiusMeter", 0))
            except Exception:  # noqa: BLE001
                continue
            meters = distance_m(latitude, longitude, item_lat, item_lon)
            if best is None or meters - radius < best[0]:
                best = (meters - radius, meters, radius, item_lat, item_lon)
        if best is None:
            log("未读到可解析的允许位置，跳过范围检查。")
        else:
            _, meters, radius, item_lat, item_lon = best
            inside = meters <= radius
            evidence["distanceMeters"] = round(meters, 1)
            evidence["radiusMeters"] = radius
            evidence["locationInside"] = inside
            log(f"位置检查：最近打卡点 {item_lat},{item_lon}，距离 {meters:.0f} 米，半径 {radius:.0f} 米 → {'在范围内' if inside else '超出范围'}")
            if not inside and not args.force:
                die("坐标不在允许范围内，请改 relay-config.json 或加 --force", EXIT_CONFIG, evidence, log_path)
    else:
        log(f"位置检查：HTTP {status}，响应无法解析，跳过。")

    if not args.submit:
        log("演练结束：加 --submit 才会真正提交。")
        write_log(evidence, log_path, status="dry-run")
        return EXIT_OK

    payload = {
        "latitude": round(latitude, 6),
        "longitude": round(longitude, 6),
        "reverseGeocode": config.get("reverseGeocode", ""),
        "clientTime": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "userAgent": user_agent,
        "coordinateSystemCode": "gcj02",
        "accuracy": int(config.get("accuracy", 30)),
        "locationDurationMs": int(config.get("locationDurationMs", 600)),
        "networkTypeCode": config.get("networkTypeCode", "wifi"),
        "locationEnabled": bool(config.get("locationEnabled", True)),
        "locationAuthorized": config.get("locationAuthorized", "authorized"),
        "locationAccuracy": config.get("locationAccuracy", "full"),
        "errorMessage": config.get("errorMessage", ""),
    }
    intent = read_intent(INTENT_PATH)
    today_business_date = business_date_now()
    if (
        intent.get("status") == "intent"
        and intent.get("businessDate") == today_business_date
        and not args.force
    ):
        log("检测到今天的提交意图未确认（上次可能超时/46004），只回查，不重发。")
        time.sleep(2)
        status, today2, raw2 = call(TODAY_URL, ticket, session, user_agent)
        evidence["todayAfter"] = today_status(today2)
        log(f"回查：HTTP {status} · {describe_today(today2) if today2 else raw2[:120]}")
        if today_status(today2) == "CHECKED_IN":
            intent.update(
                {
                    "status": "checked-in",
                    "resolvedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                    "note": "重跑时回查确认，未重复提交",
                }
            )
            write_json_atomic(INTENT_PATH, intent)
            write_log(evidence, log_path, status="checked-in")
            return EXIT_OK
        write_log(evidence, log_path, status="pending-confirmation")
        return EXIT_UNCONFIRMED

    # 提交前先落盘“意图”：即使进程被杀，下次运行也知道已经发过、不能再发。
    write_json_atomic(
        INTENT_PATH,
        {
            "businessDate": today_business_date,
            "status": "intent",
            "startedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "ticket": ticket[:12],
            "sessionId": (SESSION_ID[:8] + "…") if SESSION_ID else "",
            "latitude": payload["latitude"],
            "longitude": payload["longitude"],
            "payload": payload,
        },
    )

    # 单次提交：本进程只 POST 一次，超时或未知结果一律转为回查。
    status, submitted, raw = call(CHECKIN_URL, ticket, session, user_agent, payload)
    code = (submitted or {}).get("code")
    message = (submitted or {}).get("msg") or (submitted or {}).get("message") or raw[:160]
    evidence["checkinHttp"] = status
    evidence["checkinCode"] = code
    evidence["checkinMessage"] = message
    log(f"提交：HTTP {status} · code={code} · {message}")
    if isinstance(code, int) and code in TICKET_ERROR_CODES:
        log(f"提示：{TICKET_ERROR_CODES[code]}，需重新取票；本次未产生记录。")
    elif isinstance(code, int) and code in PENDING_CODES:
        log(f"提示：{PENDING_CODES[code]}。")

    # 票据一次性使用：无论结果如何都立刻清掉，防止重放产生第二条记录。
    record["ticket"] = ""
    record["usedAt"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    record["lastResult"] = {"code": code, "message": message}
    write_json_atomic(Path(args.ticket), record)

    time.sleep(2)
    status, today2, raw2 = call(TODAY_URL, ticket, session, user_agent)
    evidence["todayAfter"] = today_status(today2)
    log(f"复查：HTTP {status} · {describe_today(today2) if today2 else raw2[:120]}")
    if today_status(today2) == "CHECKED_IN":
        log("结果：打卡成功。")
        intent.update(
            {
                "status": "checked-in",
                "resolvedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "code": code,
                "message": message,
            }
        )
        write_json_atomic(INTENT_PATH, intent)
        write_log(evidence, log_path, status="checked-in")
        return EXIT_OK

    if isinstance(code, int) and code in PENDING_CODES:
        log("结果：待确认（46004），提交意图保留，下次只回查不重发。")
        write_log(evidence, log_path, status="pending-confirmation")
        return EXIT_UNCONFIRMED

    if isinstance(code, int) and code in TICKET_ERROR_CODES:
        intent.update(
            {
                "status": "rejected",
                "resolvedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "code": code,
                "message": message,
            }
        )
        write_json_atomic(INTENT_PATH, intent)

    log("结果：未确认为成功，请到小程序或学校页面核对。")
    write_log(evidence, log_path, status="unconfirmed")
    return EXIT_UNCONFIRMED


if __name__ == "__main__":
    sys.exit(main())