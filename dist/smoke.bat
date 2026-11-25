@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
rem ohd 冒烟（Windows）。exit 0/7/2 均为预期。
rem 用法: smoke.bat [包目录]
set BIN=%~dp0ohd.bat
set CASE=%~1
set TMP=%TEMP%
set FAIL=0

call :run doctor   0 doctor
call :run selftest 0 selftest
call :run env      0 env --json
call :run tree     0 tree --depth 3
call :run shot     0 shot --out "%TMP%\ohd-smoke.png"
call :run mem      0 mem --bundle com.ohos.sceneboard
call :run cpu      0 cpu --bundle com.ohos.sceneboard
call :run watch    0 watch --bundle com.ohos.sceneboard --count 3 --interval 1s
call :run tap      0 tap 640 1400
call :run input    0 input key Back
call :run crash    0 crash --json
call :run dmabuf   0 dmabuf snap --bundle com.ohos.sceneboard --top 3
call :run badarg   2 tree --dept 3
call :run nocap    2 no-such-capability
if not "%CASE%"=="" (
  call :run analyze 0 analyze "%CASE%"
  call :run report  0 report "%CASE%" --out "%TMP%\ohd-smoke-report.md"
  call :run repro   7 repro "%CASE%" --settle 1s
)
echo.
if %FAIL%==0 (echo 冒烟通过) else (echo 失败 %FAIL% 项，逐个看 %TMP%\ohd-smoke-*.log)
exit /b %FAIL%

:run
set N=%~1
set E=%~2
shift
shift
call %BIN% %* > "%TMP%\ohd-smoke-%N%.log" 2>&1
set C=%ERRORLEVEL%
if "%C%"=="%E%" (echo %N% exit=%C% ok) else (echo %N% exit=%C% FAIL(期望 %E%) & set /a FAIL+=1)
exit /b 0
