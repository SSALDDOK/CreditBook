import type { RecentLedgerEntryResponse } from '@/api/customers'
import { formatEntryTime, formatSignedWon, formatWon } from '@/lib/format'
import { cn } from '@/lib/utils'
import { EntryCancelledBadge, EntryTypeLabel } from './LedgerEntryParts'
import { LEDGER_ENTRY_TYPE_STYLES } from './ledgerEntryTypes'

type LedgerEntryRowProps = { entry: RecentLedgerEntryResponse }

/**
 * 거래 한 줄 (화면설계 SCR-5 최근 거래). 금액·변동 후 잔액은 서버 값(signedAmount·balanceAfter)을 그대로 표시한다.
 * 부호·색은 signedAmount 부호를 따른다(+ 초록, - 기본색).
 *
 * - 윗줄: 유형 아이콘(장식) + 같은 색 유형 라벨 칩(폭 66px 고정 — 메모 시작 위치를 맞춘다) + 메모.
 *   충전 취소·사용 취소 행은 메모 자리에 서버 memo(취소 사유)를 그대로 보여 준다.
 * - 아랫줄: 날짜 시각. 취소된 원거래(cancelled=true)는 그 앞에 회색 '취소됨' 배지만 붙인다
 *   — 취소선·금액 색 변경 없음(원거래 금액·잔액은 그 시점의 장부 기록 그대로다).
 * 유형별 라벨·아이콘·색은 ledgerEntryTypes.ts 한 곳에서 정한다.
 */
export function LedgerEntryRow({ entry }: LedgerEntryRowProps) {
  const style = LEDGER_ENTRY_TYPE_STYLES[entry.type]
  const credit = entry.signedAmount > 0

  return (
    <li
      className="flex items-center gap-5 border-b py-2.5 last:border-b-0"
      data-testid="recent-entry-row"
      data-entry-id={entry.id}
      data-entry-type={entry.type}
      data-cancelled={entry.cancelled}
    >
      <span
        aria-hidden="true"
        className={cn(
          'flex size-11 shrink-0 items-center justify-center rounded-xl font-semibold',
          style.toneClassName,
        )}
      >
        {style.icon}
      </span>
      <div className="min-w-0 flex-1">
        <p className="flex min-w-0 items-center gap-3" data-testid="recent-entry-title">
          <EntryTypeLabel type={entry.type} />
          {entry.memo && (
            <span className="truncate text-lg leading-6 font-semibold" data-testid="recent-entry-memo">
              {entry.memo}
            </span>
          )}
        </p>
        <p className="mt-0.5 flex items-center gap-2 text-muted-foreground" data-testid="recent-entry-time">
          {entry.cancelled && <EntryCancelledBadge />}
          <time dateTime={entry.performedAt}>{formatEntryTime(entry.performedAt)}</time>
        </p>
      </div>
      <div className="shrink-0 text-right">
        <p
          className={cn('text-lg leading-6 font-bold tabular-nums', credit && 'text-green-700')}
          data-testid="recent-entry-amount"
        >
          {formatSignedWon(entry.signedAmount)}
        </p>
        <p className="mt-0.5 text-muted-foreground tabular-nums" data-testid="recent-entry-balance-after">
          잔액 {formatWon(entry.balanceAfter)}
        </p>
      </div>
    </li>
  )
}
