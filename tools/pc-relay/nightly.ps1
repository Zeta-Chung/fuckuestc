<#
每晚自动打卡编排器（PC · 免抓包版）

取票来源（-TicketSource）：
    wx（默认）  程序用微信 OAuth 凭据自行换票（wx_ticket.py --mint），不打开打卡页、不碰界面；
                换票失败时自动退回 wechat-page 流程兜底。
    wechat-page 旧路径：等你打开打卡页 → 读微信 Session Storage 里的新票据。

流程：预检 → [必要时拉起微信] → 取票 → relay_checkin.py 单次提交 → 复查 → 写日志/台账/提醒。

不再接管系统代理、不需要 hosts 重定向、不需要 mitmproxy：
票据直接读自微信 WebView 的 Session Storage（见 wechat_ticket_store.py），
新鲜度由「基线之后追加」证明，历史票据不会被误用。

用法：
    pwsh -File nightly.ps1                    # 默认：程序自行换票（微信 OAuth），零人工
    pwsh -File nightly.ps1 -TicketSource wechat-page   # 旧路径：等你手动打开打卡页取票
    pwsh -File nightly.ps1 -AutoTap           # 自动拉起微信并点开小程序打卡页
    pwsh -File nightly.ps1 -DryRun            # 只演练：取票成功也不提交
    pwsh -File nightly.ps1 -SelfTest          # 只做离线自检，不联网、不提交
    pwsh -File nightly.ps1 -NoStartWeChat     # 微信没运行也不要去拉它
    pwsh -File nightly.ps1 -Deadline 23:15    # 自定义截止时间（默认 23:15）
#>
[CmdletBinding()]
param(
    [string]$Deadline = "23:15",
    [ValidateSet("wx", "wechat-page")]
    [string]$TicketSource = "wx",
    [int]$JitterMinutes = 25,
    [switch]$DryRun,
    [switch]$SelfTest,
    [switch]$AutoTap,
    [switch]$Silent,
    [switch]$SkipBaseline,
    [switch]$SkipTodayCheck,
    [switch]$NoStartWeChat,
    [switch]$NoAlert,
    [int]$PollSeconds = 5,
    [int]$WeChatWaitSeconds = 45,
    [int]$IdleSeconds = 60,
    [int]$TapAttempts = 2,
    [string]$Python = "",
    [string]$WeChatExe = "C:\Program Files\Tencent\Weixin\Weixin.exe",
    [string]$TicketFile = (Join-Path $PSScriptRoot "ticket.json"),
    [string]$ConfigFile = (Join-Path $PSScriptRoot "relay-config.json"),
    [string]$RelayScript = (Join-Path $PSScriptRoot "relay_checkin.py"),
    [string]$WxTicketScript = (Join-Path $PSScriptRoot "wx_ticket.py"),
    [string]$StoreScript = (Join-Path $PSScriptRoot "wechat_ticket_store.py"),
    [string]$TapScript = (Join-Path $PSScriptRoot "wechat_ocr_tap.ps1"),
    [string]$TapSteps = (Join-Path $PSScriptRoot "tap-steps.json"),
    [string]$OpenScript = (Join-Path $PSScriptRoot "open_miniprogram.ps1"),
    [string]$AlertScript = (Join-Path $PSScriptRoot "show-alert.ps1"),
    [string]$PopupScript = (Join-Path $PSScriptRoot "alert_popup.py"),
    [string]$ConsoleLog = (Join-Path $PSScriptRoot "nightly.log"),
    [string]$RelayConsoleLog = (Join-Path $PSScriptRoot "relay-console.log"),
    [string]$RelayLog = (Join-Path $PSScriptRoot "relay.log"),
    [string]$LedgerFile = (Join-Path $PSScriptRoot "ledger.jsonl")
)

$ErrorActionPreference = "Stop"
if ($AutoTap) { $Silent = $true }   # 自动点击时不要弹窗抢焦点

# 逐日台账：一天一条，记录「是否需要人工 / 是否只 POST 一次 / 回读结果」
$script:ledger = [ordered]@{
    date         = (Get-Date -Format "yyyy-MM-dd")
    startedAt    = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
    endedAt      = $null
    exitCode     = $null
    dryRun       = [bool]$DryRun
    autoTap      = [bool]$AutoTap
    tapExit      = $null
    wechatStarted = $false
    sessionReady = $false
    ticketSource = ""
    mintExit     = $null
    ticketFound  = $false
    baselineDone = $false
    pollSeconds  = $null
    relayExit    = $null
    needHuman    = $true
    note         = ""
}

