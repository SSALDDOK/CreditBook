import { ApiError, notifyUnauthorized } from '../client'
import type { ChangePasswordRequest, LoginRequest, SessionResponse } from '../auth'

// 화면 개발용 목 인증. 실제 서버처럼 쿠키를 쓰지 않고, 로그인 상태를 이 모듈의 메모리에만 둔다
// (브라우저 저장소에 두지 않는다 — 새로고침하면 처음 상태로 돌아간다).
//
// - 처음 상태: 로그인된 직원(김직원, STAFF) — 개발 서버를 켜면 지금처럼 고객 목록이 바로 보인다
// - 로그아웃하면 로그인 화면으로 간다. 아이디·비밀번호를 아무 값이나 넣으면 로그인된다
// - 비밀번호가 'wrong' 이면 로그인 실패(401 INVALID_CREDENTIALS) 화면을 볼 수 있다
// - 아이디가 'owner' 면 다른 직원(박사장, ADMIN)으로 로그인된다(로컬 개발 DB 사장 아이디와 같게) — 직원 관리(SCR-12)는 이 계정으로 본다
// - 아이디가 'newstaff' 면 비밀번호를 바꿔야 하는 직원(이신입, STAFF)으로 로그인된다 — 첫 로그인 강제 변경(SCR-13)
// 비밀번호 변경(mockChangePassword): 현재 비밀번호가 'wrong' 이면 400 INVALID_CURRENT_PASSWORD, 'locked' 면 423 ACCOUNT_LOCKED(5분),
// 새 비밀번호가 현재와 같으면 400 PASSWORD_UNCHANGED. 성공하면 mustChangePassword 가 false 가 된다

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

// 처음 로그인한 직원 — 사장이 발급한 초기 비밀번호로 들어와 비밀번호를 바꿔야 한다
function mockMustChangeSession(): SessionResponse {
  return {
    employeeId: '00000000-0000-4000-9000-000000000003',
    name: '이신입',
    role: 'STAFF',
    mustChangePassword: true,
    expiresAt: new Date(Date.now() + MOCK_SESSION_TTL_MS).toISOString(),
  }
}

let current: SessionResponse | null = mockSession()

/** 지금 로그인한 목 세션(없으면 null) — 다른 목 API 가 권한(역할)을 판단할 때 쓴다 */
export function mockCurrentSession(): SessionResponse | null {
  return current
}

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
  // 'login.fail' 은 매장 개설 뒤 이어지는 로그인이 실패하는 경우를 볼 때 쓴다(mock/setup.ts)
  if (password === 'wrong' || loginId === 'login.fail') {
    throw new ApiError(401, 'INVALID_CREDENTIALS', '아이디 또는 비밀번호가 올바르지 않습니다.')
  }
  current = loginId === 'owner' ? mockAdminSession() : loginId === 'newstaff' ? mockMustChangeSession() : mockSession()
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

const MOCK_LOCK_SECONDS = 300

export async function mockChangePassword({
  currentPassword,
  newPassword,
  newPasswordConfirm,
}: ChangePasswordRequest): Promise<SessionResponse> {
  await delay()
  if (!current) {
    notifyUnauthorized()
    throw new ApiError(401, 'UNAUTHENTICATED', '로그인이 필요합니다.')
  }
  if (currentPassword === 'locked') {
    throw new ApiError(423, 'ACCOUNT_LOCKED', '로그인을 5회 실패해 계정이 잠겼습니다. 잠시 후 다시 시도해 주세요.', [], {
      retryAfterSeconds: MOCK_LOCK_SECONDS,
    })
  }
  if (currentPassword === 'wrong') {
    throw new ApiError(400, 'INVALID_CURRENT_PASSWORD', '현재 비밀번호가 올바르지 않습니다.')
  }
  if (newPassword.length < 8 || newPassword.length > 72) {
    throw new ApiError(400, 'INVALID_INPUT', '입력값을 확인해 주세요.', [
      { field: 'newPassword', message: '비밀번호는 8–72자여야 합니다.' },
    ])
  }
  if (newPassword !== newPasswordConfirm) {
    throw new ApiError(400, 'INVALID_INPUT', '입력값을 확인해 주세요.', [
      { field: 'newPasswordConfirm', message: '비밀번호 확인이 일치하지 않습니다.' },
    ])
  }
  if (newPassword === currentPassword) {
    throw new ApiError(400, 'PASSWORD_UNCHANGED', '현재 비밀번호와 다른 비밀번호를 입력해 주세요.')
  }
  current = { ...current, mustChangePassword: false }
  return current
}
