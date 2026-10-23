@echo off
title Starting BT Mouse Companion...
cd /d "%~dp0"

:: 1. Launch standalone windowless .exe if present (opens directly to desktop & system tray with zero terminal)
if exist "%~dp0pc_server\dist\BT-Mouse-Companion.exe" (
    start "" "%~dp0pc_server\dist\BT-Mouse-Companion.exe"
    exit /b 0
)
if exist "%~dp0BT-Mouse-Companion.exe" (
    start "" "%~dp0BT-Mouse-Companion.exe"
    exit /b 0
)

:: 2. Otherwise launch via pythonw (GUI windowless Python runner)
if exist "%~dp0pc_server\app_gui.py" (
    cd /d "%~dp0pc_server"
    start pythonw app_gui.py
    exit /b 0
)

:: 3. Console fallback
cd /d "%~dp0pc_server"
python server.py
pause
