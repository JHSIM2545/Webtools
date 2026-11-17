# Webtools (SIM Tools)

## SIM Tools 통합 앱 (단일 APK)

- `android/suite` 모듈이 런처와 모든 도구를 하나의 앱(`kr.simtools.app`)으로 묶는다. 빌드: `.github/workflows/suite.yml` → 릴리스 `suite-latest` 의 `SIM-Tools.apk`.
- 화면(웹)은 지금처럼 GitHub Pages 에서 불러온다. 웹만 바꾸면 앱 재설치 없이 반영된다. 인터넷이 없을 때만 앱 안에 함께 들어 있는 같은 화면 파일(`assets/web`)로 열린다(웹 변경이 push 되면 APK 도 다시 만들어진다).
- 런처의 "앱 추가/제거"는 APK 설치가 아니라 런처 보드에 도구를 켜고 끄는 것이다(`SimLauncher` 창구, `SuiteExtra.java`).
- 도구는 `simtools://tool/<id>` 로 별도 창(documentLaunchMode)에서 열린다. 새 도구를 추가하면 `SuiteExtra.IDS` 와 런처 `TOOLS` 에 넣는다.
- 개별 앱 빌드(`android/shell`·`android/app`)와 버전표 시스템은 없앴다. 안드로이드 앱은 이 통합 앱 하나뿐이다. SIM Control(블루투스 HID)도 `PadActivity` 로 들어 있다.
- 버전은 하나다: 루트 `VERSION` 파일(예: `2.0`) + 빌드 실행 번호 → 앱 버전 `2.0.<번호>`(`versionCode` = 번호). 앱의 "버전 정보"에도 이 버전 하나만 보인다. 화면(웹) 변경은 Pages 로 바로 반영되고, 업데이트 확인 때 자동으로 맞춘다. 큰 변경 때만 `VERSION` 을 올린다.
- 아이콘: 어두운 남색 바탕에 색색 타일(`icon.svg`). 앱 아이콘은 `android/suite/src/main/res`(적응형 아이콘)에 들어 있어 옛 개별 앱 아이콘과 구분된다.
