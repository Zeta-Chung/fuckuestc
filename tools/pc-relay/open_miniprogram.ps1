<#
冷启动：把「成电智慧学工」小程序窗口打开。

微信 4.x 是 Qt 窗口，UIA 树里没有元素、侧栏图标也没有文字，所以这里借用
wechat_ocr_tap.ps1 的 PrintWindow + OCR 能力：在主窗口里按文字点
「小程序」（发现页里的入口）→ 再点最近使用里的「成电智慧」，小程序窗口就开了。
窗口被其他程序遮挡也能工作（PrintWindow 渲染窗口自身内容）。

用法：
    pwsh -File open_miniprogram.ps1            # 需要时打开小程序窗口
    pwsh -File open_miniprogram.ps1 -Force     # 已经开着也重新点一遍
退出码：0 = 小程序窗口已就绪；2 = 没打开成功；3 = 找不到微信主窗口
#>
[CmdletBinding()]
param(
    [string]$WindowMatch = "成电智慧学工|晚安成电",
    [string]$MainTitle = "微信",
    [string]$WeChatExe = "C:\Program Files\Tencent\Weixin\Weixin.exe",
    [string]$TapScript = (Join-Path $PSScriptRoot "wechat_ocr_tap.ps1"),
    [string]$StepsFile = (Join-Path $PSScriptRoot "cold-start-steps.json"),
    [int]$WaitSeconds = 20,
    [double]$DiscoverXRatio = 0.073,
    [double]$DiscoverYRatio = 0.595,
    [switch]$Force,
    [switch]$Restart
)

$ErrorActionPreference = "Stop"
function Write-Step { param([string]$Message) Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $Message) }

Add-Type @"
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public class MiniWin {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  public struct RECT { public int L,T,R,B; }
  // 不限定进程：小程序窗口属于 WeChatAppEx，不是 Weixin.exe
  public static List<string> Find(string pattern) {
    var rows = new List<string>();
    EnumWindows((h,l) => {
      if (!IsWindowVisible(h)) return true;
      var sb = new StringBuilder(512); GetWindowText(h, sb, 512);
      string title = sb.ToString();
      if (title.Length == 0) return true;
      RECT r; GetWindowRect(h, out r);
      if (r.R-r.L < 120 || r.B-r.T < 120) return true;
      if (System.Text.RegularExpressions.Regex.IsMatch(title, pattern)) rows.Add(title);
      return true;
    }, IntPtr.Zero);
    return rows;
  }
  public static IntPtr FindHandle(string pattern) {
    IntPtr found = IntPtr.Zero;
    EnumWindows((h,l) => {
      if (!IsWindowVisible(h)) return true;
      var sb = new StringBuilder(512); GetWindowText(h, sb, 512);
      RECT r; GetWindowRect(h, out r);
      if (r.R-r.L < 120 || r.B-r.T < 120) return true;
      if (System.Text.RegularExpressions.Regex.IsMatch(sb.ToString(), pattern)) { found = h; return false; }
      return true;
    }, IntPtr.Zero);
    return found;
  }
  public static bool Close(IntPtr h) { return PostMessage(h, 0x0010, IntPtr.Zero, IntPtr.Zero); }
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
}
"@

function Test-MiniprogramWindow {
    $found = [MiniWin]::Find($WindowMatch)
    if ($found.Count -gt 0) { return $found[0] }
    return $null
}

$opened = Test-MiniprogramWindow
if ($Restart -and $opened) {
    # 关键：小程序停在打卡页时，再点入口不会重新加载页面，也就不会重签发票据。
    # 先关掉窗口（窗口消息，不依赖鼠标），再重新打开 → 页面重新加载 → 新票据。
    $stale = [MiniWin]::FindHandle($WindowMatch)
    if ($stale -ne [IntPtr]::Zero) {
        Write-Step ("先关闭已有的小程序窗口：{0}" -f $opened)
        [void][MiniWin]::Close($stale)
        $closeDeadline = (Get-Date).AddSeconds(10)
        while ((Get-Date) -lt $closeDeadline) {
            Start-Sleep -Milliseconds 500
            if (-not (Test-MiniprogramWindow)) { Write-Step "小程序窗口已关闭"; break }
        }
        if (Test-MiniprogramWindow) {
            Write-Step "小程序窗口没关掉，仍按原窗口继续（可能取不到新票）"
            exit 0
        }
        Start-Sleep -Seconds 2   # 等主窗口重绘完再点，否则那一下会落空
    }
    $opened = $null
}
if ($opened -and -not $Force -and -not $Restart) {
    Write-Step ("小程序窗口已就绪：{0}" -f $opened)
    exit 0
}

