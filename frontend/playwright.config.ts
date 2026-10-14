import { defineConfig, devices } from '@playwright/test'

// E2E (Playwright). 실제 백엔드에 붙어 돈다 — 목 데이터 아님.
// 화면은 운영 빌드(npm run build, VITE_USE_MOCK 미지정 → 실제 API)를 vite preview 로 띄우고,
// preview 가 /api 를 http://localhost:8080 (백엔드)으로 넘긴다(vite.config.ts 의 proxy 를 preview 도 쓴다).
// 실행 방법·계정 환경변수는 README 의 "E2E (Playwright)" 절.

const PORT = 4173
// localhost 로 접속해야 한다 — 세션 쿠키(CB_SESSION)가 Secure 라 http 에서는 브라우저가 "안전한 출처"로 보는 localhost 에서만 저장된다
const BASE_URL = process.env.E2E_BASE_URL ?? `http://localhost:${PORT}`

export default defineConfig({
  testDir: './e2e',
  // 테스트는 실행 순서에 의존하지 않는다(CLAUDE.md 테스트 규칙). CI 는 백엔드·DB 하나를 같이 쓰므로 워커 1개
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  // 재시도하지 않는다 — TC 판정은 한 번 실행 결과로 한다(재시도로 통과한 간헐 실패를 숨기지 않게)
  retries: 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }], ['junit', { outputFile: 'test-results/junit.xml' }]]
    : [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 30_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  // E2E_BASE_URL 을 주면 이미 떠 있는 화면에 붙고, 아니면 운영 빌드 + preview 를 직접 띄운다
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: `npm run build && npm run preview -- --port ${PORT} --strictPort`,
        url: BASE_URL,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
        // .env.local 에 VITE_USE_MOCK=true 가 있어도 E2E 는 실제 API 로 빌드한다(실행 환경변수가 .env 파일보다 우선)
        env: { VITE_USE_MOCK: 'false' },
      },
})
