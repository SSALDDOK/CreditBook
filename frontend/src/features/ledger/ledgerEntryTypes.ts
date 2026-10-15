import type { LedgerEntryType } from '@/api/customers'

// 거래 유형 표시(라벨·아이콘 글자·색)는 이 한 곳에서 정한다(문서 용어 그대로 — 사용은 "차감"이라 쓰지 않는다).
// 고객 상세의 최근 거래(SCR-5)와 거래 이력 화면이 같은 규칙을 쓴다.
// 색은 화면설계 SCR-5 값 그대로: 충전 초록, 사용 파랑, 취소(충전 취소·사용 취소) 주황.

export type LedgerEntryTypeStyle = {
  /** 유형 라벨 칩 문구 — 스크린리더가 유형을 읽는 텍스트 */
  label: string
  /** 왼쪽 정사각 아이콘 글자(장식, aria-hidden) */
  icon: string
  /** 아이콘·라벨 칩 공통 색 (바탕 + 글자) */
  toneClassName: string
}

const CHARGE_TONE = 'bg-[#E7F6EE] text-[#1B7A4B]'
const USE_TONE = 'bg-[#EAEEFC] text-[#2B4ACB]'
const CANCEL_TONE = 'bg-[#FFF1E0] text-[#B45309]'

export const LEDGER_ENTRY_TYPE_STYLES: Record<LedgerEntryType, LedgerEntryTypeStyle> = {
  CHARGE: { label: '충전', icon: '충', toneClassName: CHARGE_TONE },
  USE: { label: '사용', icon: '사', toneClassName: USE_TONE },
  CHARGE_CANCEL: { label: '충전 취소', icon: '취', toneClassName: CANCEL_TONE },
  USE_CANCEL: { label: '사용 취소', icon: '취', toneClassName: CANCEL_TONE },
}

/** 유형 표시 문구만 필요할 때 */
export const LEDGER_ENTRY_TYPE_LABELS: Record<LedgerEntryType, string> = {
  CHARGE: LEDGER_ENTRY_TYPE_STYLES.CHARGE.label,
  USE: LEDGER_ENTRY_TYPE_STYLES.USE.label,
  CHARGE_CANCEL: LEDGER_ENTRY_TYPE_STYLES.CHARGE_CANCEL.label,
  USE_CANCEL: LEDGER_ENTRY_TYPE_STYLES.USE_CANCEL.label,
}
