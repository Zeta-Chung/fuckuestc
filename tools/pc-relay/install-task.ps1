<#
注册/删除每晚自动打卡的计划任务。

用法：
    pwsh -File install-task.ps1                    # 每天 21:02 触发（需你手动点小程序里那两下）
    pwsh -File install-task.ps1 -AutoTap           # 每天 21:02 触发，并自动点击微信小程序（全自动）
    pwsh -File install-task.ps1 -At 21:05 -AutoTap # 自定义时间
    pwsh -File install-task.ps1 -Remove            # 删除任务
#>
[CmdletBinding()]
param(
    [string]$At = "21:02",
    [string]$TaskName = "UESTC-Checkin-Relay",
    [switch]$AutoTap,
    [switch]$Remove
)

$ErrorActionPreference = "Stop"

if ($Remove) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host "已删除计划任务：$TaskName"
    exit 0
}

$runner = Join-Path $PSScriptRoot "nightly.bat"
if (-not (Test-Path $runner)) { throw "找不到 $runner" }

$action = if ($AutoTap) {
    New-ScheduledTaskAction -Execute $runner -Argument "-AutoTap" -WorkingDirectory $PSScriptRoot
} else {
    New-ScheduledTaskAction -Execute $runner -WorkingDirectory $PSScriptRoot
}
$trigger = New-ScheduledTaskTrigger -Daily -At $At
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Hours 3) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
$mode = if ($AutoTap) { "-AutoTap（实验性：真实点击微信界面，实测会误点视频号，不建议）" } else { "无（默认：弹提醒请你打开打卡页，程序只取票+提交）" }
Write-Host "已注册计划任务：$TaskName，每天 $At 触发（参数：$mode）"
Write-Host "前提：电脑开机不睡眠、PC 微信已登录、ticket.json 里有可用会话凭据"
Write-Host "到点后：桌面会弹红色提醒「现在请打开打卡页」，你打开打卡页停留 3 秒即可"
Write-Host "手动测试：schtasks /Run /TN $TaskName"