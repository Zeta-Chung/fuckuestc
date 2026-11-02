@echo off
setlocal
cd /d "%~dp0"
set "PYTHONUTF8=1"
set "PYTHONIOENCODING=utf-8"
set "PY=%~dp0python\python.exe"
if not exist "%PY%" if exist "%ProgramFiles%\Python312\python.exe" set "PY=%ProgramFiles%\Python312\python.exe"
if not exist "%PY%" if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
if not exist "%PY%" set "PY=python"
"%PY%" "%~dp0nightly.py" %*
set "CODE=%ERRORLEVEL%"
if not "%CODE%"=="0" echo [exit %CODE%] check-in not completed, see messages above.
endlocal & exit /b %CODE%