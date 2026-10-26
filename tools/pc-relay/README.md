# PC 自动打卡（票据中继）

学校从 2026-10 起给打卡写操作加了微信票据：

- 提交 `POST /daily-checkin/api/student-checkin-record/checkin` 必须带 `X-Checkin-Ticket`；
- 票据只能由微信客户端用 `wx.login` 的 code 换取（`POST /platform-app/api/auth/checkin-ticket`，code 5 分钟一次性、票据约 30 分钟）；
- 小程序把票据拼进 H5 地址（`?checkinTicket=…`），H5 存 `sessionStorage` 后作为请求头发送；`46001/46002/46003` 表示票据失效，需要回小程序重新授权。

手机端 App 拿不到票据，所以在**装了微信并保持登录的 Windows 电脑**上抓一次票据，再用同一对凭据提交。本目录就是这套工具。

本机已完成配置的逐步操作见 [完整运行流程](RUNBOOK.md)。

> **2026-10-24 实测结论（重要）**：微信小程序运行时（WMPF）**不走系统代理/PAC**，mitmproxy 的普通代理模式抓不到它任何请求。
> 目前唯一验证成功的取票路线是 **hosts + 自写 TLS 反向代理**：`enable_hosts_redirect.ps1` + `mini_tls_proxy.py`，
> 已实测跑到 `提交 HTTP 200 · code=0 · 操作成功`，复查 `CHECKED_IN`（打卡时间 2026-10-24 21:35:27）。

## 组件

| 文件 | 作用 |
| --- | --- |
| `mini_tls_proxy.py` | **取票用**：本机 443 上的极简 TLS 反向代理，用 mitmproxy CA 给 `jzsz.uestc.edu.cn` 签发带 SAN 的证书，上游固定真实 IP，顺手把票据/`sessionId`/`SESSION` Cookie 写进 `ticket.json` |
| `enable_hosts_redirect.ps1` / `disable_hosts_redirect.ps1` | 把 `jzsz.uestc.edu.cn` 指向 `127.0.0.1`（需要管理员），用完必须还原 |
| `ticket_saver.py` | mitmproxy 插件：命中取票响应后原子写入 `ticket.json`（普通代理模式下使用，WMPF 不走代理时无效） |
| `relay_checkin.py` | 只读演练 / 提交 / 复查 `today`，追加 JSONL 证据到 `relay.log`（只用标准库；提交带 `?sessionId=` 与 `SESSION` Cookie） |
| `nightly.ps1` | 编排：备份并接管系统代理 → 起抓包 → 提醒打开小程序 → 轮询新鲜票据 → 调提交器 → 恢复代理 |
| `nightly.bat` | 双击入口（自动选 `pwsh` 或 `powershell`） |
| `install-task.ps1` | 注册/删除每天自动触发的计划任务 |
| `wechat_ocr_tap.ps1` | **默认**自动点击实现：把小程序窗口前置 → 截该窗口 → Windows 内置 OCR 找文字 → 按坐标点击（微信 4.x 的 Qt/Chromium 界面不暴露 UI Automation 控件，实测控件树为空） |
| `wechat_tap.ps1` | 备用实现：按 UI Automation 控件名点击，仅在旧版微信能读到控件时可用 |
| `start_reverse_capture.ps1` | 尝试用 mitmproxy 反向模式取票（**在 hosts 指向本机时会自己连自己，已废弃**，仅留作记录） |
| `preflight.ps1` | 打卡前自检：mitmdump、python、坐标、微信是否运行、系统代理现状、学校接口可达性 |
| `tap-steps.json` | 点击顺序与超时配置（匹配时忽略空白，因 OCR 会把「请打卡」拆成「请 打 卡」） |
| `relay-config.example.json` | 坐标与定位字段模板 |
| `start_capture.bat` | 只抓包、不提交的手动入口（调试用） |

## 取票 + 提交（已验证的最小流程，约 2 分钟）

1. 管理员 PowerShell：`pwsh -File enable_hosts_redirect.ps1`（把域名指到本机）。
2. 普通 PowerShell：`python mini_tls_proxy.py`（监听 443，保持窗口）。
3. 小程序里点「晚安成电」打开打卡页（触发领取票据）。
4. 看 `ticket.json` 是否生成 → `python relay_checkin.py --force --submit`。
5. 管理员 PowerShell：`pwsh -File disable_hosts_redirect.ps1`（还原 hosts，**必做**）。

## 首次配置

1. 安装 mitmproxy：`pip install mitmproxy`。
2. 安装根证书：双击 `%USERPROFILE%\.mitmproxy\mitmproxy-ca-cert.cer` → **本地计算机** → **受信任的根证书颁发机构**（目录里没有该文件时，先运行一次 `mitmdump`）。
3. 复制 `relay-config.example.json` 为 `relay-config.json`，改成本人要用的坐标（默认欣苑 `30.674677,104.096273`）。
4. 确认 PC 微信已登录，且能打开「成电智慧学工」小程序的打卡页。
5. 用**非沙箱**的 PowerShell 自测代理接管与恢复：`pwsh -File nightly.ps1 -SelfTest`；结束后 `ProxyEnable` 必须回到原值。
6. 演练一次（不提交）：`nightly.bat -DryRun`，按弹出的提醒打开小程序打卡页，等脚本自动跑完。
7. 正式使用：双击 `nightly.bat`，同样打开小程序打卡页，脚本会自动提交并复查。
8. 注册每天自动触发：`pwsh -File install-task.ps1 -AutoTap`（默认 **21:02**，避开系统时间差；全自动含小程序自动点击；不加 `-AutoTap` 则需你手动点击；删除用 `-Remove`）。

