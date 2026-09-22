@echo off
chcp 65001 >nul
title doc-tool 视频工具
cd /d %~dp0

rem ffmpeg 路径（按实际安装位置调整）
set "FFMPEG_PATH=D:\Program Files\FormatFactory\ffmpeg.exe"

rem 模型默认 minicpm-v（application.yml 默认值），如需临时换模型在此设置
rem set OLLAMA_MODEL=minicpm-v

if not exist "target\doc-tool-1.0.0.war" (
    echo [1/2] 首次运行，先打包...
    call mvn -DskipTests package
)
echo [2/2] 启动应用 http://localhost:8081
java -Xms256m -Xmx1g -jar target\doc-tool-1.0.0.war
pause
