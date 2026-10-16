import type { LedgerEntryType } from '@/api/customers'
import { cn } from '@/lib/utils'
import { LEDGER_ENTRY_TYPE_STYLES } from './ledgerEntryTypes'

// 고객 상세의 최근 거래(SCR-5)와 거래 이력(SCR-8)이 함께 쓰는 거래 표시 조각.

/** 유형 라벨 칩(폭 66px 고정 — 옆 메모의 시작 위치를 맞춘다). 색·문구는 ledgerEntryTypes.ts */
export function EntryTypeLabel({ type, className }: { type: LedgerEntryType; className?: string }) {
  const style = LEDGER_ENTRY_TYPE_STYLES[type]
  return (
    <span
      className={cn(
        'inline-flex w-[66px] shrink-0 items-center justify-center rounded-md py-1 text-sm font-semibold whitespace-nowrap',
        style.toneClassName,
        className,
      )}
      data-testid="entry-type-label"
    >
      {style.label}
    </span>
  )
}

/**
 * 반제(취소)된 원거래 앞에 붙는 회색 '취소됨' 배지. 취소선·금액 색 변경은 하지 않는다
 * — 원거래 금액·잔액은 그 시점의 장부 기록 그대로다. 서버의 cancelled 값이 true 일 때만 그린다.
 */
export function EntryCancelledBadge({ className }: { className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center rounded-md bg-muted px-2 py-0.5 text-sm font-semibold text-muted-foreground',
        className,
      )}
      data-testid="entry-cancelled-badge"
    >
      취소됨
    </span>
  )
}
