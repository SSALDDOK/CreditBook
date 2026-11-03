import { Loader2 } from 'lucide-react'
import { useEffect, useId, useState, type FormEvent } from 'react'
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

// 429 LOGIN_RATE_LIMITED 안내(SCR-1 화면 문구). 남은 시간은 보여 주지 않는다
export const LOGIN_RATE_LIMITED_MESSAGE = '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.'

/**
 * 로그인 실패를 화면에 어떻게 보여 줄지. 분기는 status·code 로만 한다.
 * - field: 비밀번호 칸 아래 빨간 글씨(401·400 은 같은 문구, 5xx 는 공통 문구, 응답이 없으면 네트워크 문구)
 * - locked: 423 ACCOUNT_LOCKED — 카드 위 빨간 상자. lockedUntil 은 잠금이 풀리는 시각(ms), 알 수 없으면 null
 * - rateLimited: 429 LOGIN_RATE_LIMITED — 카드 위 노란 상자
 */
type LoginFailure =
  | { kind: 'field'; message: string }
  | { kind: 'locked'; message: string; lockedUntil: number | null }
  | { kind: 'rateLimited' }

/** details.retryAfterSeconds 가 양의 유한한 숫자일 때만 돌려준다(아니면 남은 시간을 표시하지 않는다). */
function retryAfterSeconds(details: unknown): number | null {
  if (typeof details !== 'object' || details === null) return null
  const value = (details as { retryAfterSeconds?: unknown }).retryAfterSeconds
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null
}

function toLoginFailure(e: unknown, now: number): LoginFailure {
  if (e instanceof ApiError) {
    if (e.status === 423 && e.code === 'ACCOUNT_LOCKED') {
      const seconds = retryAfterSeconds(e.details)
      return { kind: 'locked', message: e.message, lockedUntil: seconds === null ? null : now + seconds * 1000 }
    }
    if (e.status === 429 && e.code === 'LOGIN_RATE_LIMITED') return { kind: 'rateLimited' }
    return { kind: 'field', message: e.status >= 500 ? COMMON_ERROR_MESSAGE : LOGIN_FAILED_MESSAGE }
  }
  return { kind: 'field', message: NETWORK_ERROR_MESSAGE }
}

// 카드 위 안내 상자(만료·잠금·429) 공통. 글자는 화면설계 13px, 한글이 단어 중간에서 끊기지 않게 keep-all
const NOTICE_TEXT = 'mt-6 rounded-xl border px-4 py-4 text-[13px] font-semibold break-keep'

/** 남은 초를 '남은 시간 N분 N초'로. 1분 미만이어도 '0분 45초'로 쓴다(화면 문구 표 형식). */
function formatRemaining(totalSeconds: number): string {
  return `남은 시간 ${Math.floor(totalSeconds / 60)}분 ${totalSeconds % 60}초`
}

