# 휴대폰 직접 로그인 테스트

## 상태와 범위

이 버전은 개인 테스트용 Codex 기기 코드 로그인과 구독 잔여량 직접 조회를 구현한다. PC companion, Codex 실행 파일, ADB reverse는 앱 실행에 필요하지 않다. ADB는 개발 중 설치·검증 도구로만 사용한다.

2026-10-27에 OpenAI 서버의 기기 코드 발급이 HTTP 200으로 응답하고 필요한 필드를 반환하는 것을 확인했다. 사용자가 지금 로그인하기 어렵다고 알려 실제 계정의 로그인 완료, 사용량 응답, 실제 토큰 갱신은 검증하지 못했다. 코드 발급 성공이나 모의 테스트 통과를 실제 사용량 조회 성공으로 해석하면 안 된다.

이 경로는 Codex 공개 소스에 사용된 통신 방식을 Android에서 구현한 호환 연동이다. 외부 앱용으로 안정성을 보장하는 공개 구독 사용량 API가 아니다. 제공자 변경, 계정 설정, 네트워크 접근 제한으로 실패할 수 있다. 공식 Sign in with ChatGPT의 공개 클라이언트 등록과 별개이며 TokenPilot 전용 OAuth 클라이언트를 발급받은 것이 아니다. 상용·호스팅 서비스나 공개 배포로 확장하기 전에는 별도의 지원 경로와 이용 조건을 확인해야 한다.

로그인 취소 후의 실제 에뮬레이터 화면은 로컬의 `docs/direct-login-preview.png`에서 확인할 수 있다. 화면 캡처는 공개 저장소에서 제외한다.

## 갤럭시 S23에서 시험하는 방법

1. `app/build/outputs/apk/debug/app-debug.apk`를 휴대폰으로 옮겨 설치한다. 이전 개발 APK와 서명이 같으면 데이터 유지 업데이트가 가능하다.
2. 홈에서 **Codex 로그인**을 누른다. 앱에 일회용 코드가 표시된다.
3. **공식 로그인 페이지 열기**를 누르고 `https://auth.openai.com/codex/device`에서 코드를 입력한다. 로그인 화면에는 Codex 인증으로 표시될 수 있다. 비밀번호·인증 코드는 공식 페이지에서만 입력한다.
4. 기기 코드 로그인이 비활성화되어 있다는 안내가 나오면 ChatGPT 보안 설정 또는 소속 워크스페이스 권한에서 사용 가능 여부를 확인한다.
5. 15분 이내에 완료하고 앱으로 돌아온다. 인증이 성공하면 앱에서 직접 사용량을 요청한다. 휴대폰이 앱을 종료했거나 화면 회전으로 대기가 취소된 경우 새 로그인을 시작한다.
6. PC를 끄고 모바일 데이터에서도 **새로고침**을 눌러 확인한다. 잔여율·초기화 시각을 공식 사용량 화면과 비교한다. 5시간 값이 응답에 없으면 `정보 없음`이 정상이다.
7. 설정에서 알림 권한과 사용량 표시를 켠다. 알림은 마지막 조회값을 표시한다. 앱을 보고 있을 때만 60초마다 조회하며 백그라운드 네트워크 갱신은 아직 구현하지 않았다.
8. **연결 해제**를 누르면 기기의 인증 정보, 자동 조회값, 자동 조회에 따른 알림을 지운다. 수동 기록과 알림 스위치 설정은 유지한다. 서버의 인증 폐기 성공 여부는 화면에 안내한다.

## 통신과 저장

