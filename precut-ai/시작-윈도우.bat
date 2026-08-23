@echo off
chcp 65001 >nul
title PreCut AI
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 (
  echo.
  echo  [!] Python이 설치되어 있지 않습니다.
  echo      https://www.python.org/downloads/ 에서 설치할 때
  echo      "Add python.exe to PATH" 체크를 꼭 켜 주세요.
  echo.
  pause
  exit /b 1
)

where ffmpeg >nul 2>nul
if errorlevel 1 (
  echo.
  echo  [!] ffmpeg가 아직 없습니다. 관리자 권한 없이 이렇게 설치할 수 있어요:
  echo      winget install ffmpeg
  echo      설치 후 이 파일을 다시 실행해 주세요.
  echo.
  pause
  exit /b 1
)

echo.
echo  PreCut AI를 시작합니다... 잠시 후 브라우저가 열립니다.
echo  (끝내려면 이 창에서 Ctrl+C 또는 창 닫기)
echo.
python -m precut web
pause
