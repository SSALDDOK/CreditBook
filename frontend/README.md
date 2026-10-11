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

## 검사

```bash
npm run build      # 타입 검사(tsc -b) + 프로덕션 빌드
npm run lint       # oxlint
```

## 구조

```
src/
├─ api/                 API 호출·요청/응답 타입, 목 데이터(api/mock)
├─ components/ui/       shadcn/ui 컴포넌트
├─ components/layout/   공통 레이아웃(사이드바)
├─ features/customers/  고객 목록 화면(SCR-3)
└─ lib/                 표시 형식(금액·날짜) 등 공용 함수
```
