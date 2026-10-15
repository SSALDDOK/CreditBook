import { formatNumber } from '@/lib/format'

// 금액 입력칸 규칙 — 충전·사용 모달이 같이 쓴다. 형식(원 단위 양의 정수)만 본다.
// 1회 충전 한도는 화면 편의로 미리 보여 줄 뿐이고, 잔액 부족·한도의 최종 판단은 서버가 한다.
// 소수로 바꾸지 않는다 — 숫자 문자열이 정수 형식일 때만 Number 로 바꾼다.
//
// 예:
//   normalizeAmountInput('50,000') → '50000'       formatAmountInput('50000') → '50,000'
//   normalizeAmountInput('0050')   → '50'          formatAmountInput('12.5')  → '12.5'(형식 오류는 친 그대로)
//   checkAmount('')        → { amount: null, error: 'EMPTY' }
//   checkAmount('0')       → { amount: null, error: 'NOT_POSITIVE' }
//   checkAmount('-500')    → { amount: null, error: 'NOT_POSITIVE' }
//   checkAmount('12.5')    → { amount: null, error: 'NOT_INTEGER' }
//   checkAmount('5천')     → { amount: null, error: 'NOT_NUMBER' }
//   checkAmount('300001', 300000) → { amount: 300001, error: 'OVER_MAX' }
//   checkAmount('50000', 300000)  → { amount: 50000, error: null }

/** 1조 미만(12자리)까지 받는다 — 한글 금액이 억 단위까지 읽고, Number 정밀도 안이다 */
export const AMOUNT_MAX_DIGITS = 12

export type AmountErrorKind = 'EMPTY' | 'NOT_POSITIVE' | 'NOT_INTEGER' | 'NOT_NUMBER' | 'TOO_LARGE' | 'OVER_MAX'

export type AmountCheck = { amount: number | null; error: AmountErrorKind | null }

const DIGITS_ONLY = /^\d+$/

/** 입력칸 글자를 저장할 형태로: 콤마·공백을 지우고, 숫자만이면 앞자리 0 을 지운다. 그 밖은 친 그대로 둔다(사유 표시용) */
export function normalizeAmountInput(raw: string): string {
  const stripped = raw.replace(/[,\s]/g, '')
  if (DIGITS_ONLY.test(stripped)) {
    const trimmed = stripped.replace(/^0+(?=\d)/, '')
    return trimmed
  }
  return stripped
}

/** 저장된 입력을 칸에 보여 줄 형태로: 숫자만이면 3자리 콤마, 아니면 그대로 */
export function formatAmountInput(normalized: string): string {
  if (DIGITS_ONLY.test(normalized) && normalized.length <= AMOUNT_MAX_DIGITS) {
    return formatNumber(Number(normalized))
  }
  return normalized
}

/** 입력 형식을 검사한다. maxAmount 를 주면(충전 1회 한도) 넘을 때 OVER_MAX — amount 는 함께 돌려준다 */
export function checkAmount(normalized: string, maxAmount?: number): AmountCheck {
  if (normalized === '') return { amount: null, error: 'EMPTY' }
  if (normalized.startsWith('-')) return { amount: null, error: 'NOT_POSITIVE' }
  if (/^\d*\.\d*$/.test(normalized) && normalized !== '.') return { amount: null, error: 'NOT_INTEGER' }
  if (!DIGITS_ONLY.test(normalized)) return { amount: null, error: 'NOT_NUMBER' }
  if (normalized.length > AMOUNT_MAX_DIGITS) return { amount: null, error: 'TOO_LARGE' }
  const amount = Number(normalized)
  if (amount < 1) return { amount: null, error: 'NOT_POSITIVE' }
  if (maxAmount !== undefined && amount > maxAmount) return { amount, error: 'OVER_MAX' }
  return { amount, error: null }
}

/** 형식 오류 문구 — 서버 INVALID_AMOUNT 문구(PrepaidAccount)와 같은 말투. OVER_MAX 는 화면마다 달라 호출하는 쪽이 정한다 */
export const AMOUNT_ERROR_MESSAGES: Record<Exclude<AmountErrorKind, 'OVER_MAX'>, string> = {
  EMPTY: '금액을 입력해 주세요.',
  NOT_POSITIVE: '금액은 1원 이상이어야 합니다.',
  NOT_INTEGER: '금액은 원 단위 정수여야 합니다.',
  NOT_NUMBER: '금액은 숫자로만 입력해 주세요.',
  TOO_LARGE: '금액을 확인해 주세요.',
}
