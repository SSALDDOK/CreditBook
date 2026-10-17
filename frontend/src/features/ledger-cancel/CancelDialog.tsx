import { Loader2 } from 'lucide-react'
import { useEffect, useId, useRef, useState, type RefObject } from 'react'
import {
  CANCEL_REASON_MAX_LENGTH,
  cancelCharge,
  cancelUse,
  readChargeAlreadyUsedDetails,
  type CancelResponse,
} from '@/api/cancels'
import { ApiError, COMMON_ERROR_MESSAGE, NETWORK_ERROR_MESSAGE, toUserMessage } from '@/api/client'
import type { TransactionResponse } from '@/api/transactions'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'
import {
  previewBalanceAfterChargeCancel,
  previewBalanceAfterUseCancel,
} from '@/features/ledger-input/balancePreview'
import { useIdempotencyKey } from '@/features/ledger-input/useIdempotencyKey'
import { EntryTypeLabel } from '@/features/ledger/LedgerEntryParts'
import { formatMonthDayTime, formatNumber, formatWon } from '@/lib/format'
import { toKoreanAmount } from '@/lib/koreanAmount'
import { cn } from '@/lib/utils'
import { cancelKindOf, type CancelKind } from './cancelKind'

// 거래 이력(SCR-8)의 취소 사유 창 — 사용 취소(REQ-34)·충전 취소(REQ-7)가 같이 쓴다. 한 단계 창이다(확인 단계 없음).
// [취소하기] 한 번에 POST 1번. 취소할 수 있는지(당일·잔액·이미 취소됨)는 서버가 판단하고, 화면은 그 응답을 보여 준다.
//
// 요청 키(REQ-10): 창을 열 때 만들고, 같은 창에서 다시 [취소하기]를 누르면(네트워크 오류 뒤 등) 같은 키, 창을 닫으면 버린다
// (창을 닫으면 본문 컴포넌트가 사라져 키도 함께 사라진다). 그래서 응답만 끊긴 요청을 다시 보내도 취소 거래는 하나만 생긴다.
// 자동 재시도는 없다.
//
// 잔액 변화 상자의 "취소 후 잔액"은 balancePreview.ts 의 표시 전용 예상값이다. 처리 뒤 잔액은 이력·고객 상세 재조회 값만 쓴다.

const TEXT: Record<
  CancelKind,
  { title: string; subject: string; amountLabel: string; reversalLabel: string; pendingLabel: string }
> = {
  use: { title: '사용 취소', subject: '이 사용 건을', amountLabel: '사용 금액', reversalLabel: '사용 취소', pendingLabel: '사용 취소 처리 중' },
  charge: { title: '충전 취소', subject: '이 충전 건을', amountLabel: '충전 금액', reversalLabel: '충전 취소', pendingLabel: '충전 취소 처리 중' },
}

type CancelDialogCustomer = { id: string; name: string; balance: number }

const BUTTON_CLASS = 'h-12 min-w-28 rounded-xl px-6 text-lg font-bold'
const GRAY_DISABLED = 'disabled:bg-zinc-200 disabled:text-zinc-500 disabled:opacity-100'

export function CancelDialog({
  target,
  customer,
  onClose,
  onCancelled,
  onLedgerChanged,
}: {
  /** 취소할 원거래. null 이면 창이 닫혀 있다 */
  target: TransactionResponse | null
  /** 고객 id·이름·현재 잔액 — 이력 화면이 받은 고객 상세 응답 값 그대로 */
  customer: CancelDialogCustomer
  /** [닫기]·Esc — 호출하는 쪽이 창을 닫는다(이미 취소됨·거절 뒤에는 이력을 이미 다시 불러왔다) */
  onClose: () => void
  /** 취소 성공 — 호출하는 쪽이 창을 닫고 이력·고객 상세를 서버에서 다시 받는다 */
  onCancelled: (response: CancelResponse) => void
  /** 거절·이미 취소됨·동시 처리 — 장부나 취소 가능 여부가 바뀌었을 수 있다. 호출하는 쪽이 이력·고객 상세를 다시 받는다. 창은 열린 채 */
  onLedgerChanged: () => void
}) {
  const kind = target ? cancelKindOf(target) : null
  const open = target !== null && kind !== null
  // 처리 중에는 Esc·바깥 누르기로 닫히지 않게 본문이 알려 준다
  const pendingRef = useRef(false)

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next && !pendingRef.current) onClose()
      }}
    >
      {open && (
        <CancelDialogBody
          key={target.id}
          kind={kind}
          target={target}
          customer={customer}
          pendingRef={pendingRef}
          onClose={onClose}
          onCancelled={onCancelled}
          onLedgerChanged={onLedgerChanged}
        />
      )}
    </Dialog>
  )
}

