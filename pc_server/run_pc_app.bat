@echo off
title BT Mouse & Keyboard Companion
cd /d "%~dp0"
echo Starting BT Mouse & Keyboard PC Companion...
python app_gui.py
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [!] GUI launch failed, falling back to console server...
    python server.py
    pause
)
