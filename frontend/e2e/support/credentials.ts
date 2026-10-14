// E2E 로그인 계정. 비밀번호는 리포에 두지 않고 실행할 때 환경변수로만 받는다(공개 리포).
// CI 는 실행마다 랜덤 비밀번호로 직원을 시드하고 이 환경변수로 넘긴다(.github/workflows/ci.yml 의 e2e job).
// 로컬은 backend/dev/seed-local.sql 로 만든 계정의 비밀번호를 넣는다 — README "E2E (Playwright)" 절.

export type E2eAccount = { loginId: string; password: string }

function required(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`환경변수 ${name} 이 없습니다. frontend/README.md 의 "E2E (Playwright)" 절을 보세요.`)
  return value
}

/** 직원(STAFF) 계정 — 시드의 staff */
export function staffAccount(): E2eAccount {
  return { loginId: process.env.E2E_STAFF_LOGIN_ID ?? 'staff', password: required('E2E_STAFF_PASSWORD') }
}

/** 사장(ADMIN) 계정 — 시드의 owner */
export function adminAccount(): E2eAccount {
  return { loginId: process.env.E2E_ADMIN_LOGIN_ID ?? 'owner', password: required('E2E_ADMIN_PASSWORD') }
}