type Outcome =
  /** 서버가 거절 — 장부·잔액은 그대로. [취소하기]는 더 누를 수 없다 */
  | { kind: 'rejected'; message: string; detail: string | null }
  /** 다른 곳에서 먼저 취소됨(ALREADY_CANCELLED) — 오류가 아니라 안내 */
  | { kind: 'already-cancelled'; message: string }

function CancelDialogBody({
  kind,
  target,
  customer,
  pendingRef,
  onClose,
  onCancelled,
  onLedgerChanged,
}: {
  kind: CancelKind
  target: TransactionResponse
  customer: CancelDialogCustomer
  pendingRef: RefObject<boolean>
  onClose: () => void
  onCancelled: (response: CancelResponse) => void
  onLedgerChanged: () => void
}) {
  const text = TEXT[kind]
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string | null>(null)
  const [retryError, setRetryError] = useState<string | null>(null)
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  const [pending, setPending] = useState(false)
  const idempotencyKey = useIdempotencyKey()

  const reasonId = useId()
  const noteId = useId()
  const reasonErrorId = useId()

  // 창을 열 때 새 요청 키. 창을 닫으면 이 컴포넌트가 사라져 키도 버려진다
  useEffect(() => {
    idempotencyKey.issue()
  }, [idempotencyKey])

  const trimmed = reason.trim()
  const canSubmit = !pending && outcome === null && trimmed.length > 0

  async function submit() {
    if (pendingRef.current || !canSubmit) return // 더블클릭해도 요청은 1번
    pendingRef.current = true
    setPending(true)
    setRetryError(null)
    try {
      const send = kind === 'use' ? cancelUse : cancelCharge
      const response = await send(customer.id, target.id, { reason: trimmed }, idempotencyKey.current())
      onCancelled(response)
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
      // 응답을 못 받았다 — 서버에서 처리됐을 수 있으므로 같은 키를 유지한다. 다시 누르면 같은 키로 보낸다
      setRetryError(`${NETWORK_ERROR_MESSAGE} [취소하기]를 다시 누르면 같은 요청으로 다시 보냅니다.`)
      return
    }
    if (e.status === 401) return // 로그인 화면 이동은 공통 401 처리가 한다
    if (e.status >= 500) {
      setRetryError(COMMON_ERROR_MESSAGE) // 같은 키 유지
      return
    }
    switch (e.code) {
      case 'INVALID_INPUT': {
        const field = e.fieldErrors.find((f) => f.field === 'reason')
        setReasonError(field?.message ?? toUserMessage(e))
        return
      }
      case 'IDEMPOTENCY_KEY_REUSED':
      case 'INVALID_IDEMPOTENCY_KEY':
        // 이 키는 더 쓸 수 없다 — 새 키를 만들고, 직원이 확인한 뒤 다시 누르게 한다
        idempotencyKey.issue()
        setRetryError(toUserMessage(e))
        return
      case 'CONCURRENT_MODIFICATION':
        // 다른 요청이 먼저 처리됨 — 안내하고 이력·잔액을 다시 받는다. 키는 유지(다시 누를 수 있다)
        setRetryError(toUserMessage(e))
        onLedgerChanged()
        return
      case 'ALREADY_CANCELLED':
        // 동시 취소에서 늦은 쪽 — 이미 원하는 상태다. 오류가 아니라 안내로 보이고, 이력을 바로 다시 받는다
        setOutcome({ kind: 'already-cancelled', message: toUserMessage(e) })
        onLedgerChanged()
        return
      case 'CHARGE_ALREADY_USED': {
        const details = readChargeAlreadyUsedDetails(e.details)
        setOutcome({
          kind: 'rejected',
          message: toUserMessage(e),
          detail: details
            ? `현재 잔액 ${formatWon(details.balance)} · 취소하려는 충전 ${formatWon(details.chargeAmount)}`
            : null,
        })
        onLedgerChanged()
        return
      }
    }
    // CHARGE_CANCEL_NOT_SAME_DAY·INVALID_CANCEL_TARGET·LEDGER_ENTRY_NOT_FOUND·CUSTOMER_NOT_FOUND·403 등 — 이 창에서 더 할 수 없다.
    // 취소 가능 여부가 바뀌었을 수 있으므로 이력을 다시 받는다
    setOutcome({ kind: 'rejected', message: toUserMessage(e), detail: null })
    onLedgerChanged()
  }

  const note =
    outcome?.kind === 'rejected'
      ? '장부와 잔액은 바뀌지 않았습니다.'
      : outcome?.kind === 'already-cancelled'
        ? '다른 곳에서 먼저 취소되어 거래 이력을 다시 불러왔습니다.'
        : `원래 거래는 지워지지 않고 '${text.reversalLabel}' 거래가 장부에 추가됩니다.`
  const describedBy = [noteId, reasonError ? reasonErrorId : null].filter(Boolean).join(' ')
  const state = pending ? 'pending' : (outcome?.kind ?? 'idle')

  return (
    <DialogContent
      showCloseButton={false}
      data-testid="cancel-dialog"
      data-kind={kind}
      data-state={state}
      aria-busy={pending}
      onEscapeKeyDown={(e) => {
        if (pendingRef.current) e.preventDefault()
      }}
      // 태블릿에서 바깥을 잘못 눌러 사유가 사라지지 않게 바깥 누르기로는 닫지 않는다([닫기]·Esc 로 닫는다)
      onInteractOutside={(e) => e.preventDefault()}
      className="max-h-[calc(100dvh-2rem)] gap-0 overflow-y-auto rounded-3xl p-0 text-base sm:max-w-[33rem]"
    >
      <div className="flex flex-col gap-1 px-6 pt-7">
        <DialogTitle className="text-2xl font-bold">{text.title}</DialogTitle>
        <DialogDescription className="text-base text-muted-foreground">
          {customer.name} 고객 · {text.subject} 취소합니다
        </DialogDescription>
      </div>

      <div className="flex flex-col gap-4 px-6 pt-5">
        <section aria-label="취소할 거래" className="rounded-2xl border px-4" data-testid="cancel-target">
          <div className="flex min-w-0 items-center gap-2 border-b py-3">
            <EntryTypeLabel type={target.type} />
            <span className="truncate text-lg font-bold" data-testid="cancel-target-memo">
              {target.memo}
            </span>
          </div>
          <dl className="grid grid-cols-[5.5rem_1fr] text-base">
            <dt className="border-b py-3 text-muted-foreground">거래 일시</dt>
            <dd className="border-b py-3 tabular-nums" data-testid="cancel-target-time">
              {formatMonthDayTime(target.performedAt)} · {target.performedByName}
            </dd>
            <dt className="py-3 text-muted-foreground">{text.amountLabel}</dt>
            <dd className="py-3 tabular-nums" data-testid="cancel-target-amount" data-amount={target.amount}>
              {formatNumber(target.amount)}원 ({toKoreanAmount(target.amount)}원)
            </dd>
          </dl>
        </section>

        {outcome?.kind === 'rejected' ? (
          <div
            role="alert"
            className="rounded-2xl border border-destructive/30 bg-destructive/10 px-5 py-4 text-destructive"
            data-testid="cancel-error"
          >
            <p className="text-base font-bold">{outcome.message}</p>
            {outcome.detail && (
              <p className="mt-1 text-base tabular-nums" data-testid="cancel-error-detail">
                {outcome.detail}
              </p>
            )}
          </div>
        ) : outcome?.kind === 'already-cancelled' ? (
          <p
            role="status"
            className="rounded-2xl bg-muted px-5 py-4 text-lg font-bold text-foreground"
            data-testid="cancel-already-cancelled"
          >
            {outcome.message}
          </p>
        ) : (
          <BalancePreview kind={kind} balance={customer.balance} amount={target.amount} />
        )}

        <div className="flex flex-col gap-2">
          <div className="flex items-baseline justify-between">
            <label htmlFor={reasonId} className="text-base font-bold">
              취소 사유 <span className="ml-1 text-sm font-semibold text-destructive">필수</span>
            </label>
            <span className="text-sm text-muted-foreground tabular-nums" data-testid="cancel-reason-count">
              {reason.length} / {CANCEL_REASON_MAX_LENGTH}
            </span>
          </div>
          <textarea
            id={reasonId}
            value={reason}
            onChange={(e) => {
              setReason(e.target.value)
              setReasonError(null)
            }}
            maxLength={CANCEL_REASON_MAX_LENGTH}
            rows={2}
            required
            aria-required="true"
            aria-invalid={reasonError ? true : undefined}
            aria-describedby={describedBy}
            autoComplete="off"
            data-testid="cancel-reason-input"
            className={cn(
              'w-full resize-none rounded-xl border border-input bg-transparent px-4 py-3 text-lg outline-none',
              'focus-visible:border-primary focus-visible:ring-3 focus-visible:ring-ring/30',
              'aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20',
            )}
          />
          {reasonError && (
            <p id={reasonErrorId} role="alert" className="text-base text-destructive" data-testid="cancel-reason-error">
              {reasonError}
            </p>
          )}
          <p id={noteId} className="text-sm text-muted-foreground" data-testid="cancel-note">
            {note}
          </p>
        </div>

        {retryError && (
          <p
            role="alert"
            className="rounded-xl bg-destructive/10 px-4 py-3 text-base text-destructive"
            data-testid="cancel-retry-error"
          >
            {retryError}
          </p>
        )}
      </div>

      <div className="flex justify-end gap-3 px-6 pt-5 pb-6">
        <Button
          type="button"
          variant={outcome ? 'default' : 'outline'}
          onClick={onClose}
          disabled={pending}
          className={cn(BUTTON_CLASS, outcome && 'bg-sidebar text-white hover:bg-sidebar/90')}
          data-testid="cancel-close"
        >
          닫기
        </Button>
        <Button
          type="button"
          onClick={submit}
          disabled={!canSubmit}
          aria-label={pending ? text.pendingLabel : undefined}
          data-state={pending ? 'pending' : 'idle'}
          className={cn(BUTTON_CLASS, 'bg-destructive text-white hover:bg-destructive/90', GRAY_DISABLED)}
          data-testid="cancel-confirm"
        >
          {pending ? <Loader2 aria-hidden="true" className="size-6 animate-spin" /> : '취소하기'}
        </Button>
      </div>
    </DialogContent>
  )
}

