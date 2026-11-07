# 보안 점검 (OWASP 기반, REQ-27)

이미 만든 API를 공격자 입장에서 다시 두드려 보는 점검이다. 결과는 "통과"만 적지 않고 **보낸 요청과 받은 응답**을 함께 남긴다.

- 1부: 다시 쓸 수 있는 점검 방법 — 항목마다 무엇을 보내고, 무엇이 오면 통과인지
- 2부: 점검 기록 — 언제, 무엇을 보냈고 무엇을 받았는지

## 1부. 점검 방법

### 준비 — 화면을 거치지 않고 요청 보내기

화면에 버튼이 없어도 서버 주소로 직접 요청을 보낼 수 있다. 이 점검은 그 요청을 서버가 막는지 본다. 도구는 무엇이든 같다.

| 도구 | 로그인 상태 | 쓰임 |
|---|---|---|
| 브라우저 개발자도구 Console + Network | 화면에서 로그인하면 쿠키가 자동으로 붙는다 | 한두 건 빠르게 확인, Network 탭 캡처를 버그 리포트에 첨부 |
| Postman | 로그인 요청을 한 번 보내면 쿠키가 유지된다 | 점검 요청을 컬렉션으로 저장해 반복 실행 |
| curl | 쿠키 파일을 직접 다룬다(`-c` 저장, `-b` 보냄) | 스크립트로 여러 건을 한 번에 실행·기록 |

브라우저 콘솔 예 — 화면에 STAFF로 로그인한 상태에서:

```js
// 1번 항목: ADMIN 전용 API → 403 이어야 한다
fetch('/api/customers/<고객ID>/deactivate', { method: 'PATCH' }).then(r => r.status)

// + 항목: 충전에 balance 를 끼워 넣는다 → balanceAfter 가 기존 잔액 + 1000 이어야 한다
fetch('/api/customers/<고객ID>/charges', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
  body: JSON.stringify({ amount: 1000, balance: 999999 })
}).then(r => r.json())
```

curl 예 — 로그인 토큰은 HttpOnly 쿠키(`CB_SESSION`)로 오므로 쿠키 파일로 로그인 상태를 이어 간다.

```bash
B=http://localhost:8080
# 로그인 → 쿠키를 staff.jar 에 저장 (비밀번호는 환경변수로만)
curl -s -c staff.jar -X POST $B/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"loginId\":\"staff\",\"password\":\"$E2E_STAFF_PASSWORD\"}"

# 이후 요청은 -b staff.jar 로 쿠키를 보낸다. -w 로 상태 코드를 끝에 붙여 본다
curl -s -b staff.jar $B/api/auth/session -w ' [%{http_code}]'
```

- 한글이 든 본문은 Windows에서 인자로 넘기면 깨진다 → 파일에 저장하고 `--data-binary @body.json`으로 보낸다
- 충전·사용·취소는 `Idempotency-Key` 헤더가 필수다. 요청마다 새 UUID를 넣는다
- 손으로 만든 거래는 장부에 영구히 남으므로 로컬 Docker DB에서만 한다

### 점검 항목

| # | 항목 | 보내는 요청 | 통과 기준 | 실패 신호 |
|---|---|---|---|---|
| 1 | 권한 우회(수직) | 낮은 권한(STAFF) 쿠키로 높은 권한(ADMIN) 전용 API 호출 — `PATCH /api/customers/{id}/deactivate` | 403, 데이터 변화 없음 | 200·204, 실제로 비활성화됨 |
| 2 | 객체 참조 우회(IDOR) | A 고객 경로에 B 고객의 거래 ID — `POST /api/customers/{A}/charges/{B의 충전 ID}/cancel` | 4xx 거절, B 잔액 그대로 | 2xx, B의 거래가 취소되거나 내용이 보임 |
| 3 | 입력값 검증 | 이름·메모·검색어에 `' OR '1'='1`, `'; DROP TABLE x;--`, `<script>alert(1)</script>`, `{{7*7}}` / 길이 한도 +1 | 넣은 글자 그대로 저장·응답, 검색 결과가 늘지 않음, 한도 초과는 400 | 500, 결과가 전부 나옴, `49`로 바뀜, 화면에서 스크립트 실행 |
| 4 | 응답 과다 노출 | 조회 API 응답에서 `password`·`$2a$`·`secret`·`token`·`version`·연락처 원문(`01[0-9]{8,9}`) 검색 | 0건 | 하나라도 나옴 |
| 5 | 오류 응답 | 깨진 JSON, 타입 오류(`"amount":"abc"`), 거대한 수(`1e400`), UUID 아닌 ID, 없는 경로, 안 되는 메서드, 틀린 Content-Type, `//`·`..` 경로 | 정해진 형식(`code`·`message`), 내부 정보 없음 | `Exception`, `at com.`, `SELECT`, 파일 경로, 다른 형식의 본문 |
| 6 | 세션 종료 | 로그아웃 전 쿠키를 복사해 두고 → 로그아웃 → 복사한 쿠키로 조회·충전 | 401 | 200·201 (토큰이 계속 유효) |
| 7 | 개인정보 마스킹 | 응답의 연락처 형식, 서버 로그에서 방금 쓴 연락처·검색어 검색 | 응답은 `010-****-1234`, 로그 0건 | 원문 노출 |
| 8 | 전송 구간 | 다른 출처(`Origin: https://evil.example`)로 preflight(`OPTIONS`) / 배포 주소에 `http://` 접속 | 허용 헤더(`Access-Control-Allow-Origin`) 없음 / HTTPS로 넘어감 | 허용 헤더가 그 출처나 `*` |
| + | 속성 수준 권한(mass assignment, OWASP API3) | 요청 본문에 없는 필드 끼워 넣기 — 충전에 `"balance":999999`, 등록에 `"active":false` | 끼운 값이 반영되지 않음 | 잔액·상태가 끼운 값으로 바뀜 |

