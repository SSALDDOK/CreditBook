import type { CustomerDetailResponse } from '@/api/customers'
import { toUserMessage } from '@/api/client'
import { createUse, readInsufficientBalanceDetails, type UsageResponse } from '@/api/uses'
import { AmountField } from '@/features/ledger-input/AmountField'
import { previewBalanceAfterUse, previewShortage } from '@/features/ledger-input/balancePreview'
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
import { insufficientBalanceMessage } from './useMessages'

// SCR-7 사용 모달 (REQ-8·9·10·17·31·33). 고객 상세(SCR-5) 위에 뜬다. ADMIN·STAFF 차이 없음.
// 1 입력 → 2 확인 → [확인] 한 번에 POST 1번. 처리 뒤 잔액은 서버 응답·상세 재조회 값으로만 보여 준다.
// 입력·확인 흐름과 공통 오류 처리는 useLedgerInputFlow(충전 모달과 공용)가 맡는다.
//
// 잔액 부족: 입력 금액이 현재 잔액보다 크면(같으면 허용 — 잔액 0원이 된다) 금액 칸 아래 부족액을 보여 주고 [사용]을 막는다.
// 이 표시는 편의다(부족액은 balancePreview 의 표시 전용 값). 서버가 INSUFFICIENT_BALANCE 를 주면 그 details 값 그대로 안내한다.

export const USE_RESTORED_MESSAGE = '입력하던 금액을 불러왔습니다. 확인 후 사용하세요.'

export function UseDialog({
  open,
  onCancel,
  onUsed,
  onConflict,
  onInactive,
  customer,
  employeeId,
  restoredDraft,
}: {
  open: boolean
  /** [취소]·Esc — 호출하는 쪽이 모달을 닫고 저장된 입력(복구용)을 지운다 */
  onCancel: () => void
  /** 사용 성공 — 호출하는 쪽이 모달을 닫고 상세를 다시 불러온다 */
  onUsed: (response: UsageResponse) => void
  /** 409 CONCURRENT_MODIFICATION·400 INSUFFICIENT_BALANCE — 호출하는 쪽이 상세(현재 잔액)를 다시 불러온다. 모달은 열린 채로 둔다 */
  onConflict: () => void
  /** 409 CUSTOMER_INACTIVE(다른 기기에서 비활성화됨) — 호출하는 쪽이 모달을 닫고, 이 문구를 보여 주고, 상세를 다시 불러온다 */
  onInactive: (message: string) => void
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
        <UseDialogBody
          customer={customer}
          employeeId={employeeId}
          restoredDraft={restoredDraft}
          onCancel={onCancel}
          onUsed={onUsed}
          onConflict={onConflict}
          onInactive={onInactive}
        />
      )}
    </LedgerInputDialog>
  )
}

