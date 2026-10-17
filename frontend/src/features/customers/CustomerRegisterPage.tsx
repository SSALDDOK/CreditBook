import { Loader2 } from 'lucide-react'
import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode, type RefObject } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { ApiError, toUserMessage } from '@/api/client'
import {
  readDuplicatePhoneCustomers,
  registerCustomer,
  type CustomerRegisterResponse,
  type DuplicatePhoneCustomer,
} from '@/api/customers'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  customerListTarget,
  listReturnOf,
  type CustomerListReturn,
  type CustomerLocationState,
} from './customerListReturn'
import {
  countChars,
  formatPhoneForDisplay,
  MEMO_MAX_LENGTH,
  prefillFromSearch,
  validateRegisterInput,
  type RegisterField,
  type RegisterFieldErrors,
} from './customerRegisterInput'
import { DuplicatePhoneDialog } from './DuplicatePhoneDialog'

/**
 * 고객 목록 → 고객 등록으로 넘기는 화면 이동 상태. 주소(URL)에는 넣지 않는다 — 연락처가 브라우저 기록·서버 로그에 남지 않게.
 * - prefillQuery: 검색 0건의 [바로 등록]이 넘긴 검색어(칸 채우기 규칙은 prefillFromSearch)
 * - listReturn: [취소]·브레드크럼 '고객'이 돌아갈 목록(검색어·페이지·비활성 포함)
 */
export type CustomerRegisterLocationState = { listReturn?: CustomerListReturn; prefillQuery?: string } | null

/**
 * SCR-4 고객 등록. 고객 앞에서 바로 입력하는 화면이라 이름·연락처·메모만 받는다(STAFF·ADMIN 같은 화면).
 * 등록되면 고객 상세(SCR-5)로 간다 — "등록 후 바로 충전하기"가 켜져 있으면 상세가 기존 충전 모달(SCR-6)을 바로 연다.
 * 같은 번호 고객이 있으면 서버가 409 DUPLICATE_PHONE 을 주고, 확인 창에서 [새로 등록]을 누르면 같은 입력으로
 * confirmDuplicatePhone: true 를 다시 보낸다. 등록 API 에는 멱등키가 없어 자동 재시도는 하지 않는다.
 */
