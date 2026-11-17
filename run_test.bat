@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist build\com\cncd\ch04\test\ProtocolIntegrationTest.class call compile.bat
java -cp build com.cncd.ch04.test.ProtocolIntegrationTest
pause