function UseDialogBody({
  customer,
  employeeId,
  restoredDraft,
  onCancel,
  onUsed,
  onConflict,
  onInactive,
}: {
  customer: CustomerDetailResponse
  employeeId: string
  restoredDraft: LedgerDraft | null
  onCancel: () => void
  onUsed: (response: UsageResponse) => void
  onConflict: () => void
  onInactive: (message: string) => void
}) {
  const { state: settingsState, retry: retrySettings } = useLedgerInputSettings()
  const settings = settingsState.status === 'success' ? settingsState.data : null

  const flow = useLedgerInputFlow<UsageResponse>({
    kind: 'use',
    customerId: customer.id,
    employeeId,
    restoredDraft,
    settings,
    memoPresets: settings?.useMemoPresets,
    // 잔액 부족이 보이는 동안은 [사용]을 막는다(편의). 최종 판단은 서버
    isAmountBlocked: (amount) => previewShortage(customer.balance, amount) !== null,
    send: (request, key) => createUse(customer.id, request, key),
    onSuccess: onUsed,
    onConflict,
    handleOwnError: (e, { backToInput, showAmountError }) => {
      switch (e.code) {
        case 'INSUFFICIENT_BALANCE': {
          // 다른 기기에서 잔액이 바뀐 경우 등 — 서버가 준 현재 잔액·부족액 그대로 안내하고, 상세를 다시 받는다
          const details = readInsufficientBalanceDetails(e.details)
          backToInput()
          showAmountError(details ? insufficientBalanceMessage(details.balance, details.shortage) : toUserMessage(e))
          onConflict()
          return true
        }
        case 'CUSTOMER_INACTIVE':
          // 다른 기기에서 비활성화됨 — 이 모달에서 더 할 수 있는 일이 없다. 닫고 상세에서 안내한다
          onInactive(toUserMessage(e))
          return true
      }
      return false
    },
  })

  // 표시 전용 부족액(사용자 승인). 형식이 맞는 금액일 때만 본다
  const typedShortage =
    flow.check.error === null && flow.check.amount !== null ? previewShortage(customer.balance, flow.check.amount) : null

  function amountError(): string | null {
    if (flow.amountServerError) return flow.amountServerError
    // 잔액 부족은 치는 즉시 알린다(빨간 테두리 + 문구, [사용] 비활성)
    if (typedShortage !== null) return insufficientBalanceMessage(customer.balance, typedShortage)
    return flow.amountFormatError
  }

  // 두 단계가 같은 DialogContent 를 쓴다(단계가 바뀔 때 모달이 다시 열리거나 포커스가 밖으로 튀지 않게)
  const confirmingAmount = flow.confirmingAmount
  const confirming = confirmingAmount !== null
  // 확인 화면에 있는 동안 상세를 다시 받아(409 뒤) 잔액이 모자라게 됐으면 예상 잔액 대신 부족 안내를 보이고 [확인]을 막는다
  const confirmShortage = confirming ? previewShortage(customer.balance, confirmingAmount) : null
  // 표시 전용 예상값(사용자 승인). 처리 뒤 잔액은 서버 응답·상세 재조회 값으로만 보여 준다
  const expected = confirming && confirmShortage === null ? previewBalanceAfterUse(customer.balance, confirmingAmount) : 0

  return (
    <LedgerInputDialogContent
      step={confirming ? 'confirm' : 'input'}
      title={confirming ? '사용 내용 확인' : '선결제 사용'}
      description={
        confirming
          ? `${customer.name} 고객 · 아래 내용으로 사용합니다`
          : `${customer.name} · 현재 잔액 ${formatWon(customer.balance)}`
      }
      busy={flow.pending}
      testId="use-dialog"
      titleRef={flow.titleRef}
      onOpenAutoFocus={(e) => e.preventDefault()}
      banner={
        !confirming && restoredDraft ? (
          <RestoredDraftBanner testId="use-restored-banner">{USE_RESTORED_MESSAGE}</RestoredDraftBanner>
        ) : undefined
      }
      footer={
        confirming ? (
          <LedgerConfirmFooter
            testIdPrefix="use"
            pending={flow.pending}
            pendingLabel="사용 처리 중"
            confirmDisabled={confirmShortage !== null}
            onBack={flow.backToInput}
            onConfirm={flow.submit}
          />
        ) : (
          <LedgerInputFooter
            testIdPrefix="use"
            formId={flow.formId}
            nextLabel="사용"
            nextDisabled={!flow.canProceed}
            onCancel={onCancel}
          />
        )
      }
    >
      {confirming ? (
        <LedgerConfirmSummary
          testIdPrefix="use"
          summaryLabel="사용 후 잔액"
          customer={customer}
          expectedBalance={expected}
          expectedCaption={expected === 0 ? '잔액이 0원이 됩니다' : `${toKoreanAmount(expected)}원이 남습니다`}
          problem={confirmShortage !== null ? insufficientBalanceMessage(customer.balance, confirmShortage) : null}
          amountLabel="사용 금액"
          amount={confirmingAmount}
          memo={flow.memo.trim()}
        />
      ) : (
        <>
          <LedgerSettingsStatus state={settingsState} onRetry={retrySettings} testIdPrefix="use" />
          {settings && (
            <form
              id={flow.formId}
              onSubmit={flow.goToConfirm}
              noValidate
              aria-label="사용 입력"
              className="flex flex-col gap-6"
            >
              <AmountField
                label="사용 금액"
                value={flow.amountInput}
                onChange={flow.changeAmount}
                error={amountError()}
                inputRef={flow.amountRef}
                testIdPrefix="use"
              />
              <MemoField
                label="주문 메모"
                value={flow.memo}
                onChange={flow.changeMemo}
                presets={settings.useMemoPresets}
                maxLength={settings.memoMaxLength}
                hint={`문구를 누르면 뒤에 이어 붙고, 다시 누르면 빠집니다 (선택, ${settings.memoMaxLength}자까지)`}
                error={flow.memoServerError}
                testIdPrefix="use"
              />
            </form>
          )}
        </>
      )}
      <LedgerFormError message={flow.formError} testId="use-error" />
    </LedgerInputDialogContent>
  )
}
