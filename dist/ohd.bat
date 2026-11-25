@echo off
rem ohd 启动包装（Windows）。两种形态自适应：
rem   开发形态：本脚本在 dist\ 下，jar 由 Gradle installDist 产出
rem   打包形态：lib\ 与 runtime\ 与本脚本同级（同事解压即用，无需 JDK）
setlocal
set "SELF=%~dp0"
set "LIB="
if exist "%SELF%lib" set "LIB=%SELF%lib"
if not defined LIB if exist "%SELF%..\ohd-cli\build\install\ohd\lib" set "LIB=%SELF%..\ohd-cli\build\install\ohd\lib"

if not defined LIB (
  echo 找不到 ohd 的 jar 目录 1>&2
  echo 开发时先在仓库根执行 dist\build.bat；分发包缺 lib\ 说明打包不完整 1>&2
  exit /b 1
)

set "JAVA=%SELF%runtime\bin\java.exe"
if exist "%JAVA%" goto run
where java >nul 2>nul
if errorlevel 1 (
  echo 找不到 java，且未打包运行时：执行 dist\package.bat 生成 1>&2
  exit /b 1
)
set "JAVA=java"

:run
"%JAVA%" -cp "%LIB%\*" ohd.cli.MainKt %*
exit /b %ERRORLEVEL%
