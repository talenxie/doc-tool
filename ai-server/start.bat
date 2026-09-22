@echo off
title AI Server - LLaVA-7B
chcp 65001 >nul
echo ========================================
echo   MiniCPM-V AI Server
echo ========================================
echo.

python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found
    pause
    exit /b 1
)

echo [1/3] Installing dependencies...
pip install -r "%~dp0requirements.txt"

echo.
echo [2/3] Starting AI Server...
echo.

python "%~dp0server.py"

pause
