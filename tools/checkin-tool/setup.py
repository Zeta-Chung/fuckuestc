"""首次配置向导：微信扫码授权 → 选打卡点 → 写配置 → 注册每晚自动任务。

用法（双击「首次配置.bat」即可）：
    python setup.py                 # 全流程
    python setup.py --no-task       # 只写配置，不注册计划任务
    python setup.py --at 21:05      # 自定义每晚时间
"""
from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import _common as common  # noqa: E402

common.bootstrap()

TASK_NAME = "UESTC-Checkin-Tool"


def ask(prompt: str, default: str = "") -> str:
    suffix = f"（默认 {default}）" if default else ""
    try:
        answer = input(f"{prompt}{suffix}：").strip()
    except EOFError:
        return default
    return answer or default


def qr_login() -> None:
    from moon_uestc import WeChatClient

    with WeChatClient() as wechat:
        qr = wechat.create_qr()
        common.QR_FILE.write_bytes(qr.image)
        print()
        print(f"二维码已保存：{common.QR_FILE}")
        try:
            import os

            os.startfile(str(common.QR_FILE))  # noqa: S606 - 用户要看这张图
        except OSError:
            print("（自动打开失败，请手动打开上面这张图片）")
        print("请用【本人微信】扫码，并在微信里点「确认」。等待中，最多 5 分钟…")

        deadline = time.monotonic() + 300
        scanned = False
        while time.monotonic() < deadline:
            result = wechat.poll_qr(qr.uuid)
            if result.status == "authorized" and result.code is not None:
                common.save_creds(wechat.exchange_code(result.code))
                print("✓ 授权成功，凭据已加密保存（只有本机这个 Windows 账户能解开）")
                return
            if result.status in ("cancelled", "expired"):
                raise RuntimeError(f"扫码{result.status}，请重新运行本向导")
            if result.status == "scanned" and not scanned:
                print("已扫码，请在微信里点「确认」…")
                scanned = True
            time.sleep(1)
    raise RuntimeError("等待超时，请重新运行本向导")


def pick_location(session: str) -> dict:
    from moon_uestc import MoonClient

    with MoonClient(session=session) as client:
        info = client.auth.mini_program_user_info().data
        student = getattr(getattr(info, "user_data", None), "username", "") or ""
        full_name = getattr(getattr(info, "user_data", None), "full_name", "") or ""
        print(f"\n学号：{student}  姓名：{full_name}")

        locations = client.student.available_locations().data or []
        print(f"\n服务器返回 {len(locations)} 个允许打卡的地点：")
        rows = []
        for index, item in enumerate(locations, start=1):
            name = getattr(item, "location_name", "") or f"地点{index}"
            lat = float(getattr(item, "latitude", 0.0))
            lng = float(getattr(item, "longitude", 0.0))
            radius = float(getattr(item, "radius_meter", 0.0) or 0.0)
            rows.append((name, lat, lng, radius))
            print(f"  {index}) {name}  {lat:.6f},{lng:.6f}  半径 {radius:.0f} 米")

        choice = ask("\n请选择你的打卡地点序号（回车=第 1 个，输入 0 手动填坐标）", "1")
        if choice == "0":
            lat = float(ask("纬度（GCJ-02）").strip())
            lng = float(ask("经度（GCJ-02）").strip())
            name = ask("地点名称（可留空）", "")
        else:
            index = max(1, min(len(rows), int(choice))) if rows else 1
            if not rows:
                raise RuntimeError("服务器没有返回可用地点，请改用 0 手动填写坐标")
            name, lat, lng, _radius = rows[index - 1]

        try:
            address = client.student.reverse_geocode(latitude=lat, longitude=lng).data or ""
        except Exception:  # noqa: BLE001 - 尽力而为
            address = ""
    return {"latitude": lat, "longitude": lng, "locationName": name, "reverseGeocode": address}


