import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { flushSync } from 'react-dom'
import { getSession, login as apiLogin, logout as apiLogout, type LoginRequest } from '@/api/auth'
import { ApiError, setUnauthorizedHandler } from '@/api/client'
import { LOGIN_PATH, loginPath } from '@/routes/redirect'
import { AuthContext, type AuthContextValue, type AuthState } from './AuthContext'

/**
 * 인증 상태를 가진다. 하는 일:
 * 1. 처음 열릴 때 GET /api/auth/session 으로 로그인 여부를 확인한다(쿠키는 화면이 읽을 수 없다)
 * 2. 보호 API 의 401 을 한 곳에서 받아 "세션 만료"로 로그인 화면에 보낸다(원래 보던 화면을 next 로)
 * 3. 뒤로가기 캐시(bfcache)에서 페이지가 되살아나면 그 자리에서 화면을 가리고 세션을 다시 확인한다
 *
 * 상태가 바뀔 때마다 세대(generation)를 올리고, 늦게 도착한 세션 확인 응답이 그 사이의 로그인·로그아웃을 덮어쓰지 않게 한다.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'checking' })
  const stateRef = useRef<AuthState>(state)
  const generation = useRef(0)
  const checkController = useRef<AbortController | null>(null)

  const transition = useCallback((next: AuthState): number => {
    generation.current += 1
    stateRef.current = next
    setState(next)
    return generation.current
  }, [])

  /** 세션 확인 요청을 보낸다. 상태는 이미 checking 이어야 하고, gen 은 그 checking 의 세대다. */
  const startCheck = useCallback(
    (gen: number) => {
      checkController.current?.abort()
      const controller = new AbortController()
      checkController.current = controller
      getSession(controller.signal)
        .then((session) => {
          if (generation.current === gen) transition({ status: 'authenticated', session })
        })
        .catch((error: unknown) => {
          if (controller.signal.aborted || generation.current !== gen) return
          if (error instanceof ApiError && error.status === 401) transition({ status: 'anonymous' })
          else transition({ status: 'error', error })
        })
    },
    [transition],
  )

  // 1. 첫 확인 — 초기 상태가 이미 checking 이므로 지금 세대로 요청만 보낸다
  useEffect(() => {
    startCheck(generation.current)
    return () => checkController.current?.abort()
  }, [startCheck])

  // 2. 보호 API 401 → 세션 만료. 로그인 상태였을 때만 처리한다(여러 요청이 동시에 401 이어도 한 번만)
  useEffect(
    () =>
      setUnauthorizedHandler(() => {
        if (stateRef.current.status !== 'authenticated') return
        const { pathname, search, hash } = window.location
        transition({ status: 'anonymous', loginTarget: loginPath({ next: pathname + search + hash, reason: 'expired' }) })
      }),
    [transition],
  )

  // 3. bfcache 복원 — 로그아웃 뒤 뒤로가기로 예전 문서가 통째로 되살아나도 고객 정보가 남아 보이지 않게 한다.
  //    flushSync 로 복원 직후 바로 보호 화면을 내리고(checking), 서버에 세션을 다시 묻는다
  useEffect(() => {
    function onPageShow(e: PageTransitionEvent) {
      if (!e.persisted) return
      let gen = 0
      flushSync(() => {
        gen = transition({ status: 'checking' })
      })
      startCheck(gen)
    }
    window.addEventListener('pageshow', onPageShow)
    return () => window.removeEventListener('pageshow', onPageShow)
  }, [transition, startCheck])

  const login = useCallback(
    async (request: LoginRequest) => {
      const session = await apiLogin(request)
      checkController.current?.abort()
      transition({ status: 'authenticated', session })
    },
    [transition],
  )

  const logout = useCallback(async () => {
    // 서버가 쿠키를 지운 것을 확인한 뒤에만 로그아웃 상태로 바꾼다 — 실패했는데 로그아웃된 것처럼 보이면
    // 쿠키가 남은 채로 자리를 떠나게 된다
    await apiLogout()
    checkController.current?.abort()
    // 보호 화면이 이 주소로 replace 이동한다 — 지금 보던 보호 화면 기록이 로그인 화면으로 바뀐다
    transition({ status: 'anonymous', loginTarget: LOGIN_PATH })
  }, [transition])

  const recheck = useCallback(() => {
    startCheck(transition({ status: 'checking' }))
  }, [transition, startCheck])

  const value = useMemo<AuthContextValue>(
    () => ({ state, login, logout, recheck }),
    [state, login, logout, recheck],
  )
  return <AuthContext value={value}>{children}</AuthContext>
}
