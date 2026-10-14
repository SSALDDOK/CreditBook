import { Loader2 } from 'lucide-react'
import { Navigate, Outlet, useLocation } from 'react-router'
import { toUserMessage } from '@/api/client'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/features/auth/AuthContext'
import { loginPath } from './redirect'

/** 세션 확인 중 표시. 확인이 끝나면 사라진다 — E2E 는 data-testid="auth-checking" 이 사라지기를 기다리면 된다. */
export function AuthChecking() {
  return (
    <div
      role="status"
      data-testid="auth-checking"
      className="flex min-h-screen items-center justify-center gap-3 bg-muted text-muted-foreground"
    >
      <Loader2 aria-hidden="true" className="size-6 animate-spin" />
      로그인 상태를 확인하는 중…
    </div>
  )
}

/**
 * 보호 라우트(레이아웃 라우트). 로그인이 확인되기 전에는 자식 라우트(보호 화면)를 그리지 않는다 — 잠깐이라도 고객 정보가 보이지 않게.
 * 로그인 안 됐으면 로그인 화면으로 replace 이동한다(원래 가려던 주소를 next 로).
 */
export function RequireAuth() {
  const { state, recheck } = useAuth()
  const { pathname, search, hash } = useLocation()

  switch (state.status) {
    case 'checking':
      return <AuthChecking />
    case 'error':
      return (
        <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-muted p-8">
          <p role="alert" data-testid="auth-error" className="text-destructive">
            {toUserMessage(state.error)}
          </p>
          <Button type="button" variant="outline" onClick={recheck} data-testid="auth-retry">
            다시 시도
          </Button>
        </div>
      )
    case 'anonymous':
      return <Navigate to={state.loginTarget ?? loginPath({ next: pathname + search + hash })} replace />
    case 'authenticated':
      return <Outlet />
  }
}
