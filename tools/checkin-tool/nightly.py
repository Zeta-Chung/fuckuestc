"""每晚自动打卡：现场换 SESSION → 取票 → 提交 → 回读 → 记台账。

用法：
    python nightly.py             # 正式执行（每天只提交一次）
    python nightly.py --dry-run   # 只查询状态，不提交
    python nightly.py --no-alert  # 不弹窗（手动调试用）
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import _common as common  # noqa: E402

common.bootstrap()

EXIT_OK = 0
EXIT_AUTH = 2
EXIT_NET = 3
EXIT_REJECTED = 5

CHECKED_IN = "CHECKED_IN"


def today_status(record) -> str:
    if record is None:
        return "NO_RECORD"
    value = getattr(record, "record_status_code", None) or ""
    text = str(value).upper()
    if "CHECK" in text and "IN" in text:
        return CHECKED_IN
    return text or "UNKNOWN"


def describe(record) -> str:
    if record is None:
        return "今天还没有打卡记录"
    status = today_status(record)
    when = getattr(record, "checkin_time", None) or getattr(record, "update_time", None) or ""
    date = getattr(record, "business_date", "") or ""
    return f"状态={status}，业务日期={date}，打卡时间={when}"


def build_request(config: dict, client_time: str):
    from moon_uestc.models import CheckinRequest

    return CheckinRequest(
        client_time=client_time,
        latitude=config["latitude"],
        longitude=config["longitude"],
        reverse_geocode=config.get("reverseGeocode", ""),
        user_agent=config.get("userAgent", ""),
        accuracy=config.get("accuracy", 30),
        location_duration_ms=config.get("locationDurationMs", 600),
        network_type_code=config.get("networkTypeCode", "wifi"),
        location_enabled=config.get("locationEnabled", True),
        location_authorized=config.get("locationAuthorized", "authorized"),
        location_accuracy=config.get("locationAccuracy", "full"),
        error_message=config.get("errorMessage", ""),
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="成电每日打卡 · 自动执行")
    parser.add_argument("--dry-run", action="store_true", help="只查询，不提交")
    parser.add_argument("--no-alert", action="store_true", help="失败也不弹窗")
    parser.add_argument("--deadline", default="23:15", help="最晚尝试到几点，默认 23:15")
    parser.add_argument("--retry", type=int, default=3, help="取票/提交总尝试次数，默认 3")
    parser.add_argument("--retry-gap", type=int, default=60, help="两次尝试间隔秒数，默认 60")
    args = parser.parse_args()

    started = time.strftime("%Y-%m-%d %H:%M:%S")
    record: dict = {
        "date": time.strftime("%Y-%m-%d"),
        "startedAt": started,
        "dryRun": args.dry_run,
        "needHuman": False,
    }
    exit_code = EXIT_OK
    note = ""

    try:
        config = common.load_config()
        from moon_uestc import MoonClient, WeChatClient

        with WeChatClient() as wechat:
            credentials = common.fresh_credentials(wechat)
            session = common.mini_session(wechat, credentials)

            with MoonClient(session=session) as client:
                current = client.student.today().data
                record["statusBefore"] = today_status(current)
                common.log(f"今日状态：{describe(current)}")

                if today_status(current) == CHECKED_IN:
                    note = "already-checked-in"
                    common.log("今天已经打过卡，本次无事可做")
                elif args.dry_run:
                    note = "dry-run"
                    common.log("演练结束：去掉 --dry-run 才会真正提交")
                else:
                    deadline = args.deadline
                    problem = ""
                    for attempt in range(1, args.retry + 1):
                        if time.strftime("%H:%M") > deadline:
                            problem = f"已过最晚时间 {deadline}"
                            break
                        try:
                            code = wechat.get_code(app_id=common.APP_ID, credentials=credentials)
                            ticket = client.auth.request_checkin_ticket(code=code).data
                            client.set_checkin_ticket(ticket.ticket.get_secret_value())
                            common.log(f"取票成功（第 {attempt} 次），开始提交")
                            client.student.checkin(build_request(config, time.strftime("%Y-%m-%d %H:%M:%S")))
                            record["ticketSource"] = "wx-oauth"
                            problem = ""
                            break
                        except Exception as exc:  # noqa: BLE001 - 需要分类型判断
                            problem = f"{type(exc).__name__}: {exc}"
                            common.log(f"第 {attempt} 次失败：{problem[:160]}")
                            client.set_checkin_ticket(None)
                            if attempt < args.retry:
                                time.sleep(args.retry_gap)

                    if problem:
                        exit_code = EXIT_REJECTED
                        note = "submit-failed"
                        record["needHuman"] = True
                        common.log(f"提交失败：{problem[:200]}")
                    else:
                        time.sleep(2)
                        after = client.student.today().data
                        record["statusAfter"] = today_status(after)
                        common.log(f"回读：{describe(after)}")
                        if today_status(after) == CHECKED_IN:
                            note = "confirmed"
                        else:
                            exit_code = EXIT_REJECTED
                            note = "unconfirmed"
                            record["needHuman"] = True
                            common.log("提交返回成功，但回读不是 CHECKED_IN，请人工确认")

        if exit_code == EXIT_OK:
            common.log(f"=== 结束（{note}）===")
        else:
            if not args.no_alert:
                common.popup(
                    "自动打卡失败",
                    f"{time.strftime('%m-%d %H:%M')} 打卡未成功：{note}\n\n请打开微信小程序「成电智慧学工」手动打卡。",
                    kind="alert",
                )
    except FileNotFoundError as exc:
        exit_code = EXIT_AUTH
        note = "not-configured"
        record["needHuman"] = True
        common.log(f"配置缺失：{exc}")
        if not args.no_alert:
            common.popup("还没配置", f"{exc}\n\n请双击「首次配置.bat」。", kind="alert")
    except Exception as exc:  # noqa: BLE001 - 顶层兜底
        exit_code = EXIT_NET
        note = f"error:{type(exc).__name__}"
        record["needHuman"] = True
        common.log(f"出错了：{type(exc).__name__}: {exc}")
        if not args.no_alert:
            common.popup("自动打卡出错", f"{type(exc).__name__}: {str(exc)[:200]}\n\n请手动确认打卡状态。", kind="alert")

    record.update(
        {
            "endedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
            "exitCode": exit_code,
            "note": note,
        }
    )
    common.write_ledger(record)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())