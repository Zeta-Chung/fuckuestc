# 打卡助手（CheckinAssistant）

在 Android 设备上完成电子科技大学每日打卡的辅助工具：一次配置，每天按设定时间自动登录并提交，提交后回读当天记录确认结果。

> 本项目仅供学习交流，请勿用于非法用途。使用者须遵守学校相关规定，风险自负，详见[免责声明](#免责声明)。

当前状态（2026-10-24）：学校给写操作增加了微信票据校验（提交需带 `X-Checkin-Ticket`，票据只能由微信小程序签发，约 30 分钟有效），**手机端自动提交因此被服务端拒绝**（`POST_REJECTED · 请从微信小程序重新进入打卡应用后提交`）。手机端仍可完成登录、只读检查与配置；自动提交改由 [PC 票据中继](tools/pc-relay/README.md) 承担，该工具已完成离线验证，真实提交待当晚窗口实测。

## 重构原因（为什么变成「手机端只读 + PC 票据中继」）

本项目最初的目标是「手机端全自动提交」。2026-10-24 学校给写接口加上微信票据校验后，这条路在服务端被堵死，架构随之调整：

1. **票据只能由微信签发**：提交必须带 `X-Checkin-Ticket`，而票据由小程序在 `wx.login()` 之后向 `/api/auth/checkin-ticket` 换取，有效期约 5 分钟（服务端 `expiresAt` 恒为 `null`，只能按本地 `capturedAt` 判断时效）。手机端没有微信登录态，拿不到票据，任何提交都会被拒绝（`POST_REJECTED · 请从微信小程序重新进入打卡应用后提交`）。逆向微信登录协议在技术上可行，但要交出微信账号凭据，风险与收益不对等，因此放弃。
2. **PC 端放弃界面自动点击**：微信 4.x 是 Qt 自绘界面，实测不暴露 UI Automation 控件树，`PostMessage` 合成点击被忽略，只能靠截图 OCR + 真实鼠标坐标点击。实测中曾误点「视频号」导致播放视频、窗口假死，且分辨率、DPI、窗口位置一变就失效——不可靠，相关脚本（`wechat_ocr_tap.ps1`、`open_miniprogram.ps1`）已降级为实验工具，默认流程不再调用。
3. **改为只读微信自己的缓存**：票据在微信 WebView 的 Session Storage（LevelDB）里是明文，解析即可，不需要 hosts 重定向、管理员权限或 TLS 代理，早期的 `mini_tls_proxy.py` + hosts 方案因此退居备用。
4. **人工只剩一个动作**：每晚人工打开一次打卡页（约 3 秒），程序负责取新票据 → 单次提交 → 回读 `CHECKED_IN` → 记账。取舍是「不碰账号凭据、不注入 GUI 输入、失败可回查」，代价是做不到真正无人值守。

手机端因此保留登录、只读检查、配置与补偿执行；自动提交交给 PC 票据中继。

## 功能特性

- **加密配置**：账号、密码、浏览器指纹、坐标与打卡参数使用 Android Keystore（AES/GCM）加密保存在应用私有目录。
- **每日定时**：`AlarmManager` + `WorkManager` 调度，支持精确/近似模式，开机后自动恢复。
- **结果确认**：提交后回读当天记录，只有 `CHECKED_IN` 且 `abnormalFlag=false` 才算成功；超时显示“结果不确定”，不自动重发。
- **位置预设**：内置“欣苑”“校内”两个位置，选择后自动填入 GCJ-02 坐标与地址；也可手动填写任意坐标。
- **时间约束**：每日打卡时间限制在 `21:00–23:30`。
- **失败提醒**：前台顶部弹窗，后台高优先级通知。
- **应用内帮助**：分步“使用说明”与“常见问题”，覆盖首次配置与失败处理。

## 快速开始

### 安装

使用构建产物 `app/build/outputs/apk/debug/app-debug.apk`，或按[构建与测试](#构建与测试)自行编译。

### 首次配置

每台手机、每个账号独立配置，手机需要联网。不要在群聊、截图或仓库中分享密码、指纹、Cookie 和精确位置。

#### 需准备的信息

1. **账号和密码**：使用平时登录学校统一身份认证（IDAS）的本人账号与密码。忘记密码时走学校正式找回流程，不使用他人的账号。
2. **浏览器指纹**：在电脑 Chrome 或 Edge 中用本人账号打开 `https://jzsz.uestc.edu.cn`。出现 IDAS 登录页（地址栏显示 `idas.uestc.edu.cn`）时按 `F12`，保持开发者工具打开，按学校页面提示完成登录和人工验证。随后在开发者工具中依次打开 **Application（应用/应用程序）→ Storage（存储）→ Cookies → `https://idas.uestc.edu.cn`**，查找 `MULTIFACTOR_BROWSER_FINGERPRINT`，只复制该行的 **Value（值）**。列表中没有 IDAS 域时可重新打开登录入口并在 IDAS 页面查看；没有该 Cookie 时先完成本人浏览器上的正式可信设备验证。不要填写他人的指纹或猜测一个值。也可以点应用内“浏览器指纹”下方的“自动获取”，在应用内登录一次后自动填入。
3. **经纬度和地址**：登录 JZSZ 后打开打卡页面，按 `F12` 打开 **Network（网络）**，刷新页面，搜索 `available-locations`。点开 `/daily-checkin/api/checkin-location/available-locations`，在 **Response（响应）** 中找到允许位置的 `latitude`、`longitude` 与 `radiusMeter`。从本人可使用的位置中选一个，`latitude` 填“纬度”、`longitude` 填“经度”。坐标系按接口使用 GCJ-02。需要地址时，在同一已登录浏览器访问 `https://jzsz.uestc.edu.cn/daily-checkin/api/map/reverse?lat=纬度数字&lng=经度数字`，复制响应 `data` 中的地址；接口无结果时以打卡页面显示的地址为准。
4. **打卡时间**：先看学校打卡页面显示的当日时段。页面未写明时，在 **Network** 中搜索 `checkin-schedule/effective`，查看响应 `data` 中的 `studentCheckinStartOffsetMinute` 与 `studentCheckinEndOffsetMinute`。这两个数是当天 00:00 起的分钟数，除以 60 得小时、余数为分钟；例如 `1380` 是 `23:00`。在允许时段内选一个 `HH:mm` 填写，本应用只接受 `21:00`–`23:30`。预填的 `22:00` 只是默认值，不是学校指定时间。
5. **User-Agent 与其余参数**：`User-Agent` 默认填入当前设备 WebView 的值，通常直接保留；`accuracy`、`locationDurationMs`、`networkTypeCode`、`locationEnabled` 等已有预填值，若本人正常打卡请求有明确要求再调整。标有“可留空”的输入框可以不填。拿不到必填信息时先不要提交打卡。

#### 填写并保存

1. 将 APK 传到手机，在文件管理器中点开安装；系统询问来源时按提示允许。安装后打开“打卡助手”。
2. 按页面填写，应用内右上角“使用说明”有同样的分步指引；“高级参数”卡片默认收起，需要时再展开。

   | 页面输入框 | 填写内容 |
   | --- | --- |
   | 账号、密码 | 上面第 1 步的本人登录信息。 |
   | 获授权的浏览器指纹 | 只填第 2 步找到的 `MULTIFACTOR_BROWSER_FINGERPRINT` 值，不填整段 Cookie，也不填 `SESSION`。 |
   | 打卡位置 | 点“欣苑”或“校内”自动填入坐标和地址；都不是你的位置时展开“自定义坐标”手填。 |
   | 每日打卡时间（HH:mm）、时区 | 按第 4 步选择 `21:00`–`23:30` 内的时间；中国大陆通常保留 `Asia/Shanghai`。 |
   | User-Agent、其余参数 | 按第 5 步处理；没有特殊要求时保留预填值。 |

3. 点“保存加密配置”，看到“配置已加密保存”后继续；出现红色提示时按提示检查必填项、坐标、时间格式和数字。
4. 点“只读检查”，等页面下方出现“最终”结果。它只检查登录与当天记录，**不会提交打卡**。看到 `READ_ONLY`，或当天已有记录时看到 `SUCCESS`，再继续。
5. 需要提交时点一次“立即打卡一次”，再到学校页面核对当天记录。只有回读为 `CHECKED_IN` 且 `abnormalFlag=false` 才算成功；显示“结果不确定”时先查记录，不要连点。
6. 点“开启每日自动打卡”。Android 13 及以上询问通知权限时选择允许，随后**再点一次**。看到“每日任务已开启”和“下次时间”才算完成。

### 日常查看与失败处理

- 打开应用查看“每日任务”“下次时间”“最近结果”；停止时点“关闭每日自动打卡”。修改配置后点“保存加密配置”，已开启的任务会随之更新。
- 运行失败时：前台显示可关闭的顶部弹窗，后台发出“打卡失败提醒”通知。可在“打开失败通知设置”中允许横幅；系统免打扰也可能隐藏横幅，失败记录仍可在应用内查看。
- 提示需要人工验证时，按学校登录页提示完成验证后再运行“只读检查”；自动任务不会越过人工验证继续提交。“打开可见 IDAS 对照页”仅供检查登录页面，日常运行不需要打开。
- Android 12 及以上可在“打开精确闹钟权限设置”中允许精确闹钟；未允许时显示“近似”调度，实际触发可能晚于设定时间。
- 若当天已过设定时间仍未打卡，开机或重新打开应用时会自动补跑一次（仅限 `21:00–23:30` 内且当天尚未提交）。

## 常见问题

| 现象或提示 | 解决方法 |
| --- | --- |
| 保存提示“打卡时间必须在 21:00 到 23:30 之间” | 把每日时间改到 `21:00`–`23:30` 内，时区保持 `Asia/Shanghai`，并让手机自动同步时间。 |
| 指纹格式无效或提示为空 | 只填 `MULTIFACTOR_BROWSER_FINGERPRINT` 的值，不要带分号、换行或整段 Cookie。 |
| 只读检查显示 `NEEDS_HUMAN` | 按学校登录页提示完成人工验证，再重新“只读检查”；自动任务不会越过验证。 |
| 显示 `OUTSIDE_WINDOW` | 当前不在学校允许时段，核对手机时间和学校页面上的时段。 |
| 显示 `CONFIG_LOCATION_OUTSIDE` | 坐标不在允许范围：重新点“欣苑”或“校内”，或按 `available-locations` 重填 GCJ-02 坐标。 |
| 显示 `ALREADY_ATTEMPTED` 或“结果不确定” | 当天已发过提交。若上次是 `POST_REJECTED`，可在“高级参数”里清除今天的提交锁后重试；若是“结果不确定”，先查学校当天记录，不要反复提交。 |
| 显示 `POST_REJECTED · 请从微信小程序重新进入打卡应用后提交` | 学校新增微信票据校验，手机端拿不到票据。改用 [PC 票据中继](tools/pc-relay/README.md)，或每次手动在小程序里打卡。 |
| 显示 `POST_REJECTED` | 状态行会附带服务器返回的原因；按原因修正配置后，可清除今天的提交锁再试一次。 |
| 显示 `NETWORK_IO` 或 `LOCATIONS_UNKNOWN` | 网络或接口异常：切换网络、确认能打开学校页面后重试。 |
| 收不到失败通知 | 允许通知权限，确认“打卡失败提醒”渠道为高重要性并关闭免打扰；Android 13 及以上允许后需再点一次“开启每日自动打卡”。 |
| 定时没有按点触发 | 允许精确闹钟权限；系统省电策略可能延迟触发，应用会显示“近似”调度。 |
| 保存失败：设备密钥不可用 | 重启手机后重试；仍失败需清除应用数据后重新配置。 |
| 每晚不固定时间收到学校或微信群发的“未打卡”提醒，但应用显示成功 | 这类通知可能是群发或滞后发送；先到学校打卡页面核对当天记录，确认 `CHECKED_IN` 后即可忽略。 |
| 换手机、重装或长时间未用后提示需要人工验证 | 清除应用数据或卸载重装，重新点“自动获取”抓取本机指纹，保存后再做只读检查。 |
| 手机关机或闹钟延迟错过了设定时间 | 开机后或再次打开应用时，若仍在 `21:00–23:30` 之间且当天未打卡，应用会自动补跑一次。 |

## 项目结构

```
app/src/main/java/edu/uestc/checkin/
├── model/       配置模型、位置预设、运行结果
├── protocol/    IDAS 登录、JZSZ 接口、Cookie 隔离、HTTP 传输
├── run/         单次运行编排与每日提交台账
├── schedule/    闹钟、WorkManager、开机恢复
├── storage/     Keystore 加密配置、运行日志
└── ui/          主界面、登录对照页、失败提醒

tools/pc-relay/  PC 票据中继：抓票、提交、每晚自动编排与计划任务
```

更多说明见 [架构说明](docs/architecture.md) 与 [隐私说明](docs/privacy.md)。

## 构建与测试

需要 JDK 17、Android SDK Platform 35 与 Build Tools 35：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。技术栈：Kotlin 2.2.21、AGP 8.9.2、Gradle 8.11.1、WorkManager 2.10.5；首次构建需要访问 Google Maven 与 Maven Central。CI 配置见 `.github/workflows/android.yml`。

设备验收项见 [设备验收记录](docs/device-verification.md)。

## 协议依据与限制

- 初始协议字段参考公开的 [UESTC-MoonCheck PC 实现](https://github.com/richard297779/UESTC-MoonCheck/tree/main/dev/tools/pc_reference) 及[接口文档](https://github.com/richard297779/UESTC-MoonCheck/blob/main/dev/docs/api-contract.md)。它们属于第三方观察，目标环境中的脱敏响应才是最终验证依据。
- IDAS 表单、CAS 重定向或 JZSZ JSON 变更时，应用报告固定原因码，不把 HTTP 200 自动视为成功。
- 打卡 POST 每个业务日最多一次；请求超时后回读当天记录，无法确认时显示“结果不确定”，不自动重发。
- 只向 `idas.uestc.edu.cn` 与 `jzsz.uestc.edu.cn` 发起 HTTPS 请求；指纹 Cookie 只属于 IDAS 域，`SESSION` 只属于 JZSZ 域。
- 报告问题时只提交脱敏的状态码、原因码和已删去个人信息的结构样本；不要发送密码、Cookie、CAS 票据、精确坐标或原始响应。

## 参与贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)；安全问题请按 [SECURITY.md](SECURITY.md) 私下报告。版本记录见 [CHANGELOG.md](CHANGELOG.md)。

## 免责声明

该程序仅供学习交流使用，请勿用于非法用途。使用者应遵守学校关于考勤与信息系统的规定，因使用本程序产生的任何后果由使用者自行承担。本项目与电子科技大学官方无关。

## 许可证

[MIT](LICENSE)