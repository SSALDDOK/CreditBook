import { apiRequest } from './client'
import { USE_MOCK } from './config'
import { mockGetSession, mockLogin, mockLogout } from './mock/auth'

// 백엔드 auth API (LoginRequest·SessionResponse) 와 필드 이름을 맞춘다.
// 세션 토큰은 서버가 httpOnly 쿠키(CB_SESSION)로만 주고받는다 — 화면은 토큰을 읽지도 저장하지도 않고,
// 로그인 상태는 GET /api/auth/session 으로만 확인한다.

export type Role = 'ADMIN' | 'STAFF'

export type LoginRequest = { loginId: string; password: string }

export type SessionResponse = {
  employeeId: string
  name: string
  role: Role
  /** 비밀번호 변경 강제 여부 — 처리는 직원 관리 이슈(S5)에서 한다. 지금은 쓰지 않는다 */
  mustChangePassword: boolean
  /** 세션 만료 시각 (ISO-8601 Instant) */
  expiresAt: string
}

const JSON_HEADERS = { 'Content-Type': 'application/json' }

/** POST /api/auth/login — 실패는 사유와 관계없이 401 INVALID_CREDENTIALS (입력 누락은 400 INVALID_INPUT). */
export function login(request: LoginRequest): Promise<SessionResponse> {
  if (USE_MOCK) return mockLogin(request)
  return apiRequest<SessionResponse>(
    '/api/auth/login',
    { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(request) },
    // 여기서 401 은 세션 만료가 아니라 자격증명 오류다
    { redirectOnUnauthorized: false },
  )
}

/** POST /api/auth/logout — 항상 204, 서버가 쿠키를 지운다. */
export function logout(): Promise<void> {
  if (USE_MOCK) return mockLogout()
  return apiRequest<void>('/api/auth/logout', { method: 'POST' }, { redirectOnUnauthorized: false })
}

/** GET /api/auth/session — 로그인 상태면 200, 아니면 401 UNAUTHENTICATED. */
export function getSession(signal?: AbortSignal): Promise<SessionResponse> {
  if (USE_MOCK) return mockGetSession(signal)
  // 401 은 "아직 로그인하지 않음"이라는 정상 응답이다. 로그인 화면 이동은 보호 라우트가 판단한다
  return apiRequest<SessionResponse>('/api/auth/session', { signal }, { redirectOnUnauthorized: false })
}