## 2부. 점검 기록

### 2026-10-29 — 로컬(Docker DB, `local-docker` 프로필)

| # | 보낸 요청 → 받은 응답 | 판정 |
|---|---|---|
| 1 | STAFF가 `PATCH /customers/{A}/deactivate` → 403 `FORBIDDEN` | 통과 |
| 2 | A 경로에 B의 충전·사용 건으로 취소 → 400 `INVALID_CANCEL_TARGET`("다른 계좌의 거래는 취소할 수 없습니다."), B 잔액 9,000원 그대로. 거래 단건 조회 경로는 없음(404) | 통과 — 400 거절도 차단으로 본다(한 매장 직원이 모든 고객을 보는 구조). 통합 테스트로도 검증됨 |
| 3 | 페이로드 4종을 이름·메모에 → 201, 넣은 글자 그대로 저장·응답. 검색어 `' OR '1'='1` → 그 이름 1건만. 이름 1,000,000자 → 400, 메모 200자 201·201자 400 | 서버 통과. 화면 표시는 탐색적 테스트에서 확인 |
| 4 | 세션·고객 목록·상세·거래 이력·입력 설정 응답에서 금지 키워드 0건 | 통과 |
| 5 | 7가지 잘못된 요청 → 모두 `code`·`message` 형식, 내부 정보 0건. `//`·`..`가 든 주소만 프레임워크 기본 형식(`timestamp`·`status`·`error`·`path`)의 400 | 내부 정보 없음. 형식 불일치는 버그로 등록 |
| 6 | 로그아웃 204 → 이전 쿠키로 목록·충전 401. API 응답에 `Cache-Control: no-store` | 서버 통과. 브라우저 뒤로가기는 탐색적 테스트에서 확인 |
| 7 | 응답 연락처 `010-****-0270`. 서버 로그에 이번 점검 연락처·검색어 0건, 잔액 변동 로그는 계좌 ID·금액만 | 통과 |
| 8 | 다른 출처 preflight → 허용 헤더 없음. 허용 출처에 `*`를 넣으면 기동 실패 | 로컬 통과. HTTPS 강제·접근 로그의 검색어 기록은 배포 후 재확인 |
| + | 충전에 `balance`·다른 고객 ID·`type:USE` → 201, 모두 무시되고 1,000원 충전. 등록에 `id`·`active:false`·`balance` → 무시 | 피해 없음(요청 객체에 해당 필드가 없음). 다만 시도가 정상 요청처럼 묻혀 기록이 남지 않아 아래처럼 바꿨다 |

**조치 — 받지 않는 항목은 거절하고 기록한다** (전체 API)
- 요청 본문에 받지 않는 항목이 있으면 처리하지 않고 400 `INVALID_INPUT`로 응답한다. 응답의 `fieldErrors`에 항목 이름이 들어간다
- 서버 로그에 WARN `unknown field rejected: method=… path=… field=…`로 남긴다. 끼운 값은 남기지 않고, 항목 이름도 영문·숫자·`_` 64자까지만 그대로 쓴다
- 바뀐 기대 결과: 처리 직원(`performedBy`)·처리 시각(`performedAt`)을 본문에 끼워 넣은 요청은 예전에는 무시하고 처리했지만(201), 이제 거절하고 아무것도 저장하지 않는다(400). 처리 직원·시각이 로그인한 직원과 서버 시계로만 정해진다는 규칙은 그대로다
- 확인: 충전에 `balance` → 400 `INVALID_INPUT`, 잔액 그대로, WARN 로그에 `field=balance`. 화면이 보내는 요청 6종은 서버 요청 항목과 일치하며, E2E 전체 통과

### 2026-11-07 — 배포 환경 (화면 Vercel · 백엔드 Render · DB Neon)

배포 주소는 이 문서에 적지 않는다. 화면은 `/api/*`를 백엔드로 넘기며 비밀 헤더(`X-Origin-Secret`)를 붙이고, 백엔드는 그 헤더가 없거나 다른 요청을 헬스 체크(`GET /actuator/health`)만 빼고 403으로 거절한다.

