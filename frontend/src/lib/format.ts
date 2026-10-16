// 표시 형식만 바꾼다. 금액 값 자체는 서버가 준 그대로이며 여기서 계산하지 않는다.

const wonFormatter = new Intl.NumberFormat('ko-KR')

/** 45500 → "45,500" (단위를 따로 그리는 곳에서 쓴다) */
export function formatNumber(amount: number): string {
  return wonFormatter.format(amount)
}

/** 45500 → "45,500원" */
export function formatWon(amount: number): string {
  return `${wonFormatter.format(amount)}원`
}

// 매장 기준 시간대로 날짜를 보여 준다(브라우저 시간대에 따라 날짜가 바뀌지 않게).
const monthDayFormatter = new Intl.DateTimeFormat('ko-KR', {
  month: 'long',
  day: 'numeric',
  timeZone: 'Asia/Seoul',
})

/** "2026-10-05T02:10:00Z" → "10월 5일" */
export function formatMonthDay(isoInstant: string): string {
  return monthDayFormatter.format(new Date(isoInstant))
}

// 부호 표시만 붙인다: 50000 → "+50,000원", -4500 → "-4,500원", 0 → "0원". 값은 서버가 준 signedAmount 그대로다.
const signedWonFormatter = new Intl.NumberFormat('ko-KR', { signDisplay: 'exceptZero' })

/** 부호가 붙은 금액(signedAmount) 표시. 부호는 서버 값의 부호 그대로다 */
export function formatSignedWon(signedAmount: number): string {
  return `${signedWonFormatter.format(signedAmount)}원`
}

const seoulDateFormatter = new Intl.DateTimeFormat('en-CA', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  timeZone: 'Asia/Seoul',
})
const timeFormatter = new Intl.DateTimeFormat('ko-KR', {
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
  timeZone: 'Asia/Seoul',
})

/**
 * 거래 시각 표시. 매장 기준(Asia/Seoul) 오늘이면 "오늘 14:22", 아니면 "9월 18일 09:14".
 * now 는 테스트·화면에서 기준 시각을 넘길 수 있게 둔다(기본은 현재 시각).
 */
export function formatEntryTime(isoInstant: string, now: Date = new Date()): string {
  const at = new Date(isoInstant)
  const time = timeFormatter.format(at)
  if (seoulDateFormatter.format(at) === seoulDateFormatter.format(now)) {
    return `오늘 ${time}`
  }
  return `${monthDayFormatter.format(at)} ${time}`
}

const shortDateTimeFormatter = new Intl.DateTimeFormat('en-CA', {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
  timeZone: 'Asia/Seoul',
})

/** 거래 이력 표의 일시. 매장 기준(Asia/Seoul) "MM-DD HH:mm" — "2026-10-15T06:40:00Z" → "10-15 15:40" */
export function formatShortDateTime(isoInstant: string): string {
  const parts = shortDateTimeFormatter.formatToParts(new Date(isoInstant))
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === type)?.value ?? ''
  return `${part('month')}-${part('day')} ${part('hour')}:${part('minute')}`
}
