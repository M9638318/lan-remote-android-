@echo off
setlocal EnableExtensions
chcp 65001 >nul
set "EXE=%~dp0dist\LAN-Touchpad.exe"
if not exist "%EXE%" (
    echo [ERROR] GUI EXE not found:
    echo %EXE%
    echo First double-click build_exe.bat to build it.
    pause
    exit /b 1
)
start "LAN Touchpad" "%EXE%"