**1부 항목 재확인**

| # | 보낸 요청 → 받은 응답 | 판정 |
|---|---|---|
| 8 | 화면 주소에 `http://` → 308로 `https://`. 백엔드 주소에 `http://` → 301로 `https://` | 통과 |
| 8 | 다른 출처(`Origin: https://evil.example`) preflight → 화면 경유 401, 백엔드 직접 403. 둘 다 `Access-Control-Allow-*` 헤더 없음 | 통과 — 브라우저가 다른 출처 요청을 막는다 |
| 8 | 화면에서 가짜 검색어(`9999`)로 고객 검색 → 백엔드 로그에서 그 검색어 0건(인증·거절 로그도 `path=`에 경로만, 검색어 없음). 화면 배포처 대시보드 로그에는 그 요청 줄이 나타나지 않음 | 볼 수 있는 로그에는 검색어가 남지 않는다. 한계: 화면 배포처 플랫폼 내부의 요청 주소 보관 여부는 확인할 수 없어, 검색어를 본문으로 옮기는 API 변경은 지금 하지 않고 여러 매장 전환 때 다시 본다 |
| 5 | `//`가 든 주소 → 화면 배포처가 308로 정리된 주소(`/api/customers/charges`)로 돌려보내 백엔드에 닿지 않음. `..`가 든 주소(정리하지 않고 보냄) → 백엔드가 프레임워크 기본 형식 400(`timestamp`·`status`·`error`·`path`), 로그인 없이도 같음 | 내부 정보 없음. 형식 불일치는 로컬과 같다(BUG-5, 열린 상태) |
| + | 백엔드 주소로 직접 `GET /api/setup/status` → 403 `FORBIDDEN`, 화면 경유 → 200 `{"available":false}`(매장 개설 뒤) | 통과 — 직접 접근으로는 매장 개설·로그인 불가 |
| + | 로그인 IP 레이트 리밋(REQ-15): 화면 경유로 없는 아이디 로그인 11회 → 1–10회 401, 11회 429(`Retry-After`). 서버 로그 `login rate limited: ip=`가 화면 배포처가 아닌 실제 접속자 공인 IP | 통과 — 접속자마다 따로 센다 |

**TC-57 — 저장소·빌드 결과물·배포 설정에 DB 비밀번호·JWT 서명 키가 없다 (REQ-25)**

| 절차 | 확인한 것 → 결과 | 판정 |
|---|---|---|
| 1. 저장소 전체 이력 검색 | `git log -p --all -i -G "password\|secret\|jwt"`로 추가된 줄 중 값이 붙은 대입을 추림 → 31건 모두 테스트 고정값·변수명·코드. 자격 증명이 든 접속 문자열(`postgres://사용자:비밀번호@`) 0건. 원격(GitHub) 브랜치에 닿는 이력에 실제 비밀값 0건 | 통과 |
| 2. `.env`·`application-local.yml` | `.gitignore`에 있음(`git check-ignore`로 확인), 커밋 이력 0건 | 통과 |
| 3. `application.yml` | DB 비밀번호 항목 없음(배포처 환경변수 `SPRING_DATASOURCE_PASSWORD`). `jwt-secret`·`origin-guard.secret`은 환경변수 참조, 기본값 빈 값(JWT 키가 비면 기동 실패, 프록시 확인 값이 비면 확인 끔) | 통과 |
| 4. 빌드 결과물 | 백엔드 이미지의 jar(`BOOT-INF/classes` 186개 파일)와 화면 빌드(`dist` 10개 파일)에서 DB 호스트(`neon.tech`)·자격 증명 접속 문자열·BCrypt 해시·키 이름(`JWT_SECRET`·`ORIGIN_SECRET`·`x-origin-secret`) 검색 → 0건. jar 안 설정 파일은 `application.yml` 하나. 비밀 헤더는 화면 빌드가 아니라 배포처 경로 설정이 붙인다 | 통과 |
| 5. 배포처 설정 화면 | 백엔드: 환경변수 9개, DB 비밀번호 2개·JWT 키·프록시 확인 값은 가려진 값으로만 표시. 화면: `ORIGIN_SECRET` 1개를 민감 값으로 등록. 이미지 정의(`backend/Dockerfile`)와 저장소에 값 없음 | 통과 |
| 6. GitHub Actions | 저장된 Secrets를 쓰지 않는다 — E2E용 DB 비밀번호·JWT 키·직원 비밀번호를 실행마다 `openssl rand`로 만들고 `::add-mask::`로 가림, DB 비밀번호는 `POSTGRES_PASSWORD_FILE`로 넘겨 컨테이너 환경변수에 남지 않음. 최근 main CI 로그(1,526줄)에서 가려진 표시 12곳, 가려지지 않은 비밀값 0건 | 통과 |

판정: **TC-57 Pass** — 실제 비밀값은 배포처 환경변수에만 있다.
