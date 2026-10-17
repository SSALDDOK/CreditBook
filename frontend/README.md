# CreditBook Frontend

선결제 잔액 장부의 웹 화면. React + Vite + TypeScript + Tailwind CSS + shadcn/ui.

잔액은 서버가 계산한다. 화면은 서버 응답 값을 표시만 한다.

## 요구 사항

- Node.js 22 (22.13 이상 권장 — 22.12에는 경로에 한글이 있으면 `.js` 실행이 비정상 종료되는 문제가 있다)
- npm

## 실행

```bash
cd frontend
npm ci
npm run dev        # http://localhost:5173
```

개발 서버는 `/api` 요청을 `http://localhost:8080`(백엔드)으로 넘긴다.

## 목 데이터 / 실제 API

개발 서버(`npm run dev`)는 기본으로 목 데이터를, 운영 빌드(`npm run build`)는 실제 API를 쓴다. 바꾸려면 `.env.example`을 `.env.local`로 복사하고 `VITE_USE_MOCK`을 `true`/`false`로 지정한다.
전환 지점은 `src/api/config.ts` 한 곳이다.

목 모드의 로그인(`src/api/mock/auth.ts`): 처음에는 로그인된 상태(김직원, STAFF)로 시작해 고객 목록이 바로 보인다. 로그아웃하면 로그인 화면으로 가고, 아이디·비밀번호를 아무 값이나 넣으면 로그인된다. 비밀번호를 `wrong`으로 넣으면 로그인 실패 화면을 볼 수 있다. 로그인 상태는 메모리에만 있어 새로고침하면 처음 상태로 돌아간다.

목 모드의 고객(`src/api/mock/customers.ts`): 상세 화면 상태별 확인용 고객이 있다.

| 고객 | id | 상태 |
|---|---|---|
| 박서연 | `00000000-0000-4000-8000-000000000001` | 활성, 잔액 95,500원, 거래 10건(거래 이력 화면설계 이미지와 같은 장부 — 충전 취소·사용 취소·'취소됨' 포함) |
| 이준호 | `00000000-0000-4000-8000-000000000002` | 활성, 잔액 0원 — [사용] 비활성 + "잔액이 없습니다". 10-15 충전 30,000원을 취소하면 409 `CHARGE_ALREADY_USED`(현재 잔액 0원 · 충전 30,000원) |
| 정하은 | `00000000-0000-4000-8000-000000000005` | 활성, 잔액 128,000원, 거래 28건 — 거래 이력 페이지 나눔 확인용 |
| 오지훈 | `00000000-0000-4000-8000-000000000051` | 비활성, 잔액 0원 — 목록에서는 '비활성 포함'을 켜야 보인다 |
| 한지민 | `00000000-0000-4000-8000-000000000052` | 활성, 잔액 0원, 거래 0건 — 최근 거래·거래 이력 빈 안내 |

고객 상세의 최근 거래와 거래 이력(`src/api/mock/transactions.ts`)은 고객마다 하나인 장부를 같이 본다(상세는 앞 5건). 없는 UUID는 404(`CUSTOMER_NOT_FOUND`), UUID 형식이 아닌 id는 400(`INVALID_INPUT`)을 돌려준다.

목 모드의 취소(`src/api/mock/cancels.ts`, 거래 이력의 [취소]): 사용 취소·충전 취소가 성공하면 장부 맨 앞에 반제 행(메모 = 사유)이 생기고 원거래가 '취소됨'이 된다. 거절은 실제 규칙(이미 취소됨 → 409 `ALREADY_CANCELLED`, 잔액보다 큰 충전 취소 → 409 `CHARGE_ALREADY_USED`, 빈 사유 → 400 `INVALID_INPUT`)으로 나고, 목 장부의 날짜는 고정이라 '당일 충전'은 장부에 적힌 `cancellable` 값으로 대신한다. 그 밖의 오류 흐름은 취소 사유를 아래 낱말 그대로 넣으면 볼 수 있다(충전·사용 목의 특수 금액과 같은 역할 — `mock/charges.ts`·`mock/uses.ts` 머리말).

