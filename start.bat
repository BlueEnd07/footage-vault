@echo off
rem Starts Footage Vault in the system tray (bottom-right, near the clock).
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
  echo First run: setting up Python environment...
  python -m venv .venv || goto :error
)
.venv\Scripts\python -m pip install -q -r requirements.txt || goto :error
start "" .venv\Scripts\pythonw.exe run.py
exit /b

:error
echo Setup failed. Is Python installed?
pause
