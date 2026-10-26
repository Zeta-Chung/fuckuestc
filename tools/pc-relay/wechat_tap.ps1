<#
PC 微信小程序自动点击（UI Automation，无需额外依赖）

默认按 tap-steps.json 的顺序查找并点击界面元素，例如：
    成电智慧学工 → 请打卡 → 确认

用法：
    pwsh -File wechat_tap.ps1 -DryRun        # 只查找并打印命中元素，不点击
    pwsh -File wechat_tap.ps1                # 依次点击
    pwsh -File wechat_tap.ps1 -Dump          # 导出微信窗口控件树到 wechat-tree.txt

退出码：0 全部步骤完成；1 有步骤未找到；2 找不到微信窗口。
#>
[CmdletBinding()]
param(
    [string[]]$Steps = @(),
    [string]$StepsFile = (Join-Path $PSScriptRoot "tap-steps.json"),
    [int]$StepTimeoutSeconds = 25,
    [int]$SettleSeconds = 2,
    [switch]$DryRun,
    [switch]$Dump,
    [string]$DumpFile = (Join-Path $PSScriptRoot "wechat-tree.txt"),
    [string]$LogFile = (Join-Path $PSScriptRoot "wechat-tap.log")
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
Add-Type -Namespace Win32 -Name Mouse -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
[DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, System.UIntPtr dwExtraInfo);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(System.IntPtr hWnd);
[DllImport("user32.dll")] public static extern bool ShowWindow(System.IntPtr hWnd, int nCmdShow);
'@

$TrueCondition = [System.Windows.Automation.Condition]::TrueCondition
$Descendants = [System.Windows.Automation.TreeScope]::Descendants
$Children = [System.Windows.Automation.TreeScope]::Children

function Write-Log {
    param([string]$Message)
    $line = "{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message
    Write-Host $line
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

function Get-WeChatWindows {
    $root = [System.Windows.Automation.AutomationElement]::RootElement
    $windows = $root.FindAll($Children, $TrueCondition)
    $found = @()
    foreach ($window in $windows) {
        try {
            $processId = $window.Current.ProcessId
            $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
            if (-not $process) { continue }
            if ($process.ProcessName -notmatch "^(Weixin|WeChat|WeChatAppEx)$") { continue }
            $path = ""
            try { $path = $process.Path } catch { }
            if ($path -match "QQMusic|QQNT|Androws|Yuanbao|WeMeet") { continue }
            $score = 0
            if ($process.ProcessName -eq "Weixin") { $score = 3 }
            elseif ($path -match "xwechat|RadiumWMPF|wmpfsdk") { $score = 2 }
            $found += [pscustomobject]@{ Window = $window; Score = $score }
        } catch {
        }
    }
    return ($found | Sort-Object -Property Score -Descending | ForEach-Object { $_.Window })
}

function Get-AllElements {
    param($Scope)
    try {
        return $Scope.FindAll($Descendants, $TrueCondition)
    } catch {
        return @()
    }
}

function Find-ByText {
    param($Windows, [string]$Text, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        foreach ($window in $Windows) {
            foreach ($element in (Get-AllElements -Scope $window)) {
                try {
                    $name = $element.Current.Name
                    if ($name -and $name.Contains($Text)) { return $element }
                } catch {
                }
            }
        }
        Start-Sleep -Milliseconds 700
    }
    return $null
}

function Invoke-ElementClick {
    param($Element)
    try {
        $pattern = $Element.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
        $pattern.Invoke()
        return "InvokePattern"
    } catch {
    }
    $rect = $Element.Current.BoundingRectangle
    if ($rect.Width -le 0 -or $rect.Height -le 0) { return "" }
    $x = [int]($rect.X + $rect.Width / 2)
    $y = [int]($rect.Y + $rect.Height / 2)
    [void][Win32.Mouse]::SetCursorPos($x, $y)
    Start-Sleep -Milliseconds 150
    [Win32.Mouse]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
    [Win32.Mouse]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
    return "mouse"
}

function Get-Steps {
    if ($Steps.Count -gt 0) { return $Steps }
    if (-not (Test-Path $StepsFile)) {
        throw "找不到步骤文件 $StepsFile，且未通过 -Steps 指定"
    }
    $config = Get-Content -Path $StepsFile -Raw | ConvertFrom-Json
    $list = @()
    foreach ($item in $config.steps) {
        if ($item.text) { $list += $item.text }
    }
    if ($list.Count -eq 0) { throw "$StepsFile 里没有 steps[].text" }
    return $list
}

$windows = Get-WeChatWindows
if ($windows.Count -eq 0) {
    Write-Log "找不到微信窗口（请先启动 PC 微信）"
    exit 2
}
Write-Log ("找到 {0} 个微信相关窗口" -f $windows.Count)

if ($Dump) {
    $lines = @()
    foreach ($window in $windows) {
        try {
            $lines += "== 窗口：$($window.Current.Name) [$($window.Current.ClassName)] pid=$($window.Current.ProcessId)"
        } catch {
        }
        foreach ($element in (Get-AllElements -Scope $window)) {
            try {
                $rect = $element.Current.BoundingRectangle
                $lines += ("{0} | {1} | {2},{3} {4}x{5}" -f $element.Current.ControlType.ProgrammaticName, $element.Current.Name, [int]$rect.X, [int]$rect.Y, [int]$rect.Width, [int]$rect.Height)
            } catch {
            }
        }
    }
    [System.IO.File]::WriteAllLines($DumpFile, $lines)
    Write-Log ("控件树已写入 {0}（{1} 行）" -f $DumpFile, $lines.Count)
    exit 0
}

$steps = Get-Steps
foreach ($window in $windows) {
    try {
        [void][Win32.Mouse]::ShowWindow($window.Current.NativeWindowHandle, 9)
        [void][Win32.Mouse]::SetForegroundWindow($window.Current.NativeWindowHandle)
    } catch {
    }
}
Start-Sleep -Seconds $SettleSeconds

$failed = 0
foreach ($step in $steps) {
    $element = Find-ByText -Windows $windows -Text $step -TimeoutSeconds $StepTimeoutSeconds
    if (-not $element) {
        Write-Log ("未找到「{0}」（超时 {1} 秒）" -f $step, $StepTimeoutSeconds)
        $failed = 1
        continue
    }
    $rect = $element.Current.BoundingRectangle
    if ($DryRun) {
        Write-Log ("命中「{0}」：{1} @ {2},{3} {4}x{5}（DryRun 未点击）" -f $step, $element.Current.ControlType.ProgrammaticName, [int]$rect.X, [int]$rect.Y, [int]$rect.Width, [int]$rect.Height)
        continue
    }
    $how = Invoke-ElementClick -Element $element
    if (-not $how) { Write-Log ("「{0}」没有可点击区域，跳过" -f $step); $failed = 1; continue }
    Write-Log ("已点击「{0}」（{1}）" -f $step, $how)
    Start-Sleep -Seconds $SettleSeconds
}

exit $failed