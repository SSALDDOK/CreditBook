import { Loader2 } from 'lucide-react'
import { useId, useRef, useState, type FormEvent, type RefObject } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router'
import { ApiError, toUserMessage } from '@/api/client'
import {
  editCustomer,
  readDuplicatePhoneCustomers,
  type CustomerDetailResponse,
  type CustomerEditRequest,
  type DuplicatePhoneCustomer,
} from '@/api/customers'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/features/auth/AuthContext'
import { cn } from '@/lib/utils'
import { listReturnOf, type CustomerLocationState } from './customerListReturn'
import {
  countChars,
  formatPhoneForDisplay,
  MEMO_MAX_LENGTH,
  validateEditInput,
  type RegisterField,
  type RegisterFieldErrors,
} from './customerRegisterInput'
import { CustomerLoadError } from './CustomerLoadError'
import { CustomerTextField } from './CustomerTextField'
import { DeactivateCustomerDialog } from './DeactivateCustomerDialog'
import { DuplicatePhoneDialog, type DuplicatePhoneDialogText } from './DuplicatePhoneDialog'
import { useCustomerDetail } from './useCustomerDetail'

/** 다른 곳에서 먼저 고쳐 409 CONCURRENT_MODIFICATION 을 받았을 때 폼 위 노란 상자 — 화면 고정 문구(서버 문구 쓰지 않음) */
const CONFLICT_NOTICE = '다른 곳에서 이 고객 정보가 먼저 바뀌었습니다. 최신 내용을 불러왔으니 다시 확인해 주세요.'

const DUPLICATE_TEXT: DuplicatePhoneDialogText = {
  confirmLabel: '그래도 저장',
  confirmPendingLabel: '그래도 저장 처리 중',
  confirmTestId: 'duplicate-phone-save-anyway',
}

const FORM_BUTTON_CLASS = 'h-11 rounded-xl px-6 text-base font-bold'

type EditInput = { name: string; phone: string; memo: string }

/**
 * SCR-9 고객 정보 수정 (REQ-3·REQ-4). 고객 상세의 [정보 수정]에서 온다 — STAFF·ADMIN 같은 폼.
 * - 폼: 이름(필수)·연락처(비워서 열고, 입력했을 때만 바꾼다)·메모. 저장은 PATCH /api/customers/{id} 에 상세의 version 을 실어 보낸다.
 *   성공하면 고객 상세로 가고, 상세는 서버에서 다시 읽는다.
 * - 409 CONCURRENT_MODIFICATION: 입력을 버리고 최신 값을 다시 불러와 폼을 채운 뒤 노란 상자로 알린다.
 * - 409 DUPLICATE_PHONE: 고객 등록과 같은 확인 창 — [그래도 저장]은 같은 입력으로 confirmDuplicatePhone: true 를 다시 보낸다.
 * - 위험 구역(고객 비활성화)은 ADMIN 에게만 그린다(STAFF 는 DOM 에 없음 — 최종 판단은 서버의 403). 비활성화할 수 있는지(잔액 0원)는
 *   서버가 판단한다. 이미 비활성인 고객이면 위험 구역 대신 안내만 보인다.
 * 화면은 잔액을 계산하지 않는다. 변경 요청이라 자동 재시도는 없다.
 */
