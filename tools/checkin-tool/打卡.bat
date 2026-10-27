@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

set "PY="
if exist "%~dp0python\python.exe" set "PY=%~dp0python\python.exe"
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
if not defined PY if exist "%ProgramFiles%\Python312\python.exe" set "PY=%ProgramFiles%\Python312\python.exe"
if not defined PY set "PY=python"

"%PY%" "%~dp0nightly.py" %*
set "CODE=%ERRORLEVEL%"
if not "%CODE%"=="0" (
  echo.
  echo [退出码 %CODE%] 打卡未完成，请看上面的提示。
)
endlocal & exit /b %CODE%