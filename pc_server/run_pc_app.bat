@echo off
title Starting BT Mouse Companion...
cd /d "%~dp0"

:: 1. Launch standalone windowless .exe if present
if exist "%~dp0dist\BT-Mouse-Companion.exe" (
    start "" "%~dp0dist\BT-Mouse-Companion.exe"
    exit /b 0
)
if exist "%~dp0..\BT-Mouse-Companion.exe" (
    start "" "%~dp0..\BT-Mouse-Companion.exe"
    exit /b 0
)

:: 2. Launch via pythonw (GUI windowless Python runner)
start pythonw app_gui.py
exit /b 0