export function CustomerRegisterPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const entryState = location.state as CustomerRegisterLocationState
  const listReturn = listReturnOf(entryState)
  // 넘겨받은 검색어는 첫 렌더에서만 읽는다(수정 가능)
  const [initial] = useState(() => prefillFromSearch(entryState?.prefillQuery))

  const [name, setName] = useState(initial.name)
  const [phone, setPhone] = useState(initial.phone)
  const [memo, setMemo] = useState('')
  const [chargeAfter, setChargeAfter] = useState(true)
  const [errors, setErrors] = useState<RegisterFieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const pendingRef = useRef(false)
  const [duplicates, setDuplicates] = useState<DuplicatePhoneCustomer[] | null>(null)
  // 확인 창의 부제·[새로 등록]에 쓸, 409 를 받았을 때의 입력값. 창이 떠 있는 동안 폼을 고쳐도 이 값으로 다시 보낸다
  const [duplicateInput, setDuplicateInput] = useState<{ name: string; phone: string; memo: string } | null>(null)

  const titleId = useId()
  const nameRef = useRef<HTMLInputElement>(null)
  const phoneRef = useRef<HTMLInputElement>(null)
  const memoRef = useRef<HTMLInputElement>(null)
  const fieldRefs: Record<RegisterField, RefObject<HTMLInputElement | null>> = {
    name: nameRef,
    phone: phoneRef,
    memo: memoRef,
  }

  // 커서: 채운 칸 다음의 빈 필수 칸(이름을 채웠으면 연락처, 연락처를 채웠으면 이름), 빈 칸으로 들어왔으면 이름 칸
  useEffect(() => {
    const target = initial.name ? phoneRef : nameRef
    target.current?.focus()
  }, [initial])

  // 넘겨받은 검색어(연락처일 수 있다)를 방문 기록의 화면 상태에서도 지운다. 돌아갈 목록 조건만 남긴다
  useEffect(() => {
    if (entryState?.prefillQuery === undefined) return
    navigate(location.pathname, { replace: true, state: { listReturn } satisfies CustomerRegisterLocationState })
  }, [entryState?.prefillQuery, listReturn, location.pathname, navigate])

  function changeField(field: RegisterField, value: string) {
    if (field === 'name') setName(value)
    else if (field === 'phone') setPhone(value)
    else setMemo(value)
    setErrors((prev) => (prev[field] ? { ...prev, [field]: undefined } : prev))
    setFormError(null)
  }

  function showFieldErrors(next: RegisterFieldErrors) {
    setErrors(next)
    const first = (['name', 'phone', 'memo'] as const).find((f) => next[f])
    if (first) fieldRefs[first].current?.focus()
  }

  function goToCustomer(customerId: string, openCharge: boolean) {
    const state: CustomerLocationState = openCharge ? { listReturn, openCharge: true } : { listReturn }
    // 등록 화면은 방문 기록에서 바꿔 치운다 — 상세에서 뒤로가기로 채워진 폼에 돌아와 같은 고객을 또 만드는 일이 없게
    navigate(`/customers/${customerId}`, { replace: true, state })
  }

  async function send(input: { name: string; phone: string; memo: string }, confirmDuplicatePhone: boolean) {
    if (pendingRef.current) return // 더블클릭해도 요청은 1번
    pendingRef.current = true
    setPending(true)
    setFormError(null)
    let created: CustomerRegisterResponse | null = null
    try {
      created = await registerCustomer({
        name: input.name.trim(),
        phone: input.phone.trim(),
        ...(input.memo.trim() ? { memo: input.memo.trim() } : {}),
        confirmDuplicatePhone,
      })
    } catch (e: unknown) {
      handleError(e, input)
    } finally {
      pendingRef.current = false
      setPending(false)
    }
    if (created) goToCustomer(created.id, chargeAfter)
  }

  // 분기는 code 로만 한다. 문구는 서버 message → 5xx·응답 없음이면 공통 문구(toUserMessage)
  function handleError(e: unknown, input: { name: string; phone: string; memo: string }) {
    setDuplicates(null)
    if (e instanceof ApiError) {
      if (e.status === 401) return // 로그인 화면 이동은 공통 401 처리가 한다
      if (e.status < 500) {
        switch (e.code) {
          case 'DUPLICATE_PHONE': {
            const existing = readDuplicatePhoneCustomers(e.details)
            if (existing) {
              setDuplicateInput(input)
              setDuplicates(existing)
              return
            }
            break
          }
          case 'INVALID_INPUT': {
            const next: RegisterFieldErrors = {}
            for (const f of e.fieldErrors) {
              if ((f.field === 'name' || f.field === 'phone' || f.field === 'memo') && !next[f.field]) {
                next[f.field] = f.message
              }
            }
            if (Object.keys(next).length > 0) {
              showFieldErrors(next)
              return
            }
            break
          }
          case 'INVALID_CUSTOMER_NAME':
            showFieldErrors({ name: toUserMessage(e) })
            return
          case 'INVALID_PHONE_NUMBER':
            showFieldErrors({ phone: toUserMessage(e) })
            return
        }
      }
    }
    setFormError(toUserMessage(e))
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (pendingRef.current) return
    const input = { name, phone, memo }
    const invalid = validateRegisterInput(input)
    if (Object.keys(invalid).length > 0) {
      // 형식이 틀리면 서버에 보내지 않고 칸 아래에 안내한다
      showFieldErrors(invalid)
      return
    }
    void send(input, false)
  }

  const memoCount = countChars(memo)

  return (
    <div className="flex flex-col pt-1">
      <section
        aria-labelledby={titleId}
        data-testid="customer-register"
        data-state={pending ? 'pending' : 'idle'}
        className="w-full max-w-[34rem] rounded-2xl border bg-background px-7 pt-7 pb-6"
      >
        <h2 id={titleId} className="text-xl font-bold">
          새 고객 등록
        </h2>

        <form onSubmit={handleSubmit} noValidate aria-label="새 고객 등록" className="mt-5 flex flex-col gap-5">
          <RegisterTextField
            label="이름"
            required
            hint="1–20자, 공백만은 안 됩니다"
            value={name}
            onChange={(v) => changeField('name', v)}
            error={errors.name}
            inputRef={nameRef}
            autoComplete="off"
            testId="customer-register-name"
          />
          <RegisterTextField
            label="연락처"
            required
            hint="숫자 9–11자리 · 하이픈은 있어도 되고 없어도 됩니다"
            placeholder="010-0000-0000"
            type="tel"
            inputMode="tel"
            value={phone}
            onChange={(v) => changeField('phone', v)}
            error={errors.phone}
            inputRef={phoneRef}
            autoComplete="off"
            testId="customer-register-phone"
          />
          <RegisterTextField
            label="메모"
            hint="200자 이하"
            placeholder="예: 단골, 아메리카노 선호"
            value={memo}
            onChange={(v) => changeField('memo', v)}
            maxLength={MEMO_MAX_LENGTH}
            error={errors.memo}
            inputRef={memoRef}
            autoComplete="off"
            testId="customer-register-memo"
            counter={
              <span className="text-sm text-muted-foreground tabular-nums" data-testid="customer-register-memo-count">
                {memoCount} / {MEMO_MAX_LENGTH}
              </span>
            }
          />

          <ChargeAfterCheckbox checked={chargeAfter} onChange={setChargeAfter} />

          {formError && (
            <p
              role="alert"
              className="rounded-xl bg-destructive/10 px-4 py-3 text-base text-destructive"
              data-testid="customer-register-error"
            >
              {formError}
            </p>
          )}

          <div className="flex justify-end gap-3 border-t pt-5">
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                const list = customerListTarget(listReturn)
                navigate(list.to, { state: list.state })
              }}
              className="h-11 min-w-24 rounded-xl px-6 text-base font-bold"
              data-testid="customer-register-cancel"
            >
              취소
            </Button>
            <Button
              type="submit"
              disabled={pending}
              aria-label={pending ? '등록 처리 중' : undefined}
              data-state={pending ? 'pending' : 'idle'}
              className="h-11 min-w-28 rounded-xl px-6 text-base font-bold"
              data-testid="customer-register-submit"
            >
              {pending ? <Loader2 aria-hidden="true" className="size-5 animate-spin" /> : '등록'}
            </Button>
          </div>
        </form>
      </section>

      <DuplicatePhoneDialog
        customers={duplicates}
        phoneDisplay={duplicateInput ? formatPhoneForDisplay(duplicateInput.phone) : ''}
        pending={pending}
        onClose={() => setDuplicates(null)}
        onRegisterAnyway={() => {
          if (duplicateInput) void send(duplicateInput, true)
        }}
        onGoToCustomer={(id) => goToCustomer(id, false)}
      />
    </div>
  )
}

