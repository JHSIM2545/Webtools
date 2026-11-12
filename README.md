# Webtools (SIM Tools)

## 버전 관리 규칙 (안드로이드 APK)

- **화면(HTML/JS) 수정은 APK 버전과 무관**합니다. 앱은 GitHub Pages 의 화면을 불러오므로 푸시만 하면 반영되고, 런처에 UPDATE 표시가 붙지 않습니다.
- **APK 는 '내용이 바뀐 앱'만 새 버전**을 받습니다 (`.github/workflows/android.yml`).
  1. 앱마다 버전·빌드시각을 고정한 비교용 빌드를 만들고, 서명을 뺀 APK 내용(코드·매니페스트·리소스·assets)의 지문을 계산합니다 (`.github/scripts/fingerprint.py`).
  2. 직전 릴리스(`apps-latest`)의 `versions.json` 과 지문이 같으면 버전·빌드시각·APK 를 그대로 둡니다. 다르면 그 앱만 새 버전(= Actions 실행 번호)을 받습니다 (`.github/scripts/apk-version.sh`).
  3. 런처는 `versions.json` 의 앱별 `v` 가 설치된 앱의 versionCode 보다 클 때만 UPDATE 를 표시합니다.
- 앱 하나의 설정(예: 권한·공유 수신)을 바꾸면 그 앱의 APK 내용만 달라지므로 그 앱만 갱신됩니다. 모든 앱이 공유하는 네이티브 코드(`android/shell/src/main`)를 고치면 모든 앱 APK 가 실제로 달라지므로 전부 갱신됩니다. 설치된 앱에 영향 없는 정리(리팩터링)는 커밋 메시지에 `[keep-version]` 을 넣으면 새 버전으로 치지 않습니다.
- 버전 이름은 `1.0.<버전번호>`, 빌드 번호는 versionCode(= 버전번호), 빌드 날짜는 그 버전을 만든 시각입니다 (안 바뀐 앱은 예전 값 유지). 앱의 ⋯ → 버전 정보에서 볼 수 있습니다.
- 새 앱을 추가할 때: `android.yml` 의 matrix, `sw.js` SHELL 목록, 런처 매니페스트(`android/shell/src/launcher/AndroidManifest.xml`)의 `<queries>`, `Extra.java` 의 PKG 목록에 넣습니다.
- 릴리스를 수동으로 지우지 마세요. 버전표(`versions.json`)가 사라지면 CI 가 중단됩니다(빈 표로 대체해 모든 앱을 새 버전으로 만드는 일을 막기 위해).
