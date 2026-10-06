---
name: react-patterns
description: "React 컴포넌트 패턴 — hooks 규칙, 상태 위치 결정, 데이터 가져오기, API 호출·오류 처리, 폼, 보호 라우트, 에러 바운더리, 렌더 성능. CreditBook은 Vite SPA라서 원본 ECC 스킬(react-patterns·frontend-patterns·error-handling)의 Next.js/RSC/Server Actions·애니메이션·Python/Go 예시는 빼고, 금액을 화면에서 계산하지 않는 규칙과 서버 ErrorResponse 처리 예시로 바꿨다. React components, hooks, state, data fetching, forms, error handling을 작성하거나 리뷰할 때 사용."
metadata:
  origin: ECC (react-patterns + frontend-patterns + error-handling, CreditBook 규칙으로 재작성)
---

# React 패턴 (CreditBook)

CreditBook 화면을 만들 때 쓰는 React 패턴. 이 프로젝트의 핵심 가치는 **잔액 정합성과 추적 가능성**이고, 화면은 그 가치를 흐리지 않는 쪽이다 — 잔액은 서버가 계산하고 화면은 서버 값을 정확히 보여 준다.

## 언제 쓰는가

- 화면 컴포넌트·커스텀 훅을 만들거나 고칠 때
- 상태를 어디에 둘지 정할 때
- API를 부르고 오류를 화면에 보여 줄 때
- 충전·사용·취소 같은 폼을 만들 때
- 로그인·보호 라우트를 만들 때

## 원칙 1 — 금액은 서버 값을 그대로 쓴다

원본 ECC 스킬은 "파생 값은 렌더 중에 계산하라"며 장바구니 합계를 `reduce`로 구하는 예를 든다. **CreditBook에서는 금액에 이 패턴을 쓰지 않는다.** 잔액·변동 후 잔액·부족액은 모두 서버 응답에 있다.

```tsx
// 금지: 화면에서 잔액을 계산 — 서버와 어긋나는 순간 장부를 믿을 수 없게 된다
setBalance(balance - amount);

// 금지: 금액 낙관적 갱신 — 서버가 거절해도 잠깐 틀린 잔액이 보인다
const [optimistic, addOptimistic] = useOptimistic(balance, (b, a: number) => b - a);

// 좋음: 서버 응답의 balanceAfter 를 표시하고, 목록은 다시 불러온다
const res = await createUse(customerId, { amount, memo });   // UsageResponse
setBalance(res.balanceAfter);
```

- 서버의 `BigDecimal`은 JSON 숫자로 온다(원 단위 정수). 받은 값은 **표시만** 한다 — 더하기·빼기·곱하기·`toFixed` 없음
- 표시용 포맷은 렌더 중 파생해도 된다: `new Intl.NumberFormat('ko-KR').format(balance) + '원'`
- 계산이 아닌 파생(필터·정렬·문자열 포맷)은 렌더 중에 하고 `useEffect`로 상태를 복사하지 않는다

## 원칙 2 — 업무 판단은 서버가 한다

잔액 부족, 취소 가능 여부(당일·이미 취소됨), 권한(ADMIN/STAFF)은 서버가 판단해 거절 응답(`code`)을 준다. 화면이 같은 판단을 먼저 해서 버튼을 숨기거나 막는 것은 **편의일 뿐 보안이 아니다** — 서버 거절을 반드시 함께 처리한다.

## Hooks 규칙

- 최상위에서만 호출, 조건문 안에서 호출하지 않는다
- 구독·타이머·리스너는 정리 함수로 해제한다
- 이전 상태에 의존하는 갱신은 함수형 업데이트(`setX(prev => ...)`)
- `useMemo`/`useCallback`/`React.memo`는 기본으로 쓰지 않는다 — 실제로 느린 것이 확인될 때만
- 같은 훅 묶음이 두 컴포넌트 이상에 나올 때만 커스텀 훅으로 뺀다

## 상태 위치 결정

```
한 컴포넌트만 쓴다            → 그 안의 useState
부모와 몇몇 자식이 쓴다       → 가장 가까운 공통 부모로 올린다
멀리 떨어진 곳에서 드물게 읽는다(로그인 직원·역할) → Context
서버에서 온 데이터(고객 목록·잔액·거래 이력)       → 서버 상태 라이브러리 또는 화면 단위 조회
```

- 전역 상태는 **인증 정보(직원·역할)** 정도로 최소화한다. 잔액을 전역 상태에 복사해 두지 않는다 — 복사본은 서버와 어긋난다
- 서버 상태 라이브러리(TanStack Query 등)·라우터·폼 라이브러리 **도입은 기술 설계라서 CB-32 착수 때 추천안으로 사용자 승인**을 받은 뒤 쓴다. 승인 전이면 아래 예시를 "패턴"으로만 참고한다