function Write-Log {
    param([string]$Message)
    $line = "{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message
    Write-Host $line
    Add-Content -Path $ConsoleLog -Value $line -Encoding UTF8
}

function Write-Ledger {
    param([int]$Code)
    $script:ledger.endedAt = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
    $script:ledger.exitCode = $Code
    try {
        ($script:ledger | ConvertTo-Json -Compress) | Add-Content -Path $LedgerFile -Encoding UTF8
    } catch {
        Write-Log ("台账写入失败：{0}" -f $_.Exception.Message)
    }
}

function Exit-With {
    param([int]$Code)
    Write-Ledger -Code $Code
    Write-Log ("=== 结束（退出码 {0}，需要人工：{1}）===" -f $Code, $script:ledger.needHuman)
    exit $Code
}

function Show-Popup {
    # 独立进程弹窗：优先用 pythonw + alert_popup.py（不产生「隐藏窗口的 powershell」，
    # 避免被火绒等安全软件按 rp_name_execcreate_powershell_hidden 拦截）；
    # 找不到 python 时退回进程内 WinForms。
    param([string]$Title, [string]$Text, [ValidateSet("alert", "info")][string]$Kind = "alert")
    if ($script:pythonExe -and $script:popupScript -and (Test-Path $script:popupScript)) {
        $pythonw = Join-Path (Split-Path -Parent $script:pythonExe) "pythonw.exe"
        $launcher = if (Test-Path $pythonw) { $pythonw } else { $script:pythonExe }
        try {
            Start-Process -FilePath $launcher -WindowStyle Hidden -ArgumentList @(
                $script:popupScript, "--title", $Title, "--text", $Text, "--kind", $Kind
            ) | Out-Null
            return
        } catch {
            Write-Log ("弹窗启动失败：{0}" -f $_.Exception.Message)
        }
    }
    try {
        Add-Type -AssemblyName System.Windows.Forms -ErrorAction Stop
        $icon = if ($Kind -eq "alert") { "Warning" } else { "Information" }
        [System.Windows.Forms.MessageBox]::Show(
            $Text, $Title, [System.Windows.Forms.MessageBoxButtons]::OK,
            [System.Windows.Forms.MessageBoxIcon]::$icon
        ) | Out-Null
    } catch {
        Write-Log ("弹窗失败（{0}）：{1}" -f $Kind, $Text)
    }
}

function Show-Alert {
    # 红色大字提醒：独立进程，便于 nightly 立刻退出而不挡人
    param([string]$Title, [string]$Text)
    Write-Log ("[需要人工] {0}：{1}" -f $Title, $Text)
    if ($NoAlert) { return }   # 注意：-AutoTap 会打开 -Silent，但提醒必须照常弹，否则失败没人知道
    Show-Popup -Title $Title -Text $Text -Kind alert
}

