# TokenPilot

> 공개 소스 저장소입니다. 개인 데이터, 로그인 정보, 환경 설정, 로컬 실행 파일, 빌드 결과물과 실제 사용자 화면 캡처는 포함하지 않습니다. 원본 로컬 Git 이력을 가져오지 않은 현재 소스 스냅샷입니다.

Codex 자동 연결(개발용)을 추가했다. PC 서버 실행과 ADB 연결 방법은 `companion/README.md`를 참고한다. Android 디버그 앱에서 자동 잔여량과 초기화 시각을 확인할 수 있다. 무선 및 백그라운드 연결은 아직 지원하지 않는다.

월정액 AI 서비스의 남은 사용량과 초기화 시간을 관리하는 Android 앱이다.

## 개발 환경

- Android Studio Quail 4 (2026.1.4)
- Kotlin 2.3.20
- Android Gradle Plugin 9.0.1
- Gradle 9.1.0
- Jetpack Compose
- `compileSdk` / `targetSdk`: 36
- `minSdk`: 26

Android SDK 기본 경로:

```text
%LOCALAPPDATA%\Android\Sdk
```

## 빌드

새 PowerShell에서 다음 명령을 실행한다.

```powershell
cd path\to\TokenPilot
.\gradlew.bat assembleDebug
```

테스트가 추가된 이후에는 다음 명령을 사용한다.

```powershell
.\gradlew.bat test
```

제품 기획은 `docs/product-plan.md`, 개발 이력은 `docs/development-log.md`에서 관리한다.

## 현재 사용 가능한 기능

- 자동 조회 영역에는 5시간·주간 카드가 모두 표시된다. 값이 제공되지 않은 구간은 `정보 없음`으로 안내하며, 정상 응답이 오면 잔여율과 초기화 시각으로 표시한다.

- 홈에서 Codex, Claude, Gemini, Grok의 사용량을 직접 등록하고 수정하거나 삭제한다.
- 같은 서비스에 서로 다른 제한 구간을 등록할 수 있다.
- 초기화 시각은 설정에서 선택한 시간대 기준 `yyyy-MM-dd HH:mm` 형식으로 입력한다. 기본값은 기기 시간대 자동 적용이다.
- 초기화 탭은 예정 시각순으로 정렬한다. 시간이 지난 기록은 재확인을 요청한다.
- 기록은 기기 내 SharedPreferences에 저장한다. Codex PC 연결은 개발용 ADB 방식이다.
- 밝은 배경, 네이비 연결 카드, 민트 잔여율로 화면을 구성한다. 잔여율 20% 이하는 경고색으로 표시하며 미제공 값은 0%로 대체하지 않는다.

검증 명령: `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug`

## 알림 사용법과 제한

1. 홈에서 PC 연결을 통해 Codex 사용량을 한 번 조회한다.
2. 설정의 알림 권한 요청을 허용한다. 기본적으로 두 알림 스위치는 꺼져 있다.
3. `알림창 사용량 표시`를 켜면 마지막 조회값·확인 시각·초기화 예정 시각을 표시한다. 백그라운드에서 새 사용량을 가져오지는 않는다.
   - 접힌 알림은 5시간(민트)·주간(라벤더) 두 카드에 잔여율을 표시한다. 미제공 구간은 `정보 없음`으로 표시하며 값을 추정하지 않는다. 펼치면 상세 시각을 볼 수 있고 `열기`로 앱에 진입한다.
4. `초기화 예정 알림`을 켜면 제공된 5시간/주간 등 한도의 시각을 예약한다. 수동 기록은 현재 예약 대상이 아니다.
5. `예약 알림 테스트`로 약 10초 후 테스트 알림을 예약할 수 있다. 실제 사용량과 초기화 시각은 변경하지 않는다.

- Android 표준 비정확 알람을 사용하므로 배터리 절약/Doze 등의 상태에 따라 늦어질 수 있다. 정확한 초 단위 알림은 보장하지 않는다.
- 초기화 완료를 확인한 알림이 아니라 저장된 예정 시각 도달 안내이다. 새 잔여량은 앱에서 다시 조회해야 한다.
- 동일 구간·초기화 시각의 중복 알림을 방지한다. 재부팅·앱 업데이트 후 저장된 예약을 복구하며, 24시간 이상 지난 알림은 보내지 않는다.
- 앱을 강제 종료하면 Android가 예약을 제거할 수 있다. 앱을 다시 열어야 복구된다.
- 알림은 권한과 개별 채널 설정의 영향을 받는다. 시스템 알림 설정에서 종류별 소리·차단을 변경한다. Android 버전에 따라 사용량 표시 알림도 밀어서 지울 수 있다.
- 인증 정보 없이 필요한 조회 메타데이터만 기기에 저장한다. 선택한 시간대를 알림에도 적용한다.

Android 근거: [알림 권한](https://developer.android.com/develop/ui/compose/notifications/notification-permission), [알람 예약](https://developer.android.com/develop/background-work/services/alarms), [Android 14 ongoing 알림 변경](https://developer.android.com/about/versions/14/behavior-changes-all#non-dismissable-notifications).