/** 잔액 변화 상자 — "현재 잔액 → 취소 후 예상 잔액"(표시 전용) + 한 줄 안내 */
function BalancePreview({ kind, balance, amount }: { kind: CancelKind; balance: number; amount: number }) {
  const expected =
    kind === 'use' ? previewBalanceAfterUseCancel(balance, amount) : previewBalanceAfterChargeCancel(balance, amount)
  const caption =
    kind === 'use'
      ? `사용한 ${formatWon(amount)}이 잔액으로 돌아옵니다`
      : expected === null
        ? `현재 잔액이 충전한 ${formatWon(amount)}보다 적습니다`
        : `충전한 ${formatWon(amount)}이 잔액에서 빠집니다`

  return (
    <section
      aria-label="취소 후 잔액"
      className="rounded-2xl bg-primary/10 px-5 py-4 text-sidebar"
      data-testid="cancel-balance-preview"
      data-balance={balance}
      data-expected-balance={expected ?? undefined}
    >
      <p className="text-2xl font-bold tabular-nums">
        {expected === null ? (
          <>현재 잔액 {formatWon(balance)}</>
        ) : (
          <>
            {formatWon(balance)}
            <span aria-hidden="true" className="mx-2">
              →
            </span>
            <span className="sr-only">에서</span>
            {formatWon(expected)}
          </>
        )}
      </p>
      <p className="mt-1 text-base">{caption}</p>
    </section>
  )
}