function Get-PythonExe {
    $candidates = @()
    if ($Python) { $candidates += $Python }
    $candidates += @("python", "py")
    foreach ($candidate in $candidates) {
        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    foreach ($fallback in @(
            (Join-Path $env:ProgramFiles "Python312\python.exe"),
            (Join-Path $env:ProgramFiles "Python311\python.exe"),
            (Join-Path $env:LOCALAPPDATA "Programs\Python\Python312\python.exe"))) {
        if (Test-Path $fallback) { return $fallback }
    }
    return $null
}

function Show-Message {
    param([string]$Title, [string]$Text)
    if ($Silent) {
        Write-Log ("[静默] {0}：{1}" -f $Title, $Text)
        return
    }
    Show-Popup -Title $Title -Text $Text -Kind info
}

function Get-IdleReport {
    # Qt 窗口不认合成点击，只能用真实鼠标；所以先看你是否正在用电脑，避免和你抢光标。
    if (-not ("IdleProbe" -as [type])) {
        Add-Type @"
using System;
using System.Runtime.InteropServices;
public class IdleProbe {
  [StructLayout(LayoutKind.Sequential)] public struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X; public int Y; }
  [DllImport("user32.dll")] public static extern bool GetLastInputInfo(ref LASTINPUTINFO info);
  [DllImport("kernel32.dll")] public static extern uint GetTickCount();
  [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  public static uint IdleSeconds() {
    var info = new LASTINPUTINFO();
    info.cbSize = (uint)Marshal.SizeOf(info);
    if (!GetLastInputInfo(ref info)) return 0;
    return (GetTickCount() - info.dwTime) / 1000;
  }
  public static int CursorX() { POINT p; GetCursorPos(out p); return p.X; }
  public static int CursorY() { POINT p; GetCursorPos(out p); return p.Y; }
  public static void MoveCursor(int x, int y) { SetCursorPos(x, y); }
}
"@
    }
    return [IdleProbe]::IdleSeconds()
}

function Wait-ForIdle {
    param([int]$Seconds, [datetime]$Until)
    if ($Seconds -le 0) { return $true }
    $idle = Get-IdleReport
    if ($idle -ge $Seconds) { return $true }
    Write-Log ("你正在用电脑（键盘/鼠标已静默 {0} 秒），等满 {1} 秒空闲再点，免得抢你的光标" -f $idle, $Seconds)
    while ((Get-Date) -lt $Until) {
        Start-Sleep -Seconds 3
        if ((Get-IdleReport) -ge $Seconds) {
            Write-Log "你已经空闲，开始点击"
            return $true
        }
    }
    Write-Log "等到截止时间你一直在用电脑；仍然尝试点击（失败会提醒）"
    return $false
}

function Invoke-Store {
    # 调用 wechat_ticket_store.py，返回 @{ Exit; Output }
    param([string[]]$StoreArgs, [string]$PythonExe)
    $output = & $PythonExe $StoreScript @StoreArgs 2>&1
    return @{ Exit = $LASTEXITCODE; Output = ($output | Out-String).Trim() }
}

function Test-WeChatWindow {
    # 「进程在」不等于「窗口在」：微信可以被关到托盘，此时必须重新启动才能唤出窗口
    if (-not ("WindowProbe" -as [type])) {
        Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class WindowProbe {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  public static IntPtr Find(string title) {
    IntPtr found = IntPtr.Zero;
    EnumWindows((h,l) => {
      var sb = new StringBuilder(512); GetWindowText(h, sb, 512);
      if (sb.ToString().IndexOf(title, StringComparison.Ordinal) < 0) return true;
      if (!IsWindowVisible(h) || IsIconic(h)) { ShowWindow(h, 9); System.Threading.Thread.Sleep(400); }
      found = h; return false;
    }, IntPtr.Zero);
    return found;
  }
}
"@
    }
    return ([WindowProbe]::Find("微信")) -ne [IntPtr]::Zero
}

function Start-WeChatIfNeeded {
    param([string]$Exe, [int]$WaitSeconds)
    if (Test-WeChatWindow) {
        Write-Log "微信主窗口已在（可点击）"
        return $true
    }
    $main = @(Get-Process -Name "Weixin", "WeChat" -ErrorAction SilentlyContinue)
    if ($NoStartWeChat) {
        Write-Log "微信窗口不在了；-NoStartWeChat 已指定，跳过唤起"
        return $false
    }
    if (-not (Test-Path $Exe)) {
        Write-Log ("找不到微信可执行文件：{0}" -f $Exe)
        return $false
    }
    if ($main.Count -gt 0) {
        Write-Log ("微信进程还在（{0} 个）但没有窗口，重新启动一次把窗口唤出来" -f $main.Count)
    } else {
        Write-Log ("拉起微信：{0}" -f $Exe)
    }
    Start-Process -FilePath $Exe | Out-Null
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if (Test-WeChatWindow) {
            Write-Log "微信主窗口已出现，再等 8 秒让界面就绪"
            Start-Sleep -Seconds 8
            $script:ledger.wechatStarted = $true
            return $true
        }
    }
    Write-Log ("等待 {0} 秒仍没看到微信主窗口（可能需要手机确认登录）" -f $WaitSeconds)
    return $false
}

$exitCode = 0
Write-Log "=== 每晚自动打卡（免抓包版）开始 ==="

$pythonExe = Get-PythonExe
if (-not $pythonExe) {
    Write-Log "找不到 python，请安装 Python 3 或加进 PATH"
    Show-Alert -Title "自动打卡未启动" -Text "找不到 python，请安装 Python 3 或把它加进 PATH。"
    Exit-With 2
}
Write-Log ("python：{0}" -f $pythonExe)
$script:pythonExe = $pythonExe
$script:popupScript = $PopupScript

