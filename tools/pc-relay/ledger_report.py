#!/usr/bin/env python3
"""逐日台账报表：把 nightly 的 ledger.jsonl 与提交器证据日志 relay.log 合起来看。

关心三件事（对应验收标准）：
    1. 当天是否需要人工（needHuman / autoTap / tapExit）
    2. 当天到底 POST 了几次（relay.log 里带 checkinHttp 的行数，正常应为 1 或 0）
    3. 学校记录是否确认（todayAfter / status）

用法：
    python ledger_report.py [--ledger ledger.jsonl] [--relay relay.log] [--days 7]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
from collections import defaultdict

HERE = pathlib.Path(__file__).resolve().parent

# Windows 控制台默认 GBK，直接 print("⚠") 会抛 UnicodeEncodeError。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass


def load_jsonl(path: pathlib.Path) -> list[dict]:
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ledger", default=str(HERE / "ledger.jsonl"))
    parser.add_argument("--relay", default=str(HERE / "relay.log"))
    parser.add_argument("--days", type=int, default=7)
    args = parser.parse_args()

    ledger = load_jsonl(pathlib.Path(args.ledger))
    relay = load_jsonl(pathlib.Path(args.relay))

    posts: dict[str, list[dict]] = defaultdict(list)
    for row in relay:
        stamp = str(row.get("time") or "")[:10]
        if stamp and "checkinHttp" in row:
            posts[stamp].append(row)

    runs: dict[str, list[dict]] = defaultdict(list)
    for row in ledger:
        stamp = str(row.get("date") or "")[:10]
        if stamp:
            runs[stamp].append(row)

    days = sorted(set(runs) | set(posts))[-args.days:]
    if not days:
        print("还没有台账数据。先跑一次 nightly.ps1。")
        return 0

    header = f"{'日期':<11}{'需人工':<7}{'自动点击':<9}{'取票秒':<7}{'POST':<6}{'提交码':<8}{'回读':<12}{'退出码':<7}备注"
    print(header)
    print("-" * len(header))
    for day in days:
        day_runs = runs.get(day, [])
        last = day_runs[-1] if day_runs else {}
        day_posts = posts.get(day, [])
        code = day_posts[-1].get("checkinCode") if day_posts else None
        after = day_posts[-1].get("todayAfter") if day_posts else None
        need = "是" if last.get("needHuman") else ("否" if day_runs else "?")
        auto = "是" if last.get("autoTap") else "否"
        if last.get("tapExit") not in (None, 0):
            auto += f"(失败{last.get('tapExit')})"
        poll = last.get("pollSeconds")
        note = last.get("note") or ""
        if len(day_runs) > 1:
            note = (note + f"；当天跑过 {len(day_runs)} 次").strip("；")
        print(
            f"{day:<11}{need:<7}{auto:<9}{str(poll if poll is not None else '-'):<7}"
            f"{len(day_posts):<6}{str(code if code is not None else '-'):<8}"
            f"{str(after or '-'):<12}{str(last.get('exitCode', '-')):<7}{note}"
        )

    print()
    multi = {day: len(rows) for day, rows in posts.items() if len(rows) > 1}
    if multi:
        print(f"⚠ 有 {len(multi)} 天出现多次 POST：{multi}")
    else:
        print("POST 次数检查：有提交记录的每天都不超过 1 次。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())