## 데이터 가져오기

| 필요 | 방법 |
|---|---|
| 목록·상세 조회 + 캐시 + 갱신 | 서버 상태 라이브러리(승인 시) |
| 충전·사용·취소 같은 변경 | 이벤트 핸들러에서 호출 → 성공 후 관련 조회를 다시 불러온다(invalidate) |
| 단순 일회성 호출 | 이벤트 핸들러의 `fetch` |

- 애플리케이션 데이터를 `useEffect` + `fetch`로 직접 다루면 경쟁 상태·캐시 없음·재시도 없음 문제가 생긴다. 라이브러리 없이 쓸 때는 정리 함수에서 `AbortController`로 이전 요청을 취소한다
- 고객 검색 입력은 디바운스(300ms 안팎)한 값으로 조회한다

## API 호출 계층 — `src/api`

모든 호출은 한 곳의 클라이언트를 거친다. 서버 오류 응답은 **평평한 형태**다(`ErrorResponse`, 백엔드 `global/error`가 확정본):

```json
{ "code": "INSUFFICIENT_BALANCE", "message": "잔액이 부족합니다.", "fieldErrors": [],
  "details": { "balance": 3000, "shortage": 1500 } }
```

```ts
// src/api/client.ts
export type FieldError = { field: string; message: string };

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,               // ErrorCode 이름 — 분기는 이 값으로만
    message: string,                      // 서버가 정한 사용자용 문구
    readonly fieldErrors: FieldError[] = [],
    readonly details?: unknown,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(`${import.meta.env.VITE_API_BASE_URL ?? ''}${path}`, {
    ...init,
    credentials: 'include',               // 인증 방식(httpOnly 쿠키 예정, CB-15)에 맞춘다
    headers: { 'Content-Type': 'application/json', ...init.headers },
  });
  if (res.ok) return (res.status === 204 ? undefined : await res.json()) as T;

  const body = await res.json().catch(() => null);
  throw new ApiError(
    res.status,
    body?.code ?? 'INTERNAL_ERROR',
    body?.message ?? '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
    body?.fieldErrors ?? [],
    body?.details,
  );
}
```

- 요청·응답 타입은 백엔드 DTO(`*Request`·`*Response`)와 필드 이름을 똑같이 맞춘다(`balanceAfter`, `maskedPhone`). 화면에 필요한 필드가 없으면 API를 바꾸지 말고 보고한다 — API 명세는 사용자 승인 사항이다
- 토큰을 직접 헤더에 붙이거나 저장하지 않는다(`localStorage`·`sessionStorage` 금지)

## 오류를 화면에 보여 주기

- **분기는 `code`로만 한다.** `message` 문구로 분기하지 않는다(문구는 바뀔 수 있다 — `ErrorCode` 주석 참조)
- 표시 문구 우선순위: ① 요청에 적힌 화면설계서 "오류 메시지 표준" ② 서버 `message`(사용자용 문구로 설계됨) ③ 5xx·응답 본문 없음이면 공통 문구. 스택·예외 이름은 서버가 보내지 않으며 화면도 만들지 않는다

| code | 화면 처리 |
|---|---|
| `INVALID_INPUT` | `fieldErrors`를 각 입력칸 아래에 연결(`aria-describedby`) |
| `INSUFFICIENT_BALANCE` | `details.balance`(현재 잔액)·`details.shortage`(부족액)를 **서버 값 그대로** 안내 — 부족액을 화면에서 빼서 구하지 않는다 |
| `CHARGE_LIMIT_EXCEEDED`·`INVALID_AMOUNT` | 금액 입력칸 오류로 표시 |
| `UNAUTHENTICATED` (401) | 로그인 화면으로 이동, 돌아올 경로 보존 |
| 403 | 권한 없음 안내(S4의 403 화면 전까지는 메시지) |
| `CONCURRENT_MODIFICATION` (409) | "다른 요청이 먼저 처리되었습니다" 안내 + 잔액·목록 다시 불러오기 |
| `INTERNAL_ERROR`·5xx | 공통 문구 |

```ts
// src/api/errorMessages.ts — 화면설계서 표준 문구가 정해지면 여기 채운다
const SCREEN_MESSAGES: Partial<Record<string, string>> = {};

export function toUserMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status >= 500) return '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
    return SCREEN_MESSAGES[e.code] ?? e.message;
  }
  return '네트워크 연결을 확인해 주세요.';
}
```

### 재시도 — 변경 요청은 자동으로 다시 보내지 않는다