foreach ($required in @($StoreScript, $RelayScript)) {
    if (-not (Test-Path $required)) {
        Write-Log ("缺少脚本：{0}" -f $required)
        Exit-With 2
    }
}
if (-not (Test-Path $ConfigFile)) {
    $example = Join-Path $PSScriptRoot "relay-config.example.json"
    if (Test-Path $example) {
        Copy-Item -Path $example -Destination $ConfigFile
        Write-Log "已从示例生成 relay-config.json，请核对坐标后重跑"
        Show-Alert -Title "配置待确认" -Text "已生成 relay-config.json，请核对坐标后再跑一次。"
        Exit-With 2
    }
    Write-Log "找不到 relay-config.json"
    Exit-With 2
}

# 会话凭据自检：ticket.json 必须留有 SESSION 与 sessionId（票据本身每晚都会重新取）
$sessionReady = $false
if (Test-Path $TicketFile) {
    try {
        $record = Get-Content -Path $TicketFile -Raw | ConvertFrom-Json
        $sessionReady = [bool]($record.session -and $record.sessionId)
        Write-Log ("会话凭据：session {0} 字符 / sessionId {1}" -f "$($record.session)".Length, $record.sessionId)
    } catch {
        Write-Log "ticket.json 解析失败，请检查"
    }
} else {
    Write-Log "找不到 ticket.json（提交需要里面的 SESSION 与 sessionId）"
}
$script:ledger.sessionReady = $sessionReady
if (-not $sessionReady) {
    Write-Log "缺少会话凭据：需要重新登录学校账号或重新取票落盘"
    Show-Alert -Title "会话凭据失效" -Text "ticket.json 里缺少 SESSION / sessionId。请重新登录学校账号（或在小程序里重新打开打卡页），再跑一次。"
    Exit-With 2
}

$now = Get-Date
if ($now.TimeOfDay -lt [TimeSpan]"21:00" -or $now.TimeOfDay -gt [TimeSpan]"23:30") {
    Write-Log ("提示：现在是 {0}，不在打卡时段（21:00–23:30）内，提交会被平台拒绝；先打开打卡页取票也可以，但票据只有几分钟有效期" -f $now.ToString("HH:mm"))
}

# 0) 今天是否已经打过卡：打过就直接结束，不必等票、更不能重复提交
if (-not $SkipTodayCheck) {
    $statusOutput = & $pythonExe $RelayScript --ticket $TicketFile --config $ConfigFile --log $RelayLog --status-only 2>&1
    $statusExit = $LASTEXITCODE
    Write-Log ("今日状态检查（退出码 {0}）：{1}" -f $statusExit, (($statusOutput | Out-String).Trim() -replace "`r?`n", " / "))
    if ($statusExit -eq 0) {
        Write-Log "今天已经打过卡，本次无事可做"
        $script:ledger.needHuman = $false
        $script:ledger.note = "already-checked-in"
        Exit-With 0
    }
    if ($statusExit -eq 3) {
        Write-Log "无法确认今日状态（网络或会话问题），不提交"
        Show-Alert -Title "会话或网络异常" -Text "查不到今日打卡状态（往往是 SESSION 失效）。请重新登录学校账号后再跑；本次没有提交任何内容。"
        $script:ledger.note = "status-error"
        Exit-With 3
    }
}

# 3) 取票
$ticketLine = $null
$tapStarted = Get-Date