| 취소 사유 | 응답 |
|---|---|
| `응답끊김` | 처음 한 번은 처리됐는데 응답만 끊긴 것처럼 네트워크 오류. 같은 창에서 [취소하기]를 다시 누르면 같은 요청 키라 취소 거래가 하나만 생긴다 |
| `먼저취소` | 다른 기기에서 먼저 취소된 경우 — '박사장'의 취소를 먼저 기록한 뒤 409 `ALREADY_CANCELLED` |
| `세션만료` | 401 — 로그인 화면으로 간다(사유는 복구하지 않는다) |
| `동시수정` | 409 `CONCURRENT_MODIFICATION` |
| `당일아님` | (충전 취소) 409 `CHARGE_CANCEL_NOT_SAME_DAY` |
| `대상오류` | 400 `INVALID_CANCEL_TARGET` |
| `사유오류` | 400 `INVALID_INPUT` (`fieldErrors.reason`) |
| `키재사용` | 422 `IDEMPOTENCY_KEY_REUSED` |
| `서버오류` | 500 `INTERNAL_ERROR` |

## 라우트와 인증

라우터는 react-router v7(declarative 모드 — `BrowserRouter`·`Routes`·`Route`)이다. 라우트 표는 `src/App.tsx` 한 곳에 있다.

| 경로 | 화면 | 로그인 |
|---|---|---|
| `/login` | SCR-1 로그인 | 공개 |
| `/customers` | SCR-3 고객 목록 | 필요 |
| `/customers/:customerId` | SCR-5 고객 상세 | 필요 |
| `/customers/:customerId/transactions` | SCR-8 거래 이력 | 필요 |
| 그 밖 (`/` 포함) | `/customers`로 이동 | — |

로그인이 필요한 화면은 `RequireAuth` 레이아웃 라우트(`<Route element={<RequireAuth />}>`)의 자식으로 추가한다. 경로 파라미터는 `path="/customers/:customerId"` + `useParams()`로 쓴다.

- 로그인 상태는 `GET /api/auth/session`으로만 확인한다. 세션 토큰은 서버가 httpOnly 쿠키(`CB_SESSION`)로 주고받고, 화면은 읽거나 저장하지 않는다
- 로그인하지 않고 보호 화면에 들어오면 `/login?next=<원래 경로>`로, 보호 API가 401을 주면 `/login?next=<보던 경로>&reason=expired`로 보낸다(만료 안내 표시). 로그인하면 `next`로 돌아가고, 없으면 `/customers`
- `next`는 같은 출처의 앱 내부 경로만 받는다. `//evil.com`, `http://…` 같은 값은 무시하고 `/customers`로 보낸다(`src/routes/redirect.ts`)
- 로그아웃은 서버가 쿠키를 지운 뒤 로그인 화면으로 replace 이동한다. 뒤로가기로 보호 화면에 오면 다시 로그인 화면으로 간다. 뒤로가기 캐시(bfcache)에서 페이지가 되살아나면 화면을 가리고 세션을 다시 확인한다
- `Secure` 쿠키라 http 에서는 `localhost`로 접속해야 로그인이 유지된다(`127.0.0.1`이나 사설 IP로 접속하면 쿠키가 저장되지 않는다)

## 검사

```bash
npm run build      # 타입 검사(tsc -b) + 프로덕션 빌드
npm run lint       # oxlint
```

## E2E (Playwright)

실제 백엔드에 붙어 도는 E2E. 브라우저는 chromium 하나다. 테스트는 `e2e/` 아래에 `*.spec.ts`로 둔다.

