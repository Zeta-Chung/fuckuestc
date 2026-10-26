<#
PC 微信小程序自动点击（窗口截图 + Windows 内置 OCR）

为什么这样做：新版微信小程序窗口（WeChatAppEx / Chrome_WidgetWin）不暴露
UI Automation 控件树，按控件名点击不可行，因此改为：
    截取小程序窗口画面 → OCR 找文字 → 按文字坐标点击（需要滚动时先滚轮下滑）。

用法：
    pwsh -File wechat_ocr_tap.ps1 -ListText                        # 只列出 OCR 到的文字（诊断）
    pwsh -File wechat_ocr_tap.ps1 -ListText -Steps '{"scroll":-5}' # 先滚轮再看（Steps 可直接传 JSON）
    pwsh -File wechat_ocr_tap.ps1 -DryRun                          # 走一遍步骤但不点击
    pwsh -File wechat_ocr_tap.ps1                                  # 按 tap-steps.json 执行
    pwsh -File wechat_ocr_tap.ps1 -FullScreen                      # 弹窗不在窗口内时截全屏

步骤类型（tap-steps.json 的 steps 数组，按顺序执行）：
    { "text": "晚安成电" }      找到文字并点击
    { "scroll": -5 }            在窗口中心滚轮下滑 5 格（正数向上）
    { "wait": 2 }               等待 2 秒

说明：OCR 是 WinRT API，PowerShell 7 不支持，脚本会自动转交给 Windows PowerShell 5.1。
退出码：0 全部步骤完成；1 有步骤未完成；2 找不到窗口或缺少 OCR 语言包。
#>
[CmdletBinding()]
param(
    [string]$Steps = "",
    [string]$StepsFile = "",
    [string]$WindowMatch = "成电智慧学工|晚安成电",
    [int]$StepTimeoutSeconds = 30,
    [int]$SettleSeconds = 2,
    [switch]$DryRun,
    [switch]$ListText,
    [switch]$FullScreen,
    [switch]$NoPrintWindow,
    [string]$LogFile = ""
)

$ErrorActionPreference = "Stop"

# Windows OCR 是 WinRT API：PowerShell 7 不支持 WinRT 投影，自动转交给 Windows PowerShell 5.1
if ($PSVersionTable.PSEdition -eq "Core") {
    $legacy = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"
    if (Test-Path $legacy) {
        $forwarded = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $PSCommandPath)
        foreach ($key in $PSBoundParameters.Keys) {
            $value = $PSBoundParameters[$key]
            if ($value -is [switch]) {
                if ($value.IsPresent) { $forwarded += "-$key" }
            } else {
                $forwarded += @("-$key", "$value")
            }
        }
        & $legacy @forwarded
        exit $LASTEXITCODE
    }
    throw "需要 Windows PowerShell 5.1 来调用 OCR"
}

# 默认路径在参数绑定后计算：Windows PowerShell 5.1 在 param 默认值里取不到 $PSScriptRoot
if (-not $StepsFile) { $StepsFile = Join-Path $PSScriptRoot "tap-steps.json" }
if (-not $LogFile) { $LogFile = Join-Path $PSScriptRoot "wechat-tap.log" }

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public class OcrWin {
  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc lpEnumFunc, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int X, int Y, int cx, int cy, uint uFlags);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdcBlt, uint nFlags);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, IntPtr pid);
  [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT point);
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr value);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
}
'@

$MOUSEEVENTF_LEFTDOWN = 0x0002
$MOUSEEVENTF_LEFTUP = 0x0004
$MOUSEEVENTF_WHEEL = 0x0800
$SW_RESTORE = 9
$HWND_TOPMOST = [IntPtr](-1)
$HWND_NOTOPMOST = [IntPtr](-2)
$SWP_FLAGS = 0x0001 -bor 0x0002

# 必须声明 DPI 感知：否则 GetWindowRect / 截图 / 光标坐标都是「虚拟化」尺寸，
# 与实际屏幕像素差一个缩放倍率（实测 ~1.39 倍），OCR 算出的点击坐标就会整体偏移。
try { [void][OcrWin]::SetProcessDpiAwarenessContext([IntPtr](-4)) } catch { }

function Write-Log {
    param([string]$Message)
    $line = "{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message
    Write-Host $line
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

function Await($WinRtTask, $ResultType) {
    $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    })[0]
    $netTask = $asTaskGeneric.MakeGenericMethod($ResultType).Invoke($null, @($WinRtTask))
    $netTask.Wait(-1) | Out-Null
    $netTask.Result
}

