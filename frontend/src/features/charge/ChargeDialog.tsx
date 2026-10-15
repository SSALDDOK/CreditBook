import { Loader2 } from 'lucide-react'
import { useEffect, useId, useRef, useState, type FormEvent, type RefObject } from 'react'
import { createCharge, type ChargeResponse } from '@/api/charges'
import { ApiError, COMMON_ERROR_MESSAGE, NETWORK_ERROR_MESSAGE, toUserMessage } from '@/api/client'
import type { CustomerDetailResponse } from '@/api/customers'
import type { LedgerInputSettingsResponse } from '@/api/settings'
import { Button } from '@/components/ui/button'
import { AmountField } from '@/features/ledger-input/AmountField'
import { AMOUNT_ERROR_MESSAGES, checkAmount } from '@/features/ledger-input/amountInput'
import { previewBalanceAfterCharge } from '@/features/ledger-input/balancePreview'
import type { LedgerDraft } from '@/features/ledger-input/draftStorage'
import {
  LEDGER_DIALOG_BUTTON_CLASS,
  LedgerInputDialog,
  LedgerInputDialogContent,
  type LedgerInputStep,
} from '@/features/ledger-input/LedgerInputDialog'
import { MemoField } from '@/features/ledger-input/MemoField'
import { joinMemo, selectedMemoChips } from '@/features/ledger-input/memoChips'
import { RestoredDraftBanner } from '@/features/ledger-input/RestoredDraftBanner'
import { useIdempotencyKey } from '@/features/ledger-input/useIdempotencyKey'
import { useLedgerInputSettings } from '@/features/ledger-input/useLedgerInputSettings'
import { useSaveDraftOnUnauthorized } from '@/features/ledger-input/useSaveDraftOnUnauthorized'
import { toKoreanAmount } from '@/lib/koreanAmount'
import { formatNumber, formatWon } from '@/lib/format'

// SCR-6 충전 모달 (REQ-5·6·10·17·31·33). 고객 상세(SCR-5) 위에 뜬다. ADMIN·STAFF 차이 없음.
// 1 입력 → 2 확인 → [확인] 한 번에 POST 1번. 처리 뒤 잔액은 서버 응답·상세 재조회 값으로만 보여 준다.

const QUICK_AMOUNTS = [30000, 50000, 100000]
export const CHARGE_RESTORED_MESSAGE = '입력하던 금액을 불러왔습니다. 확인 후 충전하세요.'

export function ChargeDialog({
  open,
  onCancel,
  onCharged,
  onConflict,
  customer,
  employeeId,
  restoredDraft,
}: {
  open: boolean
  /** [취소]·Esc — 호출하는 쪽이 모달을 닫고 저장된 입력(복구용)을 지운다 */
  onCancel: () => void
  /** 충전 성공 — 호출하는 쪽이 모달을 닫고 상세를 다시 불러온다 */
  onCharged: (response: ChargeResponse) => void
  /** 409(동시 처리 등) — 호출하는 쪽이 상세를 다시 불러온다. 모달은 열린 채로 둔다 */
  onConflict: () => void
  customer: CustomerDetailResponse
  employeeId: string
  /** 재로그인 후 복구할 입력. 있으면 입력 화면을 이 값으로 채우고 안내 띠를 보인다 */
  restoredDraft: LedgerDraft | null
}) {
  return (
    <LedgerInputDialog
      open={open}
      onOpenChange={(next) => {
        if (!next) onCancel()
      }}
    >
      {open && (
        <ChargeDialogBody
          customer={customer}
          employeeId={employeeId}
          restoredDraft={restoredDraft}
          onCancel={onCancel}
          onCharged={onCharged}
          onConflict={onConflict}
        />
      )}
    </LedgerInputDialog>
  )
}

