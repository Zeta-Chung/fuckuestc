# 贡献指南

感谢参与改进。提交前请确认不会泄露本人或他人的敏感信息。

## 环境要求

- JDK 17
- Android SDK Platform 35、Build Tools 35
- Gradle 8.11.1（项目自带 wrapper，首次构建需要网络）

## 构建与测试

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 代码结构

| 目录 | 职责 |
| --- | --- |
| `model/` | 配置模型、位置预设、运行结果 |
| `protocol/` | IDAS 登录、JZSZ 接口、Cookie 隔离、HTTP 传输 |
| `run/` | 单次运行编排、每日提交台账 |
| `schedule/` | 闹钟、WorkManager、开机恢复 |
| `storage/` | Keystore 加密配置、运行日志 |
| `ui/` | 主界面、登录对照页、失败提醒 |

## 提交规范

- 一个 PR 只做一件事，避免无关重构。
- 提交信息建议使用 `<type>: <summary>`，`type` 取 `feat`、`fix`、`docs`、`test`、`refactor`、`chore`。
- 不提交构建产物、`local.properties`、抓包文件与个人配置。
- 新增或修改协议逻辑时，同步更新 `docs/architecture.md`、`CHANGELOG.md` 与相关单元测试。

## 安全红线

- 不提交真实账号、密码、浏览器指纹、`SESSION`、CAS 票据。
- 不提交精确坐标、含个人信息的截图、原始接口响应。
- 测试数据一律使用假值。

## PR 检查清单

- [ ] `:app:testDebugUnitTest :app:assembleDebug` 通过
- [ ] 新增逻辑有对应单元测试
- [ ] 没有改变既有原因码语义（如改动需同步文档）
- [ ] README / CHANGELOG 已更新
- [ ] 变更中不含任何真实凭证或个人信息