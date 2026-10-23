@echo off
rem Starts Footage Vault in the system tray (bottom-right, near the clock).
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  echo First run: setting up Python environment...
  where python >nul 2>nul
  if errorlevel 1 (
    py -3 -m venv .venv || goto :error
  ) else (
    python -m venv .venv
    if errorlevel 1 py -3 -m venv .venv || goto :error
  )
)
if not exist ".venv\.requirements-ok" goto :install
fc /b requirements.txt ".venv\.requirements-ok" >nul 2>&1
if errorlevel 1 goto :install
goto :run

:install
echo Installing packages...
.venv\Scripts\python -m pip install -q -r requirements.txt || goto :error
copy /y requirements.txt ".venv\.requirements-ok" >nul

:run
start "" .venv\Scripts\pythonw.exe run.py
exit /b

:error
echo Setup failed. Is Python 3.11+ installed?
echo Run install.ps1 once (see README), or install Python from https://www.python.org/downloads/
pause
