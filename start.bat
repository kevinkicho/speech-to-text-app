@echo off
setlocal
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 (
  echo [error] Python is not on PATH. Install Python 3.10+ from https://www.python.org/downloads/windows/
  echo         During install, check "Add python.exe to PATH".
  pause
  exit /b 1
)

if not exist .venv (
  echo [setup] Creating virtual environment in .venv ...
  python -m venv .venv
  if errorlevel 1 ( echo [error] venv creation failed & pause & exit /b 1 )
  call .venv\Scripts\activate.bat
  echo [setup] Installing dependencies ...
  python -m pip install --upgrade pip >nul
  pip install -r requirements.txt
  if errorlevel 1 ( echo [error] pip install failed & pause & exit /b 1 )
) else (
  call .venv\Scripts\activate.bat
)

if "%STT_TOKEN%"=="" set STT_TOKEN=change-me
if "%STT_PORT%"=="" set STT_PORT=8080

echo.
echo =========================================================
echo  STT server running on port %STT_PORT%
echo  Token: (set; not echoed — see server log for redacted form)
echo.
echo  Next step: expose it over HTTPS via Tailscale, e.g.
echo    tailscale serve --bg --https=443 http://localhost:%STT_PORT%
echo  then open https://^<your-machine^>.tail-xxxx.ts.net on phone
echo =========================================================
echo.

python server.py
