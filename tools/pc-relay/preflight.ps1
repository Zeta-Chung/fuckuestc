<#
打卡前自检：一条命令确认今晚能不能跑（只读，不改任何设置）

用法：
    pwsh -File preflight.ps1
退出码：0 关键项全部通过；1 有阻塞项。
#>
[CmdletBinding()]
param(
    [string]$ConfigFile = (Join-Path $PSScriptRoot "relay-config.json"),
    [string]$TicketFile = (Join-Path $PSScriptRoot "ticket.json"),
    [string]$TodayUrl = "https://jzsz.uestc.edu.cn/daily-checkin/api/student-checkin-record/today"
)

$ErrorActionPreference = "Continue"
$blocked = 0

function Report {
    param([bool]$Ok, [string]$Name, [string]$Detail, [switch]$Blocking)
    $mark = if ($Ok) { "[OK]  " } else { "[!!]  " }
    Write-Host ("{0}{1}：{2}" -f $mark, $Name, $Detail)
    if (-not $Ok -and $Blocking) { $script:blocked = 1 }
}

$mitmdump = Get-Command mitmdump -ErrorAction SilentlyContinue
Report ([bool]$mitmdump) "mitmdump" ($(if ($mitmdump) { $mitmdump.Source } else { "未找到，请 pip install mitmproxy" })) -Blocking

$python = Get-Command python -ErrorAction SilentlyContinue
Report ([bool]$python) "python" ($(if ($python) { $python.Source } else { "未找到，请安装 Python 3 并加入 PATH" })) -Blocking

if (Test-Path $ConfigFile) {
    try {
        $config = Get-Content -Path $ConfigFile -Raw | ConvertFrom-Json
        $lat = [double]$config.latitude
        $lon = [double]$config.longitude
        Report $true "坐标配置" ("{0},{1} · {2}" -f $lat, $lon, $config.reverseGeocode)
    } catch {
        Report $false "坐标配置" "解析失败：$($_.Exception.Message)" -Blocking
    }
} else {
    Report $false "坐标配置" "缺少 $ConfigFile（可从 relay-config.example.json 复制）" -Blocking
}

$wechat = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match "^(Weixin|WeChat|WeChatAppEx)$" }
if ($wechat) {
    $names = ($wechat | Select-Object -ExpandProperty ProcessName -Unique) -join ", "
    Report $true "PC 微信" ("运行中：$names")
} else {
    Report $false "PC 微信" "未运行；自动点击与取票都需要它" -Blocking
}

$proxy = Get-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"
Report $true "系统代理现状" ("ProxyEnable={0} ProxyServer={1}（运行时会临时接管为 127.0.0.1:8080 并还原）" -f $proxy.ProxyEnable, $proxy.ProxyServer)

if (Test-Path $TicketFile) {
    try {
        $record = Get-Content -Path $TicketFile -Raw | ConvertFrom-Json
        $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $left = [int](([long]$record.expiresAt - $now) / 1000)
        Report ($left -gt 0) "旧票据" ("剩余 {0} 秒（每次运行只接受当次新抓到的票）" -f $left)
    } catch {
        Report $false "旧票据" "文件损坏，运行时会被新票据覆盖"
    }
} else {
    Report $true "旧票据" "无（正常运行状态）"
}

if ($python) {
    $code = & $python.Source -c "import urllib.request,urllib.error,sys`ntry:`n    r=urllib.request.urlopen('$TodayUrl',timeout=15)`n    print(r.status)`nexcept urllib.error.HTTPError as e:`n    print(e.code)`nexcept Exception as e:`n    print('ERR')" 2>&1
    $reachable = "$code".Trim() -match "^\d{3}$"
    Report $reachable "学校接口可达" ("today 返回 HTTP $code（200/401/403 都算通）") -Blocking
}

if ($blocked -eq 0) {
    Write-Host "`n结论：关键项全部通过，可以运行 nightly.bat -AutoTap" -ForegroundColor Green
} else {
    Write-Host "`n结论：存在阻塞项，先处理上面标 [!!] 的条目" -ForegroundColor Yellow
}
exit $blocked