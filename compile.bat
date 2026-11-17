@echo off
chcp 65001 >nul
cd /d "%~dp0"
if exist build rmdir /s /q build
mkdir build
javac -encoding UTF-8 -d build src\com\cncd\ch04\common\*.java src\com\cncd\ch04\server\*.java src\com\cncd\ch04\client\*.java src\com\cncd\ch04\test\*.java
if errorlevel 1 (
  echo.
  echo 编译失败，请确认已经安装 JDK 8 或更高版本，并且 javac 已加入 PATH。
  pause
  exit /b 1
)
echo.
echo 编译成功，class 文件位于 build 目录。
pause