export function CustomerEditPage() {
  const { customerId = '' } = useParams()
  const { state, refetch } = useCustomerDetail(customerId)
  const { state: auth } = useAuth()
  // 위험 구역을 그릴지 정하는 편의 판단일 뿐 — 비활성화 권한은 서버가 확인한다(STAFF 는 403)
  const isAdmin = auth.status === 'authenticated' && auth.session.role === 'ADMIN'
  // 노란 상자를 띄운 고객 id — 같은 화면에서 다른 고객으로 옮겨 가면 상자가 따라가지 않는다
  const [conflictFor, setConflictFor] = useState<string | null>(null)
  const conflictNotice = conflictFor === customerId
  // 동시 수정 뒤 같은 버전으로 다시 받아도 폼을 새로 채우도록 key 에 더한다
  const [resetCount, setResetCount] = useState(0)

  function handleConflict() {
    setConflictFor(customerId)
    setResetCount((n) => n + 1)
    refetch()
  }

  const notFound = state.status === 'error' && state.error instanceof ApiError && state.error.code === 'CUSTOMER_NOT_FOUND'
  const viewState =
    state.status === 'loading' ? 'loading' : state.status === 'success' ? 'ready' : notFound ? 'not-found' : 'error'

  return (
    <div
      className="flex flex-col gap-4 pt-1"
      data-testid="customer-edit"
      data-state={viewState}
      aria-busy={state.status === 'loading'}
    >
      {state.status === 'error' ? (
        <CustomerLoadError error={state.error} onRetry={refetch} testIdPrefix="customer-edit" />
      ) : state.data ? (
        <CustomerEditView
          key={`${state.data.id}:${state.data.version}:${resetCount}`}
          customer={state.data}
          loading={state.status === 'loading'}
          isAdmin={isAdmin}
          conflictNotice={conflictNotice}
          onConflict={handleConflict}
          onNotFound={refetch}
        />
      ) : (
        <p role="status" className="p-8 text-muted-foreground" data-testid="customer-edit-loading">
          불러오는 중…
        </p>
      )}
    </div>
  )
}

