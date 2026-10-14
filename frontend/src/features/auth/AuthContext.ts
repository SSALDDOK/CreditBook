import { createContext, useContext } from 'react'
import type { LoginRequest, SessionResponse } from '@/api/auth'

/**
 * 인증 상태. 전역 상태는 이것 하나다(로그인한 직원·역할). 고객·잔액 같은 서버 데이터는 여기 두지 않는다.
 * - checking: 서버에 세션을 확인하는 중 — 보호 화면을 아직 그리지 않는다
 * - authenticated: 로그인됨
 * - anonymous: 로그인 안 됨. loginTarget 이 있으면 보호 화면이 그 주소(로그인 화면)로 보낸다
 * - error: 세션 확인 자체가 실패(네트워크·5xx) — 로그인 여부를 모르므로 보호 화면을 그리지 않고 다시 시도를 안내한다
 */
export type AuthState =
  | { status: 'checking' }
  | { status: 'authenticated'; session: SessionResponse }
  | { status: 'anonymous'; loginTarget?: string }
  | { status: 'error'; error: unknown }

export type AuthContextValue = {
  state: AuthState
  /** 로그인. 실패하면 ApiError 를 던진다(화면이 문구를 정한다). 성공하면 상태만 바꾸고, 이동은 로그인 화면이 한다 */
  login: (request: LoginRequest) => Promise<void>
  /** 로그아웃. 서버가 쿠키를 지운 뒤에만 로그아웃 상태로 바꾼다. 실패하면 오류를 던지고 로그인 상태를 유지한다 */
  logout: () => Promise<void>
  /** 세션을 다시 확인한다(세션 확인 실패 후 "다시 시도") */
  recheck: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth 는 AuthProvider 안에서만 쓸 수 있다')
  return value
}
