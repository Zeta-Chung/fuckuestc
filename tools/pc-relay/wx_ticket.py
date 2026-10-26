"""自行取票：用微信 OAuth 凭据换出小程序 code，再换 checkinTicket。

替代「人工打开打卡页 + 读微信缓存」这一步。凭据来自一次人工扫码（`--qr`），
之后靠 refresh_token 续期；票据仍由服务端签发，和之前完全一样。

用法：
    python wx_ticket.py --qr        # 首次授权：生成二维码图片并等待扫码
    python wx_ticket.py --mint      # 取一张新票并写入 ticket.json
    python wx_ticket.py --status    # 只看凭据状态（不联网）

安全约定：不打印凭据、code、票据明文，只打印长度与状态码。
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import time

BASE_DIR = pathlib.Path(__file__).resolve().parent
VENDOR = BASE_DIR / "vendor" / "site"
for path in (VENDOR, BASE_DIR):
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))

APP_ID = "wxaa78bf2905f01cee"  # 目标小程序 AppID（晚安成电 / 成电智慧学工）
MINT_URL = "https://jzsz.uestc.edu.cn/platform-app/api/auth/checkin-ticket"
CREDS_FILE = BASE_DIR / "wx-credentials.json"
TICKET_FILE = BASE_DIR / "ticket.json"
QR_FILE = BASE_DIR / "wx-qr.png"


def log(message: str) -> None:
    print(f"[wx] {message}", flush=True)


def load_creds():
    from moon_uestc import WeChatCredentials

    data = json.loads(CREDS_FILE.read_text(encoding="utf-8"))
    return WeChatCredentials.model_validate(data)


def save_creds(credentials) -> None:
    payload = {
        "openid": credentials.openid.get_secret_value(),
        "access_token": credentials.access_token.get_secret_value(),
        "refresh_token": credentials.refresh_token.get_secret_value(),
        "expires_at": credentials.expires_at,
    }
    CREDS_FILE.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def ticket_meta() -> dict:
    if not TICKET_FILE.exists():
        return {}
    try:
        return json.loads(TICKET_FILE.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError:
        return {}


def write_ticket(ticket: str) -> None:
    meta = ticket_meta()
    record = {
        "ticket": ticket,
        "expiresAt": None,
        "capturedAt": int(time.time() * 1000),
        "userAgent": meta.get("userAgent", ""),
        "sessionId": meta.get("sessionId", ""),
        "session": meta.get("session", ""),
        "capturedFrom": "wechat-oauth",
        "usedAt": None,
        "lastResult": None,
    }
    tmp = TICKET_FILE.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(TICKET_FILE)


def current_creds(wechat):
    """取可用凭据：过期或临近过期时先刷新。"""
    credentials = load_creds()
    now = int(time.time())
    if credentials.expires_at - 120 <= now:
        log(f"凭据剩余 {credentials.expires_at - now} 秒，先刷新")
        credentials = wechat.refresh(credentials)
        save_creds(credentials)
        log("凭据已刷新并保存")
    return credentials


def cmd_status() -> int:
    if not CREDS_FILE.exists():
        log("还没有微信授权凭据，先运行 --qr 扫码")
        return 1
    credentials = load_creds()
    left = credentials.expires_at - int(time.time())
    log(f"凭据存在，access_token {len(credentials.access_token.get_secret_value())} 字符，"
        f"refresh_token {len(credentials.refresh_token.get_secret_value())} 字符，"
        f"剩余 {left} 秒（{'需要刷新' if left <= 120 else '有效'}）")
    return 0


def cmd_qr(open_image: bool) -> int:
    from moon_uestc import WeChatClient

    with WeChatClient() as wechat:
        qr = wechat.create_qr()
        QR_FILE.write_bytes(qr.image)
        log(f"二维码已保存：{QR_FILE}")
        if open_image:
            try:
                import os

                os.startfile(str(QR_FILE))  # noqa: S606 - 用户可见的二维码图片
            except OSError as exc:
                log(f"自动打开失败（{exc}），请手动打开该图片")
        log("请用本人微信扫码并确认应用宝授权，等待中（最多 300 秒）")

        deadline = time.monotonic() + 300
        scanned = False
        while time.monotonic() < deadline:
            result = wechat.poll_qr(qr.uuid)
            if result.status == "authorized" and result.code is not None:
                credentials = wechat.exchange_code(result.code)
                save_creds(credentials)
                log("授权完成，凭据已保存（不打印内容）")
                return 0
            if result.status in ("cancelled", "expired"):
                log(f"扫码{result.status}，请重新运行 --qr")
                return 2
            if result.status == "scanned" and not scanned:
                log("已扫码，请在微信里点确认")
                scanned = True
            time.sleep(1)
    log("等待超时，请重新运行 --qr")
    return 3


def cmd_mint() -> int:
    from moon_uestc import MoonClient, WeChatClient

    if not CREDS_FILE.exists():
        log("没有微信授权凭据，先运行 --qr")
        return 1

    with WeChatClient() as wechat:
        credentials = current_creds(wechat)

        # 第一步：用新鲜 code 登录小程序，拿到小程序侧 SESSION（不同于 H5 SESSION）
        bind_code = wechat.get_code(app_id=APP_ID, credentials=credentials)
        log(f"已获得绑定用 code（{len(bind_code.get_secret_value())} 字符）")
        with MoonClient() as bootstrap:
            binding = bootstrap.auth.check_wechat_bind(code=bind_code).data
        if not binding.bind or binding.session_id is None:
            url = binding.authorize_url.get_secret_value() if binding.authorize_url else ""
            log(f"该微信尚未绑定学号，需先在小程序里完成绑定：{url[:60]}")
            return 7
        mini_session = binding.session_id
        log(f"小程序 SESSION 已获取（{len(mini_session.get_secret_value())} 字符）")

        # 第二步：换一张全新的 code，用小程序 SESSION 换票
        ticket_code = wechat.get_code(app_id=APP_ID, credentials=credentials)
        log(f"已获得换票用 code（{len(ticket_code.get_secret_value())} 字符）")
        with MoonClient(session=mini_session) as mini_program:
            ticket = mini_program.auth.request_checkin_ticket(code=ticket_code).data.ticket

    value = ticket.get_secret_value()
    if not value:
        log("换票响应里没有 ticket")
        return 6
    write_ticket(value)
    log(f"取票成功：{value[:10]}…（共 {len(value)} 字符），已写入 {TICKET_FILE.name}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="用微信 OAuth 凭据自行取票")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--qr", action="store_true", help="生成二维码并等待扫码授权")
    group.add_argument("--mint", action="store_true", help="取一张新票写入 ticket.json")
    group.add_argument("--status", action="store_true", help="查看凭据状态")
    parser.add_argument("--no-open", action="store_true", help="--qr 时不自动打开图片")
    args = parser.parse_args()

    if args.qr:
        return cmd_qr(not args.no_open)
    if args.mint:
        return cmd_mint()
    return cmd_status()


if __name__ == "__main__":
    raise SystemExit(main())