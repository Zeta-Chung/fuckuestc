"""通用引导：定位依赖、加载凭据、弹窗、写台账。

这个目录是「分发给同学用」的成品目录：
- 开发时复用隔壁 tools/pc-relay 的 vendor 依赖；
- 打包成绿色包时，依赖会放到本目录的 vendor/site 下（脚本优先用它）。
"""
from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent
APP_ID = "wxaa78bf2905f01cee"  # 成电智慧学工小程序
DEFAULT_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/144.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781(0x6700143B) NetType/WIFI "
    "MiniProgramEnv/Windows WindowsWechat/WMPF WindowsWechat(0x63090a13) "
    "UnifiedPCWindowsWechat(0xf2541b18) XWEB/25297"
)
CREDS_FILE = ROOT / "wx-credentials.json.dpapi"
CONFIG_FILE = ROOT / "config.json"
LEDGER_FILE = ROOT / "ledger.jsonl"
QR_FILE = ROOT / "wx-qr.png"


def bootstrap() -> None:
    """把依赖与公共模块加进 sys.path（本目录优先，其次相邻的 pc-relay）。"""
    candidates = [
        ROOT / "vendor" / "site",
        ROOT.parent / "pc-relay" / "vendor" / "site",
    ]
    for path in candidates:
        if path.is_dir():
            sys.path.insert(0, str(path))
            break
    for path in (ROOT, ROOT.parent / "pc-relay"):
        if path.is_dir() and str(path) not in sys.path:
            sys.path.append(str(path))


def log(message: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def creds_path() -> pathlib.Path:
    """凭据位置：本目录优先，其次相邻的 pc-relay（本机开发时复用）。"""
    if CREDS_FILE.exists():
        return CREDS_FILE
    legacy = ROOT.parent / "pc-relay" / CREDS_FILE.name
    return legacy if legacy.exists() else CREDS_FILE


def load_creds():
    import dpapi
    from moon_uestc import WeChatCredentials

    path = creds_path()
    if not path.exists():
        raise FileNotFoundError("还没有微信授权凭据，请先运行「首次配置.bat」")
    raw = dpapi.unprotect(path.read_bytes())
    return WeChatCredentials.model_validate(json.loads(raw.decode("utf-8")))


def save_creds(credentials) -> None:
    import dpapi

    payload = {
        "openid": credentials.openid.get_secret_value(),
        "access_token": credentials.access_token.get_secret_value(),
        "refresh_token": credentials.refresh_token.get_secret_value(),
        "expires_at": credentials.expires_at,
    }
    sealed = dpapi.protect(json.dumps(payload, ensure_ascii=False).encode("utf-8"))
    tmp = CREDS_FILE.with_suffix(".tmp")
    tmp.write_bytes(sealed)
    tmp.replace(CREDS_FILE)


def fresh_credentials(wechat):
    """取得可用凭据：快过期就先刷新并落盘。"""
    credentials = load_creds()
    if credentials.expires_at - 120 <= int(time.time()):
        credentials = wechat.refresh(credentials)
        save_creds(credentials)
        log("微信凭据已刷新")
    return credentials


def mini_session(wechat, credentials) -> str:
    """用新鲜 code 登录小程序，拿到小程序侧 SESSION（可用于读接口与提交）。"""
    from moon_uestc import MoonClient

    code = wechat.get_code(app_id=APP_ID, credentials=credentials)
    with MoonClient() as bootstrap_client:
        binding = bootstrap_client.auth.check_wechat_bind(code=code).data
    if not binding.bind or binding.session_id is None:
        url = binding.authorize_url.get_secret_value() if binding.authorize_url else ""
        raise RuntimeError(f"该微信还没有绑定学号，需要先在小程序里完成绑定：{url[:80]}")
    return binding.session_id.get_secret_value()


def load_config() -> dict:
    if not CONFIG_FILE.exists():
        raise FileNotFoundError("还没有配置文件，请先运行「首次配置.bat」")
    return json.loads(CONFIG_FILE.read_text(encoding="utf-8"))


def save_config(config: dict) -> None:
    tmp = CONFIG_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(CONFIG_FILE)


def write_ledger(record: dict) -> None:
    try:
        with LEDGER_FILE.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    except OSError:
        pass


def popup(title: str, text: str, kind: str = "alert", auto_close: float = 0.0) -> None:
    """弹窗：pythonw + MessageBoxW（不启动隐藏窗口的 powershell）。"""
    pythonw = pathlib.Path(sys.executable).with_name("pythonw.exe")
    launcher = pythonw if pythonw.exists() else pathlib.Path(sys.executable)
    script = ROOT / "alert_popup.py"
    if not script.exists():
        script = ROOT.parent / "pc-relay" / "alert_popup.py"
    if not script.exists():
        log(f"[提示] {title}：{text}")
        return
    args = [str(script), "--title", title, "--text", text, "--kind", kind]
    if auto_close > 0:
        args += ["--auto-close", str(auto_close)]
    try:
        subprocess.Popen([str(launcher), *args], creationflags=0x08000000)
    except OSError as exc:
        log(f"弹窗失败（{exc}）：{title}：{text}")