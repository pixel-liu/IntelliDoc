@echo off
setlocal
cd /d "%~dp0"

rem This applies only to this invocation; it does not change the system execution policy.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-local.ps1"
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
  echo.
  echo IntelliDoc failed to stop cleanly. Review the message above.
  pause
)

exit /b %EXIT_CODE%
