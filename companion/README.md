# Codex 자동 사용량 조회 검증 도구

## Android 개발용 연결

```powershell
node companion/server.mjs
# 다른 터미널에서, 연결된 기기 ID를 지정한다.
adb -s emulator-5554 reverse tcp:18745 tcp:18745
adb -s emulator-5554 shell am start -n com.tokenpilot.app/.MainActivity
```

서버 터미널은 실행 상태로 유지한다. Ctrl+C로 종료한다. USB 휴대폰은 USB 디버깅을 승인하고 `adb devices`의 해당 ID로 바꾼다. 기기를 다시 연결하면 reverse 명령을 다시 실행한다.

서버는 PC의 127.0.0.1:18745에만 바인딩한다. Android 디버그 앱만 loopback HTTP를 허용한다. 무선 연결이나 정식 배포용 연결은 지원하지 않는다. 로컬 PC의 다른 프로세스도 이 포트에 접근할 수 있으므로 계정 정보나 인증 토큰은 응답에 포함하지 않는다.

앱 화면이 활성화된 동안 60초 간격으로 조회한다. 서버는 55초 캐시와 진행 중 요청 공유를 사용한다. 실패 시 503을 반환하고 앱은 이전 값을 오래된 정보로 표시한다. 이전 값은 화면 메모리에만 보존되며 앱을 종료하면 사라진다. 저장된 수동 기록에는 영향을 주지 않는다.

서버 테스트: `node --test companion/*.test.mjs`

프로젝트 루트에서 실행한다. Node.js와 로그인된 Codex CLI가 PATH에 있어야 한다.

```powershell
node companion/codex-usage.mjs
node --test companion/codex-usage.test.mjs
```

Codex app-server의 stdio 연결에서 initialize, initialized, account/rateLimits/read 순으로 통신한다. 사용량을 JSON으로 한 번 출력한 뒤 자체 실행한 프로세스를 종료한다. 외부 포트를 열지 않으며 Android 연결 서버는 아직 제공하지 않는다.

- remainingPercent: 남은 비율. 알 수 없으면 null이다.
- windowMinutes: 한도 구간 길이(분).
- resetsAt: Unix 초. Android 밀리초로 바꿀 때 1000을 곱해야 한다.
- checkedAt: 조회 성공 시각(UTC).
- bucketId: 복수 한도가 생겨도 구분하기 위한 식별자.

인증 파일을 직접 읽거나 복사하지 않는다. 인증은 Codex에 맡긴다. 응답 전체를 저장하지 않고 필요한 한도 필드만 출력한다. 조회 실패를 잔여량 0이나 100으로 바꾸지 않는다. 30초 제한과 프로세스 정리를 적용했다.

공식 근거: https://learn.chatgpt.com/docs/app-server

현재 확인 버전: codex-cli 0.158.0-alpha.2.1. 실험적 CLI 인터페이스이므로 업데이트 후 호환성을 재검증해야 한다.