function ChargeDialogBody({
  customer,
  employeeId,
  restoredDraft,
  onCancel,
  onCharged,
  onConflict,
}: {
  customer: CustomerDetailResponse
  employeeId: string
  restoredDraft: LedgerDraft | null
  onCancel: () => void
  onCharged: (response: ChargeResponse) => void
  onConflict: () => void
}) {
  const { state: settingsState, retry: retrySettings } = useLedgerInputSettings()
  const settings = settingsState.status === 'success' ? settingsState.data : null

  const [step, setStep] = useState<LedgerInputStep>('input')
  const [amountInput, setAmountInput] = useState(() =>
    restoredDraft?.amount != null ? String(restoredDraft.amount) : '',
  )
  const [amountTouched, setAmountTouched] = useState(restoredDraft?.amount != null)
  const [amountServerError, setAmountServerError] = useState<string | null>(null)
  const [memo, setMemo] = useState(() => joinMemo(restoredDraft?.memoChips ?? []))
  const [memoServerError, setMemoServerError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const pendingRef = useRef(false)
  const idempotencyKey = useIdempotencyKey()

  const amountRef = useRef<HTMLInputElement>(null)
  const titleRef = useRef<HTMLHeadingElement>(null)
  const formId = useId()

  const check = checkAmount(amountInput, settings?.chargeMaxAmount)

  // 401 로 로그인 화면에 가기 직전에 금액·칩 선택만 저장한다(REQ-17). 직접 친 메모 글자는 저장하지 않는다
  useSaveDraftOnUnauthorized(employeeId, customer.id, 'charge', () => ({
    amount: check.amount,
    memoChips: settings ? selectedMemoChips(memo, settings.chargeMemoPresets) : (restoredDraft?.memoChips ?? []),
  }))

  // 단계가 바뀌면 포커스를 옮긴다: 입력 화면은 금액 칸, 확인 화면은 제목(스크린리더가 새 화면을 읽게)
  const settingsReady = settings !== null
  useEffect(() => {
    if (step === 'input' && settingsReady) amountRef.current?.focus()
    if (step === 'confirm') titleRef.current?.focus()
  }, [step, settingsReady])

  function amountErrorMessage(): string | null {
    if (amountServerError) return amountServerError
    if (check.error === 'OVER_MAX' && settings) {
      // 한도 초과는 치는 즉시 알린다(빨간 테두리 + 문구, [충전] 비활성)
      return `한 번에 충전할 수 있는 금액은 ${formatWon(settings.chargeMaxAmount)}까지입니다.`
    }
    if (check.error && check.error !== 'OVER_MAX' && amountTouched) return AMOUNT_ERROR_MESSAGES[check.error]
    return null
  }

  function goToConfirm(e?: FormEvent) {
    e?.preventDefault()
    if (!settings || check.error !== null || check.amount === null) {
      setAmountTouched(true)
      return
    }
    idempotencyKey.issue() // 확인 화면에 들어갈 때 새 키
    setFormError(null)
    setStep('confirm')
  }

  function backToInput() {
    idempotencyKey.discard() // [이전]으로 나가면 키를 버린다
    setFormError(null)
    setStep('input')
  }

  async function submit() {
    if (pendingRef.current || check.amount === null) return // 더블클릭해도 요청은 1번
    pendingRef.current = true
    setPending(true)
    setFormError(null)
    const trimmedMemo = memo.trim()
    try {
      const response = await createCharge(
        customer.id,
        { amount: check.amount, ...(trimmedMemo ? { memo: trimmedMemo } : {}) },
        idempotencyKey.current(),
      )
      onCharged(response)
    } catch (e: unknown) {
      handleError(e)
    } finally {
      pendingRef.current = false
      setPending(false)
    }
  }

  // 분기는 code(·status)로만 한다. 문구는 서버 message → 5xx·응답 없음이면 공통 문구
  function handleError(e: unknown) {
    if (!(e instanceof ApiError)) {
      // 응답을 못 받았다 — 서버에서 처리됐을 수 있으므로 같은 키를 유지한다. 다시 [확인]하면 같은 키로 보낸다
      setFormError(`${NETWORK_ERROR_MESSAGE} [확인]을 다시 누르면 같은 요청으로 다시 보냅니다.`)
      return
    }
    if (e.status === 401) return // 로그인 화면 이동은 공통 401 처리가 한다(입력은 그 직전에 저장됨)
    if (e.status >= 500) {
      setFormError(COMMON_ERROR_MESSAGE) // 같은 키 유지
      return
    }
    switch (e.code) {
      case 'INVALID_AMOUNT':
      case 'CHARGE_LIMIT_EXCEEDED':
        backToInput()
        setAmountServerError(e.message)
        return
      case 'INVALID_INPUT': {
        const amountField = e.fieldErrors.find((f) => f.field === 'amount')
        const memoField = e.fieldErrors.find((f) => f.field === 'memo')
        backToInput()
        if (amountField) setAmountServerError(amountField.message)
        if (memoField) setMemoServerError(memoField.message)
        if (!amountField && !memoField) setFormError(toUserMessage(e))
        return
      }
      case 'IDEMPOTENCY_KEY_REUSED':
      case 'INVALID_IDEMPOTENCY_KEY':
        // 이 키는 더 쓸 수 없다 — 새 키를 만들고, 직원이 내용을 확인한 뒤 [확인]을 다시 누르게 한다
        idempotencyKey.issue()
        setFormError(toUserMessage(e))
        return
    }
    if (e.status === 409) {
      // 다른 요청이 먼저 처리됨 등 — 안내하고 상세(현재 잔액)를 서버에서 다시 받는다
      setFormError(toUserMessage(e))
      onConflict()
      return
    }
    setFormError(toUserMessage(e)) // CUSTOMER_NOT_FOUND·403 등
  }

  const formErrorBlock = formError && (
    <p role="alert" className="rounded-xl bg-destructive/10 px-4 py-3 text-base text-destructive" data-testid="charge-error">
      {formError}
    </p>
  )

  // 두 단계가 같은 DialogContent 를 쓴다(단계가 바뀔 때 모달이 다시 열리거나 포커스가 밖으로 튀지 않게)
  const confirming = step === 'confirm' && check.amount !== null

  return (
    <LedgerInputDialogContent
      step={confirming ? 'confirm' : 'input'}
      title={confirming ? '충전 내용 확인' : '선결제 충전'}
      description={
        confirming
          ? `${customer.name} 고객 · 아래 내용으로 충전합니다`
          : `${customer.name} · 현재 잔액 ${formatWon(customer.balance)}`
      }
      busy={pending}
      testId="charge-dialog"
      titleRef={titleRef}
      onOpenAutoFocus={(e) => e.preventDefault()}
      banner={
        !confirming && restoredDraft ? (
          <RestoredDraftBanner testId="charge-restored-banner">{CHARGE_RESTORED_MESSAGE}</RestoredDraftBanner>
        ) : undefined
      }
      footer={
        confirming ? (
          <>
            <Button
              type="button"
              variant="outline"
              onClick={backToInput}
              disabled={pending}
              className={LEDGER_DIALOG_BUTTON_CLASS}
              data-testid="charge-back"
            >
              이전
            </Button>
            <Button
              type="button"
              onClick={submit}
              disabled={pending}
              aria-label={pending ? '충전 처리 중' : undefined}
              data-state={pending ? 'pending' : 'idle'}
              className={LEDGER_DIALOG_BUTTON_CLASS}
              data-testid="charge-confirm"
            >
              {pending ? <Loader2 aria-hidden="true" className="size-6 animate-spin" /> : '확인'}
            </Button>
          </>
        ) : (
          <>
            <Button
              type="button"
              variant="outline"
              onClick={onCancel}
              className={LEDGER_DIALOG_BUTTON_CLASS}
              data-testid="charge-cancel"
            >
              취소
            </Button>
            <Button
              type="submit"
              form={formId}
              disabled={!settings || check.error !== null}
              className={LEDGER_DIALOG_BUTTON_CLASS}
              data-testid="charge-next"
            >
              충전
            </Button>
          </>
        )
      }
    >
      {confirming && check.amount !== null ? (
        <ChargeConfirmSummary customer={customer} amount={check.amount} memo={memo.trim()} />
      ) : (
        <>
          {settingsState.status === 'loading' && (
            <p role="status" className="py-10 text-center text-muted-foreground" data-testid="charge-settings-loading">
              불러오는 중…
            </p>
          )}
          {settingsState.status === 'error' && (
            <div className="flex flex-col items-start gap-3 py-4">
              <p role="alert" className="text-destructive" data-testid="charge-settings-error">
                {toUserMessage(settingsState.error)}
              </p>
              <Button type="button" variant="outline" onClick={retrySettings} data-testid="charge-settings-retry">
                다시 시도
              </Button>
            </div>
          )}
          {settings && (
            <ChargeInputForm
              formId={formId}
              settings={settings}
              amountInput={amountInput}
              onAmountChange={(next) => {
                setAmountInput(next)
                setAmountTouched(true)
                setAmountServerError(null)
              }}
              amountError={amountErrorMessage()}
              amountRef={amountRef}
              memo={memo}
              onMemoChange={(next) => {
                setMemo(next)
                setMemoServerError(null)
              }}
              memoError={memoServerError}
              onSubmit={goToConfirm}
            />
          )}
        </>
      )}
      {formErrorBlock}
    </LedgerInputDialogContent>
  )
}

function ChargeInputForm({
  formId,
  settings,
  amountInput,
  onAmountChange,
  amountError,
  amountRef,
  memo,
  onMemoChange,
  memoError,
  onSubmit,
}: {
  formId: string
  settings: LedgerInputSettingsResponse
  amountInput: string
  onAmountChange: (next: string) => void
  amountError: string | null
  amountRef: RefObject<HTMLInputElement | null>
  memo: string
  onMemoChange: (next: string) => void
  memoError: string | null
  onSubmit: (e: FormEvent) => void
}) {
  return (
    <form id={formId} onSubmit={onSubmit} noValidate aria-label="충전 입력" className="flex flex-col gap-6">
      <AmountField
        label="충전 금액"
        value={amountInput}
        onChange={onAmountChange}
        quickAmounts={QUICK_AMOUNTS}
        hint={`한 번에 ${formatWon(settings.chargeMaxAmount)}까지 충전할 수 있습니다`}
        error={amountError}
        inputRef={amountRef}
        testIdPrefix="charge"
      />
      <MemoField
        label="결제 수단 메모"
        value={memo}
        onChange={onMemoChange}
        presets={settings.chargeMemoPresets}
        maxLength={settings.memoMaxLength}
        hint={`기록용 메모 (선택, ${settings.memoMaxLength}자까지)`}
        error={memoError}
        testIdPrefix="charge"
      />
    </form>
  )
}

function ChargeConfirmSummary({
  customer,
  amount,
  memo,
}: {
  customer: CustomerDetailResponse
  amount: number
  memo: string
}) {
  const [detailsOpen, setDetailsOpen] = useState(false)
  const detailsId = useId()
  // 표시 전용 예상값(사용자 승인). 처리 뒤 잔액은 서버 응답·상세 재조회 값으로만 보여 준다
  const expected = previewBalanceAfterCharge(customer.balance, amount)

  return (
    <>
      <section aria-label="충전 후 잔액" className="rounded-2xl bg-muted px-6 py-5" data-testid="charge-confirm-summary">
        <p className="text-2xl font-bold tabular-nums">
          <span data-testid="charge-confirm-current-balance">{formatWon(customer.balance)}</span>
          <span aria-hidden="true" className="mx-3 text-muted-foreground">
            →
          </span>
          <span className="sr-only">에서</span>
          <span className="text-primary" data-testid="charge-confirm-expected-balance" data-balance={expected}>
            {formatWon(expected)}
          </span>
        </p>
        <p className="mt-2 text-lg" data-testid="charge-confirm-expected-korean">
          {toKoreanAmount(expected)}원으로 늘어납니다
        </p>
      </section>

      <div>
        <button
          type="button"
          aria-expanded={detailsOpen}
          aria-controls={detailsId}
          onClick={() => setDetailsOpen((v) => !v)}
          className="flex h-11 items-center gap-2 rounded-lg px-1 text-base font-semibold focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
          data-testid="charge-confirm-details-toggle"
        >
          <span aria-hidden="true">{detailsOpen ? '▼' : '▶'}</span>
          상세 내용
        </button>
        <dl
          id={detailsId}
          hidden={!detailsOpen}
          className="mt-2 grid grid-cols-[7rem_1fr] gap-x-4 gap-y-2 rounded-xl border px-5 py-4"
          data-testid="charge-confirm-details"
        >
          <dt className="text-muted-foreground">고객</dt>
          <dd data-testid="charge-confirm-customer">
            {customer.name} · <span className="tabular-nums">{customer.maskedPhone}</span>
          </dd>
          <dt className="text-muted-foreground">충전 금액</dt>
          <dd className="tabular-nums" data-testid="charge-confirm-amount">
            {formatNumber(amount)}원 ({toKoreanAmount(amount)}원)
          </dd>
          <dt className="text-muted-foreground">메모</dt>
          <dd className="break-all" data-testid="charge-confirm-memo">
            {memo || '(없음)'}
          </dd>
        </dl>
      </div>

      <p className="text-base text-muted-foreground">[확인]을 누르면 바로 장부에 기록됩니다.</p>
    </>
  )
}
