@echo off
chcp 65001 >nul
title SIM PAD PC
cd /d "%~dp0"
echo.
echo  [SIM PAD] starting...

set "PY="
py -3 --version >nul 2>nul && set "PY=py -3"
if not defined PY python --version >nul 2>nul && set "PY=python"
if not defined PY goto nopython

echo  [SIM PAD] using: %PY%
%PY% "%~dp0simpad_pc.py" %*
echo.
echo  [SIM PAD] stopped. If you saw an error above, please send a screenshot.
pause
exit /b 0

:nopython
echo.
echo  Python is not installed.
echo  Run this command once, then run this file again:
echo.
echo      winget install Python.Python.3.12
echo.
echo  Or install from https://www.python.org/downloads/  (check "Add python.exe to PATH")
echo.
pause
exit /b 1
