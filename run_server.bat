@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist build\com\cncd\ch04\server\MainServer.class call compile.bat
java -cp build com.cncd.ch04.server.MainServer 3500 data
pause