- **조회(GET)** 만 네트워크 오류·5xx에서 자동 재시도할 수 있다(지수 백오프, 최대 2–3회)
- **충전·사용·취소(POST)는 자동 재시도 금지.** 첫 요청이 서버에서 처리됐는데 응답만 끊겼다면 재시도가 중복 차감이 된다. 멱등키(CB-34)가 확정된 뒤에도 재시도는 **같은 `Idempotency-Key`** 로만 보낸다
- 4xx는 재시도하지 않는다
- 모든 `catch`는 처리·다시 던지기·표시 중 하나를 한다. 오류를 삼키지 않는다

## 폼

- 입력값이 다른 UI를 움직이거나 즉시 검증이 필요하면 제어 컴포넌트로 만든다. 필드가 많고 교차 검증이 있으면 폼 라이브러리(도입 시 승인)
- 금액 입력은 **양의 정수 문자열**만 받는다. 소수점·`parseFloat` 없음. 1회 충전 상한 같은 정책값은 서버가 최종 판단하고, 화면 검증은 편의다

```tsx
const AMOUNT_PATTERN = /^[1-9]\d*$/;   // 원 단위 양의 정수

function validateAmount(raw: string): string | null {
  if (!AMOUNT_PATTERN.test(raw)) return '금액은 1원 이상의 정수로 입력해 주세요.';
  return null;                          // 상한·잔액 판단은 서버 응답으로
}
const amount = Number(raw);              // 정수 문자열만 통과했으므로 안전 (Number.MAX_SAFE_INTEGER 이하)
```

- 제출 중에는 버튼을 비활성화하고(`disabled={pending}`) 같은 요청이 두 번 나가지 않게 한다. 이것만으로 중복을 막았다고 보지 않는다 — 서버 멱등키가 최종 방어선이다
- 사유·메모는 서버 길이 제한(200자)에 맞춰 `maxLength`를 둔다

## 로그인·보호 라우트

```tsx
// 라우터는 CB-32에서 승인된 것을 쓴다. 아래는 패턴.
function RequireAuth({ children }: { children: React.ReactNode }) {
  const { status } = useAuth();           // 'checking' | 'authenticated' | 'anonymous'
  const location = useLocation();
  if (status === 'checking') return <PageSpinner data-testid="auth-checking" />;
  if (status === 'anonymous') return <Navigate to="/login" replace state={{ from: location }} />;
  return <>{children}</>;
}
```

- 인증 확인이 끝나기 전에는 보호 화면을 그리지 않는다(잠깐이라도 고객 정보가 보이지 않게)
- 로그아웃은 서버에 알리고 인증 상태를 비운 뒤 `replace`로 이동한다 — 뒤로가기로 보호 화면이 다시 보이면 안 된다(TC-19)
- 어느 API든 401이면 같은 처리(로그인 이동)로 모은다

## 에러 바운더리

렌더 중 예외로 화면 전체가 하얗게 되는 것을 막는다. 이벤트 핸들러·비동기 오류는 잡지 못하므로 위 `toUserMessage`로 따로 처리한다.

```tsx
export class ErrorBoundary extends React.Component<{ children: React.ReactNode }, { hasError: boolean }> {
  state = { hasError: false };
  static getDerivedStateFromError() { return { hasError: true }; }
  render() {
    if (this.state.hasError) {
      // 원본 예시처럼 error.message 를 화면에 출력하지 않는다
      return <p role="alert" data-testid="app-error">일시적인 오류가 발생했습니다. 새로고침해 주세요.</p>;
    }
    return this.props.children;
  }
}
```

## 컴포넌트 구성

- 상속 대신 조합(`children`, 이름 붙은 슬롯 props)
- 공통 UI는 shadcn/ui(`src/components/ui`)를 먼저 쓰고, 화면별 조합은 `src/features/<영역>`에 둔다
- 목록의 `key`는 서버 id(UUID). 배열 인덱스 금지
- 긴 목록 가상화는 고객 1,000명 성능 TC(TC-18, S5)에서 필요가 확인되면 도입한다

## 넣지 않은 것 (원본 대비)

- Next.js App Router·Server Components·Server Actions·`"use client"` — Vite SPA라 해당 없음
- `useOptimistic` 낙관적 UI — 금액에 쓰지 않는다(원칙 1)
- Framer Motion 애니메이션, Python·Go 오류 처리 예시

## 관련

- `vite-patterns` — 환경변수·개발 프록시·빌드
- `frontend-a11y` — 라벨·오류 연결·포커스(Playwright `getByRole`·`getByLabel`과 직결)
- `e2e-testing` — 사용자가 E2E를 쓰기 쉽게 화면을 만드는 기준
- `api-design` — 서버 응답·오류 형식