`npx playwright test`를 실행하면 `playwright.config.ts`가 운영 빌드(`npm run build`, 실제 API)를 만들고 `vite preview`(http://localhost:4173, `/api` → http://localhost:8080)를 띄운 뒤 테스트를 돌린다. 백엔드와 DB는 미리 띄워 둔다.

### 로컬 실행

1. 리포 루트에서 `docker compose up -d` → `backend/`에서 `./gradlew bootRun` (기본 프로필 `local-docker` — Neon 프로필 `local`로 돌리지 않는다)
2. 처음 한 번 직원 시드: `backend/dev/seed-local.sql` 머리말의 명령. ADMIN `owner`, STAFF `staff`가 만들어진다
3. 시드할 때 넣은 비밀번호를 환경변수로 넘겨 실행한다 (PowerShell)
   ```powershell
   cd frontend
   npx playwright install chromium        # 처음 한 번
   $env:E2E_STAFF_PASSWORD='<staff 비밀번호>'; $env:E2E_ADMIN_PASSWORD='<owner 비밀번호>'
   npm run e2e                             # 전체
   npm run e2e -- --grep "@TC-19"          # TC-19 태그가 붙은 테스트만
   npm run e2e:report                      # 마지막 실행 HTML 리포트
   ```

| 환경변수 | 기본값 | 뜻 |
|---|---|---|
| `E2E_STAFF_PASSWORD` / `E2E_ADMIN_PASSWORD` | 없음(필수) | 시드 계정 비밀번호. 리포에 쓰지 않는다 |
| `E2E_STAFF_LOGIN_ID` / `E2E_ADMIN_LOGIN_ID` | `staff` / `owner` | 로그인 아이디 |
| `E2E_BASE_URL` | 없음 | 주면 preview 를 띄우지 않고 그 주소에 붙는다 |

계정은 `e2e/support/credentials.ts`의 `staffAccount()`·`adminAccount()`로 읽는다.

### TC 태그

테스트 제목에 `@TC-n`을 넣는다(JUnit `@Tag("TC-n")`과 짝). 예: `test('로그아웃 후 뒤로가기로 고객 목록이 보이지 않는다 @TC-19', …)` → `--grep "@TC-19"`로 고른다.

### data-testid

역할(`getByRole`)·라벨(`getByLabel`)로 찾기 어려운 요소와 핵심 요소에 `화면-요소` 형태의 영문 kebab-case `data-testid`를 붙인다. 이름을 바꾸면 PR에 적는다.

| 화면 | testid | 요소 |
|---|---|---|
| 공통 | `page-title` | 머리글 제목 (상세는 "고객 › 상세") |
| 공통 | `auth-checking` | 로그인 확인 중 표시 — 사라지면 보호 화면이 그려진다 |
| SCR-3 목록 | `customer-search-input` | 검색창 |
| SCR-3 목록 | `customer-include-inactive` | '비활성 포함' 체크박스 (`getByLabel('비활성 포함')`으로도 찾는다) |
| SCR-3 목록 | `customer-list` | 목록 영역. `data-state`가 `loading` → `ready`/`error` |
| SCR-3 목록 | `customer-row` | 고객 행 (`data-customer-id`, `data-active`) — 누르면 상세로 이동 |
| SCR-3 목록 | `customer-name` | 행의 이름 링크 (상세로 이동) |
| SCR-3 목록 | `customer-inactive-badge` | 비활성 행의 '비활성' 배지 |
| SCR-3 목록 | `customer-phone` / `customer-balance` / `customer-created-at` | 행의 연락처·잔액·등록일 |
| SCR-3 목록 | `customer-empty` | 0건 안내 |
| SCR-3 목록 | `customer-include-inactive-hint` | 토글이 꺼진 채 검색 0건일 때 힌트 한 줄 |
| SCR-3 목록 | `customer-total` / `page-prev` / `page-next` | 건수·페이지 이동 |
| SCR-5 상세 | `customer-detail` | 화면 영역. `data-state`가 `loading` → `ready`/`not-found`/`error` |
| SCR-5 상세 | `customer-detail-name` / `customer-detail-phone` | 이름·마스킹 연락처 |
| SCR-5 상세 | `customer-detail-inactive-badge` | '비활성' 배지 |
| SCR-5 상세 | `customer-inactive-notice` | 비활성 고객 안내 배너 |
| SCR-5 상세 | `customer-detail-balance` | 현재 잔액 (`data-balance`에 서버 값) |
| SCR-5 상세 | `customer-last-entry` | "마지막 거래 … · 직원" 줄 (거래 0건이면 없음) |
| SCR-5 상세 | `customer-charge-button` / `customer-use-button` | [충전]·[사용] (`getByRole('button', { name: '사용' })`로도 찾는다) |
| SCR-5 상세 | `customer-use-hint` | 잔액 0원일 때 "잔액이 없습니다" |
| SCR-5 상세 | `recent-entries` / `recent-entry-row` | 최근 거래 목록·행 (`data-entry-id`, `data-entry-type`, `data-cancelled`) |
| SCR-5 상세 | `recent-entry-title` / `recent-entry-time` / `recent-entry-amount` / `recent-entry-balance-after` | 행의 유형·메모, 시각, 부호 붙은 금액, 변동 후 잔액 |
| SCR-5 상세 | `recent-entries-empty` | 거래 0건 안내 |
| SCR-5 상세 | `customer-not-found` / `customer-detail-back` | 404 안내·목록으로 돌아가기 |
| SCR-5 상세 | `error-message` / `customer-detail-retry` | 그 밖의 오류 문구·다시 시도 |
| SCR-8 이력 | `transaction-row` | 거래 행 (`data-entry-id`, `data-type`, `data-cancelled`, `data-cancellable`) |
| SCR-8 이력 | `transaction-cancel-button` | 행의 [취소] (`data-kind`=`use`/`charge`). 서버 `cancellable`이면 활성, `cancelled`면 비활성, 그 밖은 없음 |
| SCR-8 취소 창 | `cancel-dialog` | 취소 사유 창 (`getByRole('dialog', { name: '사용 취소' })`·`'충전 취소'`). `data-kind`=`use`/`charge`, `data-state`=`idle`/`pending`/`rejected`/`already-cancelled` |
| SCR-8 취소 창 | `cancel-target` / `cancel-target-time` / `cancel-target-amount` | 원거래 상자·거래 일시·금액(`data-amount`) |
| SCR-8 취소 창 | `cancel-balance-preview` | 잔액 변화 상자(표시 전용 예상값, `data-balance`·`data-expected-balance`) |
| SCR-8 취소 창 | `cancel-reason-input` / `cancel-reason-count` / `cancel-reason-error` | 사유 입력(`getByLabel('취소 사유')`)·글자 수·사유 오류 |
| SCR-8 취소 창 | `cancel-confirm` / `cancel-close` | [취소하기]·[닫기] |
| SCR-8 취소 창 | `cancel-error` / `cancel-error-detail` | 거절 상자·둘째 줄(잔액 부족 details) |
| SCR-8 취소 창 | `cancel-already-cancelled` / `cancel-retry-error` / `cancel-note` | 이미 취소됨 안내·다시 보낼 수 있는 오류(네트워크·5xx·409 동시 처리·422)·아래 안내 문구 |

### CI

`.github/workflows/ci.yml`의 `e2e` job이 일회용 PostgreSQL(`postgres:18-alpine`) → 백엔드 jar(`local-docker` 프로필) → 직원 시드 → `npx playwright test`를 headless로 돌린다. DB 비밀번호·JWT 서명 키·직원 비밀번호는 실행마다 새로 만들고 로그에서 가린다. 실패하면 Playwright 리포트·trace와 백엔드 로그를 artifact로 올린다.

## 구조

```
src/
├─ api/                 API 호출·요청/응답 타입, 목 데이터(api/mock)
├─ components/ui/       shadcn/ui 컴포넌트
├─ components/layout/   공통 레이아웃(사이드바)
├─ features/auth/       로그인 화면(SCR-1)·인증 상태(AuthProvider)
├─ features/customers/  고객 목록(SCR-3)·고객 상세(SCR-5) 화면
├─ features/ledger/     거래 유형 표시 문구·거래 행(화면 간 공용)
├─ routes/              보호 라우트(RequireAuth)·로그인 후 이동 경로 검사 — 라우트 표는 App.tsx
└─ lib/                 표시 형식(금액·날짜) 등 공용 함수
e2e/                    Playwright 테스트(사용자 작성), support/ 계정 읽기
```
