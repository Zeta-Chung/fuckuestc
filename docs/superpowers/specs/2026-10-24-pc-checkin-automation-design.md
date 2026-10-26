# PC 自动打卡（票据中继）设计

日期：2026-10-24
状态：待实现（v1 目标：当晚 21:00–23:30 窗口内跑通）

## 背景

学校给打卡写操作增加了微信票据校验：

- 提交接口 `POST /daily-checkin/api/student-checkin-record/checkin` 必须携带 `X-Checkin-Ticket`；
- 票据只能由微信客户端用 `wx.login` 的 code 换取（`POST /platform-app/api/auth/checkin-ticket`，code 5 分钟、一次性）；
- 票据为 43 字符 base64url，有效期约 30 分钟；
- 小程序把票据拼进 H5 地址（`?checkinTicket=…`），H5 存 `sessionStorage` 后作为请求头发出；`46001/46002/46003` 表示票据缺失/过期/不匹配，需回到小程序重新授权。

因此手机端 App 无法自行提交。可行路线是：在**装有微信并保持登录的 Windows 电脑**上抓一次票据（含配对 SESSION），再由脚本用同一对凭据完成提交。

## 目标

1. 每晚 21:00 自动就绪：设置系统代理、启动抓包、提醒用户打开一次小程序。
2. 取到新鲜票据后自动提交并复查，结果落盘可查。
3. 无论成功、失败、被强杀，**系统代理必须恢复**，不得留下断网状态。
4. 全流程可离线演练（`--dry-run`），`--submit` 才真正提交。

## 非目标

- 不实现微信协议（ESP32 等方案不在本设计内）。
- 不做 UI 自动化点微信（脆弱，且可能触发微信风控）。
- 不伪造定位日志；定位字段按小程序真实枚举上报（`locationAccuracy=full` 等）。

## 组件

| 组件 | 文件 | 职责 |
| --- | --- | --- |
| 取票抓包器 | `tools/pc-relay/ticket_saver.py` | mitmproxy 插件，命中 `checkin-ticket` 响应后原子写入 `ticket.json` |
| 提交器 | `tools/pc-relay/relay_checkin.py` | 只读演练 / 提交 / 复查，写 `relay.log` |
| 编排器 | `tools/pc-relay/nightly.ps1` | 代理开关兜底、启动抓包、提醒、轮询票据、调用提交器、通知结果 |
| 启动器 | `tools/pc-relay/nightly.bat` | 双击/计划任务入口 |
| 计划任务安装 | `tools/pc-relay/install-task.ps1` | 注册每天 21:00 触发 |
| 配置 | `tools/pc-relay/relay-config.json` | 坐标与定位字段（不入库） |

## 数据流

```
21:00 nightly.ps1
  ├─ 备份并接管系统代理 (127.0.0.1:8080)
  ├─ 启动 mitmdump -s ticket_saver.py（日志 capture.log）
  ├─ 弹出提醒：请在 PC 微信打开「成电智慧学工」小程序打卡页
  ├─ 轮询 ticket.json（新鲜且未过期）──超时 23:15──▶ 通知“未取到票”
  └─ 命中后：relay_checkin.py --submit
        ├─ GET today（只读）
        ├─ GET available-locations（距离检查）
        ├─ POST checkin + X-Checkin-Ticket
        ├─ GET today（复查）
        └─ 追加 relay.log
  finally: 结束 mitmdump、恢复原代理设置
```

## 关键实现约束

1. **代理恢复**：`nightly.ps1` 用 `try/finally`，并在启动时把原始注册表值备份到 `proxy-backup.json`，异常退出后下次启动也会先尝试恢复。
2. **票据时效**：提交器默认拒绝过期票据（退出码 2），`--force` 可覆盖。
3. **配对会话**：票据与签发时的 SESSION 必须成对使用，抓包器同时落盘两者。
4. **枚举对齐**：`locationAuthorized=authorized`、`locationAccuracy=full`、`accuracy<=100`、`networkTypeCode` 使用配置值。
5. **可观测**：`capture.log`、`relay.log`（JSONL）保留票据前缀、HTTP 状态、业务码、复查状态。

## 成功标准

1. 打开小程序后 30 秒内自动提交，`today` 复查为 `CHECKED_IN`。
2. 强杀 `nightly.ps1` 后系统代理恢复（离线验证）。
3. `relay.log` 含完整证据链（票据前 12 位、HTTP 码、业务码、复查状态）。
4. `tools/pc-relay/README.md` 按 ≤10 步可从零复现。

## 阶段 2：微信界面自动点击（可选）

目标：把「在小程序里点两下」也自动化，实现无人值守。

实现：`wechat_tap.ps1` 用 Windows 内置 UI Automation（`UIAutomationClient`，无额外依赖）在微信进程中查找控件，按 `tap-steps.json` 的文本顺序点击；`nightly.ps1 -AutoTap` 在抓包启动后调用它。

约束与前提：

1. **PC 微信必须能拿到范围内定位**（Windows 定位服务开启、微信有定位权限），否则点击成功也会被判超范围。
2. 界面文本随微信版本变化；未命中时用 `-Dump` 导出 `wechat-tree.txt`，按真实控件名改 `tap-steps.json`。
3. 点击是模拟鼠标（先试 `InvokePattern`，失败则移动光标点击），需要微信窗口能前置。
4. 冷启动（从关闭状态打开小程序并进入打卡页）需要更多步骤文本，待拿到真实控件树后补进 `tap-steps.json`。

## 未验证风险

- 服务端是否交叉校验小程序定位日志；若校验，本方案会被拒。
- 票据与 SESSION 绑定强度未知（脚本按“成对复用”保守处理）。
- 30 分钟内重复提交、跨日行为未验证。