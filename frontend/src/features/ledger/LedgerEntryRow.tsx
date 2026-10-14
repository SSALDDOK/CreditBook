import type { RecentLedgerEntryResponse } from '@/api/customers'
import { formatEntryTime, formatSignedWon, formatWon } from '@/lib/format'
import { cn } from '@/lib/utils'
import { LEDGER_ENTRY_TYPE_LABELS } from './ledgerEntryTypes'

type LedgerEntryRowProps = { entry: RecentLedgerEntryResponse }

/**
 * 거래 한 줄. 금액·변동 후 잔액은 서버 값(signedAmount·balanceAfter)을 그대로 표시한다.
 * 부호·색은 signedAmount 부호를 따른다(+ 초록, - 기본색).
 *
 * 취소 표시(cancelled 배지, 충전 취소·사용 취소 행 전용 모양)는 화면설계가 나온 뒤 추가한다 —
 * 지금은 모든 유형을 같은 모양으로 그리고, data-entry-type·data-cancelled 로만 구분해 둔다.
 * 배지는 제목 줄(entry-title 옆)에 넣으면 된다.
 */
export function LedgerEntryRow({ entry }: LedgerEntryRowProps) {
  const label = LEDGER_ENTRY_TYPE_LABELS[entry.type]
  const credit = entry.signedAmount > 0

  return (
    <li
      className="flex items-center gap-5 border-b py-5 last:border-b-0"
      data-testid="recent-entry-row"
      data-entry-id={entry.id}
      data-entry-type={entry.type}
      data-cancelled={entry.cancelled}
    >
      <span
        aria-hidden="true"
        className="flex size-12 shrink-0 items-center justify-center rounded-xl bg-primary/10 font-semibold text-primary"
      >
        {label.charAt(0)}
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-lg font-semibold" data-testid="recent-entry-title">
          {label}
          {entry.memo && ` · ${entry.memo}`}
        </p>
        <p className="mt-1 text-muted-foreground" data-testid="recent-entry-time">
          <time dateTime={entry.performedAt}>{formatEntryTime(entry.performedAt)}</time>
        </p>
      </div>
      <div className="shrink-0 text-right">
        <p
          className={cn('text-lg font-bold tabular-nums', credit && 'text-green-700')}
          data-testid="recent-entry-amount"
        >
          {formatSignedWon(entry.signedAmount)}
        </p>
        <p className="mt-1 text-muted-foreground tabular-nums" data-testid="recent-entry-balance-after">
          잔액 {formatWon(entry.balanceAfter)}
        </p>
      </div>
    </li>
  )
}
