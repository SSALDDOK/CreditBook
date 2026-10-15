import { Loader2 } from 'lucide-react'
import { toUserMessage } from '@/api/client'
import { Button } from '@/components/ui/button'
import { LEDGER_DIALOG_BUTTON_CLASS, LEDGER_DIALOG_PRIMARY_BUTTON_CLASS } from './ledgerDialogStyles'
import type { LedgerInputSettingsState } from './useLedgerInputSettings'

// 충전·사용 모달이 같이 쓰는 작은 부품들. data-testid 는 `${testIdPrefix}-…` 로 모달마다 다르게 붙는다
// (예: charge-next / use-next). 이름은 CB-36 충전 모달의 것을 그대로 유지한다.

/** 입력 설정을 받는 중·실패 표시. 받았으면 아무것도 그리지 않는다 */
export function LedgerSettingsStatus({
  state,
  onRetry,
  testIdPrefix,
}: {
  state: LedgerInputSettingsState
  onRetry: () => void
  testIdPrefix: string
}) {
  if (state.status === 'loading') {
    return (
      <p role="status" className="py-10 text-center text-muted-foreground" data-testid={`${testIdPrefix}-settings-loading`}>
        불러오는 중…
      </p>
    )
  }
  if (state.status === 'error') {
    return (
      <div className="flex flex-col items-start gap-3 py-4">
        <p role="alert" className="text-destructive" data-testid={`${testIdPrefix}-settings-error`}>
          {toUserMessage(state.error)}
        </p>
        <Button type="button" variant="outline" onClick={onRetry} data-testid={`${testIdPrefix}-settings-retry`}>
          다시 시도
        </Button>
      </div>
    )
  }
  return null
}

/** 모달 아래쪽 오류 띠(네트워크 오류·409·422 등) */
export function LedgerFormError({ message, testId }: { message: string | null; testId: string }) {
  if (!message) return null
  return (
    <p role="alert" className="rounded-xl bg-destructive/10 px-4 py-3 text-base text-destructive" data-testid={testId}>
      {message}
    </p>
  )
}

/** 입력 화면 버튼 — [취소] [충전/사용]. 주 버튼은 입력 form 의 submit 이다(Enter 로도 확인 화면으로 간다) */
export function LedgerInputFooter({
  testIdPrefix,
  formId,
  nextLabel,
  nextDisabled,
  onCancel,
}: {
  testIdPrefix: string
  formId: string
  nextLabel: string
  nextDisabled: boolean
  onCancel: () => void
}) {
  return (
    <>
      <Button
        type="button"
        variant="outline"
        onClick={onCancel}
        className={LEDGER_DIALOG_BUTTON_CLASS}
        data-testid={`${testIdPrefix}-cancel`}
      >
        취소
      </Button>
      <Button
        type="submit"
        form={formId}
        disabled={nextDisabled}
        className={LEDGER_DIALOG_PRIMARY_BUTTON_CLASS}
        data-testid={`${testIdPrefix}-next`}
      >
        {nextLabel}
      </Button>
    </>
  )
}

/** 확인 화면 버튼 — [이전] [확인]. 처리 중에는 둘 다 비활성, [확인]은 스피너와 data-state="pending" */
export function LedgerConfirmFooter({
  testIdPrefix,
  pending,
  pendingLabel,
  confirmDisabled = false,
  onBack,
  onConfirm,
}: {
  testIdPrefix: string
  pending: boolean
  /** 처리 중 [확인]의 접근 가능한 이름 (예: '충전 처리 중') */
  pendingLabel: string
  confirmDisabled?: boolean
  onBack: () => void
  onConfirm: () => void
}) {
  return (
    <>
      <Button
        type="button"
        variant="outline"
        onClick={onBack}
        disabled={pending}
        className={LEDGER_DIALOG_BUTTON_CLASS}
        data-testid={`${testIdPrefix}-back`}
      >
        이전
      </Button>
      <Button
        type="button"
        onClick={onConfirm}
        disabled={pending || confirmDisabled}
        aria-label={pending ? pendingLabel : undefined}
        data-state={pending ? 'pending' : 'idle'}
        className={LEDGER_DIALOG_PRIMARY_BUTTON_CLASS}
        data-testid={`${testIdPrefix}-confirm`}
      >
        {pending ? <Loader2 aria-hidden="true" className="size-6 animate-spin" /> : '확인'}
      </Button>
    </>
  )
}