Add-Type @"
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public class MainProbe {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  public static bool Has(string title) {
    bool found = false;
    EnumWindows((h,l) => {
      if (!IsWindowVisible(h)) return true;
      var sb = new StringBuilder(512); GetWindowText(h, sb, 512);
      if (sb.ToString().IndexOf(title, StringComparison.Ordinal) >= 0) { found = true; return false; }
      return true;
    }, IntPtr.Zero);
    return found;
  }
}
"@
if (-not [MainProbe]::Has($MainTitle)) {
    Write-Step "微信主窗口不在（可能被关到托盘），尝试启动微信把它唤出来"
    if (Test-Path $WeChatExe) {
        Start-Process -FilePath $WeChatExe | Out-Null
        $waitMain = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $waitMain -and -not [MainProbe]::Has($MainTitle)) { Start-Sleep -Seconds 2 }
        if ([MainProbe]::Has($MainTitle)) { Start-Sleep -Seconds 6 }
    }
    if (-not [MainProbe]::Has($MainTitle)) {
        Write-Step ("仍然没有标题含「{0}」的窗口，无法冷启动" -f $MainTitle)
        exit 3
    }
    Write-Step "微信主窗口已出现"
}

if (-not (Test-Path $TapScript)) { Write-Step "找不到 $TapScript"; exit 2 }

$shell = (Get-Command pwsh -ErrorAction SilentlyContinue)
if (-not $shell) { $shell = (Get-Command powershell -ErrorAction SilentlyContinue) }

# 先看主窗口现在是什么状态：「小程序」是开关，面板开着时再点一次会关掉！
function Get-MainText {
    $output = & $shell.Source -NoProfile -ExecutionPolicy Bypass -File $TapScript -WindowMatch $MainTitle -ListText -Steps '[{"wait":1}]' 2>&1 | Out-String
    $rows = @()
    foreach ($line in $output -split "`r?`n") {
        if ($line -match '\((\d+),(\d+)\)\s+(.+)$') { $rows += ($matches[3] -replace '\s', '') }
    }
    return $rows
}
function Test-Has { param([string[]]$Rows, [string]$Needle) ($Rows | Where-Object { $_ -like "*$Needle*" }).Count -gt 0 }

$rows = Get-MainText
$panelOpen = Test-Has $rows "成电智慧"
$discoverPage = (Test-Has $rows "朋友圈") -or (Test-Has $rows "小程序")
Write-Step ("主窗口 OCR 到 {0} 段文字；面板已开={1}，发现页={2}" -f $rows.Count, $panelOpen, $discoverPage)

if ($panelOpen) {
    Write-Step "面板已经开着，直接点「成电智慧」（不再点「小程序」，否则会把面板关掉）"
    $steps = @(
        @{ wait = 1 },
        @{ text = "成电智慧"; timeoutSeconds = 15 },
        @{ wait = 8 }
    )
} elseif ($discoverPage) {
    Write-Step "发现页已显示、面板没开：点「小程序」打开面板，再点「成电智慧」"
    $steps = @(
        @{ wait = 1 },
        @{ text = "小程序"; timeoutSeconds = 15 },
        @{ wait = 3 },
        @{ text = "成电智慧"; timeoutSeconds = 25 },
        @{ wait = 8 }
    )
} else {
    Write-Step "主窗口不在发现页（可能在聊天列表）：先用坐标点侧栏「发现」，再点「小程序」→「成电智慧」"
    if (-not ("NavClick" -as [type])) {
        Add-Type @"
using System;
using System.Runtime.InteropServices;
public class NavClick {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L,T,R,B; }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
  public static void Click(IntPtr h, double rx, double ry) {
    RECT r; GetWindowRect(h, out r);
    int x = (int)(r.L + rx * (r.R - r.L));
    int y = (int)(r.T + ry * (r.B - r.T));
    SetCursorPos(x, y);
    System.Threading.Thread.Sleep(140);
    mouse_event(2, 0, 0, 0, UIntPtr.Zero);
    System.Threading.Thread.Sleep(70);
    mouse_event(4, 0, 0, 0, UIntPtr.Zero);
  }
}
"@
    }
    $main = [MiniWin]::FindHandle($MainTitle)
    [NavClick]::Click($main, $DiscoverXRatio, $DiscoverYRatio)
    Start-Sleep -Milliseconds 1200
    $steps = @(
        @{ wait = 1 },
        @{ text = "小程序"; timeoutSeconds = 15 },
        @{ wait = 3 },
        @{ text = "成电智慧"; timeoutSeconds = 25 },
        @{ wait = 8 }
    )
}

$stepsJson = ConvertTo-Json -InputObject @($steps) -Compress -Depth 4
Write-Step "点击步骤：$stepsJson"
& $shell.Source -NoProfile -ExecutionPolicy Bypass -File $TapScript -WindowMatch $MainTitle -Steps $stepsJson 2>&1 |
    ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) {
    Write-Step ("点击脚本退出码 {0}" -f $LASTEXITCODE)
}

$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ((Get-Date) -lt $deadline) {
    $title = Test-MiniprogramWindow
    if ($title) { Write-Step ("小程序窗口已打开：{0}" -f $title); exit 0 }
    Start-Sleep -Milliseconds 800
}
Write-Step ("等待 {0} 秒仍未看到小程序窗口" -f $WaitSeconds)
exit 2