# 把计划任务重新注册为「电池也启动、睡眠后补跑」的版本
# 用法：pwsh -File fix-task-battery.ps1 -TaskName "UESTC-Checkin-Relay"
param(
    [string]$TaskName = "UESTC-Checkin-Relay",
    [string]$At = "21:05",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$RelayDir = $PSScriptRoot
$Bat = Join-Path $RelayDir "nightly.bat"
$User = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$Start = (Get-Date).Date.AddDays(0).ToString("yyyy-MM-dd") + "T" + $At + ":00"

$xml = @"
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <URI>\$TaskName</URI>
  </RegistrationInfo>
  <Principals>
    <Principal id="Author">
      <UserId>$User</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <StartWhenAvailable>true</StartWhenAvailable>
    <ExecutionTimeLimit>PT1H</ExecutionTimeLimit>
    <Enabled>true</Enabled>
  </Settings>
  <Triggers>
    <CalendarTrigger>
      <StartBoundary>$Start</StartBoundary>
      <Enabled>true</Enabled>
      <ScheduleByDay>
        <DaysInterval>1</DaysInterval>
      </ScheduleByDay>
    </CalendarTrigger>
  </Triggers>
  <Actions Context="Author">
    <Exec>
      <Command>"$Bat"</Command>
      <WorkingDirectory>$RelayDir</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
"@

$tmp = Join-Path $RelayDir ("task-" + $TaskName + ".xml")
[System.IO.File]::WriteAllText($tmp, $xml, [System.Text.UnicodeEncoding]::new($false, $true))
Write-Host "已生成任务定义：$tmp"
Write-Host "  触发时间：每天 $At"
Write-Host "  电池：允许启动 / 不允许因切电池而停止"
Write-Host "  睡眠错过：开机后补跑（StartWhenAvailable）"

if ($DryRun) { Write-Host "（-DryRun：不注册，仅生成 XML）"; exit 0 }

$result = schtasks /create /tn "\$TaskName" /xml "$tmp" /f 2>&1
$exit = $LASTEXITCODE
$result | ForEach-Object { Write-Host "  $_" }
if ($exit -ne 0) {
    Write-Host ""
    Write-Host "注册失败（退出码 $exit）。请在你自己的 PowerShell 里执行："
    Write-Host "  schtasks /create /tn `"\$TaskName`" /xml `"$tmp`" /f"
    exit $exit
}
Write-Host "已注册：$TaskName"
exit 0