$null = [Windows.Media.Ocr.OcrEngine, Windows.Media.Ocr, ContentType = WindowsRuntime]
$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime]

$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
if (-not $engine) {
    Write-Log "OCR：没有可用的语言包，请在「设置 → 时间和语言 → 语言」中安装中文识别"
    exit 2
}

function Get-CandidateWindow {
    $script:candidate = [IntPtr]::Zero
    $script:candidateTitle = ""
    $script:candidateArea = 0
    $script:fallback = [IntPtr]::Zero
    $script:fallbackTitle = ""
    $callback = [OcrWin+EnumProc]{
        param($hWnd, $lParam)
        if (-not [OcrWin]::IsWindowVisible($hWnd)) { return $true }
        $title = New-Object System.Text.StringBuilder 512
        [void][OcrWin]::GetWindowText($hWnd, $title, 512)
        if ($title.Length -gt 0 -and $title.ToString() -match $WindowMatch) {
            $rect = New-Object OcrWin+RECT
            [void][OcrWin]::GetWindowRect($hWnd, [ref]$rect)
            $width = $rect.Right - $rect.Left
            $height = $rect.Bottom - $rect.Top
            if ($width -gt 200 -and $height -gt 200) {
                if (($width * $height) -gt $script:candidateArea) {
                    $script:candidate = $hWnd
                    $script:candidateTitle = $title.ToString()
                    $script:candidateArea = $width * $height
                }
            } elseif ($script:fallback -eq [IntPtr]::Zero) {
                # 最小化的窗口在屏幕外（常见位置 -21333,-21333），尺寸很小；记下来，稍后用 SW_RESTORE 还原
                $script:fallback = $hWnd
                $script:fallbackTitle = $title.ToString()
            }
        }
        return $true
    }
    [void][OcrWin]::EnumWindows($callback, [IntPtr]::Zero)
    if ($script:candidate -ne [IntPtr]::Zero) {
        return [pscustomobject]@{ Handle = $script:candidate; Title = $script:candidateTitle; Restored = $false }
    }
    if ($script:fallback -ne [IntPtr]::Zero) {
        return [pscustomobject]@{ Handle = $script:fallback; Title = $script:fallbackTitle; Restored = $true }
    }
    return $null
}

function Set-WindowTopmost {
    param([IntPtr]$Handle, [bool]$On)
    [void][OcrWin]::ShowWindow($Handle, $SW_RESTORE)
    if ($On) {
        [void][OcrWin]::SetWindowPos($Handle, $HWND_TOPMOST, 0, 0, 0, 0, $SWP_FLAGS)
        [void][OcrWin]::BringWindowToTop($Handle)

        # 滚轮（WM_MOUSEEWHEEL）只发给「有键盘焦点的窗口」，所以必须真正把焦点拿过来：
        # 先轻敲一次 ALT 让系统认为刚有用户输入，再把本线程挂到当前前台线程上置前。
        $foreground = [OcrWin]::GetForegroundWindow()
        if ($foreground -ne $Handle) {
            [OcrWin]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)
            [OcrWin]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
            $foregroundThread = [OcrWin]::GetWindowThreadProcessId($foreground, [IntPtr]::Zero)
            $ourThread = [OcrWin]::GetCurrentThreadId()
            $attached = $false
            if ($foregroundThread -ne 0 -and $foregroundThread -ne $ourThread) {
                $attached = [OcrWin]::AttachThreadInput($ourThread, $foregroundThread, $true)
            }
            [void][OcrWin]::SetForegroundWindow($Handle)
            if ($attached) { [void][OcrWin]::AttachThreadInput($ourThread, $foregroundThread, $false) }
        }
        Start-Sleep -Milliseconds 700
        $now = [OcrWin]::GetForegroundWindow()
        Write-Log ("窗口置顶/置前完成：前台 hwnd={0}（目标 {1}）{2}" -f $now, $Handle, $(if ($now -eq $Handle) { "✓" } else { "✗ 焦点没拿到，滚轮可能无效" }))
    } else {
        [void][OcrWin]::SetWindowPos($Handle, $HWND_NOTOPMOST, 0, 0, 0, 0, $SWP_FLAGS)
    }
}

function Save-AndRecognize {
    param($Bitmap)
    $path = Join-Path $env:TEMP "wechat-ocr-tap.png"
    $Bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
    $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $softwareBitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $result = Await ($engine.RecognizeAsync($softwareBitmap)) ([Windows.Media.Ocr.OcrResult])
    $stream.Dispose()
    return $result
}

