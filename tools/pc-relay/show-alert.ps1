<#
醒目提醒：屏幕顶部置顶的红色大字窗口，点「我知道了」才关闭。
用于「需要人工介入」的失败（取不到票 / 会话或授权失效 / 提交异常），
由 nightly.ps1 以独立进程启动，所以不会挡住它自己退出。

用法：
    pwsh -File show-alert.ps1 -Text "内容" [-Title "标题"] [-Seconds 0]
    -Seconds 0 表示一直显示到手动关闭。
#>
[CmdletBinding()]
param(
    [string]$Title = "打卡助手需要你处理",
    [string]$Text = "",
    [int]$Seconds = 0
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$form = New-Object System.Windows.Forms.Form
$form.Text = $Title
$form.TopMost = $true
$form.StartPosition = "Manual"
$form.FormBorderStyle = "FixedToolWindow"
$form.BackColor = [System.Drawing.Color]::FromArgb(150, 18, 18)
$width = 760
$height = 240
$area = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$form.Size = New-Object System.Drawing.Size($width, $height)
$form.Location = New-Object System.Drawing.Point([int](($area.Width - $width) / 2), 70)

$label = New-Object System.Windows.Forms.Label
$label.Text = $Text
$label.ForeColor = [System.Drawing.Color]::White
$label.Font = New-Object System.Drawing.Font("Microsoft YaHei UI", 14, [System.Drawing.FontStyle]::Bold)
$label.Dock = "Fill"
$label.TextAlign = "MiddleCenter"
$label.Padding = New-Object System.Windows.Forms.Padding(18)

$button = New-Object System.Windows.Forms.Button
$button.Text = "我知道了"
$button.Dock = "Bottom"
$button.Height = 46
$button.FlatStyle = "Flat"
$button.BackColor = [System.Drawing.Color]::White
$button.Font = New-Object System.Drawing.Font("Microsoft YaHei UI", 12)
$button.Add_Click({ $form.Close() })

$form.Controls.Add($label)
$form.Controls.Add($button)
$form.Add_Shown({ $form.Activate() })

if ($Seconds -gt 0) {
    $timer = New-Object System.Windows.Forms.Timer
    $timer.Interval = $Seconds * 1000
    $timer.Add_Tick({ $timer.Stop(); $form.Close() })
    $timer.Start()
}

[void]$form.ShowDialog()