@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
echo ============================================================
echo       LAN Touchpad - Windows GUI EXE builder
echo ============================================================

set "PY_LAUNCHER="
if /i "%CI%"=="true" set "PY_LAUNCHER=python"
if not defined PY_LAUNCHER (
  where py >nul 2>nul
  if not errorlevel 1 set "PY_LAUNCHER=py -3"
)
if not defined PY_LAUNCHER (
  where python >nul 2>nul
  if not errorlevel 1 set "PY_LAUNCHER=python"
)
if not defined PY_LAUNCHER (
  echo [ERROR] Python launcher or python command was not found.
  pause
  exit /b 1
)

for /f "usebackq delims=" %%P in (`%PY_LAUNCHER% "%~dp0select_python.py"`) do set "PY_EXE=%%P"
if not defined PY_EXE (
  echo [ERROR] No compatible 64-bit Python 3.10-3.14 was selected.
  pause
  exit /b 1
)
echo Selected Python: "%PY_EXE%"
"%PY_EXE%" --version

if not exist ".venv\Scripts\python.exe" (
  "%PY_EXE%" -m venv ".venv"
  if errorlevel 1 goto :failed
)
set "VENV_PY=%~dp0.venv\Scripts\python.exe"
"%VENV_PY%" -m pip install --upgrade pip
if errorlevel 1 goto :failed
set "VGAMEPAD_SKIP_VIGEMBUS_INSTALL=true"
"%VENV_PY%" -m pip install -r "%~dp0requirements-build.txt"
if errorlevel 1 goto :failed

if not exist "%~dp0build\spec" mkdir "%~dp0build\spec"
if not exist "%~dp0build\work" mkdir "%~dp0build\work"
"%VENV_PY%" -m PyInstaller --noconfirm --clean --onefile --windowed ^
  --name "LAN-Touchpad" ^
  --distpath "%~dp0dist" --workpath "%~dp0build\work" --specpath "%~dp0build\spec" ^
  --collect-all mss --collect-all pyautogui --collect-all pyscreeze ^
  --collect-all PIL --collect-all vgamepad ^
  --hidden-import server --hidden-import tkinter.filedialog ^
  "%~dp0gui.py"
if errorlevel 1 goto :failed
if not exist "%~dp0dist\LAN-Touchpad.exe" goto :failed

echo.
echo BUILD SUCCESS: "%~dp0dist\LAN-Touchpad.exe"
echo GUI application built. GitHub Actions additionally creates the installer.
if not defined CI pause
exit /b 0

:failed
echo.
echo [ERROR] Build failed. Review the output above.
echo Build files are retained under windows\build for diagnosis.
if not defined CI pause
exit /b 1