function Convert-ToItems {
    param($Result, [int]$Left, [int]$Top)
    # 逐「词」记录坐标：OCR 会把一整行组成一个 line，取 line 的中心可能落到完全不相干的位置
    # （实测：命中「晚安成电」后点到了底栏的「个人中心」）。所以匹配后只用命中文字所在那几个词的中心。
    $items = @()
    foreach ($line in $Result.Lines) {
        $words = @($line.Words)
        if ($words.Count -eq 0) { continue }
        $normalized = ""
        $boxes = @()
        foreach ($word in $words) {
            $rect = $word.BoundingRect
            $text = ($word.Text -replace "\s", "")
            if ($text.Length -eq 0) { continue }
            $start = $normalized.Length
            $normalized += $text
            $boxes += [pscustomobject]@{
                Start = $start
                End   = $normalized.Length - 1
                X     = [int]($Left + $rect.X + $rect.Width / 2)
                Y     = [int]($Top + $rect.Y + $rect.Height / 2)
            }
        }
        if ($boxes.Count -eq 0) { continue }
        $items += [pscustomobject]@{
            Text       = $line.Text
            Normalized = $normalized
            Boxes      = $boxes
            X          = [int](($boxes | Measure-Object -Property X -Average).Average)
            Y          = [int](($boxes | Measure-Object -Property Y -Average).Average)
        }
    }
    return $items
}

function Get-MatchPoint {
    param($Item, [string]$Needle)
    $index = $Item.Normalized.IndexOf($Needle)
    if ($index -lt 0) { return $null }
    $end = $index + $Needle.Length - 1
    $covering = @($Item.Boxes | Where-Object { $_.End -ge $index -and $_.Start -le $end })
    if ($covering.Count -eq 0) { return $null }
    return [pscustomobject]@{
        X    = [int](($covering | Measure-Object -Property X -Average).Average)
        Y    = [int](($covering | Measure-Object -Property Y -Average).Average)
        Text = $Item.Text
    }
}

function Select-Match {
    param($Items, [string]$Text)
    $needle = $Text -replace "\s", ""
    foreach ($item in $Items) {
        if (-not $item.Normalized) { continue }
        $point = Get-MatchPoint -Item $item -Needle $needle
        if ($point) { return $point }
    }
    return $null
}

