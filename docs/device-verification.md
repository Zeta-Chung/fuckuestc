# 设备验收记录

状态：2026-10-22 已在真机完成安装、配置、登录、只读检查与一次手动打卡；提交后回读记录一致。该提交发生在后来观察到的微信票据校验之前，不能证明当前版本仍能由 Android 原生客户端提交。另有一台设备首次提示 `NEEDS_HUMAN`，卸载重装并重新抓取指纹后恢复正常，说明旧配置/旧指纹会失效。其余项目待后续验收，未执行项不代表通过。

2026-10-24 补充：微信票据校验已确认（提交需带 `X-Checkin-Ticket`，Android 原生客户端无法自行取票），[PC 票据中继](../tools/pc-relay/README.md) 工具已就位；第 19 行仍为待执行，离线演练不能替代真实票据验证。

| 项目 | 结果 | 脱敏证据 |
| --- | --- | --- |
| JDK/SDK/Gradle 构建成功 | 通过 | 2026-10-22：`:app:testDebugUnitTest :app:assembleDebug` 显示 `BUILD SUCCESSFUL`；37 个单元测试通过；`app-debug.apk` 已通过 `apksigner verify` 验签 |
| APK 安装、启动及配置加密保存 | 通过 | 2026-10-22 真机安装启动正常，“配置已加密保存”提示出现 |
| IDAS 原生自动登录（WebView 关闭） | 通过 | 只读检查返回 `IDAS · SUCCESS`，无票据/Cookie 记录 |
| JZSZ 会话激活及当天记录解析 | 通过 | 只读检查返回 `JZSZ · PORTAL_READY`、`TODAY · PENDING` |
| 手动单次打卡与服务端回读一致（票据校验变化前） | 通过 | 2026-10-22 21:03 打卡窗口内手动提交成功，回读确认通过；不作为当前写接口的通过证据 |
| 同一天重复点击或重复触发不重复 POST | 待执行 | 同一业务日期的请求次数、提交锁状态和当天记录；清除提交锁不计入正常流程 |
| POST 响应丢失或超时后的不确定状态 | 待执行 | 仅一次 POST、随后回读当天记录；未确证时保持“不确定”且不重发 |
| 运行期间断网与恢复网络 | 待执行 | 失败提醒、当天记录回读、恢复后是否误报成功或重复提交 |
| 错过设定时间与开放窗口 | 待执行 | 窗口内开机/打开应用时补跑一次；窗口结束后不补交，并显示次日安排 |
| 前台失败顶部弹窗 | 待执行 | 截图需遮蔽账号和位置 |
| 后台失败顶部横幅通知 | 待执行 | 通知权限和渠道状态 |
| 关闭 WebView 后每日自动登录与只读状态 | 待执行 | 实际与预定时间、登录及当天记录；当前 Android 原生提交受微信票据校验阻断 |
| PC 中继使用真实小程序票据后的单次提交 | 待执行 | 只记录脱敏的取票、提交与当天记录结果；须由本人进入小程序，不能以离线演练代替通过 |
| 重启后恢复定时 | 待执行 | 重启前后下次触发时间、窗口内补跑次数、当天记录 |

测试人员应在此表填写实际结果。若 Android 的免打扰或通知渠道设置阻止顶部横幅，应记录系统状态，不能把应用内记录当成横幅验收通过。

## 离线（单元层）覆盖

2026-10-24 在本仓库离线运行 `gradle :app:testDebugUnitTest --offline`，41 个用例全部通过。下表只证明逻辑分支，**不代表设备验收通过**：

| 对应验收行 | 测试用例 | 覆盖内容 |
| --- | --- | --- |
| 同一天重复点击或重复触发不重复 POST | `CheckinRunnerTest.secondRunDoesNotSubmitAgain` | 当天已提交过时第二次运行 0 次 POST，返回 `UNCERTAIN` / `ALREADY_ATTEMPTED` |
| POST 响应丢失或超时后的不确定状态 | `CheckinRunnerTest.timedOutPostReadsBackOnce`、`unknownSuccessFieldNeverPasses` | 超时后只回读、始终 1 次 POST；回读仍非 `CHECKED_IN` 时判 `UNCERTAIN`，不重发 |
| 运行期间断网与恢复网络 | `CheckinRunnerTest.offlineNetworkFailureNeverPosts` | 登录阶段 `IOException` → `FAILURE` / `NETWORK_IO`，0 次 POST |
| 错过设定时间与开放窗口 | `CheckinRunnerTest.outsideWindowNeverPosts`、`CheckinCompensationTest` | 时段外返回 `OUTSIDE_WINDOW` 且不提交；补跑仅在 `21:00–23:30` 内且当日未提交时发生 |
| 重启后恢复定时（下次触发时间计算） | `DailySchedulerTest` | 已过设定时间排到次日，含时区与 DST 边界；开机恢复本身仍需真机验收 |
| 只读检查与已打卡不重复提交 | `CheckinRunnerTest.readOnlyNeverPosts`、`alreadyCheckedInNeverPosts` | 只读模式与 `CHECKED_IN` 状态下 0 次 POST |