function RegisterTextField({
  label,
  required = false,
  hint,
  value,
  onChange,
  error,
  inputRef,
  testId,
  counter,
  ...inputProps
}: {
  label: string
  required?: boolean
  hint: string
  value: string
  onChange: (value: string) => void
  error?: string
  inputRef: RefObject<HTMLInputElement | null>
  testId: string
  counter?: ReactNode
  placeholder?: string
  type?: 'text' | 'tel'
  inputMode?: 'text' | 'tel'
  maxLength?: number
  autoComplete?: string
}) {
  const inputId = useId()
  const hintId = useId()
  const errorId = useId()
  const describedBy = error ? `${hintId} ${errorId}` : hintId

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-baseline justify-between">
        <label htmlFor={inputId} className="text-base font-bold">
          {label}
          {required ? (
            <span className="ml-1.5 text-sm font-semibold text-destructive">필수</span>
          ) : (
            <span className="ml-1.5 text-sm font-normal text-muted-foreground">선택</span>
          )}
        </label>
        {counter}
      </div>
      <Input
        ref={inputRef}
        id={inputId}
        type={inputProps.type ?? 'text'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        required={required}
        aria-required={required || undefined}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        data-testid={`${testId}-input`}
        {...inputProps}
        className="h-11 rounded-xl px-4 text-base md:text-base"
      />
      <p id={hintId} className="text-sm text-muted-foreground">
        {hint}
      </p>
      {error && (
        <p id={errorId} role="alert" className="text-sm font-semibold text-destructive" data-testid={`${testId}-error`}>
          {error}
        </p>
      )}
    </div>
  )
}

function ChargeAfterCheckbox({ checked, onChange }: { checked: boolean; onChange: (checked: boolean) => void }) {
  const id = useId()
  return (
    <div className="flex items-center gap-3">
      <input
        id={id}
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        data-testid="customer-register-charge-after"
        className="size-5 accent-primary"
      />
      <label htmlFor={id} className="text-base">
        등록 후 바로 충전하기
      </label>
    </div>
  )
}
