<#
还原 hosts（删除 jzsz.uestc.edu.cn → 127.0.0.1 的记录）。需要管理员权限。
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

$backup = "$hostsPath.bak-checkin"
if (Test-Path $backup) {
    Copy-Item -Path $backup -Destination $hostsPath -Force
    Remove-Item -Path $backup -Force
    Write-Host "已用备份还原 hosts" -ForegroundColor Green
} else {
    $lines = Get-Content -Path $hostsPath
    $kept = $lines | Where-Object { $_ -notmatch [regex]::Escape($HostName) }
    [System.IO.File]::WriteAllLines($hostsPath, $kept, [System.Text.Encoding]::ASCII)
    Write-Host "已删除含 $HostName 的记录" -ForegroundColor Green
}
ipconfig /flushdns | Out-Null
