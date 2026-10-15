import { formatNumber } from '@/lib/format'

/**
 * 잔액 부족 안내 (SCR-7 '잔액 부족', TC-14) — 화면 미리 표시와 서버 INSUFFICIENT_BALANCE(details) 가 같은 문장을 쓴다.
 * 값은 넘겨받은 그대로 보여 준다(서버 응답이면 details.balance·shortage 그대로).
 * 예: insufficientBalanceMessage(3000, 2000) → "잔액이 2,000원 부족합니다 (현재 잔액 3,000원)"
 */
export function insufficientBalanceMessage(balance: number, shortage: number): string {
  return `잔액이 ${formatNumber(shortage)}원 부족합니다 (현재 잔액 ${formatNumber(balance)}원)`
}
