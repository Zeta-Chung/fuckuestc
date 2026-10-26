# 更新日志

本项目版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### Added

- **2026-10-25 免抓包取票：直接读微信 WebView 的 Session Storage**。经实测（16 张历史票据 + 一张现场新票 `jCtN3G1AY6iD…` 提交得到 `code=4006 今日已完成打卡`）确认：票据在 `%APPDATA%\Tencent\xwechat\radium\web\profiles\webview_*\Session Storage\*.log` 里是**明文**（varint 长度前缀 + UTF-16LE，43 字符）。新增 `tools/pc-relay/wechat_ticket_store.py`：`--baseline`（打开打卡页之前记录日志大小与已有票据）→ `--read --write ticket.json`（只接受基线之后追加的新票；基线文件消失、体积缩水或出现新增 `.ldb` 一律 fail-closed 拒绝，避免把历史条目当成本次票据），自带 `--self-test`（5/5：无新写入拒绝、只接受追加票并忽略墓碑、缩水拒绝、新增 `.ldb` 拒绝、基线目录不符拒绝）。此路线**不需要 hosts 重定向、管理员权限或 TLS 代理**。
- **2026-10-25 提交端防重复**（`relay_checkin.py`）：`--max-ticket-age`（默认 300 秒）改用 `capturedAt` 判断时效（服务端 `expiresAt` 恒为 null）；提交前原子写 `submit-intent.json` 记录意图，提交后清空 `ticket.json` 的票据并记 `usedAt`/`lastResult`（票据一次性，防重放）；当天存在未确认意图时**只回查、不重发**（`--force` 可绕过）；`46004` 归入「结果待确认」（只回查、保留意图，退出码 1），`46001–46003` 落 `rejected`。桩函数用例 4/4 通过（`work/test_relay_guard.py`）。
- **2026-10-24 实测打通取票：`mini_tls_proxy.py` + hosts 重定向**。微信小程序运行时（WMPF）不走系统代理/PAC，mitmproxy 普通代理模式抓不到它的任何请求；改为把 `jzsz.uestc.edu.cn` 指向本机、由自写极简 TLS 反向代理（用 mitmproxy CA 签发带 SAN 的证书、上游固定真实 IP）截获，成功抓到票据并完成提交：`提交 HTTP 200 · code=0 · 操作成功`，复查 `CHECKED_IN`（21:35:27，距打卡点 17 米 / 半径 140 米）。配套脚本 `enable_hosts_redirect.ps1`、`disable_hosts_redirect.ps1`。
- `relay_checkin.py` 支持小程序实际使用的 `?sessionId=` 参数鉴权（此前只带 `SESSION` Cookie 会得到 `code=4001 登录无效`），并自动去掉抓包里的 `SESSION=` 前缀。
- `nightly.ps1` 端口自动挑选（8080 被占时自动换 8081 等）并在抓包进程秒退时立即还原代理；`-AutoTap` 自动进入静默模式，避免弹窗遮挡小程序窗口。
- PC 票据中继（`tools/pc-relay/`）：`nightly.ps1` 每晚自动接管系统代理、抓取微信签发的 `X-Checkin-Ticket`、轮询到新鲜票据后自动提交并复查，无论成败都恢复原代理设置。
- `install-task.ps1`：注册/删除每天 21:00 的 Windows 计划任务。
- `ticket_saver.py` 改为原子写入并记录 `capture.log`；`relay_checkin.py` 增加 JSONL 证据日志 `relay.log`、票据过期拦截与退出码约定。
- 设计文档：`docs/superpowers/specs/2026-10-24-pc-checkin-automation-design.md`。
- 新增两个单元用例：断网与时段外均不提交（`offlineNetworkFailureNeverPosts`、`outsideWindowNeverPosts`）。
- PC 微信界面自动点击：`wechat_tap.ps1` 用 Windows UI Automation 依次点击「成电智慧学工 → 请打卡 → 确认」，`nightly.ps1 -AutoTap` 把它接进每晚流程；`-Dump` 可导出控件树便于按真实文本调整 `tap-steps.json`。
- 新增 [完整运行流程](tools/pc-relay/RUNBOOK.md)：按本机已核实环境（证书、定位开关、微信版本、代理现状）给出阶段 1/2/3 步骤、验证点与失败分支。
- `install-task.ps1` 新增 `-AutoTap` 开关（注册带自动点击的计划任务），默认触发时间改为 **21:02**（留 2 分钟避开系统时间差）。
- 新增 `preflight.ps1` 打卡前自检（mitmdump / python / 坐标 / 微信进程 / 系统代理 / 学校接口可达性），接入 RUNBOOK 阶段 2 第 1 步。
- 新增 `wechat_ocr_tap.ps1`：微信 4.x 界面不暴露 UI Automation 控件（实测控件树为空），改为「置顶并抢焦点 → 截图 → Windows 内置 OCR → 按坐标点击」；支持 `text`/`scroll`/`wait` 三类步骤。实测要点：滚轮消息发给焦点窗口（必须真正拿到焦点）、中文匹配需忽略空白、负滚轮值需按 `4294967296 + delta` 折算成 uint32、`PrintWindow` 对该窗口无效只能屏幕截图、确认弹窗按钮文字为「确定」。`nightly.ps1 -AutoTap` 默认改用该脚本。
- “自动获取浏览器指纹”：在应用内打开 IDAS 登录页，登录成功进入打卡站点后自动读取 `MULTIFACTOR_BROWSER_FINGERPRINT` 并填入配置。
- 补偿执行：开机或打开应用时，若当天已过设定时间、仍在 `21:00–23:30` 内且未提交，立即补跑一次打卡。

### Changed

- README 状态说明改为“手机端自动提交被微信票据校验阻断，自动提交由 PC 票据中继承担”，并新增对应常见问题行。
- 设备验收记录补充 2026-10-24 状态说明，并新增“离线（单元层）覆盖”一节，明确离线证据不等于设备验收通过。
- 常见问题新增“每晚收到群发‘未打卡’提醒但应用显示成功”的处理说明。
- 常见问题新增“换手机/重装/长期未用后提示需要人工验证”的处理说明；`NEEDS_HUMAN` 提示改为建议重新抓取本机指纹。

## [0.1.0] - 2026-10-22

### Added

- 卡片式中文字界面：运行概览、账号与验证、打卡位置、打卡时间、高级参数分区。
- “欣苑 / 校内”位置预设，选择后自动填入 GCJ-02 坐标与地址。
- 应用内“使用说明”与“常见问题”弹窗。
- 自定义自适应启动图标（含 Android 13 主题图标）。
- 启动异常兜底页，异常时显示堆栈而非直接闪退。

### Changed

- 每日打卡时间限制为 `21:00–23:30`（默认 `22:00`）。
- CAS `service` 使用站点根地址，登录后由 `/daily-checkin/checkLogin?appId=294` 激活打卡门户。
- 状态栏适配 Android 15 edge-to-edge，内容不再被状态栏遮挡。

### Fixed

- 登录页正文包含“多因素认证 / 验证码”字样时，提交凭证前被误判为 `NEEDS_HUMAN`。
- 启动阶段提前访问 `window.insetsController` 导致的空指针闪退。

### Verified

- 34 个单元测试通过；`assembleDebug` 构建成功。
- 2026-10-22 真机通过只读检查（`READ_ONLY`），并在打卡窗口内完成一次手动提交，回读记录一致。