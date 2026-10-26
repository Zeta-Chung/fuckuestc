<#
启动反向代理抓票（配合 hosts 把 jzsz.uestc.edu.cn 指向 127.0.0.1）。

为什么用反向代理：微信小程序运行时（WMPF）不走系统代理/PAC，常规抓包抓不到它的请求；
把域名指向本机后，小程序连的就是本机的 mitmproxy，再由它按真实 DNS 转发到服务器，票据即可落盘。

关键点（都踩过坑）：
- 必须用 dns_name_servers 指定公共 DNS，否则 mitmproxy 会按 hosts 把上游也解析成 127.0.0.1，
  变成「自己连自己」，日志表现为 Server TLS handshake failed。
- 启动后会做一次自检（本机 curl 走反向代理访问学校接口），非 200 就说明链路没通。

用法：
    pwsh -File start_reverse_capture.ps1          # 前台运行，Ctrl+C 结束
    pwsh -File start_reverse_capture.ps1 -Port 8443
#>
[CmdletBinding()]
param(
    [int]$Port = 443,
    [string]$HostName = "jzsz.uestc.edu.cn",
    # 只传一个 DNS：传多个（逗号分隔）时 mitmproxy 解析失败会静默退回系统解析，
    # 于是又按 hosts 把上游解析成 127.0.0.1（自己连自己）。实测单个 223.5.5.5 稳定可用。
    [string]$DnsServer = "223.5.5.5",
    [string]$SaverScript = "",
    [switch]$SkipSelfTest
)

$ErrorActionPreference = "Stop"
if (-not $SaverScript) { $SaverScript = Join-Path $PSScriptRoot "ticket_saver.py" }
if (-not (Test-Path $SaverScript)) { throw "找不到 $SaverScript" }

$mitmdump = (Get-Command mitmdump -ErrorAction SilentlyContinue)
if (-not $mitmdump) { throw "找不到 mitmdump，请先 pip install mitmproxy" }

$logOut = Join-Path $PSScriptRoot "reverse-capture.log"
$logErr = Join-Path $PSScriptRoot "reverse-capture-err.log"

Write-Host ("反向代理：https://{0} → 127.0.0.1:{1}（DNS: {2}）" -f $HostName, $Port, $DnsServer)
$process = Start-Process -FilePath $mitmdump.Source `
    -ArgumentList @("--mode", "reverse:https://$HostName", "--listen-host", "127.0.0.1",
                    "--listen-port", "$Port", "--set", "dns_name_servers=$DnsServer",
                    "--set", "confdir=$env:USERPROFILE\.mitmproxy", "-s", $SaverScript) `
    -PassThru -WindowStyle Hidden -RedirectStandardOutput $logOut -RedirectStandardError $logErr
Start-Sleep -Seconds 4

if ($process.HasExited) {
    Write-Host ("启动失败（退出码 {0}）" -f $process.ExitCode) -ForegroundColor Red
    Get-Content $logOut -Tail 10 -ErrorAction SilentlyContinue
    Get-Content $logErr -Tail 10 -ErrorAction SilentlyContinue
    exit 1
}

if (-not $SkipSelfTest) {
    $code = & curl.exe -s -k --resolve "$($HostName):$($Port):127.0.0.1" --max-time 20 -o NUL -w "%{http_code}" "https://$($HostName):$($Port)/daily-checkin/api/student-checkin-record/today" 2>$null
    if ("$code" -eq "200" -or "$code" -eq "401" -or "$code" -eq "403") {
        Write-Host ("自检通过：反向代理返回 HTTP {0}" -f $code) -ForegroundColor Green
    } else {
        Write-Host ("自检失败：HTTP {0}（请把 reverse-capture.log 最后几行发我）" -f $code) -ForegroundColor Yellow
        Get-Content $logOut -Tail 8 -ErrorAction SilentlyContinue
    }
}

Write-Host "保持本窗口运行。现在去小程序打开「晚安成电」打卡页，票据会写入 ticket.json"
Write-Host "结束后按 Ctrl+C（或关闭窗口）"
try {
    Wait-Process -Id $process.Id
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    Write-Host "反向代理已停止"
}
