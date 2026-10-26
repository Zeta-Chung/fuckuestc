"""打卡提醒弹窗（独立进程，由 pythonw.exe 启动，不闪黑窗）。

用 user32.MessageBoxW 直接弹窗，避免「隐藏窗口的 powershell.exe」这种被安全软件
当成可疑行为的调用模式（火绒规则 rp_name_execcreate_powershell_hidden）。

用法：
    pythonw alert_popup.py --title "自动打卡成功" --text "已提交并复查为 CHECKED_IN"
    pythonw alert_popup.py --title "测试" --text "..." --auto-close 30
"""
from __future__ import annotations

import argparse
import ctypes
import threading
import time

MB_OK = 0x00000000
MB_SETFOREGROUND = 0x00010000
MB_TOPMOST = 0x00040000
MB_ICONINFORMATION = 0x00000040
MB_ICONWARNING = 0x00000030
WM_CLOSE = 0x0010

_user32 = ctypes.WinDLL("user32", use_last_error=True)
_user32.MessageBoxW.argtypes = [
    ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_wchar_p, ctypes.c_uint
]
_user32.MessageBoxW.restype = ctypes.c_int
_user32.FindWindowW.argtypes = [ctypes.c_wchar_p, ctypes.c_wchar_p]
_user32.FindWindowW.restype = ctypes.c_void_p
_user32.PostMessageW.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_void_p, ctypes.c_void_p]
_user32.PostMessageW.restype = ctypes.c_int


def show(title: str, text: str, kind: str) -> None:
    icon = MB_ICONWARNING if kind == "alert" else MB_ICONINFORMATION
    _user32.MessageBoxW(None, text, title, MB_OK | MB_SETFOREGROUND | MB_TOPMOST | icon)


def auto_close(title: str, seconds: float) -> None:
    time.sleep(seconds)
    handle = _user32.FindWindowW("#32770", title)
    if handle:
        _user32.PostMessageW(handle, WM_CLOSE, None, None)


def main() -> int:
    parser = argparse.ArgumentParser(description="打卡提醒弹窗")
    parser.add_argument("--title", required=True)
    parser.add_argument("--text", required=True)
    parser.add_argument("--kind", choices=("alert", "info"), default="alert")
    parser.add_argument("--auto-close", type=float, default=0.0,
                        help="秒数；到点自动关闭窗口（0 = 一直等用户点确定）")
    args = parser.parse_args()

    if args.auto_close > 0:
        threading.Thread(target=auto_close, args=(args.title, args.auto_close), daemon=True).start()
    show(args.title, args.text, args.kind)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())