- 코드 발급: `POST https://auth.openai.com/api/accounts/deviceauth/usercode`.
- 로그인 승인 확인: `POST https://auth.openai.com/api/accounts/deviceauth/token`. 서버 간격을 따르되 최소 5초, 최대 60초이며 전체 대기시간은 15분이다. 이 프로토콜의 403/404는 대기 중 응답으로 처리한다.
- 코드 교환·갱신: `POST https://auth.openai.com/oauth/token`. 코드 교환에는 PKCE verifier와 지정된 기기 인증 callback을 사용한다. 갱신은 하나씩 수행하며 회전된 refresh token을 원자적으로 저장한다.
- 잔여량: `GET https://chatgpt.com/backend-api/wham/usage`. 사용량 조회만 수행하며 모델 추론, 결제, 크레딧 소비·초기화 요청은 하지 않는다.
- 연결 해제: `POST https://auth.openai.com/oauth/revoke`. 실패해도 로컬 삭제를 완료하고 서버 폐기 미확인을 안내한다.
- 공개 Codex 클라이언트 식별자 `app_EMoamEEZ73f0CkXaXp7hrann`은 비밀 키가 아니다. 별도 API 키·client secret·PC의 auth.json은 사용하지 않는다.
- access/refresh token은 Android Keystore의 AES-GCM 키로 암호화해 앱의 `noBackupFilesDir/codex-direct.enc`에 저장한다. 비밀번호, 브라우저 쿠키, 원본 ID token은 저장하지 않는다. 인증 정보를 SharedPreferences, UI 저장 상태, 로그에 기록하지 않는다.
- 토큰 내 account/expiry 필드는 고정된 HTTPS 토큰 응답에서만 읽어 요청 전달과 갱신 시점에 사용한다. 이 파싱은 JWT 서명 검증이나 사용자 신원 증명이 아니다. 실제 토큰 유효성과 사용량 접근 권한은 OpenAI 사용량 서버가 검증한다.
- HTTP 리다이렉트를 따르지 않으며 서버 오류 본문을 UI나 로그에 노출하지 않는다. 네트워크 타임아웃과 응답 크기 상한을 둔다.
- 기존 PC 조회값은 새로운 직접 조회값으로 표시하지 않는다. 계정을 바꾸려면 연결 해제 후 다시 로그인한다.

## 재현 가능한 검증

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

- `DirectCodexApiTest`: 서버 응답 해석, 주간/5시간 누락·0%, 추가 한도, 코드 교환·갱신·폐기 요청, 오류 비밀정보 비노출을 JVM에서 검증한다. 테스트에만 `org.json:json:20240303`을 사용하며 앱의 새 런타임 의존성은 없다.
- `DirectStorageChecks`: 별도의 `codex-storage-check` 키와 가상 토큰으로 Android Keystore 암호화·재읽기·회전 저장·변조 거부·삭제를 확인한다. 실제 계정은 사용하지 않는다. 이 검사는 일반 앱과 별도 테스트 APK에만 포함된다.
- `companion/probe-direct-login.mjs`: PC의 Codex 프로세스나 기존 인증 파일 없이 같은 HTTPS 흐름을 확인하는 선택적 개발 도구다. 앱 실행에는 필요 없다. 인증은 메모리에만 유지하고 조회 후 시험 세션 폐기를 시도한다. 사용자 승인 없는 대기 요청은 15분 후 만료한다.

## 근거

- [공식 Codex 인증 안내](https://learn.chatgpt.com/docs/auth): 기기 코드 로그인, 사용자 보안 설정, 구독 인증 구분.
- [공식 App Server 문서](https://learn.chatgpt.com/docs/app-server): 로컬 앱 인증 및 잔여량 조회 기능. 이 문서가 Android용 직접 HTTP API를 보장하는 것은 아니다.
- [공식 SIWC 사용량 추적 안내](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions): 공개 로그인 문서에서는 사용량 확인을 ChatGPT 설정 화면에 연결하도록 안내한다.
- OpenAI Codex 소스 확인 기준 커밋: `6fe77fc12346591b8037a8704640e40196b19044`.
  - [기기 코드 프로토콜](https://github.com/openai/codex/blob/6fe77fc12346591b8037a8704640e40196b19044/codex-rs/login/src/device_code_auth.rs)
  - [클라이언트 식별자](https://github.com/openai/codex/blob/6fe77fc12346591b8037a8704640e40196b19044/codex-rs/login/src/auth/manager.rs)
  - [잔여량 경로](https://github.com/openai/codex/blob/6fe77fc12346591b8037a8704640e40196b19044/codex-rs/backend-client/src/client/rate_limit_resets.rs)
  - [잔여량 응답 변환](https://github.com/openai/codex/blob/6fe77fc12346591b8037a8704640e40196b19044/codex-rs/backend-client/src/client.rs)
  - [인증 폐기](https://github.com/openai/codex/blob/6fe77fc12346591b8037a8704640e40196b19044/codex-rs/login/src/auth/revoke.rs)
