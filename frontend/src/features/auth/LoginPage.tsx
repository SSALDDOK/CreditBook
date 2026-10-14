import { Loader2 } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { Navigate, useSearchParams } from 'react-router'
import { ApiError, COMMON_ERROR_MESSAGE, NETWORK_ERROR_MESSAGE } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { AuthChecking } from '@/routes/RequireAuth'
import { safeNextPath } from '@/routes/redirect'
import { useAuth } from './AuthContext'

// 화면설계서 SCR-1 문구. 실패 문구는 아이디 존재 여부를 드러내지 않도록 하나만 쓴다(REQ-15) —
// 서버 message 를 그대로 띄우지 않는다
export const LOGIN_FAILED_MESSAGE = '아이디 또는 비밀번호가 올바르지 않습니다'
export const SESSION_EXPIRED_MESSAGE = '로그인이 만료되었습니다. 다시 로그인해 주세요'

/** 로그인 실패 표시 문구. 401(자격증명 오류)·400(입력 형식 오류)은 같은 문구, 5xx 는 공통 문구, 응답이 없으면 네트워크 문구. */
function loginErrorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    return e.status >= 500 ? COMMON_ERROR_MESSAGE : LOGIN_FAILED_MESSAGE
  }
  return NETWORK_ERROR_MESSAGE
}

/** SCR-1 로그인. 성공하면 next(원래 가려던·보던 화면, 같은 출처만) 또는 고객 목록으로 replace 이동한다. */
export function LoginPage() {
  const { state, login } = useAuth()
  const [params] = useSearchParams()
  const next = safeNextPath(params.get('next'))
  const expired = params.get('reason') === 'expired'

  const loginIdId = useId()
  const passwordId = useId()
  const errorId = useId()
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 이미 로그인돼 있으면(또는 방금 로그인했으면) 폼 대신 목적지로 보낸다.
  // replace 라서 뒤로가기로 로그인 화면에 돌아오지 않는다
  if (state.status === 'authenticated') return <Navigate to={next} replace />
  if (state.status === 'checking') return <AuthChecking />

  const canSubmit = loginId !== '' && password !== '' && !pending

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (!canSubmit) return
    setPending(true)
    setError(null)
    try {
      await login({ loginId, password })
      // 이동은 위의 authenticated 분기가 한다
    } catch (err: unknown) {
      setError(loginErrorMessage(err))
      setPending(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-sidebar px-6 py-12">
      <main className="w-full max-w-[25rem] rounded-3xl bg-background px-8 py-10 shadow-sm sm:px-10">
        <h1 className="text-4xl font-bold tracking-tight">CreditBook</h1>
        <p className="mt-2 text-lg text-muted-foreground">매장 선결제 장부</p>

        {expired && (
          <p
            role="status"
            data-testid="login-expired-notice"
            className="mt-6 rounded-xl border border-amber-300 bg-amber-50 px-4 py-4 font-semibold text-amber-800"
          >
            {SESSION_EXPIRED_MESSAGE}
          </p>
        )}

        <form
          onSubmit={handleSubmit}
          noValidate
          aria-label="로그인"
          aria-busy={pending}
          data-testid="login-form"
          className="mt-6 flex flex-col"
        >
          <label htmlFor={loginIdId} className="text-base font-semibold">
            아이디
          </label>
          <Input
            id={loginIdId}
            name="loginId"
            type="text"
            value={loginId}
            onChange={(e) => setLoginId(e.target.value)}
            disabled={pending}
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            // 서버 LoginRequest 제한과 같다
            maxLength={50}
            data-testid="login-id-input"
            className="mt-2 h-12 rounded-xl px-4 text-lg md:text-lg"
          />

          <label htmlFor={passwordId} className="mt-6 text-base font-semibold">
            비밀번호
          </label>
          <Input
            id={passwordId}
            name="password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={pending}
            autoComplete="current-password"
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? errorId : undefined}
            data-testid="login-password-input"
            className="mt-2 h-12 rounded-xl px-4 text-lg md:text-lg"
          />
          {error && (
            <p id={errorId} role="alert" data-testid="login-error" className="mt-2 text-base text-destructive">
              {error}
            </p>
          )}

          <Button
            type="submit"
            disabled={!canSubmit}
            aria-label={pending ? '로그인 중' : undefined}
            data-testid="login-submit"
            data-state={pending ? 'pending' : 'idle'}
            className={cn('mt-6 h-12 rounded-xl text-lg font-bold', pending && 'disabled:opacity-60')}
          >
            {pending ? <Loader2 aria-hidden="true" className="size-6 animate-spin" /> : '로그인'}
          </Button>
        </form>

        <p className="mt-6 text-center text-muted-foreground">계정 문의는 사장님에게 요청하세요</p>
      </main>
    </div>
  )
}
