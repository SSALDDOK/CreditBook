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

## 라우트와 인증

라우터는 react-router v7(declarative 모드 — `BrowserRouter`·`Routes`·`Route`)이다. 라우트 표는 `src/App.tsx` 한 곳에 있다.

| 경로 | 화면 | 로그인 |
|---|---|---|
| `/login` | SCR-1 로그인 | 공개 |
| `/customers` | SCR-3 고객 목록 | 필요 |
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

### CI

`.github/workflows/ci.yml`의 `e2e` job이 일회용 PostgreSQL(`postgres:18-alpine`) → 백엔드 jar(`local-docker` 프로필) → 직원 시드 → `npx playwright test`를 headless로 돌린다. DB 비밀번호·JWT 서명 키·직원 비밀번호는 실행마다 새로 만들고 로그에서 가린다. 실패하면 Playwright 리포트·trace와 백엔드 로그를 artifact로 올린다.

## 구조

```
src/
├─ api/                 API 호출·요청/응답 타입, 목 데이터(api/mock)
├─ components/ui/       shadcn/ui 컴포넌트
├─ components/layout/   공통 레이아웃(사이드바)
├─ features/auth/       로그인 화면(SCR-1)·인증 상태(AuthProvider)
├─ features/customers/  고객 목록 화면(SCR-3)
├─ routes/              보호 라우트(RequireAuth)·로그인 후 이동 경로 검사 — 라우트 표는 App.tsx
└─ lib/                 표시 형식(금액·날짜) 등 공용 함수
e2e/                    Playwright 테스트(사용자 작성), support/ 계정 읽기
```
