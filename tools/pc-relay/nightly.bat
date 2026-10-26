@echo off
rem 每晚自动打卡入口：双击即开始等待取票并提交
setlocal
set "HERE=%~dp0"
where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%HERE%nightly.ps1" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%nightly.ps1" %*
)
exit /b %ERRORLEVEL%