# 3a) 默认路径：程序用本机微信授权凭据自行换票，不碰界面、不需要打卡页
if ($TicketSource -eq "wx") {
    if (-not (Test-Path $WxTicketScript)) {
        Write-Log ("缺少取票脚本：{0}" -f $WxTicketScript)
        $script:ledger.note = "wx-script-missing"
        Exit-With 2
    }
    Write-Log "取票方式：微信 OAuth（wx_ticket.py --mint，自动换票）"
    if ($JitterMinutes -gt 0 -and -not $DryRun) {
        # 抖动：把固定整点打散，降低「机器定时」特征
        $delaySeconds = Get-Random -Minimum 0 -Maximum ($JitterMinutes * 60)
        Write-Log ("抖动：随机等待 {0} 分 {1} 秒再取票" -f [int]($delaySeconds / 60), ($delaySeconds % 60))
        Start-Sleep -Seconds $delaySeconds
    }
    $mintExit = 1
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        & $pythonExe $WxTicketScript --mint 2>&1 | ForEach-Object { Write-Log $_ }
        $mintExit = $LASTEXITCODE
        if ($mintExit -eq 0) { break }
        Write-Log ("自动换票失败（退出码 {0}，第 {1}/2 次）" -f $mintExit, $attempt)
        if ($attempt -lt 2) { Start-Sleep -Seconds 300 }
    }
    $script:ledger.mintExit = $mintExit
    if ($mintExit -eq 0) {
        $record = Get-Content -Path $TicketFile -Raw | ConvertFrom-Json
        $ticketLine = ("微信 OAuth 取票成功（票据 {0} 字符）" -f "$($record.ticket)".Length)
        Write-Log $ticketLine
        $script:ledger.ticketSource = "wx-oauth"
    } else {
        $script:ledger.ticketSource = "wx-oauth-failed"
        Write-Log "自动换票失败，退回人工取票兜底（需要你打开打卡页）"
        Show-Alert -Title "自动换票失败" -Text "微信 OAuth 换票失败（退出码 $mintExit）。已退回原流程：请打开「成电智慧学工」→ 打卡页并停留 3 秒，或重新扫码授权后重跑。详见 nightly.log。"
    }
}

