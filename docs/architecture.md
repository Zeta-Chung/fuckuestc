# 架构说明

## 目标

在 Android 设备上以本人账号完成每日打卡：一次配置，按设定时间自动登录、提交，并以服务端记录确认结果。应用不依赖学校 App，不修改任何服务端数据。

## 模块

| 模块 | 职责 |
| --- | --- |
| `model/` | `CheckinConfig` 配置模型与校验、`LocationPresets` 位置预设、运行结果与原因码 |
| `protocol/` | `IdasClient` 登录、`JzszClient` 打卡接口、`DomainCookieJar` Cookie 隔离、`UrlConnectionTransport` HTTPS 传输 |
| `run/` | `CheckinRunner` 单次运行编排、提交台账与回读确认 |
| `schedule/` | `DailyScheduler` 闹钟与 WorkManager、开机恢复 |
| `storage/` | `SecretStore` Keystore 加密配置、`RunLogStore` 运行日志 |
| `ui/` | `MainActivity` 主界面、`LoginProbeActivity` 可见对照页、`FailureAlert` 失败提示 |

## 单次运行流程

1. 校验配置（必填项、坐标范围、`HH:mm` 时间格式与 `21:00–23:30` 窗口）。
2. IDAS 登录：设置 `MULTIFACTOR_BROWSER_FINGERPRINT` Cookie → 获取登录页 → 解析 `execution` 与 `pwdEncryptSalt` → AES 加密密码 → POST。
3. 跟随 CAS 重定向到 `jzsz.uestc.edu.cn`，取得 `SESSION`。
4. 请求 `/daily-checkin/checkLogin?appId=294` 激活打卡门户。
5. 查询 `/student-checkin-record/today`：已 `CHECKED_IN` 则直接成功；`PENDING` 继续。
6. 只读模式在此返回 `READ_ONLY`，不提交。
7. 查询 `checkin-schedule/effective` 得到当日有效时段，校验当前时间与位置半径。
8. 认领当日提交额度（每个业务日最多一次 POST）→ 提交 → 回读记录确认。
9. 成功条件：`CHECKED_IN` 且 `abnormalFlag=false`；超时或无法确认时返回“结果不确定”，不自动重发。

## 失败与原因码

- 登录阶段：`IDAS_PAGE_HTTP`、`IDAS_EXECUTION_MISSING`、`IDAS_SALT_MISSING`、`NEEDS_HUMAN`、`IDAS_TICKET_MISSING`、`CAS_REDIRECT_HOST`、`JZSZ_SESSION_MISSING`。
- 打卡阶段：`OUTSIDE_WINDOW`、`CONFIG_LOCATION_OUTSIDE`、`ALREADY_ATTEMPTED`、`POST_REJECTED`、`NETWORK_IO`、`LOCATIONS_UNKNOWN`。
- 所有原因码为固定值，UI 只在本地映射文案，便于问题定位。

## 安全设计

- 仅允许 `idas.uestc.edu.cn` 与 `jzsz.uestc.edu.cn` 的 HTTPS 请求。
- Cookie 按域隔离：指纹只属于 IDAS，`SESSION` 只属于 JZSZ。
- 配置使用 Android Keystore（AES/GCM）加密后写入应用私有 `SharedPreferences`。
- 每个业务日最多提交一次；请求超时不重发，避免重复打卡。
- 运行日志只记录状态码、原因码与时间。

## 限制

- 学校页面结构或接口变更会导致固定原因码失败，需要更新协议实现。
- 需要账号具备当日打卡资格，且时间落在学校开放时段内。
- Android 省电策略可能延迟闹钟；未授予精确闹钟权限时退化为近似调度。