import type { TransactionResponse } from '@/api/transactions'
import { Button } from '@/components/ui/button'
import { LEDGER_ENTRY_TYPE_LABELS } from '@/features/ledger/ledgerEntryTypes'
import { formatShortDateTime, formatWon } from '@/lib/format'
import { cancelKindOf } from './cancelKind'

/**
 * 거래 이력 행 맨 오른쪽의 [취소] 버튼. 서버 값만 보고 그린다 — 화면이 날짜·잔액으로 취소 가능 여부를 정하지 않는다.
 * - cancellable=true → 활성 [취소] (흰 바탕·테두리). 누르면 취소 사유 창
 * - cancelled=true  → 비활성 [취소] (회색) — 이미 취소된 원거래
 * - 그 밖(반제 행·당일이 지난 충전 등) → 버튼 없음
 * 버튼을 숨기거나 막는 것은 편의일 뿐이다. 최종 판단은 서버가 하고, 창이 그 거절 응답을 보여 준다.
 * 같은 이름의 버튼이 여러 행에 있으므로 접근 가능한 이름에 일시·유형·금액을 넣는다(보이는 글자 '취소'를 포함).
 */
export function TransactionCancelButton({
  entry,
  onCancel,
}: {
  entry: TransactionResponse
  onCancel: (entry: TransactionResponse) => void
}) {
  const kind = cancelKindOf(entry)
  if (kind === null) return null
  const enabled = entry.cancellable && !entry.cancelled
  if (!enabled && !entry.cancelled) return null

  const name = `${formatShortDateTime(entry.performedAt)} ${LEDGER_ENTRY_TYPE_LABELS[entry.type]} ${formatWon(entry.amount)} 취소`

  return (
    <Button
      type="button"
      variant="outline"
      disabled={!enabled}
      onClick={enabled ? () => onCancel(entry) : undefined}
      aria-haspopup={enabled ? 'dialog' : undefined}
      aria-label={enabled ? name : `${name} (이미 취소됨)`}
      data-testid="transaction-cancel-button"
      data-kind={kind}
      className="h-9 w-14 rounded-lg px-0 bg-background text-base font-bold disabled:border-transparent disabled:bg-muted disabled:text-muted-foreground disabled:opacity-100"
    >
      취소
    </Button>
  )
}
