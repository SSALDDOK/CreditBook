---
name: vite-patterns
description: "Vite 빌드 도구 패턴 — vite.config.ts, 플러그인, 환경변수(VITE_), 개발 서버 프록시, 타입 검사 공백, 빌드 최적화, 흔한 함정. CreditBook은 React SPA(Vercel 정적 배포)라서 원본 ECC 스킬의 라이브러리 모드·SSR·플러그인 작성·HMR API·Docker 절은 뺐다. vite.config.ts, Vite plugins, env variables, proxy, build errors를 다룰 때 사용."
metadata:
  origin: ECC (Vite SPA 범위로 축소, CreditBook 백엔드 경로·배포에 맞춤)
---

# Vite 패턴 (CreditBook)

`frontend/`의 React + Vite + TypeScript 앱을 설정하고 빌드할 때 참고한다.

## 언제 쓰는가

- `frontend/vite.config.ts`를 만들거나 고칠 때
- 환경변수(`.env*`)를 추가할 때
- 로컬에서 백엔드(`backend/`, 8080)와 연결할 때
- `npm run build`가 실패하거나 개발에서는 되는데 빌드에서 깨질 때

## 동작 방식 (짧게)

- **개발**: 소스를 네이티브 ESM으로 그대로 제공하고 요청이 올 때 모듈별로 변환한다 — 그래서 시작이 빠르다
- **빌드**: 번들러로 묶고 tree-shaking·코드 분할·압축을 한다. 결과는 `dist/`이고 Vercel이 정적으로 배포한다
- **의존성 사전 번들링**: CJS 의존성을 한 번 ESM으로 바꿔 `node_modules/.vite`에 캐시한다
- **환경변수**: 빌드 시점에 코드에 **문자열로 박힌다**. `VITE_` 접두사만 클라이언트에 노출된다

## 기본 설정

```ts
// frontend/vite.config.ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';     // 또는 plugin-react-swc — CB-32 착수 때 하나로 정한다
import tsconfigPaths from 'vite-tsconfig-paths';

export default defineConfig({
  plugins: [react(), tsconfigPaths()],
  server: {
    port: 5173,
    proxy: {
      // 백엔드 API는 모두 /api 아래다 (CustomerController·ChargeController·UsageController).
      // 경로를 다시 쓰지 않는다 — 서버 경로와 같게 둔다
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: { sourcemap: false },               // 운영 소스맵은 원본 코드를 노출한다
});
```

- 경로 별칭(`@/…`)은 `tsconfig.json`의 `paths`에 한 번만 적고 `vite-tsconfig-paths`로 맞춘다. `resolve.alias`에 손으로 중복해 적지 않는다
- 개발 프록시를 쓰면 브라우저 입장에서 같은 출처(origin)라서 httpOnly 쿠키·CORS 문제가 개발 중에는 생기지 않는다. **운영(Vercel ↔ Render)은 출처가 다르다** — CORS 허용 출처와 쿠키 `SameSite`·`Secure` 설정은 서버 인증 설계(CB-15)에서 정하고, 화면은 그 결정에 맞춘다

## 타입 검사 공백 — 반드시 막는다

`vite build`는 TypeScript를 **변환만 하고 타입 검사를 하지 않는다.** 타입 오류가 그대로 배포된다.

```json
// frontend/package.json
"scripts": {
  "dev": "vite",
  "build": "tsc -b && vite build",
  "lint": "eslint .",
  "preview": "vite preview"
}
```

- `build` 스크립트에 `tsc -b`를 앞에 둔다(Vite React-TS 템플릿 기본값). CI도 `npm run build`를 돌린다
- 타입 오류를 없애려고 `// @ts-ignore`를 넣지 않는다. 꼭 필요하면 이유와 TODO를 함께 적고 보고한다

## 환경변수

- 파일 순서: `.env` → `.env.local` → `.env.[mode]` → `.env.[mode].local` (뒤가 앞을 덮는다)
- 코드에서는 `import.meta.env.VITE_API_BASE_URL`처럼 `VITE_` 변수만 읽는다
- **`VITE_` 접두사는 보안 경계가 아니다.** 번들 JS에 그대로 들어가므로 압축·난독화로 숨겨지지 않는다. API 주소·기능 플래그 같은 공개 값만 넣는다. DB 비밀번호·서명 키·토큰은 절대 넣지 않는다 (CLAUDE.md 절대 금지 5번)
- `envPrefix: ''` 금지(모든 환경변수가 노출된다). 설정 파일에서 `loadEnv`를 쓰면 접두사를 명시한다: `loadEnv(mode, process.cwd(), ['VITE_'])` — 세 번째 인자 `''`는 모든 변수를 읽어 들인다
- 운영 값은 Vercel 프로젝트 환경변수로 넣는다. 리포에는 `frontend/.env.example`(값 없는 양식)만 커밋한다

### `.gitignore` 확인

- `.env.local`, `.env.*.local`
- `dist/`
- `node_modules/` (`.vite` 캐시 포함)

## 흔한 함정

- **개발은 되는데 빌드가 깨진다**: 개발과 빌드의 변환기가 달라 CJS 라이브러리가 다르게 동작할 수 있다. 배포 전 `npm run build && npm run preview`로 확인한다. `vite preview`는 확인용이지 운영 서버가 아니다
- **배포 직후 이전 청크 404**: 새 빌드는 청크 파일 이름이 바뀐다. 열려 있던 화면이 옛 청크를 요청하면 실패한다 — 라우트의 동적 import 실패를 잡아 새로고침을 안내한다
- **의존성을 바꾼 뒤 이상한 오류**: `node_modules/.vite` 캐시를 지우고 다시 시작한다
- **배럴 파일(`index.ts`로 전부 다시 내보내기)**: 하나만 가져와도 전체를 읽어 개발 서버가 느려진다. 직접 경로로 가져온다(shadcn/ui 컴포넌트는 파일별 import)
- `require()`를 쓰지 않는다 — Vite는 ESM 기준이다

## 빌드 오류가 날 때

`frontend-dev` 정의의 "빌드 오류 해결" 절차를 따른다: 오류 전체를 보고 → 층(타입 / Vite 설정 / 런타임 / CSS)을 가른다 → 최소 수정 → 다시 빌드. 같은 오류가 3번 고쳐도 남으면 멈추고 보고한다.

## 넣지 않은 것 (원본 대비)

- 라이브러리 모드(`build.lib`), SSR 외부화, 플러그인 직접 작성, `import.meta.hot` HMR API, Docker 컨테이너 설정, 번들 청크 수동 분할 — 지금 범위에 필요 없다. 필요해지면 그때 추가한다

## 관련

- `react-patterns` — API 클라이언트(`VITE_API_BASE_URL`)와 오류 처리
- `cb-security-checklist` — 시크릿·입력 검증
