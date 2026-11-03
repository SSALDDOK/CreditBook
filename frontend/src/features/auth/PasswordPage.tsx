import { Loader2 } from 'lucide-react'
import { useId, useState, type FormEvent, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { ApiError, toUserMessage } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { DEFAULT_AFTER_LOGIN, PASSWORD_PATH, safeNextPath } from '@/routes/redirect'
import { useAuth } from './AuthContext'
import {
  exceedsPasswordBytes,
  hasValidPasswordLength,
  PASSWORD_CONFIRM_MISMATCH_MESSAGE,
  PASSWORD_MAX_LENGTH,
  PASSWORD_TOO_MANY_BYTES_MESSAGE,
} from './credentialRules'
import {
  FIELD_ERROR_CLASS,
  FIELD_HELP_CLASS,
  FIELD_HELP_OK_CLASS,
  FIELD_INPUT_CLASS,
  FIELD_LABEL_CLASS,
  REQUIRED_MARK_CLASS,
} from './fieldStyles'
import { LockedNotice } from './LockedNotice'
import { toLockWindow, useLockCountdown, type LockWindow } from './lockCountdown'

// SCR-13 비밀번호 변경. 로그인 화면(SCR-1)과 같은 카드 양식이고 왼쪽 메뉴(AppLayout)가 없다.
// - 강제 모드: 세션 mustChangePassword 가 true(첫 로그인·사장이 초기화한 뒤) — 바꾸면 고객 목록, 아래 링크는 [로그아웃]
// - 일반 모드: 왼쪽 아래 [비밀번호 변경]으로 들어옴 — 바꾸면 원래 화면(화면 이동 상태 from), 아래 링크는 [취소]
// 원래 화면 경로는 주소(쿼리)에 넣지 않고 화면 이동 상태로만 받는다.

/** 이 화면으로 올 때 넘기는 화면 이동 상태 — from 은 돌아갈 앱 내부 경로 */
export type PasswordLocationState = { from?: string } | null

// 강제 모드 안내는 화면설계 이미지처럼 '…발급한 비밀번호를' 뒤에서 줄을 바꾼다
const FORCED_GUIDE_LINES = ['처음 로그인했습니다. 사장님이 발급한 비밀번호를', '본인만 아는 비밀번호로 바꿔야 시작할 수 있습니다.']
const NORMAL_GUIDE = '현재 비밀번호를 확인한 뒤 새 비밀번호로 바꿉니다.'
// 화면설계 문구(서버 message 와 같다 — 서버 문구가 바뀌어도 화면 문구는 그대로 두려고 화면에 둔다)
const INVALID_CURRENT_PASSWORD_MESSAGE = '현재 비밀번호가 올바르지 않습니다.'
const PASSWORD_UNCHANGED_MESSAGE = '현재 비밀번호와 다른 비밀번호를 입력해 주세요.'

type Field = 'currentPassword' | 'newPassword' | 'newPasswordConfirm'
type FieldErrors = Partial<Record<Field, string>>

const FIELDS: readonly Field[] = ['currentPassword', 'newPassword', 'newPasswordConfirm']

/** 돌아갈 화면. 화면 이동 상태에 없거나 안전하지 않으면 고객 목록. 비밀번호 변경 화면 자신으로는 돌아가지 않는다 */
function returnPathFrom(state: unknown): string {
  const from = (state as PasswordLocationState)?.from
  const safe = safeNextPath(from)
  return new URL(safe, window.location.origin).pathname === PASSWORD_PATH ? DEFAULT_AFTER_LOGIN : safe
}

export function PasswordPage() {
  const { state, changePassword, logout } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  // 보호 라우트 안에서만 그려지므로 항상 로그인 상태다
  const forced = state.status === 'authenticated' && state.session.mustChangePassword
  const returnPath = returnPathFrom(location.state)

  const currentId = useId()
  const newId = useId()
  const confirmId = useId()
  const newHelpId = useId()
  const currentErrorId = useId()
  const newErrorId = useId()
  const confirmErrorId = useId()
  const formErrorId = useId()

  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [pending, setPending] = useState(false)
  const [serverErrors, setServerErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [lockFailure, setLockFailure] = useState<{ message: string; lock: LockWindow | null } | null>(null)
  const [leaving, setLeaving] = useState(false)

  const remainingSeconds = useLockCountdown(lockFailure?.lock ?? null)
  // 남은 시간을 알 수 없는 잠금은 다시 제출해 서버가 판단하게 둔다(로그인 화면과 같다)
  const locked = remainingSeconds !== null && remainingSeconds > 0
  const showLock = lockFailure !== null && remainingSeconds !== 0

  // 입력 중 바로 보이는 검사(편의). 최종 검증은 서버가 한다
  const newTooManyBytes = exceedsPasswordBytes(newPassword)
  const newLengthOk = hasValidPasswordLength(newPassword)
  const newValid = newLengthOk && !newTooManyBytes
  const confirmMismatch = confirm !== '' && confirm !== newPassword

  const errors: FieldErrors = {
    currentPassword: serverErrors.currentPassword,
    newPassword: newTooManyBytes ? PASSWORD_TOO_MANY_BYTES_MESSAGE : serverErrors.newPassword,
    newPasswordConfirm: confirmMismatch ? PASSWORD_CONFIRM_MISMATCH_MESSAGE : serverErrors.newPasswordConfirm,
  }

  const canSubmit =
    currentPassword !== '' && newValid && confirm === newPassword && !pending && !locked && !leaving

  function clearServerError(field: Field) {
    setServerErrors((prev) => (prev[field] ? { ...prev, [field]: undefined } : prev))
    setFormError(null)
  }

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (!canSubmit) return
    // 성공 뒤 세션이 바뀌면 forced 가 false 로 바뀌므로 제출 때의 모드로 이동할 곳을 정한다
    const destination = forced ? DEFAULT_AFTER_LOGIN : returnPath
    setPending(true)
    setServerErrors({})
    setFormError(null)
    setLockFailure(null)
    try {
      await changePassword({ currentPassword, newPassword, newPasswordConfirm: confirm })
      navigate(destination, { replace: true })
    } catch (err: unknown) {
      handleError(err)
      setPending(false)
    }
  }

  // 분기는 status·code 로만 한다
  function handleError(err: unknown) {
    if (!(err instanceof ApiError)) {
      setFormError(toUserMessage(err))
      return
    }
    if (err.status === 401) return // 로그인 화면 이동은 공통 401 처리가 한다
    if (err.status === 423 && err.code === 'ACCOUNT_LOCKED') {
      setLockFailure({ message: err.message, lock: toLockWindow(err.details, Date.now()) })
      return
    }
    switch (err.code) {
      case 'INVALID_CURRENT_PASSWORD':
        setServerErrors({ currentPassword: INVALID_CURRENT_PASSWORD_MESSAGE })
        return
      case 'PASSWORD_UNCHANGED':
        setServerErrors({ newPassword: PASSWORD_UNCHANGED_MESSAGE })
        return
      case 'INVALID_INPUT': {
        const next: FieldErrors = {}
        for (const fieldError of err.fieldErrors) {
          const field = FIELDS.find((f) => f === fieldError.field)
          if (field && !next[field]) next[field] = fieldError.message
        }
        setServerErrors(next)
        // 칸에 붙일 수 없는 오류만 있으면 폼 아래에 보여 준다
        if (Object.keys(next).length === 0) setFormError(toUserMessage(err))
        return
      }
    }
    setFormError(toUserMessage(err))
  }

  async function handleLogout() {
    setLeaving(true)
    setFormError(null)
    try {
      await logout()
      // 이동(로그인 화면으로 replace)은 보호 라우트가 한다
    } catch (err: unknown) {
      setFormError(toUserMessage(err))
      setLeaving(false)
    }
  }

  return (
    // 화면설계 SCR-13 (1024×768): 카드 450px·모서리 20px·안쪽 여백 34px, 입력 칸 382×42
    <div className="flex min-h-screen items-center justify-center bg-sidebar px-6 py-12">
      <main
        className="w-full max-w-[450px] rounded-[20px] bg-white px-[34px] pt-[34.5px] pb-[55px]"
        data-testid="password-page"
        data-mode={forced ? 'forced' : 'normal'}
      >
        <p className="text-[24px] leading-[30px] font-bold text-[#141B34]">CreditBook</p>
        <h1 className="mt-4 text-[15px] leading-5 font-bold text-[#141B34]">비밀번호 변경</h1>

        <p className="mt-[4.5px] text-[12.5px] leading-[19px] text-[#6B7280] break-keep" data-testid="password-guide">
          {forced
            ? FORCED_GUIDE_LINES.map((line) => (
                <span key={line} className="block">
                  {line}
                </span>
              ))
            : NORMAL_GUIDE}
        </p>

        {showLock && lockFailure && (
          <LockedNotice
            message={lockFailure.message}
            remainingSeconds={remainingSeconds}
            testId="password-locked-notice"
            remainingTestId="password-locked-remaining"
          />
        )}

        <form
          onSubmit={handleSubmit}
          noValidate
          aria-label="비밀번호 변경"
          aria-busy={pending}
          data-testid="password-form"
          className="mt-[16.5px] flex flex-col"
        >
          <PasswordField
            id={currentId}
            label="현재 비밀번호"
            value={currentPassword}
            onChange={(value) => {
              setCurrentPassword(value)
              clearServerError('currentPassword')
            }}
            disabled={pending}
            autoComplete="current-password"
            error={errors.currentPassword}
            errorId={currentErrorId}
            testId="password-current"
            errorTestId="password-current-error"
          />

          <PasswordField
            id={newId}
            label="새 비밀번호"
            value={newPassword}
            onChange={(value) => {
              setNewPassword(value)
              clearServerError('newPassword')
              clearServerError('newPasswordConfirm')
            }}
            disabled={pending}
            autoComplete="new-password"
            error={errors.newPassword}
            errorId={newErrorId}
            describedBy={newHelpId}
            testId="password-new"
            errorTestId="password-new-error"
            help={
              <p
                id={newHelpId}
                data-testid="password-new-help"
                data-valid={newValid}
                className={cn(FIELD_HELP_CLASS, newValid && FIELD_HELP_OK_CLASS)}
              >
                {newValid ? '✓ 8–72자' : '8–72자'}
              </p>
            }
          />

          <PasswordField
            id={confirmId}
            label="새 비밀번호 확인"
            value={confirm}
            onChange={(value) => {
              setConfirm(value)
              clearServerError('newPasswordConfirm')
            }}
            disabled={pending}
            autoComplete="new-password"
            error={errors.newPasswordConfirm}
            errorId={confirmErrorId}
            testId="password-confirm"
            errorTestId="password-confirm-error"
          />

          {formError && (
            <p id={formErrorId} role="alert" data-testid="password-error" className="mt-1 text-[12.5px] leading-[17px] text-[#C62828]">
              {formError}
            </p>
          )}

          <Button
            type="submit"
            disabled={!canSubmit}
            aria-label={pending ? '비밀번호 변경 중' : undefined}
            aria-describedby={formError ? formErrorId : undefined}
            data-testid="password-submit"
            data-state={pending ? 'pending' : 'idle'}
            // 비활성 색은 지금 구현 그대로 둔다(사용자 결정 — 화면설계 쪽을 고친다)
            className={cn('mt-[9.5px] h-[44px] rounded-[9px] text-[15px] font-bold', pending && 'disabled:opacity-60')}
          >
            {pending ? (
              <Loader2 aria-hidden="true" className="size-6 animate-spin" />
            ) : forced ? (
              '변경하고 시작하기'
            ) : (
              '변경하기'
            )}
          </Button>
        </form>

        <div className="mt-[17px] text-center leading-[17px]">
          {forced ? (
            <button
              type="button"
              onClick={handleLogout}
              disabled={leaving || pending}
              aria-busy={leaving}
              data-testid="password-logout"
              className="rounded text-[12.5px] text-[#6B7280] underline-offset-4 outline-none hover:text-foreground hover:underline focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-60"
            >
              로그아웃
            </button>
          ) : (
            <button
              type="button"
              onClick={() => navigate(returnPath, { replace: true })}
              disabled={pending}
              data-testid="password-cancel"
              className="rounded text-[12.5px] text-[#6B7280] underline-offset-4 outline-none hover:text-foreground hover:underline focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-60"
            >
              취소
            </button>
          )}
        </div>
      </main>
    </div>
  )
}

function PasswordField({
  id,
  label,
  value,
  onChange,
  disabled,
  autoComplete,
  error,
  errorId,
  describedBy,
  help,
  testId,
  errorTestId,
  className,
}: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  disabled: boolean
  autoComplete: string
  error: string | undefined
  errorId: string
  describedBy?: string
  help?: ReactNode
  testId: string
  errorTestId: string
  className?: string
}) {
  const ids = [describedBy, error ? errorId : undefined].filter(Boolean).join(' ') || undefined
  return (
    // 칸 하나의 자리 96px(이름 줄 위 → 다음 이름 줄 위). 도움말·오류 한 줄은 그 안 빈 곳에 들어간다
    <div className={cn('flex min-h-[96px] flex-col', className)}>
      {/* '필수'는 label 밖에 둔다 — 칸의 접근 이름(getByLabel)이 칸 이름 그대로이게. 필수 여부는 aria-required */}
      <div className={FIELD_LABEL_CLASS}>
        <label htmlFor={id}>{label}</label>
        <span aria-hidden="true" className={REQUIRED_MARK_CLASS}>
          필수
        </span>
      </div>
      <Input
        id={id}
        type="password"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        required
        aria-required="true"
        autoComplete={autoComplete}
        // 서버 상한과 같다(글자 수). 바이트 상한(72)은 입력 중 따로 검사한다
        maxLength={PASSWORD_MAX_LENGTH}
        aria-invalid={error ? true : undefined}
        aria-describedby={ids}
        data-testid={testId}
        className={FIELD_INPUT_CLASS}
      />
      {help}
      {error && (
        <p id={errorId} role="alert" data-testid={errorTestId} className={FIELD_ERROR_CLASS}>
          {error}
        </p>
      )}
    </div>
  )
}