function Send-Wheel {
    param([int]$Notches, [int]$X, [int]$Y)
    [void][OcrWin]::SetCursorPos($X, $Y)
    Start-Sleep -Milliseconds 150
    # 一次发一格（±120），应用对大 delta 可能直接丢弃；负数需按 4294967296 折算成 uint32
    $delta = if ($Notches -lt 0) { -120 } else { 120 }
    $wheelData = if ($delta -lt 0) { [uint32](4294967296 + $delta) } else { [uint32]$delta }
    for ($i = 0; $i -lt [Math]::Abs($Notches); $i++) {
        [OcrWin]::mouse_event($MOUSEEVENTF_WHEEL, 0, 0, $wheelData, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 90
    }
}

function Find-TextOnScreen {
    param([IntPtr]$Handle, [string]$Text)
    $area = Get-CaptureArea -Handle $Handle
    $lines = Invoke-Capture -Handle $Handle -Left $area.Left -Top $area.Top -Width $area.Width -Height $area.Height
    return Select-Match -Items $lines -Text $Text
}

function Invoke-Capture {
    param([IntPtr]$Handle, [int]$Left, [int]$Top, [int]$Width, [int]$Height)
    if ($Width -le 0 -or $Height -le 0) { return @() }

    if (-not $NoPrintWindow) {
        $bitmap = New-Object System.Drawing.Bitmap($Width, $Height)
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        $hdc = $graphics.GetHdc()
        [void][OcrWin]::PrintWindow($Handle, $hdc, 2)
        $graphics.ReleaseHdc($hdc)
        $graphics.Dispose()
        $result = Save-AndRecognize -Bitmap $bitmap
        $bitmap.Dispose()
        $items = Convert-ToItems -Result $result -Left $Left -Top $Top
        if ($items.Count -gt 0) { return $items }
        Write-Log "PrintWindow 没截到可用画面，改用屏幕截图"
    }

    $bitmap = New-Object System.Drawing.Bitmap($Width, $Height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.CopyFromScreen($Left, $Top, 0, 0, $bitmap.Size)
    $graphics.Dispose()
    $result = Save-AndRecognize -Bitmap $bitmap
    $bitmap.Dispose()
    return (Convert-ToItems -Result $result -Left $Left -Top $Top)
}

function Get-StepList {
    if ($Steps) { return ($Steps | ConvertFrom-Json) }
    if (-not (Test-Path $StepsFile)) { throw "找不到步骤文件 $StepsFile" }
    $json = [System.IO.File]::ReadAllText($StepsFile, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    if (@($json.steps).Count -eq 0) { throw "$StepsFile 里没有 steps" }
    return $json.steps
}

$window = Get-CandidateWindow
if (-not $window) {
    Write-Log "找不到匹配「$WindowMatch」的可见窗口；请先打开 PC 微信小程序"
    exit 2
}
Write-Log ("目标窗口：{0}（hwnd={1}）" -f $window.Title, $window.Handle)
if ($window.Restored) { Write-Log "该窗口当时是最小化状态，脚本会先还原它" }

function Get-WindowGeometry {
    param([IntPtr]$Handle)
    $r = New-Object OcrWin+RECT
    [void][OcrWin]::GetWindowRect($Handle, [ref]$r)
    return [pscustomobject]@{
        Left   = $r.Left
        Top    = $r.Top
        Width  = $r.Right - $r.Left
        Height = $r.Bottom - $r.Top
    }
}

function Get-CaptureArea {
    param([IntPtr]$Handle)
    $geometry = Get-WindowGeometry -Handle $Handle
    if ($FullScreen) {
        return [pscustomobject]@{
            Left   = 0
            Top    = 0
            Width  = [System.Windows.Forms.SystemInformation]::VirtualScreen.Width
            Height = [System.Windows.Forms.SystemInformation]::VirtualScreen.Height
        }
    }
    return $geometry
}

$failed = 0
[void](Set-WindowTopmost -Handle $window.Handle -On $true)
try {
    $area = Get-CaptureArea -Handle $window.Handle
    Write-Log ("截图区域：{0},{1} {2}x{3}" -f $area.Left, $area.Top, $area.Width, $area.Height)

    $stepList = Get-StepList
    # 若已经停在打卡页（能看到「请打卡」），跳过入口导航，避免把页面滚乱
    $skipEntry = [bool](Find-TextOnScreen -Handle $window.Handle -Text "请打卡")
    if ($skipEntry) { Write-Log "当前已在打卡页（识别到「请打卡」），跳过入口导航步骤" }

    foreach ($step in $stepList) {
        if ($step.entry -and $skipEntry) {
            Write-Log ("跳过入口步骤：{0}" -f $(if ($step.text) { $step.text } elseif ($step.scrollUntilText) { "滚动查找 " + $step.scrollUntilText } else { "滚动 " + $step.scroll + " 格" }))
            continue
        }

        if ($step.wait) {
            Write-Log ("等待 {0} 秒" -f $step.wait)
            Start-Sleep -Seconds ([int]$step.wait)
            continue
        }

        if ($step.scroll) {
            $notches = [int]$step.scroll
            if ($DryRun) { Write-Log ("DryRun：跳过滚动 {0} 格" -f $notches); continue }
            $area = Get-CaptureArea -Handle $window.Handle
            $targetX = [int]($area.Left + $area.Width / 2)
            $targetY = [int]($area.Top + $area.Height / 2)
            [void][OcrWin]::SetCursorPos($targetX, $targetY)
            Start-Sleep -Milliseconds 300
            $point = New-Object OcrWin+POINT
            [void][OcrWin]::GetCursorPos([ref]$point)
            Write-Log ("光标现在位于 {0},{1}（期望 {2},{3}）" -f $point.X, $point.Y, $targetX, $targetY)
            if ([Math]::Abs($point.X - $targetX) -gt 5 -or [Math]::Abs($point.Y - $targetY) -gt 5) {
                Write-Log "光标没能移动到位，滚轮不会作用到目标窗口"
                $failed = 1
                continue
            }
            Send-Wheel -Notches $notches -X $targetX -Y $targetY
            Write-Log ("已滚动 {0} 格" -f $notches)
            Start-Sleep -Seconds $SettleSeconds
            continue
        }

        if ($step.scrollUntilText) {
            # 边滑边找：不依赖页面当前滚到哪，最多下滑 maxScrolls 格
            $maxScrolls = if ($step.maxScrolls) { [int]$step.maxScrolls } else { 10 }
            $hit = Find-TextOnScreen -Handle $window.Handle -Text $step.scrollUntilText
            if ($hit) {
                Write-Log ("「{0}」已在当前画面（{1},{2}），无需滚动" -f $step.scrollUntilText, $hit.X, $hit.Y)
                continue
            }
            if ($DryRun -and $ListText) {
                $area = Get-CaptureArea -Handle $window.Handle
                Send-Wheel -Notches (-2) -X ([int]($area.Left + $area.Width / 2)) -Y ([int]($area.Top + $area.Height / 2))
                Write-Log "ListText：滚动 2 格"
                continue
            }
            $found = $false
            for ($i = 1; $i -le $maxScrolls; $i++) {
                $area = Get-CaptureArea -Handle $window.Handle
                Send-Wheel -Notches (-1) -X ([int]($area.Left + $area.Width / 2)) -Y ([int]($area.Top + $area.Height / 2))
                Start-Sleep -Milliseconds 400
                $hit = Find-TextOnScreen -Handle $window.Handle -Text $step.scrollUntilText
                if ($hit) {
                    Write-Log ("下滑 {0} 格后找到「{1}」→ ({2},{3})" -f $i, $step.scrollUntilText, $hit.X, $hit.Y)
                    $found = $true
                    break
                }
            }
            if (-not $found) {
                Write-Log ("下滑 {0} 格仍未找到「{1}」；可用 -ListText 查看当前画面" -f $maxScrolls, $step.scrollUntilText)
                $failed = 1
            }
            continue
        }

        if (-not $step.text) { continue }
        if ($ListText) { Write-Log ("ListText：跳过点击「{0}」" -f $step.text); continue }

        $timeout = $StepTimeoutSeconds
        if ($step.timeoutSeconds) { $timeout = [int]$step.timeoutSeconds }
        $deadline = (Get-Date).AddSeconds($timeout)
        $hit = $null
        while ((Get-Date) -lt $deadline) {
            $hit = Find-TextOnScreen -Handle $window.Handle -Text $step.text
            if ($hit) { break }
            Start-Sleep -Seconds 2
        }
        if (-not $hit) {
            Write-Log ("未找到「{0}」（超时 {1} 秒）；可用 -ListText 查看当前识别到的文字" -f $step.text, $timeout)
            $failed = 1
            continue
        }
        if ($DryRun) {
            Write-Log ("命中「{0}」→ ({1},{2})，DryRun 未点击" -f $step.text, $hit.X, $hit.Y)
            continue
        }
        # 命中位置若落在窗口底部（固定导航条区域），直接点会点到导航栏，先上滑把它抬到安全区
        $area = Get-CaptureArea -Handle $window.Handle
        $guard = 0
        while ($hit -and $hit.Y -gt ($area.Top + $area.Height - 130) -and $guard -lt 3) {
            Write-Log ("目标 ({0},{1}) 太靠近底部导航条，先上滑 2 格" -f $hit.X, $hit.Y)
            Send-Wheel -Notches 2 -X ([int]($area.Left + $area.Width / 2)) -Y ([int]($area.Top + $area.Height / 2))
            Start-Sleep -Milliseconds 700
            $hit = Find-TextOnScreen -Handle $window.Handle -Text $step.text
            $area = Get-CaptureArea -Handle $window.Handle
            $guard++
        }
        if (-not $hit) {
            Write-Log ("上滑后「{0}」不在画面内了，跳过" -f $step.text)
            $failed = 1
            continue
        }
        [void][OcrWin]::SetCursorPos($hit.X, $hit.Y)
        Start-Sleep -Milliseconds 200
        [OcrWin]::mouse_event($MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
        [OcrWin]::mouse_event($MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
        Write-Log ("已点击「{0}」于 ({1},{2})" -f $step.text, $hit.X, $hit.Y)
        Start-Sleep -Seconds $SettleSeconds
    }

    if ($ListText) {
        $area = Get-CaptureArea -Handle $window.Handle
        $lines = Invoke-Capture -Handle $window.Handle -Left $area.Left -Top $area.Top -Width $area.Width -Height $area.Height
        Write-Log ("OCR 到 {0} 行文字：" -f $lines.Count)
        foreach ($item in $lines) { Write-Log ("  ({0},{1}) {2}" -f $item.X, $item.Y, $item.Text) }
    }
} finally {
    [void](Set-WindowTopmost -Handle $window.Handle -On $false)
}

exit $failed