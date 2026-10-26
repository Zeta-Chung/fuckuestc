<#
把学校域名指向本机（需要管理员权限），配合反向代理抓票。

用法（管理员 PowerShell）：
    pwsh -File enable_hosts_redirect.ps1            # 添加
    pwsh -File disable_hosts_redirect.ps1           # 删除
#>
[CmdletBinding()]
param(
    [string]$HostName = "jzsz.uestc.edu.cn",
    [string]$Target = "127.0.0.1"
)

$ErrorActionPreference = "Stop"
$hostsPath = Join-Path $env:SystemRoot "System32\drivers\etc\hosts"

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "需要管理员权限：请右键 PowerShell → 以管理员身份运行，再执行本脚本" -ForegroundColor Yellow
    exit 1
}

$content = [System.IO.File]::ReadAllText($hostsPath)
$pattern = "(?m)^\s*{0}\s+{1}\s*(#.*)?$" -f [regex]::Escape($Target), [regex]::Escape($HostName)
if ($content -match $pattern) {
    Write-Host "已存在记录，无需重复添加"
    exit 0
}

$backup = "$hostsPath.bak-checkin"
if (-not (Test-Path $backup)) { Copy-Item -Path $hostsPath -Destination $backup -Force }
Add-Content -Path $hostsPath -Value ("`r`n{0} {1}  # UESTC-Checkin-Relay" -f $Target, $HostName) -Encoding Ascii
ipconfig /flushdns | Out-Null
Write-Host ("已添加：{0} {1}（原文件备份：{2}）" -f $Target, $HostName, $backup) -ForegroundColor Green
Write-Host "用完请运行 disable_hosts_redirect.ps1 还原"
