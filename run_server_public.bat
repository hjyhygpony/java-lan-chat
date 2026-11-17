@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist build\com\cncd\ch04\server\MainServer.class call compile.bat
REM 0.0.0.0 表示监听服务器全部网卡，适合公网服务器或端口映射场景
java -cp build com.cncd.ch04.server.MainServer 3500 data 0.0.0.0
pause
