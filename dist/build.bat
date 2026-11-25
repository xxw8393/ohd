@echo off
rem 构建 ohd（installDist）。不自动跑——由使用者显式执行。
setlocal
set "SELF=%~dp0"
set "ROOT=%SELF%.."

if defined GRADLE (
  set "GRADLE_BIN=%GRADLE%"
) else (
  where gradle >nul 2>nul
  if errorlevel 1 (
    echo 找不到 gradle：请安装 gradle，或设 GRADLE=C:\path\to\gradle.bat 1>&2
    exit /b 1
  )
  set "GRADLE_BIN=gradle"
)

call "%GRADLE_BIN%" -p "%ROOT%" --no-daemon installDist %*
