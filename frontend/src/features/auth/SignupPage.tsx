import { Loader2 } from 'lucide-react'
import { useEffect, useId, useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router'
import { ApiError, toUserMessage } from '@/api/client'
import { getSetupStatus, setUp, type SetupRequest } from '@/api/setup'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { DEFAULT_AFTER_LOGIN, LOGIN_PATH, type LoginLocationState } from '@/routes/redirect'
import { useAuth } from './AuthContext'
import {
  exceedsPasswordBytes,
  hasValidPasswordLength,
  isValidLoginId,
  isValidName,
  isValidStoreName,
  LOGIN_ID_INVALID_MESSAGE,
  NAME_INVALID_MESSAGE,
  PASSWORD_CONFIRM_MISMATCH_MESSAGE,
  PASSWORD_LENGTH_INVALID_MESSAGE,
  PASSWORD_TOO_MANY_BYTES_MESSAGE,
  STORE_NAME_INVALID_MESSAGE,
} from './credentialRules'
import {
  FIELD_ERROR_CLASS,
  FIELD_HELP_CLASS,
  FIELD_HELP_OK_CLASS,
  FIELD_INPUT_CLASS,
  FIELD_LABEL_CLASS,
  REQUIRED_MARK_CLASS,
} from './fieldStyles'

// SCR-11 매장 개설·사장님 가입 (REQ-29). 직원 계정이 하나도 없을 때만 열린다 — 개설 가능 여부를 확인하기 전에는 폼을 그리지 않는다.
// 가입 성공 뒤 서버는 로그인시키지 않으므로 방금 입력한 아이디·비밀번호로 로그인을 이어서 부른다.
// 비밀번호는 이 화면의 상태(메모리)에만 있다. 로그인 화면으로 넘기는 값에도 넣지 않는다.

type Field = keyof SetupRequest
type FieldErrors = Partial<Record<Field, string>>

const FIELDS: readonly Field[] = ['storeName', 'ownerName', 'loginId', 'password', 'passwordConfirm']
const EMPTY_VALUES: SetupRequest = { storeName: '', ownerName: '', loginId: '', password: '', passwordConfirm: '' }

const LOGIN_ID_HELP = '영문·숫자·마침표 4–20자'
const OWNER_ACCOUNT_NOTICE = '이 계정은 직원 추가·고객 비활성화까지 모든 기능을 쓰는 사장님 계정입니다'

// 화면설계 이미지에 맞춘 이 화면만의 값. 공통 값(--sidebar·글꼴·입력 칸)을 바꾸면 다른 화면이 달라져서 여기서 맞춘다.
// 글꼴은 Noto Sans KR 이 설치된 기기에서만 이미지와 같고, 없으면 공통 글꼴로 돌아간다
const PAGE_CLASS = "bg-[#1B2559] [font-family:'Noto_Sans_KR',var(--font-sans)]"
// 포커스·오류 테두리 2px 을 칸 안쪽으로 그려 칸 크기(42px)가 그대로이게 한다
const INPUT_CLASS =
  'px-[14px] pt-[3px] pb-[5px] caret-[#2B4ACB] placeholder:text-[#9AA1B2] focus-visible:ring-inset aria-invalid:ring-inset'
// 비밀번호 점 간격
const PASSWORD_INPUT_CLASS = 'tracking-[1.75px]'
// 칸 아래 글자 줄의 위 여백
const BELOW_FIELD_CLASS = 'mt-[4px]'

export function SignupPage() {
  const [setup, setSetup] = useState<'checking' | 'open' | 'closed'>('checking')

  useEffect(() => {
    const controller = new AbortController()
    getSetupStatus(controller.signal)
      .then(({ available }) => setSetup(available ? 'open' : 'closed'))
      .catch(() => {
        // 열 수 있는지 모르면 폼을 그리지 않고 로그인 화면으로 보낸다
        if (!controller.signal.aborted) setSetup('closed')
      })
    return () => controller.abort()
  }, [])

  if (setup === 'closed') {
    return <Navigate to={LOGIN_PATH} replace state={{ skipSetupCheck: true } satisfies LoginLocationState} />
  }
  if (setup === 'checking') {
    return (
      <div
        role="status"
        data-testid="signup-checking"
        className={cn('flex min-h-screen items-center justify-center gap-3 text-white', PAGE_CLASS)}
      >
        <Loader2 aria-hidden="true" className="size-6 animate-spin" />
        매장 개설 여부를 확인하는 중…
      </div>
    )
  }
  return <SignupForm />
}

function SignupForm() {
  const { login } = useAuth()
  const navigate = useNavigate()

  const storeNameId = useId()
  const ownerNameId = useId()
  const loginIdId = useId()
  const passwordId = useId()
  const confirmId = useId()
  const formErrorId = useId()

  const [values, setValues] = useState<SetupRequest>(EMPTY_VALUES)
  // 칸을 벗어난 뒤부터 그 칸의 형식 오류를 보여 준다
  const [touched, setTouched] = useState<Partial<Record<Field, boolean>>>({})
  const [serverErrors, setServerErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  const { storeName, ownerName, loginId, password, passwordConfirm } = values

  // 화면 검사는 편의다. 최종 검증은 서버가 한다
  const passwordLengthOk = hasValidPasswordLength(password)
  const passwordTooManyBytes = exceedsPasswordBytes(password)
  const passwordValid = passwordLengthOk && !passwordTooManyBytes

  const clientErrors: FieldErrors = {
    storeName: touched.storeName && !isValidStoreName(storeName) ? STORE_NAME_INVALID_MESSAGE : undefined,
    ownerName: touched.ownerName && !isValidName(ownerName) ? NAME_INVALID_MESSAGE : undefined,
    loginId: touched.loginId && !isValidLoginId(loginId) ? LOGIN_ID_INVALID_MESSAGE : undefined,
    // 글자 수는 맞아도 바이트가 넘칠 수 있다. 바이트는 칸을 벗어나기 전에도 바로 알린다
    password:
      passwordLengthOk && passwordTooManyBytes
        ? PASSWORD_TOO_MANY_BYTES_MESSAGE
        : touched.password && !passwordLengthOk
          ? PASSWORD_LENGTH_INVALID_MESSAGE
          : undefined,
    // 확인 칸은 입력할 때마다 비교한다
    passwordConfirm:
      passwordConfirm !== '' && passwordConfirm !== password ? PASSWORD_CONFIRM_MISMATCH_MESSAGE : undefined,
  }
  const errorOf = (field: Field) => clientErrors[field] ?? serverErrors[field]

  const canSubmit =
    isValidStoreName(storeName) &&
    isValidName(ownerName) &&
    isValidLoginId(loginId) &&
    passwordValid &&
    passwordConfirm === password &&
    !FIELDS.some((field) => serverErrors[field]) &&
    !pending

  function change(field: Field, value: string) {
    setValues((prev) => ({ ...prev, [field]: value }))
    setServerErrors((prev) => ({
      ...prev,
      [field]: undefined,
      // 비밀번호를 고치면 확인 칸의 서버 오류도 더는 맞지 않는다
      ...(field === 'password' ? { passwordConfirm: undefined } : {}),
    }))
    setFormError(null)
  }

  function touch(field: Field) {
    setTouched((prev) => (prev[field] ? prev : { ...prev, [field]: true }))
  }

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (!canSubmit) return
    setPending(true)
    setServerErrors({})
    setFormError(null)

    try {
      await setUp(values)
    } catch (err: unknown) {
      if (err instanceof ApiError && err.code === 'SETUP_ALREADY_COMPLETED') {
        navigate(LOGIN_PATH, {
          replace: true,
          state: { skipSetupCheck: true, notice: err.message } satisfies LoginLocationState,
        })
        return
      }
      showError(err)
      setPending(false)
      return
    }

    // 여기부터는 계정이 이미 만들어졌다 — 실패해도 개설을 다시 보내지 않고 로그인 화면에서 이어 가게 한다
    try {
      await login({ loginId, password })
      navigate(DEFAULT_AFTER_LOGIN, { replace: true })
    } catch {
      navigate(LOGIN_PATH, { replace: true, state: { skipSetupCheck: true, loginId } satisfies LoginLocationState })
    }
  }

  // 분기는 code 로만 한다
  function showError(err: unknown) {
    if (err instanceof ApiError && err.code === 'INVALID_INPUT') {
      const next: FieldErrors = {}
      for (const fieldError of err.fieldErrors) {
        const field = FIELDS.find((f) => f === fieldError.field)
        if (field && !next[field]) next[field] = fieldError.message
      }
      setServerErrors(next)
      // 칸에 붙일 수 없는 오류만 있으면 폼 안에 보여 준다
      if (Object.keys(next).length === 0) setFormError(toUserMessage(err))
      return
    }
    setFormError(toUserMessage(err))
  }

  const fieldProps = (field: Field) => ({
    value: values[field],
    onChange: (value: string) => change(field, value),
    onBlur: () => touch(field),
    disabled: pending,
    error: errorOf(field),
  })

  return (
    // 화면설계 SCR-11 (1024×768): 카드 500×704·모서리 20px·좌우 여백 34px, 위 16px·아래 48px.
    // 더 낮은 화면에서는 min-h-screen 이라 문서가 길어져 세로로 스크롤된다
    <div className={cn('flex min-h-screen items-center justify-center px-6 pt-4 pb-12', PAGE_CLASS)}>
      <main className="w-full max-w-[500px] rounded-[20px] bg-white px-[34px] pt-[25.5px] pb-[24px]" data-testid="signup-page">
        <p className="text-[24px] leading-[30px] font-bold text-[#141B34]">CreditBook</p>
        <h1 className="mt-[9.5px] text-[14px] leading-5 font-bold text-[#141B34]">매장 개설 · 사장님 가입</h1>
        <p className="mt-[0.5px] text-[12px] leading-[19px] text-[#6B7280] break-keep">
          매장을 처음 열 때 한 번만 하는 가입입니다
        </p>

        <form
          onSubmit={handleSubmit}
          noValidate
          aria-label="매장 개설"
          aria-busy={pending}
          data-testid="signup-form"
          className="mt-[14px] flex flex-col"
        >
          <SetupField
            {...fieldProps('storeName')}
            id={storeNameId}
            label="매장명"
            help="1–30자"
            autoFocus
            autoComplete="organization"
            testId="signup-store-name"
          />
          <SetupField
            {...fieldProps('ownerName')}
            id={ownerNameId}
            label="사장 이름"
            help="1–50자"
            autoComplete="name"
            testId="signup-owner-name"
          />
          <SetupField
            {...fieldProps('loginId')}
            id={loginIdId}
            label="아이디"
            help={LOGIN_ID_HELP}
            placeholder={LOGIN_ID_HELP}
            autoComplete="username"
            testId="signup-login-id"
          />
          <SetupField
            {...fieldProps('password')}
            id={passwordId}
            label="비밀번호"
            type="password"
            help={passwordValid ? '✓ 8–72자' : '8–72자'}
            helpOk={passwordValid}
            autoComplete="new-password"
            testId="signup-password"
          />
          <SetupField
            {...fieldProps('passwordConfirm')}
            id={confirmId}
            label="비밀번호 확인"
            type="password"
            autoComplete="new-password"
            testId="signup-password-confirm"
          />

          <p
            data-testid="signup-owner-notice"
            className="-mt-[0.5px] rounded-[9px] border border-[#F2C46B] bg-[#FFF6E5] px-[14px] pt-[6.5px] pb-[8.5px] text-[12px] leading-[19px] font-bold text-[#8A5300] break-keep"
          >
            {OWNER_ACCOUNT_NOTICE}
          </p>

          {formError && (
            <p id={formErrorId} role="alert" data-testid="signup-error" className={cn(FIELD_ERROR_CLASS, 'text-[12.5px] leading-[17px]')}>
              {formError}
            </p>
          )}

          <Button
            type="submit"
            disabled={!canSubmit}
            aria-label={pending ? '매장 개설 중' : undefined}
            aria-describedby={formError ? formErrorId : undefined}
            data-testid="signup-submit"
            data-state={pending ? 'pending' : 'idle'}
            className={cn('mt-3 h-[44px] rounded-[9px] border-0 pb-[2px] text-[15px] font-bold', pending && 'disabled:opacity-60')}
          >
            {pending ? <Loader2 aria-hidden="true" className="size-6 animate-spin" /> : '매장 개설'}
          </Button>
        </form>
      </main>
    </div>
  )
}

function SetupField({
  id,
  label,
  type = 'text',
  value,
  onChange,
  onBlur,
  disabled,
  error,
  help,
  helpOk = false,
  placeholder,
  autoFocus,
  autoComplete,
  testId,
}: {
  id: string
  label: string
  type?: 'text' | 'password'
  value: string
  onChange: (value: string) => void
  onBlur: () => void
  disabled: boolean
  error: string | undefined
  help?: string
  helpOk?: boolean
  placeholder?: string
  autoFocus?: boolean
  autoComplete: string
  testId: string
}) {
  const helpId = `${id}-help`
  const errorId = `${id}-error`
  // 화면설계처럼 오류가 있으면 안내 대신 오류를 그 자리에 보여 준다
  const showHelp = help !== undefined && !error
  return (
    // 칸 하나의 자리 94px(이름 줄 위 → 다음 이름 줄 위). 안내·오류 한 줄은 그 안 빈 곳에 들어간다.
    // 글자 수 제한(maxLength)은 두지 않는다 — 붙여 넣은 값이 조용히 잘리면 비밀번호가 입력과 달라진다. 넘치면 오류로 알린다
    <div className="flex min-h-[94px] flex-col">
      {/* '필수'는 label 밖에 둔다 — 칸의 접근 이름(getByLabel)이 칸 이름 그대로이게. 필수 여부는 aria-required */}
      <div className={cn(FIELD_LABEL_CLASS, 'gap-[6px]')}>
        <label htmlFor={id}>{label}</label>
        <span aria-hidden="true" className={REQUIRED_MARK_CLASS}>
          필수
        </span>
      </div>
      <Input
        id={id}
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        disabled={disabled}
        required
        aria-required="true"
        placeholder={placeholder}
        autoFocus={autoFocus}
        autoComplete={autoComplete}
        autoCapitalize="none"
        spellCheck={false}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : showHelp ? helpId : undefined}
        data-testid={testId}
        className={cn(FIELD_INPUT_CLASS, INPUT_CLASS, type === 'password' && PASSWORD_INPUT_CLASS)}
      />
      {showHelp && (
        <p id={helpId} data-testid={`${testId}-help`} className={cn(FIELD_HELP_CLASS, BELOW_FIELD_CLASS, helpOk && FIELD_HELP_OK_CLASS)}>
          {help}
        </p>
      )}
      {error && (
        <p id={errorId} role="alert" data-testid={`${testId}-error`} className={cn(FIELD_ERROR_CLASS, BELOW_FIELD_CLASS)}>
          {error}
        </p>
      )}
    </div>
  )
}
