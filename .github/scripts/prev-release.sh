#!/usr/bin/env bash
# 직전 릴리스(apps-latest)의 버전표(versions.json)와 APK 목록을 가져온다.
#  - 릴리스가 '없을 때만'(최초) 빈 표로 시작한다.
#  - 그 밖의 실패(네트워크 등)는 빈 표로 대체하지 않고 중단한다 → 실패 한 번에 모든 앱이 새 버전이 되는 일을 막는다.
set -uo pipefail
if out=$(gh release view apps-latest --json assets -q '.assets[].name' 2>&1); then
  printf '%s\n' "$out" > prev-assets.txt
  ok=0
  for i in 1 2 3; do
    if gh release download apps-latest -p versions.json -O prev.json --clobber 2>/dev/null && jq -e '.apps' prev.json >/dev/null 2>&1; then ok=1; break; fi
    sleep 5
  done
  if [ "$ok" != 1 ]; then echo "::error::apps-latest 릴리스의 versions.json 을 읽지 못했습니다. 빈 표로 대체하면 모든 앱이 새 버전이 되므로 중단합니다."; exit 1; fi
elif grep -qi 'not found' <<<"$out"; then
  echo '최초 실행: 이전 릴리스 없음'; echo '{}' > prev.json; : > prev-assets.txt
else
  echo "::error::릴리스 조회 실패: $out"; exit 1
fi
