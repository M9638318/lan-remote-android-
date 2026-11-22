@echo off
setlocal
cd /d "%~dp0"

echo [1/3] Checking Python...
py -3 --version
if errorlevel 1 (
    echo ERROR: Python 3.10+ was not found. Install Python and enable the py launcher.
    pause
    exit /b 1
)
py -3 -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)"
if errorlevel 1 (
    echo ERROR: Python 3.10 or newer is required. Select a supported Python with the py launcher.
    pause
    exit /b 1
)

echo [2/3] Installing dependencies...
py -3 -m pip install --upgrade pip
if errorlevel 1 goto :error
py -3 -m pip install -r requirements.txt
if errorlevel 1 goto :error

echo [3/3] Building single-file server.exe...
py -3 -m PyInstaller --noconfirm --clean --onefile --name server --console ^
  --collect-all vgamepad ^
  --collect-all mss ^
  --collect-all pyautogui ^
  server.py
if errorlevel 1 goto :error

echo.
echo BUILD SUCCESS: %CD%\dist\server.exe
echo Run server.exe, then allow it through Windows Defender Firewall on Private networks.
pause
exit /b 0

:error
echo.
echo BUILD FAILED. Read the error above.
pause
exit /b 1
