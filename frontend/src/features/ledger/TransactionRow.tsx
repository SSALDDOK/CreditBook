import type { ReactNode } from 'react'
import type { TransactionResponse } from '@/api/transactions'
import { TableCell, TableRow } from '@/components/ui/table'
import { formatShortDateTime, formatSignedWon, formatWon } from '@/lib/format'
import { cn } from '@/lib/utils'
import { EntryCancelledBadge, EntryTypeLabel } from './LedgerEntryParts'

type TransactionRowProps = {
  entry: TransactionResponse
  /** 행 높이(px) — 표 영역 높이에 맞춘 값(useFitPageSize). 없으면 기본 높이 */
  height?: number
  /**
   * 맨 오른쪽 칸에 넣을 조작(예: [취소] 버튼). 비우면 빈 칸만 둔다.
   * 버튼 상태는 서버 값만 보고 정한다 — cancellable 이면 활성, cancelled 면 비활성, 그 밖은 버튼 없음.
   * 화면이 날짜를 계산해 취소 가능 여부를 정하지 않는다.
   */
  action?: ReactNode
}

/**
 * 거래 이력 표(SCR-8)의 한 줄: 일시 · 유형 · 메모 · 금액 · 거래 후 잔액 · 처리 · (조작 칸).
 * 금액·거래 후 잔액은 서버 값(signedAmount·balanceAfter)을 그대로 표시한다. 부호·색은 signedAmount 부호를 따른다
 * (+ 초록, - 기본색). 취소된 원거래(cancelled=true)는 일시 앞에 '취소됨' 배지만 붙인다(고객 상세와 같은 표시).
 */
export function TransactionRow({ entry, height, action }: TransactionRowProps) {
  const credit = entry.signedAmount > 0

  return (
    <TableRow
      data-testid="transaction-row"
      data-entry-id={entry.id}
      data-type={entry.type}
      data-cancelled={entry.cancelled}
      data-cancellable={entry.cancellable}
      style={height ? { height } : undefined}
    >
      <TableCell className="h-12 pr-2 pl-5" data-testid="transaction-time">
        <span className="flex items-center gap-1.5 text-muted-foreground tabular-nums">
          {entry.cancelled && <EntryCancelledBadge className="px-1.5 text-xs" />}
          <time dateTime={entry.performedAt}>{formatShortDateTime(entry.performedAt)}</time>
        </span>
      </TableCell>
      <TableCell className="px-2">
        <EntryTypeLabel type={entry.type} />
      </TableCell>
      <TableCell className="truncate px-2" title={entry.memo ?? undefined} data-testid="transaction-memo">
        {entry.memo}
      </TableCell>
      <TableCell
        className={cn('px-2 text-right text-base font-bold tabular-nums', credit && 'text-green-700')}
        data-testid="transaction-amount"
      >
        {formatSignedWon(entry.signedAmount)}
      </TableCell>
      <TableCell className="px-2 text-right text-muted-foreground tabular-nums" data-testid="transaction-balance-after">
        {formatWon(entry.balanceAfter)}
      </TableCell>
      <TableCell
        className="truncate px-2 text-muted-foreground"
        title={entry.performedByName ?? undefined}
        data-testid="transaction-performer"
      >
        {entry.performedByName}
      </TableCell>
      <TableCell className="pr-3 pl-1 text-right" data-testid="transaction-action">
        {action}
      </TableCell>
    </TableRow>
  )
}
