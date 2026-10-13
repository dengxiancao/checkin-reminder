@echo off
REM ============================================================
REM  Log capture launcher for the Checkin app.
REM  Double-click this file.
REM
REM  NOTE: This batch file is intentionally pure ASCII.
REM  cmd.exe parses .bat files using the OEM codepage, so non-ASCII
REM  text here gets mangled and turns into bogus commands.
REM  All real logic (and all Chinese output) lives in the .ps1 file.
REM ============================================================

chcp 65001 >nul
setlocal

set "PSFILE=%~dp0log-tool.ps1"

if not exist "%PSFILE%" (
    echo [ERROR] Cannot find log-tool.ps1 next to this file.
    echo.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%PSFILE%"
set "RC=%errorlevel%"

if not "%RC%"=="0" (
    echo.
    echo [ERROR] Script exited with code %RC%
    pause
)

endlocal
