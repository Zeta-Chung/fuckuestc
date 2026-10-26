#!/usr/bin/env python3
"""从微信 WebView 的 Session Storage(LevelDB) 读出打卡票据，并证明这张票是「本次新写入」的。

背景（2026-10-24 本机实测）：
    * 小程序把票据写进 webview 的 sessionStorage，键名 daily-checkin-operation-ticket；
    * 该值在 %APPDATA%\\Tencent\\xwechat\\radium\\web\\profiles\\webview_*\\Session Storage\\
      的 LevelDB 文件里是明文（varint 长度前缀 + UTF-16LE，43 字符）；
    * 票据是分钟级一次性凭据，而历史条目会长期留在日志里——
      直接取「最后一条」可能取到昨天甚至更早的票（MioYiSama/moon-uestc 的 mumu_session.py
      为此专门拒绝 compacted 表）。

新鲜度判定（fail-closed，任一不满足即拒绝）：
    1. 取票前先跑 --baseline，记录每个 LevelDB 文件的大小与当时已存在的票据集合；
    2. 读取时若任何基线文件消失或体积缩水、或出现基线之后新增的文件（*.ldb/*.log 轮换）
       → 判定「日志已重写/压缩」，拒绝；
    3. 候选票据必须落在基线之后追加的字节区间内，且不在基线票据集合里；
    4. 取偏移最大的那条；一条都没有则拒绝，并提示重新在小程序打开打卡页。

用法：
    python wechat_ticket_store.py --baseline                 # 打开打卡页之前跑
    python wechat_ticket_store.py --read                     # 只读检查，打印判定
    python wechat_ticket_store.py --read --write ticket.json # 判定通过才落盘
    python wechat_ticket_store.py --list                     # 诊断：列出全部票据与偏移
    python wechat_ticket_store.py --self-test                # 离线自检

退出码：0 成功；2 无基线 / 无新鲜票据 / 日志已压缩；3 读取故障。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

KEY = b"daily-checkin-operation-ticket"
TICKET_LENGTH = 43
TICKET_RE = re.compile(r"[A-Za-z0-9_\-]{43}\Z")
DEFAULT_BASELINE = Path(__file__).resolve().parent / "wechat-ticket-baseline.json"
STORAGE_DIR_NAME = "Session Storage"
ENV_ROOT = "UESTC_WECHAT_WEBVIEW_ROOT"

EXIT_OK = 0
EXIT_STALE = 2
EXIT_IO = 3


def default_root() -> Path:
    override = os.environ.get(ENV_ROOT)
    if override:
        return Path(override)
    return Path(os.path.expandvars(r"%APPDATA%\Tencent\xwechat\radium\web\profiles"))


@dataclass(frozen=True)
class TicketHit:
    profile: str
    file: str
    offset: int
    ticket: str

    @property
    def source(self) -> str:
        return f"{self.profile}/{self.file}"


class StorageError(Exception):
    """读取层面失败（找不到目录、文件不可读）。"""


class StaleTicket(Exception):
    """无法证明票据新鲜。"""


def read_varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while True:
        if offset >= len(data):
            raise ValueError("varint 越界")
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
        shift += 7
        if shift > 63:
            raise ValueError("varint 过长")


def parse_file(path: Path) -> list[tuple[int, str]]:
    """按写入顺序返回 (字节偏移, 票据)。删除标记（长度为 0）与非票据值自动跳过。"""
    data = path.read_bytes()
    hits: list[tuple[int, str]] = []
    start = 0
    while True:
        index = data.find(KEY, start)
        if index < 0:
            return hits
        start = index + 1
        cursor = index + len(KEY)
        try:
            length, cursor = read_varint(data, cursor)
        except ValueError:
            continue
        if length != TICKET_LENGTH * 2 or cursor + length > len(data):
            continue
        value = data[cursor: cursor + length].decode("utf-16-le", "ignore")
        if TICKET_RE.match(value):
            hits.append((index, value))


def storage_dirs(root: Path) -> list[Path]:
    if not root.is_dir():
        raise StorageError(f"找不到微信 webview 配置目录：{root}")
    dirs = [item / STORAGE_DIR_NAME for item in sorted(root.glob("webview_*"), key=lambda p: p.name)]
    return [item for item in dirs if item.is_dir()]


def storage_files(root: Path) -> dict[str, Path]:
    """文件标识 -> 路径。标识形如 webview_xxx/000003.log，便于跨 profile 排序与对比。"""
    files: dict[str, Path] = {}
    for storage in storage_dirs(root):
        for path in sorted(storage.iterdir()):
            if path.is_file() and path.suffix in (".log", ".ldb"):
                files[f"{storage.parent.name}/{path.name}"] = path
    return files


def read_all(root: Path) -> list[TicketHit]:
    hits: list[TicketHit] = []
    for identifier, path in storage_files(root).items():
        profile, _, name = identifier.partition("/")
        try:
            parsed = parse_file(path)
        except OSError as exc:
            raise StorageError(f"读取 {path} 失败：{exc}") from exc
        hits.extend(TicketHit(profile, name, offset, ticket) for offset, ticket in parsed)
    return hits


def snapshot(root: Path) -> dict:
    files = storage_files(root)
    return {
        "createdAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "root": str(root),
        "files": {identifier: path.stat().st_size for identifier, path in files.items()},
        "tickets": [hit.ticket for hit in read_all(root)],
    }


def verify_fresh(baseline: dict, root: Path) -> tuple[TicketHit, dict]:
    """返回 (票据, 证据)；无法证明新鲜时抛 StaleTicket。"""
    if str(baseline.get("root") or "") != str(root):
        raise StaleTicket(f"基线来自另一个目录（{baseline.get('root')}），请重新 --baseline")
    base_files = baseline.get("files") or {}
    if not base_files:
        raise StaleTicket("基线里没有任何日志文件，请重新 --baseline")
    base_tickets = set(baseline.get("tickets") or [])

    current = storage_files(root)
    for identifier, size in base_files.items():
        path = current.get(identifier)
        if path is None:
            raise StaleTicket(f"日志文件 {identifier} 已消失（LevelDB 轮换），无法证明新鲜")
        if path.stat().st_size < size:
            raise StaleTicket(f"日志 {identifier} 体积从 {size} 缩到 {path.stat().st_size}，已被重写/压缩")
    added = sorted(set(current) - set(base_files))
    if added:
        raise StaleTicket(f"出现基线之后新增的日志文件 {added}，无法证明票据不是历史条目")

    fresh = [
        hit
        for hit in read_all(root)
        if hit.offset >= base_files.get(hit.source, 0)
        and hit.ticket not in base_tickets
    ]
    if not fresh:
        raise StaleTicket("基线之后没有新写入的票据，请在小程序里重新打开打卡页再读")
    best = max(fresh, key=lambda hit: hit.offset)
    evidence = {
        "ticket": best.ticket[:12] + "…",
        "source": best.source,
        "offset": best.offset,
        "baselineOffset": base_files.get(best.source),
        "baselineTickets": len(base_tickets),
        "freshCandidates": len(fresh),
    }
    return best, evidence


def read_json_tolerant(path: Path) -> dict:
    """容忍 BOM 的 JSON 读取（Windows 编辑器另存 ticket.json 时会加 BOM）。"""
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_ticket(path: Path, ticket: str) -> dict:
    """只更新 ticket / capturedAt，保留 session、sessionId、userAgent 等字段。"""
    record = {}
    if path.exists():
        try:
            record = read_json_tolerant(path)
        except Exception as exc:  # noqa: BLE001
            raise StorageError(f"{path} 不是合法 JSON：{exc}") from exc
    if not isinstance(record, dict):
        raise StorageError(f"{path} 内容不是对象")
    record["ticket"] = ticket
    record["capturedAt"] = int(time.time() * 1000)
    record["capturedFrom"] = "wechat-webview-session-storage"
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(record, ensure_ascii=False), encoding="utf-8")
    temporary.replace(path)
    return record


def load_baseline(path: Path) -> dict:
    if not path.exists():
        raise StaleTicket(f"没有基线文件 {path.name}，请先运行 --baseline（在打开打卡页之前）")
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except Exception as exc:  # noqa: BLE001
        raise StaleTicket(f"基线文件损坏：{exc}") from exc


def self_test() -> int:
    import shutil

    def entry(ticket: str) -> bytes:
        raw = ticket.encode("utf-16-le")
        return KEY + bytes([len(raw)]) + raw

    def tombstone() -> bytes:
        return KEY + b"\x00"

    failures = 0

    def check(name: str, condition: bool, detail: str = "") -> None:
        nonlocal failures
        failures += 0 if condition else 1
        print(f"{'PASS' if condition else 'FAIL'}  {name}{'' if condition else '  ' + detail}")

    sandbox = Path(__file__).resolve().parent / "_selftest"
    shutil.rmtree(sandbox, ignore_errors=True)
    sandbox.mkdir(parents=True, exist_ok=True)
    try:
        root = sandbox
        storage = root / "webview_test" / STORAGE_DIR_NAME
        storage.mkdir(parents=True, exist_ok=True)
        log = storage / "000003.log"
        old_ticket = "A" * TICKET_LENGTH
        new_ticket = "B" * TICKET_LENGTH
        log.write_bytes(entry(old_ticket) + tombstone() + entry("X" * 20))
        baseline = snapshot(root)

        try:
            verify_fresh(baseline, root)
            check("无新写入时拒绝", False, "却返回了票据")
        except StaleTicket:
            check("无新写入时拒绝", True)

        with log.open("ab") as handle:
            handle.write(entry(new_ticket))
        hit, _ = verify_fresh(baseline, root)
        check("只接受追加的新票（忽略历史票与墓碑）", hit.ticket == new_ticket, f"得到 {hit.ticket[:6]}")

        shrunk = snapshot(root)
        log.write_bytes(entry(old_ticket))
        try:
            verify_fresh(shrunk, root)
            check("日志缩水时拒绝", False, "却通过了")
        except StaleTicket:
            check("日志缩水时拒绝", True)

        log.write_bytes(entry(old_ticket) + entry(new_ticket))
        (storage / "000005.ldb").write_bytes(b"compacted")
        try:
            verify_fresh(baseline, root)
            check("出现新增 .ldb 时拒绝", False, "却通过了")
        except StaleTicket:
            check("出现新增 .ldb 时拒绝", True)

        (storage / "000005.ldb").unlink()
        try:
            verify_fresh({"createdAt": "x", "root": "D:/elsewhere", "files": {"a": 1}, "tickets": []}, root)
            check("基线目录不一致时拒绝", False, "却通过了")
        except StaleTicket:
            check("基线目录不一致时拒绝", True)
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)

    print(f"\n{5 - failures}/5 通过")
    return EXIT_OK if failures == 0 else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="读取并校验微信小程序签发的打卡票据新鲜度")
    parser.add_argument("--root", default=None, help="覆盖 webview profiles 目录（默认 %APPDATA% 下的微信目录）")
    parser.add_argument("--baseline-file", default=str(DEFAULT_BASELINE), help="基线文件路径")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--baseline", action="store_true", help="记录当前日志大小与已有票据")
    group.add_argument("--read", action="store_true", help="读取并校验新鲜度")
    group.add_argument("--list", action="store_true", help="诊断：列出全部票据与偏移")
    group.add_argument("--self-test", action="store_true", help="离线自检")
    parser.add_argument("--write", default=None, help="校验通过后把票据写入该 ticket.json")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    root = Path(args.root) if args.root else default_root()
    baseline_file = Path(args.baseline_file)

    try:
        if args.baseline:
            state = snapshot(root)
            temporary = baseline_file.with_suffix(baseline_file.suffix + ".tmp")
            temporary.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")
            temporary.replace(baseline_file)
            print(f"已记录基线：{len(state['files'])} 个日志文件，已有票据 {len(state['tickets'])} 张")
            print(f"基线文件：{baseline_file}")
            return EXIT_OK

        if args.list:
            hits = read_all(root)
            baseline_tickets = None
            if baseline_file.exists():
                baseline_tickets = set(load_baseline(baseline_file).get("tickets") or [])
            if baseline_tickets is None:
                print(f"共 {len(hits)} 张票据（还没有基线文件，无法区分新旧）：")
            else:
                print(f"共 {len(hits)} 张票据，基线内 {len(baseline_tickets)} 张：")
            for hit in hits:
                if baseline_tickets is None:
                    mark = "未知"
                else:
                    mark = "基线内" if hit.ticket in baseline_tickets else "基线后"
                print(f"  [{mark}] {hit.source} @{hit.offset}  {hit.ticket[:12]}…")
            return EXIT_OK

        hit, evidence = verify_fresh(load_baseline(baseline_file), root)
        # 先落盘再宣布成功：写失败时不能留下「已取到票」的假象（退出码 3）。
        record = write_ticket(Path(args.write), hit.ticket) if args.write else None
    except StaleTicket as exc:
        print(f"拒绝：{exc}", file=sys.stderr)
        return EXIT_STALE
    except StorageError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return EXIT_IO

    print(f"新鲜票据：{hit.ticket[:12]}…（来自 {hit.source}，偏移 {hit.offset}，基线 {evidence['baselineOffset']}）")
    if record is not None:
        print(f"已写入 {args.write}（sessionId={str(record.get('sessionId'))[:8]}…，capturedAt 已更新）")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())