## 全自动模式（可选：连微信里那两次点击也自动）

前提：**PC 微信必须能拿到打卡范围内的定位**（Windows「定位服务」开启、微信有定位权限）；否则点得再准，服务端也会判超范围。

1. 启动 PC 微信并保持登录，把小程序打开到能点到「请打卡」的状态。
2. 先诊断：`pwsh -File wechat_ocr_tap.ps1 -ListText -WindowMatch 成电`，确认能 OCR 到页面文字与坐标。
3. 再干跑：`pwsh -File wechat_ocr_tap.ps1 -DryRun`，看是否命中「成电智慧学工 / 请打卡 / 确认」。
4. 命中后接上编排器：`nightly.bat -AutoTap`（或 `pwsh -File nightly.ps1 -AutoTap`），它会先自动点击、再轮询票据、提交并复查。
5. 某一步找不到时，按 `-ListText` 输出的真实文字改 `tap-steps.json`；弹窗不在小程序窗口内时加 `-FullScreen`。

说明：`wechat_ocr_tap.ps1` 调用的是 WinRT OCR，PowerShell 7 不支持 WinRT，脚本会自动转交给 Windows PowerShell 5.1 执行（你不需要手动切换）。
另外必须在**你自己的普通 PowerShell** 里运行：置顶窗口、模拟鼠标点击要求与微信同等的完整性级别（Medium）；在受限/沙箱环境里 `SetWindowPos`、`PrintWindow` 会静默失败，截图会拍到上层窗口。

风险说明：这一步是按坐标/控件名模拟鼠标点击，属于个人宏操作；微信若更新界面，文本匹配会失效，需要重新 `-Dump` 调整。是否使用由你决定。

## 安全与代理行为

- 脚本启动时把原代理设置备份到 `proxy-backup.json`，`finally` 中恢复；上次异常退出残留时，下次启动会先恢复。
- 抓包只解密学校域名：`--allow-hosts jzsz\.uestc\.edu\.cn`，其余流量（含微信其它请求）只透传不解密。
- 运行前请**退出其它代理工具**（如 FlClash / Clash），否则流量会绕过系统代理导致抓不到票；脚本结束后会把系统代理恢复成运行前的原值。
- `ticket.json`、`relay-config.json`、`proxy-backup.json` 与所有 `*.log` 已加入 `.gitignore`，**不要外传**：前两者等同账号凭据。
- 计划任务需要保持登录状态；电脑关机或微信掉线都会导致当天取不到票。

## 验证状态

已验证（离线）：

- `ticket_saver.py`、`relay_checkin.py` 语法与冒烟：票据过期拦截、`relay.log` 落盘。
- `nightly.ps1` 无票超时路径、票据拾取 + 演练路径、抓包进程启动与回收。
- 代理接管/恢复代码在沙箱内无法写注册表（`Requested registry access is not allowed`），需在你自己的 PowerShell 里跑第 5 步确认。

未验证：

- 真实提交是否通过（取决于服务端是否额外交叉校验小程序定位日志）。
- 票据与 `SESSION` 的绑定强度、30 分钟内重复提交行为。
- 计划任务在关机/休眠后的恢复行为。

## 排错

| 现象 | 处理 |
| --- | --- |
| `找不到 mitmdump` | `pip install mitmproxy` 后重开终端 |
| 自动点击报「找不到匹配的可见窗口」 | 先启动 PC 微信并打开小程序；窗口标题不含关键词时用 `-WindowMatch` 指定 |
| OCR 未命中文字 | `pwsh -File wechat_ocr_tap.ps1 -ListText` 看识别结果，按真实文字改 `tap-steps.json`；弹窗在内层时加 `-FullScreen` |
| 提醒窗口出现但没有自动提交 | 确认在小程序里**进入了打卡页**（只有该页面会取票）；查看 `capture.log` |
| `票据已过期` | 重新进一次小程序打卡页取票 |
| `code=46001/46002/46003` | 票据失效或不匹配，重新取票；不要重复提交 |
| `坐标不在允许范围内` | 改 `relay-config.json`，或临时加 `--force` |
| 结束后无法上网 | 检查 `proxy-backup.json` 是否残留；重新运行一次脚本会自动恢复 |
| 计划任务没触发 | `schtasks /Query /TN UESTC-Checkin-Relay`；确认电脑已登录且未休眠 |

## 合规提示

本工具只用于本人账号，且会向学校接口提交打卡记录与位置信息；请自行确认是否符合学校规定，风险自负。