# 更新日志

本项目版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### Added

- “自动获取浏览器指纹”：在应用内打开 IDAS 登录页，登录成功进入打卡站点后自动读取 `MULTIFACTOR_BROWSER_FINGERPRINT` 并填入配置。

### Changed

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