function CustomerEditView({
  customer,
  loading,
  isAdmin,
  conflictNotice,
  onConflict,
  onNotFound,
}: {
  customer: CustomerDetailResponse
  /** 최신 값을 다시 불러오는 중 — 직전 응답을 흐리게 보이고 [저장]을 막는다(옛 version 으로 보내지 않게) */
  loading: boolean
  isAdmin: boolean
  conflictNotice: boolean
  onConflict: () => void
  /** 저장·비활성화 중 404 — 다시 불러와 공통 오류 카드를 보인다 */
  onNotFound: () => void
}) {
  const location = useLocation()
  const navigate = useNavigate()
  // 고객 상세로 돌아갈 때 목록 조건(listReturn)만 넘긴다 — 브레드크럼 '고객'이 보던 목록으로 돌아가게
  const backState: CustomerLocationState = { listReturn: listReturnOf(location.state) }

  const [name, setName] = useState(customer.name)
  const [phone, setPhone] = useState('')
  const [memo, setMemo] = useState(customer.memo ?? '')
  const [errors, setErrors] = useState<RegisterFieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const pendingRef = useRef(false)
  const [duplicates, setDuplicates] = useState<DuplicatePhoneCustomer[] | null>(null)
  // 확인 창의 부제·[그래도 저장]에 쓸, 409 를 받았을 때의 입력값
  const [duplicateInput, setDuplicateInput] = useState<EditInput | null>(null)
  const [deactivateOpen, setDeactivateOpen] = useState(false)

  const titleId = useId()
  const dangerTitleId = useId()
  const nameRef = useRef<HTMLInputElement>(null)
  const phoneRef = useRef<HTMLInputElement>(null)
  const memoRef = useRef<HTMLInputElement>(null)
  const fieldRefs: Record<RegisterField, RefObject<HTMLInputElement | null>> = {
    name: nameRef,
    phone: phoneRef,
    memo: memoRef,
  }

  function goToDetail(customerId: string) {
    // 수정 화면은 방문 기록에서 바꿔 치운다 — 상세에서 뒤로가기로 옛 값이 채워진 폼에 돌아오지 않게
    navigate(`/customers/${customerId}`, { replace: true, state: backState })
  }

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

  async function send(input: EditInput, confirmDuplicatePhone: boolean) {
    if (pendingRef.current) return // 더블클릭해도 요청은 1번
    pendingRef.current = true
    setPending(true)
    setFormError(null)
    const trimmedPhone = input.phone.trim()
    const request: CustomerEditRequest = {
      version: customer.version,
      name: input.name.trim(),
      // 연락처 칸이 비어 있으면 보내지 않는다(연락처 그대로)
      ...(trimmedPhone ? { phone: trimmedPhone } : {}),
      // 빈 문자열이면 서버가 메모를 지운다
      memo: input.memo.trim(),
      ...(confirmDuplicatePhone ? { confirmDuplicatePhone: true } : {}),
    }
    let saved = false
    try {
      await editCustomer(customer.id, request)
      saved = true
    } catch (e: unknown) {
      handleError(e, input)
    } finally {
      pendingRef.current = false
      setPending(false)
    }
    if (saved) goToDetail(customer.id)
  }

  // 분기는 code 로만 한다. 문구는 서버 message → 5xx·응답 없음이면 공통 문구(toUserMessage)
  function handleError(e: unknown, input: EditInput) {
    setDuplicates(null)
    if (e instanceof ApiError) {
      if (e.status === 401) return // 로그인 화면 이동은 공통 401 처리가 한다
      if (e.status < 500) {
        switch (e.code) {
          case 'CONCURRENT_MODIFICATION':
            onConflict()
            return
          case 'CUSTOMER_NOT_FOUND':
            onNotFound()
            return
          case 'PASSWORD_CHANGE_REQUIRED':
            return // 비밀번호 변경 화면 이동은 공통 처리(client.ts)가 한다
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
            // version 같은 칸 밖의 오류는 아래 공통 문구 자리에 서버 문구로 보인다
            const other = e.fieldErrors[0]
            if (other) {
              setFormError(other.message)
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
    if (pendingRef.current || loading) return
    const input = { name, phone, memo }
    const invalid = validateEditInput(input)
    if (Object.keys(invalid).length > 0) {
      // 형식이 틀리면 서버에 보내지 않고 칸 아래에 안내한다
      showFieldErrors(invalid)
      return
    }
    void send(input, false)
  }

  const memoCount = countChars(memo)
  const saveDisabled = pending || loading

  return (
    <div className={cn('flex flex-col gap-4', loading && 'opacity-60')}>
      {conflictNotice && (
        <p
          role="alert"
          data-testid="customer-edit-conflict"
          className="w-full max-w-[34rem] rounded-2xl border border-amber-300 bg-amber-50 px-5 py-4 font-semibold text-amber-900"
        >
          {CONFLICT_NOTICE}
        </p>
      )}

      <section
        aria-labelledby={titleId}
        data-testid="customer-edit-form-card"
        data-state={pending ? 'pending' : 'idle'}
        className="w-full max-w-[34rem] rounded-2xl border bg-background px-7 pt-7 pb-6"
      >
        <h2 id={titleId} className="text-xl font-bold">
          고객 정보 수정
        </h2>

        <form onSubmit={handleSubmit} noValidate aria-label="고객 정보 수정" className="mt-5 flex flex-col gap-5">
          <CustomerTextField
            label="이름"
            required
            hint="1–20자, 공백만은 안 됩니다"
            value={name}
            onChange={(v) => changeField('name', v)}
            error={errors.name}
            inputRef={nameRef}
            autoComplete="off"
            testId="customer-edit-name"
          />
          <CustomerTextField
            label="연락처"
            badge="none"
            labelNote={`지금 번호 ${customer.maskedPhone} · 바꿀 때만 입력`}
            hint="숫자 9–11자리 · 하이픈은 있어도 되고 없어도 됩니다"
            placeholder="010-0000-0000"
            type="tel"
            inputMode="tel"
            value={phone}
            onChange={(v) => changeField('phone', v)}
            error={errors.phone}
            inputRef={phoneRef}
            autoComplete="off"
            testId="customer-edit-phone"
          />
          <CustomerTextField
            label="메모"
            hint="200자 이하"
            placeholder="예: 단골, 아메리카노 선호"
            value={memo}
            onChange={(v) => changeField('memo', v)}
            maxLength={MEMO_MAX_LENGTH}
            error={errors.memo}
            inputRef={memoRef}
            autoComplete="off"
            testId="customer-edit-memo"
            counter={
              <span className="text-sm text-muted-foreground tabular-nums" data-testid="customer-edit-memo-count">
                {memoCount} / {MEMO_MAX_LENGTH}
              </span>
            }
          />

          {formError && (
            <p
              role="alert"
              className="rounded-xl bg-destructive/10 px-4 py-3 text-base text-destructive"
              data-testid="customer-edit-error"
            >
              {formError}
            </p>
          )}

          <div className="flex justify-end gap-3 border-t pt-5">
            <Button
              type="button"
              variant="outline"
              onClick={() => goToDetail(customer.id)}
              className={cn(FORM_BUTTON_CLASS, 'min-w-24')}
              data-testid="customer-edit-cancel"
            >
              취소
            </Button>
            <Button
              type="submit"
              disabled={saveDisabled}
              aria-label={pending ? '저장 처리 중' : undefined}
              data-state={pending ? 'pending' : 'idle'}
              className={cn(FORM_BUTTON_CLASS, 'min-w-28')}
              data-testid="customer-edit-submit"
            >
              {pending ? <Loader2 aria-hidden="true" className="size-5 animate-spin" /> : '저장'}
            </Button>
          </div>
        </form>
      </section>

      {!customer.active ? (
        // 비활성 고객 안내 — 권한과 무관한 고객 상태라 STAFF·ADMIN 모두 보인다(ADMIN 은 위험 구역 대신)
        <p
          className="w-full max-w-[34rem] rounded-2xl border bg-background px-7 py-5 text-muted-foreground"
          data-testid="customer-edit-inactive-notice"
        >
          비활성 고객입니다 — 충전하면 다시 활성화됩니다.
        </p>
      ) : (
        isAdmin && (
          <section
            aria-labelledby={dangerTitleId}
            data-testid="customer-edit-danger-zone"
            className="flex w-full max-w-[34rem] items-center justify-between gap-4 rounded-2xl border border-destructive/30 bg-background px-7 py-6"
          >
            <div className="min-w-0">
              <h3 id={dangerTitleId} className="text-lg font-bold">
                고객 비활성화
              </h3>
              <p className="mt-1 text-sm text-muted-foreground break-keep">잔액이 0원인 고객만 비활성화할 수 있습니다.</p>
            </div>
            <Button
              type="button"
              variant="outline"
              onClick={() => setDeactivateOpen(true)}
              disabled={pending || loading}
              aria-haspopup="dialog"
              className={cn(
                FORM_BUTTON_CLASS,
                'shrink-0 border-destructive/30 bg-destructive/10 text-destructive hover:bg-destructive/15 hover:text-destructive',
              )}
              data-testid="customer-deactivate-button"
            >
              고객 비활성화
            </Button>
          </section>
        )
      )}

      <DuplicatePhoneDialog
        customers={duplicates}
        phoneDisplay={duplicateInput ? formatPhoneForDisplay(duplicateInput.phone) : ''}
        pending={pending}
        text={{
          ...DUPLICATE_TEXT,
          // 모두 비활성일 때도 등록 화면의 '다시 오신 손님이면 …' 대신 번호 안내를 쓴다(수정 중인 고객은 이미 있다)
          inactiveDescription: duplicateInput
            ? `${formatPhoneForDisplay(duplicateInput.phone)} 번호로 이미 등록된 고객`
            : undefined,
        }}
        onClose={() => setDuplicates(null)}
        onRegisterAnyway={() => {
          if (duplicateInput) void send(duplicateInput, true)
        }}
        onGoToCustomer={(id) => navigate(`/customers/${id}`, { state: backState })}
      />

      <DeactivateCustomerDialog
        customer={deactivateOpen ? { id: customer.id, name: customer.name } : null}
        onClose={() => setDeactivateOpen(false)}
        onDeactivated={() => goToDetail(customer.id)}
        onConflict={() => {
          setDeactivateOpen(false)
          onConflict()
        }}
        onNotFound={() => {
          setDeactivateOpen(false)
          onNotFound()
        }}
      />
    </div>
  )
}
