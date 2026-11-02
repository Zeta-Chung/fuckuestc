# 把每晚打卡从旧链路（依赖抓包会话）切换到新工具（每次现场换会话）
# 用法：右键「使用 PowerShell 运行」，或在终端执行 pwsh -File .\switch-task.ps1
$ErrorActionPreference = "Stop"
$old = "UESTC-Checkin-Relay"
$new = "UESTC-Checkin-Tool"
$xml = Join-Path $PSScriptRoot "task-$new.xml"

if (-not (Test-Path $xml)) {
    Write-Host "找不到 $xml，请先运行 setup.py --skip-qr --no-task 生成" -ForegroundColor Red
    exit 2
}

Write-Host "1) 停用旧任务 $old …"
schtasks /Change /TN "\$old" /DISABLE | Out-Null
if ($LASTEXITCODE -eq 0) { Write-Host "   已停用（不会再自动提交）" -ForegroundColor Green }
else { Write-Host "   停用失败（任务可能本来就不存在），继续" -ForegroundColor Yellow }

Write-Host "2) 注册新任务 $new …"
schtasks /Create /TN "\$new" /XML $xml /F | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "   注册失败，请手动执行：" -ForegroundColor Red
    Write-Host "   schtasks /Create /TN `"\$new`" /XML `"$xml`" /F"
    exit 3
}
Write-Host "   已注册：每天 21:05 触发，随后随机抖动 0-25 分钟" -ForegroundColor Green

Write-Host "3) 当前状态："
schtasks /Query /TN "\$old" /FO LIST | Select-String "任务名|状态|TaskName|Status"
schtasks /Query /TN "\$new" /FO LIST | Select-String "任务名|状态|TaskName|Status"
Write-Host "`n完成。可以手动试跑一次：schtasks /Run /TN `"\$new`"" -ForegroundColor Cyan