# 3b) 兜底/旧路径：人工打开打卡页，程序读微信存储里的新票据
if (-not $ticketLine) {
# 微信：能拉起就拉起（你需要用它打开打卡页）
[void](Start-WeChatIfNeeded -Exe $WeChatExe -WaitSeconds $WeChatWaitSeconds)
$wechatApp = @(Get-Process -Name "WeChatAppEx" -ErrorAction SilentlyContinue)
Write-Log ("小程序运行时进程：{0} 个" -f $wechatApp.Count)

if ($SelfTest) {
    Write-Log "SelfTest：离线自检"
    $result = Invoke-Store -StoreArgs @("--self-test") -PythonExe $pythonExe
    Write-Log $result.Output
    if ($result.Exit -ne 0) { Write-Log "自检未通过"; $script:ledger.needHuman = $false; Exit-With 1 }
    $list = Invoke-Store -StoreArgs @("--list") -PythonExe $pythonExe
    Write-Log $list.Output
    if ($list.Exit -ne 0) { Write-Log "读不到微信 Session Storage（微信可能未登录过）"; Exit-With 1 }
    Write-Log "SelfTest 通过：脚本、配置、会话凭据与存储读取都正常"
    $script:ledger.needHuman = $false
    $script:ledger.note = "selftest"
    Exit-With 0
}

# 1) 记录基线：必须发生在打开打卡页之前，之后只有「追加写入」的票据才算新鲜
if (-not $SkipBaseline) {
    $result = Invoke-Store -StoreArgs @("--baseline") -PythonExe $pythonExe
    Write-Log ("基线：" + ($result.Output -replace "`r?`n", " / "))
    if ($result.Exit -ne 0) {
        Write-Log "建立基线失败，已放弃本次运行"
        Show-Alert -Title "取票前置失败" -Text "无法记录票据基线（微信 Session Storage 读不到）。请确认微信已登录且打开过一次打卡页。"
        Exit-With 2
    }
    $script:ledger.baselineDone = $true
}

# 2) 打开打卡页（可选自动点击；不点「请打卡」，提交由 relay 完成）
$deadlineTime = [datetime]::ParseExact($Deadline, "HH:mm", $null)
if ($deadlineTime -lt (Get-Date)) { $deadlineTime = $deadlineTime.AddDays(1) }
$tapStarted = Get-Date
if ($AutoTap) {
    Write-Log "警告：-AutoTap 是实验性开关。它会对微信窗口做真实鼠标点击，实测曾误点到「视频号」导致播放视频、窗口假死。默认不要使用，请人工打开打卡页。"
    Write-Log "开始自动打开小程序打卡页（-AutoTap）"
    $shell = (Get-Command pwsh -ErrorAction SilentlyContinue)
    if (-not $shell) { $shell = (Get-Command powershell -ErrorAction SilentlyContinue) }
    if (-not $shell) {
        Write-Log "找不到 PowerShell，跳过自动点击，请手动打开打卡页"
    } else {
        [void](Get-IdleReport)   # 触发 Add-Type，后面才能用 [IdleProbe]
        $cursorX = [IdleProbe]::CursorX()
        $cursorY = [IdleProbe]::CursorY()
        try {
            for ($attempt = 1; $attempt -le $TapAttempts; $attempt++) {
                if ((Get-Date) -ge $deadlineTime) { break }
                [void](Wait-ForIdle -Seconds $IdleSeconds -Until $deadlineTime)
                # a) 冷启动：小程序窗口没开就先打开（微信刚启动时也要能走到）
                if (Test-Path $OpenScript) {
                    & $shell.Source -NoProfile -ExecutionPolicy Bypass -File $OpenScript -Restart 2>&1 |
                        ForEach-Object { Write-Log $_ }
                    Write-Log ("打开小程序窗口退出码：{0}（第 {1} 次）" -f $LASTEXITCODE, $attempt)
                }
                # b) 在小程序里点进打卡页
                & $shell.Source -NoProfile -ExecutionPolicy Bypass -File $TapScript -StepsFile $TapSteps 2>&1 |
                    ForEach-Object { Write-Log $_ }
                $script:ledger.tapExit = $LASTEXITCODE
                Write-Log ("自动点击退出码：{0}（第 {1} 次）" -f $LASTEXITCODE, $attempt)
                # c) 等 25 秒看是否已经出票；出票就不要再点一次
                $probeDeadline = (Get-Date).AddSeconds(25)
                $probe = $null
                while ((Get-Date) -lt $probeDeadline) {
                    Start-Sleep -Seconds 3
                    $probe = Invoke-Store -StoreArgs @("--read") -PythonExe $pythonExe
                    if ($probe.Exit -eq 0) { Write-Log "点击后已经出现新票据，不再重复点击"; break }
                }
                if ($probe -and $probe.Exit -eq 0) { break }
                if ($attempt -lt $TapAttempts) { Write-Log ("第 {0} 次点击后 25 秒内没出票，重试" -f $attempt) }
            }
            # 自动点击没成功：立刻提醒（不要等到截止时间），但继续等票——你手动打开打卡页也照样能提交
            if (-not ($probe -and $probe.Exit -eq 0)) {
                $script:ledger.needHuman = $true
                if ($script:ledger.tapExit -ne 0) {
                    Show-Alert -Title "自动点击没成功" -Text "自动打开打卡页失败（点击退出码 $($script:ledger.tapExit)）。请手动打开「成电智慧学工」→ 打卡页并停留 3 秒，程序仍在等票，取到就自动提交。"
                } else {
                    Show-Alert -Title "没取到新票据" -Text "点击走了但没等到新票据。请手动打开「成电智慧学工」→ 打卡页并停留 3 秒，程序仍在等票，取到就自动提交。"
                }
            }
        } finally {
            [IdleProbe]::MoveCursor($cursorX, $cursorY)
            Write-Log "光标已还原到点击前的位置"
        }
    }
} else {
    # 默认不碰你的界面：弹顶部提醒，请你打开打卡页；取票、提交、回读由程序负责
    Show-Alert -Title "现在请打开打卡页" -Text "请打开 PC 微信「成电智慧学工」→ 打卡页，停留 3 秒即可。我会自动取票并提交（截止 $Deadline）。若提示没有新票据，把打卡页重新打开一次。"
}

# 3) 轮询磁盘上的新鲜票据
Write-Log ("开始等待新鲜票据，截止 {0}" -f $deadlineTime.ToString("HH:mm"))

$ticketLine = $null
$pollCount = 0
$abnormal = 0
$lastReason = ""
$reminded = $false
$pollStarted = Get-Date
while ((Get-Date) -lt $deadlineTime) {
    $pollCount++
    # 等了 5 分钟还没有新票：多半是你早就停在打卡页，页面没重新加载 → 再提醒一次
    if (-not $reminded -and -not $AutoTap -and (Get-Date) -gt $pollStarted.AddMinutes(5)) {
        $reminded = $true
        Show-Alert -Title "还没取到新票据" -Text "如果打卡页是之前就打开的，请退出再重新打开一次（页面重新加载才会签发新票据）。我会继续等到 $Deadline。"
    }
    $result = Invoke-Store -StoreArgs @("--read", "--write", $TicketFile) -PythonExe $pythonExe
    if ($result.Exit -eq 0) {
        $ticketLine = ($result.Output -split "`r?`n")[0]
        Write-Log ("取票成功：" + $ticketLine)
        break
    }
    if ($result.Exit -eq 3) {
        Write-Log ("读取故障，放弃本次运行：" + $result.Output)
        $exitCode = 3
        break
    }
    $first = ($result.Output -split "`r?`n")[0]
    if ($result.Exit -eq 2 -and $first -match '^拒绝：') {
        $abnormal = 0
        if ($first -ne $lastReason -or ($pollCount % 12) -eq 0) {
            Write-Log ("等待中（{0}）：{1}" -f $pollCount, $first)
            $lastReason = $first
        }
    } else {
        $abnormal++
        Write-Log ("取票脚本异常（退出码 {0}，连续 {1} 次）：{2}" -f $result.Exit, $abnormal, ($result.Output -replace "`r?`n", " | "))
        if ($abnormal -ge 3) {
            Write-Log "连续 3 次异常，放弃本次运行"
            $exitCode = 3
            break
        }
    }
    Start-Sleep -Seconds $PollSeconds
}

if (-not $ticketLine) {
    if ($exitCode -eq 0) { $exitCode = 2 }
    $script:ledger.pollSeconds = [int]((Get-Date) - $tapStarted).TotalSeconds
    Write-Log "截止时间前没有取到新鲜票据，未提交"
    if ($AutoTap -and $script:ledger.tapExit -ne 0) {
        Show-Alert -Title "取票失败：自动点击没成功" -Text "自动打开打卡页失败（退出码 $($script:ledger.tapExit)）。请确认微信已登录并有可见窗口，或手动打开打卡页后重跑。"
    } else {
        Show-Alert -Title "取票失败：需要人工打开打卡页" -Text "截止 $Deadline 仍没等到新票据。请在小程序里打开「成电智慧学工」打卡页并停留 3 秒，然后重跑 nightly.ps1。"
    }
    Exit-With $exitCode
}
$script:ledger.ticketFound = $true
$script:ledger.pollSeconds = [int]((Get-Date) - $tapStarted).TotalSeconds
} else {
    # 微信 OAuth 已取到票：无需基线、轮询或打开打卡页
    $script:ledger.ticketFound = $true
    $script:ledger.pollSeconds = [int]((Get-Date) - $tapStarted).TotalSeconds
    Write-Log ("取票耗时 {0} 秒（微信 OAuth）" -f $script:ledger.pollSeconds)
}