def build_config(place: dict) -> dict:
    return {
        "latitude": round(place["latitude"], 6),
        "longitude": round(place["longitude"], 6),
        "reverseGeocode": place.get("reverseGeocode", ""),
        "locationName": place.get("locationName", ""),
        "userAgent": common.DEFAULT_UA,
        "accuracy": 30,
        "locationDurationMs": 600,
        "networkTypeCode": "wifi",
        "locationEnabled": True,
        "locationAuthorized": "authorized",
        "locationAccuracy": "full",
        "errorMessage": "",
    }


def task_xml(at: str) -> str:
    relay = ROOT
    bat = relay / "打卡.bat"
    sid = subprocess.run(
        ["powershell", "-NoProfile", "-Command", "[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value"],
        capture_output=True, text=True,
    ).stdout.strip()
    start = time.strftime("%Y-%m-%d") + "T" + at + ":00"
    return f"""<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo><URI>\\{TASK_NAME}</URI></RegistrationInfo>
  <Principals>
    <Principal id="Author">
      <UserId>{sid}</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <StartWhenAvailable>true</StartWhenAvailable>
    <ExecutionTimeLimit>PT1H</ExecutionTimeLimit>
    <Enabled>true</Enabled>
  </Settings>
  <Triggers>
    <CalendarTrigger>
      <StartBoundary>{start}</StartBoundary>
      <Enabled>true</Enabled>
      <ScheduleByDay><DaysInterval>1</DaysInterval></ScheduleByDay>
    </CalendarTrigger>
  </Triggers>
  <Actions Context="Author">
    <Exec>
      <Command>"{bat}"</Command>
      <WorkingDirectory>{relay}</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
"""


def register_task(at: str) -> bool:
    xml_path = ROOT / f"task-{TASK_NAME}.xml"
    xml_path.write_text(task_xml(at), encoding="utf-16")
    result = subprocess.run(
        ["schtasks", "/create", "/tn", f"\\{TASK_NAME}", "/xml", str(xml_path), "/f"],
        capture_output=True, text=True, encoding="gbk", errors="replace",
    )
    if result.returncode != 0:
        print("\n计划任务注册失败，请手动执行：")
        print(f'  schtasks /create /tn "\\{TASK_NAME}" /xml "{xml_path}" /f')
        detail = (result.stdout or "") + (result.stderr or "")
        if detail.strip():
            print(f"  （{detail.strip()[:200]}）")
        return False
    print(f"✓ 已注册每晚 {at} 自动打卡（电池也会启动；睡眠错过会补跑）")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="成电每日打卡 · 首次配置向导")
    parser.add_argument("--no-task", action="store_true", help="不注册计划任务")
    parser.add_argument("--at", default="21:05", help="每晚触发时间，默认 21:05")
    parser.add_argument("--skip-qr", action="store_true", help="已有凭据时跳过扫码")
    args = parser.parse_args()

    print("=== 成电每日打卡 · 首次配置 ===")
    print("整个过程约 3 分钟，只需要你：扫一次码 + 选一个打卡地点。\n")

    if not args.skip_qr:
        qr_login()
    elif not common.creds_path().exists():
        print("✗ 没有可用凭据，不能用 --skip-qr")
        return 1

    from moon_uestc import WeChatClient

    with WeChatClient() as wechat:
        credentials = common.fresh_credentials(wechat)
        session = common.mini_session(wechat, credentials)

    place = pick_location(session)
    config = build_config(place)
    common.save_config(config)
    print(f"\n✓ 已保存打卡点：{config['locationName'] or '自定义'}  {config['latitude']},{config['longitude']}")
    if config["reverseGeocode"]:
        print(f"  地址：{config['reverseGeocode']}")

    if not args.no_task:
        register_task(args.at)

    print("\n配置完成。可以跑一次演练：双击「演练.bat」（只查询，不提交）")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001 - 向导需要友好提示
        print(f"\n✗ 出错了：{type(exc).__name__}: {exc}")
        raise SystemExit(1)