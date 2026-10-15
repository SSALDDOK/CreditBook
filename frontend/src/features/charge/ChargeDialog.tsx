import { createCharge, type ChargeResponse } from '@/api/charges'
import type { CustomerDetailResponse } from '@/api/customers'
import { AmountField } from '@/features/ledger-input/AmountField'
import { previewBalanceAfterCharge } from '@/features/ledger-input/balancePreview'
import type { LedgerDraft } from '@/features/ledger-input/draftStorage'
import { LedgerConfirmSummary } from '@/features/ledger-input/LedgerConfirmSummary'
import {
  LedgerConfirmFooter,
  LedgerFormError,
  LedgerInputFooter,
  LedgerSettingsStatus,
} from '@/features/ledger-input/LedgerDialogParts'
import { LedgerInputDialog, LedgerInputDialogContent } from '@/features/ledger-input/LedgerInputDialog'
import { MemoField } from '@/features/ledger-input/MemoField'
import { RestoredDraftBanner } from '@/features/ledger-input/RestoredDraftBanner'
import { useLedgerInputFlow } from '@/features/ledger-input/useLedgerInputFlow'
import { useLedgerInputSettings } from '@/features/ledger-input/useLedgerInputSettings'
import { formatWon } from '@/lib/format'
import { toKoreanAmount } from '@/lib/koreanAmount'

// SCR-6 충전 모달 (REQ-5·6·10·17·31·33). 고객 상세(SCR-5) 위에 뜬다. ADMIN·STAFF 차이 없음.
// 1 입력 → 2 확인 → [확인] 한 번에 POST 1번. 처리 뒤 잔액은 서버 응답·상세 재조회 값으로만 보여 준다.
// 입력·확인 흐름과 공통 오류 처리는 useLedgerInputFlow(사용 모달과 공용)가 맡는다.

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

  const flow = useLedgerInputFlow<ChargeResponse>({
    kind: 'charge',
    customerId: customer.id,
    employeeId,
    restoredDraft,
    settings,
    memoPresets: settings?.chargeMemoPresets,
    maxAmount: settings?.chargeMaxAmount,
    send: (request, key) => createCharge(customer.id, request, key),
    onSuccess: onCharged,
    onConflict,
    handleOwnError: (e, { backToInput, showAmountError }) => {
      if (e.code !== 'CHARGE_LIMIT_EXCEEDED') return false
      backToInput()
      showAmountError(e.message)
      return true
    },
  })

  function amountError(): string | null {
    if (flow.amountServerError) return flow.amountServerError
    if (flow.check.error === 'OVER_MAX' && settings) {
      // 한도 초과는 치는 즉시 알린다(빨간 테두리 + 문구, [충전] 비활성)
      return `한 번에 충전할 수 있는 금액은 ${formatWon(settings.chargeMaxAmount)}까지입니다.`
    }
    return flow.amountFormatError
  }

  // 두 단계가 같은 DialogContent 를 쓴다(단계가 바뀔 때 모달이 다시 열리거나 포커스가 밖으로 튀지 않게)
  const confirmingAmount = flow.confirmingAmount
  const confirming = confirmingAmount !== null
  // 표시 전용 예상값(사용자 승인). 처리 뒤 잔액은 서버 응답·상세 재조회 값으로만 보여 준다
  const expected = confirming ? previewBalanceAfterCharge(customer.balance, confirmingAmount) : null

  return (
    <LedgerInputDialogContent
      step={confirming ? 'confirm' : 'input'}
      title={confirming ? '충전 내용 확인' : '선결제 충전'}
      description={
        confirming
          ? `${customer.name} 고객 · 아래 내용으로 충전합니다`
          : `${customer.name} · 현재 잔액 ${formatWon(customer.balance)}`
      }
      busy={flow.pending}
      testId="charge-dialog"
      titleRef={flow.titleRef}
      onOpenAutoFocus={(e) => e.preventDefault()}
      banner={
        !confirming && restoredDraft ? (
          <RestoredDraftBanner testId="charge-restored-banner">{CHARGE_RESTORED_MESSAGE}</RestoredDraftBanner>
        ) : undefined
      }
      footer={
        confirming ? (
          <LedgerConfirmFooter
            testIdPrefix="charge"
            pending={flow.pending}
            pendingLabel="충전 처리 중"
            onBack={flow.backToInput}
            onConfirm={flow.submit}
          />
        ) : (
          <LedgerInputFooter
            testIdPrefix="charge"
            formId={flow.formId}
            nextLabel="충전"
            nextDisabled={!flow.canProceed}
            onCancel={onCancel}
          />
        )
      }
    >
      {confirming && expected !== null ? (
        <LedgerConfirmSummary
          testIdPrefix="charge"
          summaryLabel="충전 후 잔액"
          customer={customer}
          expectedBalance={expected}
          expectedCaption={`${toKoreanAmount(expected)}원으로 늘어납니다`}
          amountLabel="충전 금액"
          amount={confirmingAmount}
          memo={flow.memo.trim()}
        />
      ) : (
        <>
          <LedgerSettingsStatus state={settingsState} onRetry={retrySettings} testIdPrefix="charge" />
          {settings && (
            <form
              id={flow.formId}
              onSubmit={flow.goToConfirm}
              noValidate
              aria-label="충전 입력"
              className="flex flex-col gap-6"
            >
              <AmountField
                label="충전 금액"
                value={flow.amountInput}
                onChange={flow.changeAmount}
                quickAmounts={QUICK_AMOUNTS}
                hint={`한 번에 ${formatWon(settings.chargeMaxAmount)}까지 충전할 수 있습니다`}
                error={amountError()}
                inputRef={flow.amountRef}
                testIdPrefix="charge"
              />
              <MemoField
                label="결제 수단 메모"
                value={flow.memo}
                onChange={flow.changeMemo}
                presets={settings.chargeMemoPresets}
                maxLength={settings.memoMaxLength}
                hint={`기록용 메모 (선택, ${settings.memoMaxLength}자까지)`}
                error={flow.memoServerError}
                testIdPrefix="charge"
              />
            </form>
          )}
        </>
      )}
      <LedgerFormError message={flow.formError} testId="charge-error" />
    </LedgerInputDialogContent>
  )
}
