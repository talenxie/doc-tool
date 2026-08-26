@echo off
chcp 65001 >nul
echo ========================================
echo   安装 Tesseract OCR
echo ========================================
echo.

:: 检查是否已安装
if exist "C:\Program Files\Tesseract-OCR\tesseract.exe" (
    echo Tesseract 已安装在 C:\Program Files\Tesseract-OCR
    C:\Program Files\Tesseract-OCR\tesseract.exe --version
    pause
    exit /b 0
)

echo 正在下载 Tesseract OCR 安装包...
echo.

:: 创建临时目录
set "TEMP_DIR=%TEMP%\tesseract_install"
if not exist "%TEMP_DIR%" mkdir "%TEMP_DIR%"

:: 下载安装包 (v5.3.3 for Windows 64-bit)
set "DOWNLOAD_URL=https://digi.bib.uni-mannheim.de/tesseract/tesseract-ocr-w64-setup-5.3.3.20231005.exe"
set "INSTALLER=%TEMP_DIR%\tesseract-setup.exe"

echo 下载地址: %DOWNLOAD_URL%
echo 保存到: %INSTALLER%
echo.
echo 请手动下载并安装 Tesseract:
echo 1. 打开浏览器访问: https://github.com/UB-Mannheim/tesseract/wiki
echo 2. 下载 Windows 64-bit 版本
echo 3. 安装时勾选 "Chinese Simplified" 语言包
echo 4. 安装路径保持默认: C:\Program Files\Tesseract-OCR
echo.
echo 安装完成后重新运行本程序即可使用 OCR 功能。
echo.
pause
