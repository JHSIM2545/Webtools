@echo off
chcp 65001 >nul
title SIM PAD PC
cd /d "%~dp0"

set "PY="
py -3 --version >nul 2>nul && set "PY=py -3"
if not defined PY (python --version >nul 2>nul && set "PY=python")
if not defined PY (
  echo.
  echo  Python 이 설치되어 있지 않습니다.
  echo  아래 명령을 한 번 실행해 설치한 뒤 이 파일을 다시 실행하세요:
  echo.
  echo      winget install Python.Python.3.12
  echo.
  echo  ^(또는 https://www.python.org/downloads/ 에서 설치 - "Add python.exe to PATH" 체크^)
  echo.
  pause
  exit /b 1
)

%PY% -m pip install --quiet --disable-pip-version-check websocket-client qrcode
if errorlevel 1 (
  echo  필요한 구성요소 설치에 실패했습니다. 인터넷 연결을 확인하세요.
  pause
  exit /b 1
)

%PY% "%~dp0simpad_pc.py" %*
echo.
pause
