import { ApiError } from '../client'
import type { LoginRequest, SessionResponse } from '../auth'

// 화면 개발용 목 인증. 실제 서버처럼 쿠키를 쓰지 않고, 로그인 상태를 이 모듈의 메모리에만 둔다
// (브라우저 저장소에 두지 않는다 — 새로고침하면 처음 상태로 돌아간다).
//
// - 처음 상태: 로그인된 직원(김직원, STAFF) — 개발 서버를 켜면 지금처럼 고객 목록이 바로 보인다
// - 로그아웃하면 로그인 화면으로 간다. 아이디·비밀번호를 아무 값이나 넣으면 로그인된다
// - 비밀번호가 'wrong' 이면 로그인 실패(401 INVALID_CREDENTIALS) 화면을 볼 수 있다
// - 아이디가 'admin' 이면 다른 직원(박사장, ADMIN)으로 로그인된다

const MOCK_DELAY_MS = 400
const MOCK_SESSION_TTL_MS = 16 * 60 * 60 * 1000

function mockSession(name = '김직원'): SessionResponse {
  return {
    employeeId: '00000000-0000-4000-9000-000000000001',
    name,
    role: 'STAFF',
    mustChangePassword: false,
    expiresAt: new Date(Date.now() + MOCK_SESSION_TTL_MS).toISOString(),
  }
}

// 다른 직원 — 입력 복구(REQ-17)가 다른 직원에게는 되지 않는 것을 볼 때 쓴다
function mockAdminSession(): SessionResponse {
  return {
    employeeId: '00000000-0000-4000-9000-000000000002',
    name: '박사장',
    role: 'ADMIN',
    mustChangePassword: false,
    expiresAt: new Date(Date.now() + MOCK_SESSION_TTL_MS).toISOString(),
  }
}

let current: SessionResponse | null = mockSession()

function delay(signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, MOCK_DELAY_MS)
    signal?.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(new DOMException('Aborted', 'AbortError'))
    })
  })
}

export async function mockLogin({ loginId, password }: LoginRequest): Promise<SessionResponse> {
  await delay()
  if (password === 'wrong') {
    throw new ApiError(401, 'INVALID_CREDENTIALS', '아이디 또는 비밀번호가 올바르지 않습니다.')
  }
  current = loginId === 'admin' ? mockAdminSession() : mockSession()
  return current
}

/** 세션 만료를 흉내 낸다 — 다른 목 API 가 401 을 낼 때 함께 부른다 */
export function mockExpireSession(): void {
  current = null
}

export async function mockLogout(): Promise<void> {
  await delay()
  current = null
}

export async function mockGetSession(signal?: AbortSignal): Promise<SessionResponse> {
  await delay(signal)
  if (!current) throw new ApiError(401, 'UNAUTHENTICATED', '로그인이 필요합니다.')
  return current
}
