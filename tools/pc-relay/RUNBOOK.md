# 免抓包运行流程（PC 端 · 当前方案）

核实时间：2026-10-25 19:0x。**已不再需要 mitmproxy、hosts 重定向、系统代理**。
票据直接读自微信 WebView 的 Session Storage，新鲜度由「基线之后只有追加写入」证明。

## 一句话流程

计划任务 21:05 → 桌面弹红色提醒「现在请打开打卡页」→ **你打开 PC 微信的「成电智慧学工」→ 打卡页，停留 3 秒**
→ 程序在磁盘上发现新票据 → 只提交一次 → 回读确认 → 写台账；成功弹提示，失败弹红色提醒。

## 前提（缺一不可）

| 项目 | 要求 |
| --- | --- |
| PC 微信 | 已登录（程序会尝试拉起它，但登录需要你） |
| 电脑 | 不睡眠、不注销（计划任务以当前用户交互方式运行） |
| 会话凭据 | `ticket.json` 里有 `session` 与 `sessionId`（失效会弹「会话凭据失效」提醒） |
| 坐标 | `relay-config.json` 的 `latitude`/`longitude` 是当晚要用的位置 |

## 为什么不做自动点击（重要）

`-AutoTap` 是**实验性**开关，默认关闭，计划任务也不再使用它。原因有实测证据：

1. 微信 4.x 主窗口是 Qt，控件树（UIA）里读不到任何按钮，只能靠截图 OCR + 真实鼠标点击。
2. 「发现」页里「朋友圈 / 视频号 / 小程序」三行相邻，而「小程序」是**开关**：面板已打开时再点一次会把它关掉。
3. 2026-10-25 18:4x 实测：盲点击序列在窗口重绘时机不对时点到了「视频号」，导致微信开始播放视频、窗口假死。

结论：**这一下由人来点（2 秒），程序只做它擅长的部分**。

## 日常用法

到 21:05 看到红色提醒后，在 PC 微信里打开打卡页停留 3 秒即可，其余全自动。

手动跑（调试/补跑）：

```powershell
$R = "C:\Users\BruceC\Documents\Codex\2026-10-22\mitmproxy-post-daily-checkin-api-student\outputs\CheckinAssistant\tools\pc-relay"
pwsh -File "$R\nightly.ps1"                     # 默认：弹提醒 + 等票 + 提交（截止 23:15）
pwsh -File "$R\nightly.ps1" -DryRun             # 演练：取票成功也不提交
pwsh -File "$R\nightly.ps1" -SelfTest           # 离线自检（不联网、不提交）
pwsh -File "$R\nightly.ps1" -Deadline 22:30     # 改截止时间
pwsh -File "$R\install-task.ps1" -Remove        # 删除计划任务
```

## 组件职责

| 脚本 | 职责 |
| --- | --- |
| `nightly.ps1` | 编排：预检 → 今日状态 → 拉起微信 → 基线 → 提醒你打开打卡页 → 等票 → 单次提交 → 台账/提醒 |
| `wechat_ticket_store.py` | 读 Session Storage、证明票据新鲜、把票据写回 `ticket.json` |
| `relay_checkin.py` | 今日状态（`--status-only`）、单次提交、回读；意图台账防重复 |
| `ledger_report.py` | 逐日台账报表（是否需人工 / POST 次数 / 回读结果） |
| `show-alert.ps1` | 顶部置顶红色大字提醒 |
| `open_miniprogram.ps1`、`wechat_ocr_tap.ps1`、`tap-steps.json`、`cold-start-steps.json` | **实验性**界面自动化，仅供手动试验，不参与日常流程 |

## 退出码

| 码 | 含义 | 处理方式 |
| --- | --- | --- |
| 0 | 成功，或今天已打过卡 | 无 |
| 1 | 已提交但未回读到 `CHECKED_IN`（如 46004） | **不要重跑**，打开小程序核对今日记录 |
| 2 | 票据/配置问题（没取到新票、票据过期、缺配置） | 重新打开打卡页后重跑；核对 `relay-config.json` |
| 3 | 网络/会话异常（`SESSION` 失效、读存储故障） | 检查网络；重新登录学校账号 |

## 台账与验收

```powershell
python "$R\ledger_report.py"        # 最近 7 天：需人工 / 自动点击 / 取票耗时 / POST 次数 / 回读
Get-Content "$R\relay.log" -Tail 5  # 每次提交的完整证据（HTTP、业务码、提交前后状态）
```

验收标准（连续 7 个业务日）：每行「需人工=否」「POST ≤ 1」「回读=CHECKED_IN」；
另需覆盖程序重启、断网、授权过期、错过时间窗。

## 常见故障

1. **「取票失败：需要人工打开打卡页」**：微信没登录、窗口被最小化到托盘、或页面没加载出来。打开小程序打卡页停 3 秒即可；票据只有几分钟有效期。
2. **「自动点击没成功」**：OCR 没认出文字。用
   `pwsh -File "$R\wechat_ocr_tap.ps1" -WindowMatch "微信" -ListText -Steps '[{"wait":1}]'`
   看它读到什么；微信主题/缩放变化会影响识别。
3. **「会话凭据失效」**：在小程序里重新打开一次打卡页（会刷新 session），或在电脑微信里重新登入学校账号。
4. **票据取到了但提交被拒**：看 `relay.log` 的 `checkinCode`——`46002` = 票据过期/不匹配（重开页面重取），`4006` = 今日已完成。

## 已知约束

- 小程序首页是「应用中心」，需要下滑才能看到「晚安成电」；`tap-steps.json` 已内置下滑步骤。
- 小程序窗口必须**可见、未最小化**：最小化时窗口被移到 `-21333,-21333`，点不到。
- 置顶与真实点击需要与微信相同的完整性级别（Medium）：在受限沙箱里跑会静默失败，**请在普通 PowerShell 里运行**。

## 已废弃（保留仅作参考，nightly 不再调用）

`mini_tls_proxy.py`、`enable_hosts_redirect.ps1` / `disable_hosts_redirect.ps1`、
`start_reverse_capture.ps1`、`reverse_fix_host.py`、`ticket_saver.py`、`preflight.ps1`、
`start_capture.bat`、`relay-proxy.pac`、`proxy-backup.json`（历史残留，可删）。