/** SCR-1 로그인. 성공하면 next(원래 가려던·보던 화면, 같은 출처만) 또는 고객 목록으로 replace 이동한다. */
export function LoginPage() {
  const { state, login } = useAuth()
  const [params, setParams] = useSearchParams()
  const next = safeNextPath(params.get('next'))
  // 세션 만료 안내는 제출 전·제출 중에만 보인다. 로그인에 실패하면 주소에서 reason 을 지워(next 는 남김)
  // 그 실패 안내만 남기고, 새로고침해도 다시 나오지 않게 한다
  const expired = params.get('reason') === 'expired'

  const loginIdId = useId()
  const passwordId = useId()
  const errorId = useId()
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState<LoginFailure | null>(null)
  // 잠금 남은 시간 계산의 기준 시각. 1초마다 Date.now() 로 다시 잡는다 —
  // 누적 감소(초마다 -1)는 탭이 백그라운드일 때 타이머가 늦게 돌아 실제 시각과 어긋난다
  const [now, setNow] = useState(() => Date.now())
  const lockedUntil = failure?.kind === 'locked' ? failure.lockedUntil : null

  useEffect(() => {
    if (lockedUntil === null) return
    const timer = setInterval(() => {
      const current = Date.now()
      setNow(current)
      // 잠금이 풀리면 안내를 지우고 [로그인]을 다시 켠다
      if (current >= lockedUntil) setFailure(null)
    }, 1000)
    return () => clearInterval(timer)
  }, [lockedUntil])

  // 이미 로그인돼 있으면(또는 방금 로그인했으면) 폼 대신 목적지로 보낸다.
  // replace 라서 뒤로가기로 로그인 화면에 돌아오지 않는다
  if (state.status === 'authenticated') return <Navigate to={next} replace />
  if (state.status === 'checking') return <AuthChecking />

  const remainingSeconds = lockedUntil === null ? null : Math.max(0, Math.ceil((lockedUntil - now) / 1000))
  const locked = remainingSeconds !== null && remainingSeconds > 0
  const fieldError = failure?.kind === 'field' ? failure.message : null
  const canSubmit = loginId !== '' && password !== '' && !pending && !locked

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (!canSubmit) return
    setPending(true)
    setFailure(null)
    try {
      await login({ loginId, password })
      // 이동은 위의 authenticated 분기가 한다
    } catch (err: unknown) {
      const at = Date.now()
      setNow(at)
      setFailure(toLoginFailure(err, at))
      if (expired) {
        setParams(
          (prev) => {
            const updated = new URLSearchParams(prev)
            updated.delete('reason')
            return updated
          },
          { replace: true },
        )
      }
      setPending(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-sidebar px-6 py-12">
      <main className="w-full max-w-[26rem] rounded-3xl bg-background px-8 py-10 shadow-sm sm:px-10">
        <h1 className="text-4xl font-bold tracking-tight">CreditBook</h1>
        <p className="mt-2 text-lg text-muted-foreground">매장 선결제 장부</p>

        {expired && (
          <p
            role="status"
            data-testid="login-expired-notice"
            className={cn(NOTICE_TEXT, 'border-amber-300 bg-amber-50 text-amber-800')}
          >
            {SESSION_EXPIRED_MESSAGE}
          </p>
        )}

        {failure?.kind === 'locked' && (
          <div
            data-testid="login-locked-notice"
            className={cn(NOTICE_TEXT, 'border-destructive/30 bg-destructive/10 text-destructive')}
          >
            {/* 잠금 문구만 알림으로 한 번 읽힌다. 남은 시간 줄은 알림 영역 밖의 role="timer"(기본 aria-live 꺼짐)라
                매초 바뀌어도 화면낭독기가 다시 읽지 않는다 */}
            {/* 문장마다 줄을 바꾼다 — '잠겼습니다. 잠시 후'가 한 줄에 붙지 않게 */}
            <p role="alert">
              {failure.message.split(/(?<=\.)\s+/).map((sentence) => (
                <span key={sentence} className="block">
                  {sentence}
                </span>
              ))}
            </p>
            {locked && (
              <p role="timer" data-testid="login-locked-remaining" className="mt-1 tabular-nums">
                {formatRemaining(remainingSeconds)}
              </p>
            )}
          </div>
        )}

        {failure?.kind === 'rateLimited' && (
          <p
            role="alert"
            data-testid="login-rate-limited-notice"
            className={cn(NOTICE_TEXT, 'border-amber-300 bg-amber-50 text-amber-800')}
          >
            {LOGIN_RATE_LIMITED_MESSAGE}
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
            onChange={(e) => {
              setLoginId(e.target.value)
              // 잠금은 계정 단위라 아이디를 바꾸면 다른 직원이 바로 로그인할 수 있게 잠금 안내를 지운다.
              // 잠긴 아이디로 다시 시도하면 서버가 다시 423 과 남은 시간을 준다
              if (failure?.kind === 'locked') setFailure(null)
            }}
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
            aria-invalid={fieldError ? true : undefined}
            aria-describedby={fieldError ? errorId : undefined}
            data-testid="login-password-input"
            className="mt-2 h-12 rounded-xl px-4 text-lg md:text-lg"
          />
          {fieldError && (
            <p id={errorId} role="alert" data-testid="login-error" className="mt-2 text-base text-destructive">
              {fieldError}
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
