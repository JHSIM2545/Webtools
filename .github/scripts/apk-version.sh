#!/usr/bin/env bash
# 앱 하나의 새 버전 번호를 정한다.  usage: apk-version.sh <key> <비교용 APK> <출력폴더> <앱 모듈 build.gradle> <javac 출력 폴더>
# 앱 하나만의 설정(권한·공유 수신 등)은 APK 내용에 이미 반영돼 있어 그 앱만 달라진다.
# 규칙: APK 내용(지문)이 직전과 같으면 버전·빌드 시각을 그대로 두고, 달라진 앱만 새 번호(=실행 번호)를 받는다.
#   · 옛 방식(소스 해시)으로 기록된 값은 '현재 상태를 기준선'으로 받아들인다(한 번만, 새 버전 표시 없음)
#   · 커밋 메시지에 [keep-version] 이 있으면 내용이 달라도 새 버전으로 치지 않는다(설치된 앱에 영향 없는 정리용)
# 출력: v, t(빌드 시각 ms), n(버전 이름), build(1=실제 APK 를 새로 빌드해 올림 / 0=기존 APK 그대로)
set -euo pipefail
KEY=$1; PROBE=$2; OUT=$3; MODGRADLE=$4; CLASSDIR=$5
APKFP=$(python3 .github/scripts/fingerprint.py "$PROBE" --classes "$CLASSDIR" --list 2>fp-files.txt)
# 빌드 도구·SDK·의존성 줄(모든 앱에 공통으로 영향) — 이 줄이 바뀌면 모든 앱이 새 버전
TOOL=$(cat android/build.gradle android/settings.gradle android/gradle.properties "$MODGRADLE" | grep -E 'implementation|api |classpath|id .com.android|compileSdk|targetSdk|minSdk|sourceCompat|targetCompat|jvmTarget' | sha256sum | cut -c1-16)
FP="fp3:$(printf '%s%s' "${APKFP#fp3:}" "$TOOL" | sha256sum | cut -c1-16)"
echo "::group::$KEY APK 파일별 지문"; cat fp-files.txt; echo "::endgroup::"
PV=$(jq -r --arg k "$KEY" '.apps[$k].v // empty' prev.json)
PH=$(jq -r --arg k "$KEY" '.apps[$k].h // empty' prev.json)
PT=$(jq -r --arg k "$KEY" '.apps[$k].t // empty' prev.json)
HAVE=0; grep -qxF "simtools-$KEY.apk" prev-assets.txt && HAVE=1
KEEP=0; git log -1 --format=%B | grep -qF '[keep-version]' && KEEP=1

if   [ -z "$PV" ];                 then ACT=new     # 처음 나온 앱
elif [ -f .github/force-new-versions ] && grep -qx "$KEY" .github/force-new-versions; then ACT=new   # (일회성) 지정한 앱은 무조건 새 버전
elif [ "$PH" = "$FP" ];            then ACT=same
elif [ "${PH#fp3:}" = "$PH" ];     then ACT=adopt   # 옛 방식 기록 → 기준선으로 채택
elif [ "$KEEP" = 1 ];              then ACT=adopt
else                                    ACT=new
fi

NOW=$(( $(date +%s) * 1000 ))
if [ "$ACT" = new ]; then V=$GITHUB_RUN_NUMBER; T=$NOW; BUILD=1
else V=$PV; T=${PT:-$NOW}; if [ "$HAVE" = 1 ]; then BUILD=0; else BUILD=1; fi; fi
N="1.0.$V"
echo "[$KEY] fp=$FP prev=$PV/$PH action=$ACT -> version=$V build=$BUILD"
mkdir -p "$OUT"
jq -n --arg key "$KEY" --argjson v "$V" --arg h "$FP" --argjson t "$T" --arg n "$N" '{key:$key,v:$v,h:$h,t:$t,n:$n}' > "$OUT/ver-$KEY.json"
{ echo "v=$V"; echo "t=$T"; echo "n=$N"; echo "build=$BUILD"; } >> "${GITHUB_OUTPUT:-/dev/stdout}"
