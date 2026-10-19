@echo off
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
  echo First run: setting up Python environment...
  python -m venv .venv || goto :error
  .venv\Scripts\python -m pip install -q -r requirements.txt || goto :error
)
.venv\Scripts\python run.py
pause
exit /b

:error
echo Setup failed. Is Python installed?
pause
