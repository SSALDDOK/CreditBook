import type { LedgerEntryType } from '@/api/customers'

// 거래 유형 표시 문구는 이 한 곳에서 정한다(문서 용어 그대로 — 사용은 "차감"이라 쓰지 않는다).
// 거래 이력 화면 등 다른 화면도 이 매핑을 쓴다.
export const LEDGER_ENTRY_TYPE_LABELS: Record<LedgerEntryType, string> = {
  CHARGE: '충전',
  USE: '사용',
  CHARGE_CANCEL: '충전 취소',
  USE_CANCEL: '사용 취소',
}