# 4) 单次提交（票据一次性；成败都会清空 ticket.json 里的票据）
$relayArgs = @($RelayScript, "--ticket", $TicketFile, "--config", $ConfigFile, "--log", $RelayLog)
if (-not $DryRun) { $relayArgs += "--submit" }
Write-Log ("调用提交器：relay_checkin.py {0}" -f ($(if ($DryRun) { "（演练，不提交）" } else { "（--submit）" })))
& $pythonExe @relayArgs 2>&1 | Tee-Object -FilePath $RelayConsoleLog -Append | ForEach-Object { Write-Log $_ }
$relayExit = $LASTEXITCODE
$script:ledger.relayExit = $relayExit
Write-Log ("提交器退出码：{0}" -f $relayExit)

switch ($relayExit) {
    0 {
        $script:ledger.needHuman = $false
        if ($DryRun) {
            $script:ledger.note = "dry-run"
            Show-Message -Title "演练完成" -Text "演练通过，本次没有提交。去掉 -DryRun 即会真正提交。"
        } else {
            $script:ledger.note = "confirmed"
            Show-Message -Title "自动打卡成功" -Text "已提交并复查为 CHECKED_IN，详情见 relay.log。"
        }
    }
    1 {
        $script:ledger.note = "unconfirmed"
        Show-Alert -Title "结果待确认（未重复提交）" -Text "已提交但没复查到 CHECKED_IN（例如 46004）。程序不会重发；请打开小程序核对今日记录。"
    }
    2 {
        $script:ledger.note = "ticket-or-config"
        Show-Alert -Title "票据或配置问题" -Text "取票或配置有问题。请重新打开打卡页取票，并检查 relay-config.json。"
    }
    3 {
        $script:ledger.note = "network-or-session"
        Show-Alert -Title "网络或会话异常" -Text "提交失败：网络或会话异常。请检查网络，必要时重新登录学校账号。详见 nightly.log 与 relay.log。"
    }
    default {
        $script:ledger.note = "exit-$relayExit"
        Show-Alert -Title "自动打卡失败" -Text "提交器退出码 $relayExit，详见 nightly.log 与 relay.log。"
    }
}
$exitCode = $relayExit

Exit-With $exitCode