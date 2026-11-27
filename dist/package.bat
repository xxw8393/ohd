@echo off
chcp 65001 >nul
rem 打一个自带运行时的分发包：同事解压即用，不必预装 JDK。
rem 产物：dist\ohd-<版本>-windows-<架构>.zip   （对应 package.sh）
setlocal enabledelayedexpansion
set "SELF=%~dp0"
set "ROOT=%SELF%.."
cd /d "%ROOT%"

rem ---- 版本号：build.gradle.kts 里的  version = "x.y.z"（允许多余缩进）
set "VERSION="
for /f "tokens=1,2,3" %%a in ('findstr /r /c:"version *=" build.gradle.kts') do if not defined VERSION set "VERSION=%%~c"
if not defined VERSION set "VERSION=0.1.0"

rem ---- 平台名（对标 package.sh 的 uname -s/uname -m）
set "ARCH=%PROCESSOR_ARCHITECTURE%"
if /i "%ARCH%"=="AMD64" set "ARCH=amd64"
if /i "%ARCH%"=="ARM64" set "ARCH=arm64"
if /i "%ARCH%"=="X86" set "ARCH=x86"
set "PLATFORM=windows-%ARCH%"

echo [1/4] 构建
call "%SELF%build.bat"
if errorlevel 1 (
  echo build 失败，先解决构建 1>&2
  exit /b 1
)

echo [2/4] 生成精简运行时（jlink）
where jlink >nul 2>nul
if errorlevel 1 (
  echo 找不到 jlink：需要 JDK 17+ 且把 jlink 放进 PATH 1>&2
  exit /b 1
)
if exist "dist\runtime" rmdir /s /q "dist\runtime"
jlink --add-modules java.base,java.desktop,java.logging,java.xml,jdk.unsupported ^
      --output "dist\runtime" ^
      --strip-debug --no-header-files --no-man-pages --compress=2
if errorlevel 1 (
  echo jlink 失败 1>&2
  exit /b 1
)

echo [3/4] 组装
set "OUT=dist\ohd-%VERSION%-%PLATFORM%"
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"
xcopy /e /i /q "dist\runtime" "%OUT%\runtime" >nul
xcopy /e /i /q "ohd-cli\build\install\ohd\lib" "%OUT%\lib" >nul
copy /y "dist\ohd.bat" "%OUT%\ohd.bat" >nul
copy /y "dist\smoke.bat" "%OUT%\smoke.bat" >nul

rem ---- README.txt：解压短说明；README.md：与仓内最新正文同步
powershell -NoProfile -Command "$t = Get-Content -Raw -Encoding UTF8 'dist\README.dist.txt'; $t = $t -replace '@VERSION@','%VERSION%' -replace '@PLATFORM@','%PLATFORM%' -replace '@OHD@','ohd.bat' -replace '@SMOKE@','smoke.bat'; Set-Content -NoNewline -Encoding UTF8 -Path '%OUT%\README.txt' -Value $t"
if errorlevel 1 (
  echo README 渲染失败，回退为直接拷贝模板 1>&2
  copy /y "dist\README.dist.txt" "%OUT%\README.txt" >nul
)
copy /y "README.md" "%OUT%\README.md" >nul
rem ---- 示例扩展：目录名带 .example，避免被 ohd 当成真命令自动加载；用户拷出来才生效
if exist "%OUT%\extensions.example" rmdir /s /q "%OUT%\extensions.example"
mkdir "%OUT%\extensions.example" 2>nul
copy /y "examples\extensions\*" "%OUT%\extensions.example\" >nul

echo [4/4] 压缩
if exist "%OUT%.zip" del /q "%OUT%.zip"
powershell -NoProfile -Command "Compress-Archive -Path '%OUT%' -DestinationPath '%OUT%.zip' -Force"
if errorlevel 1 (
  echo 压缩失败：确认 PowerShell 可用 1>&2
  exit /b 1
)
for %%I in ("%OUT%.zip") do echo 完成：%%~fI（%%~zI 字节）
