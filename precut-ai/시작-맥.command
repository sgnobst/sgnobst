#!/bin/bash
cd "$(dirname "$0")"

if ! command -v python3 >/dev/null 2>&1; then
  echo "[!] Python3가 필요합니다. https://www.python.org/downloads/ 에서 설치해 주세요."
  read -r -p "엔터를 누르면 닫힙니다..."
  exit 1
fi

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "[!] ffmpeg가 아직 없습니다. 터미널에서 다음을 실행해 주세요:"
  echo "    brew install ffmpeg"
  echo "    (Homebrew가 없다면 https://brew.sh 참고)"
  read -r -p "엔터를 누르면 닫힙니다..."
  exit 1
fi

echo
echo "PreCut AI를 시작합니다... 잠시 후 브라우저가 열립니다."
echo "(끝내려면 이 창에서 Ctrl+C)"
echo
python3 -m precut web
