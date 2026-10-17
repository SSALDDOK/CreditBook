import type { TransactionResponse } from '@/api/transactions'

/** 취소 창 종류 — 사용 취소(REQ-34)·충전 취소(REQ-7) */
export type CancelKind = 'use' | 'charge'

/** 취소 창을 열 수 있는 거래 유형이면 그 종류 — 원거래(사용·충전)만. 반제 행(충전 취소·사용 취소)은 null */
export function cancelKindOf(entry: TransactionResponse): CancelKind | null {
  if (entry.type === 'USE') return 'use'
  if (entry.type === 'CHARGE') return 'charge'
  return null
}
