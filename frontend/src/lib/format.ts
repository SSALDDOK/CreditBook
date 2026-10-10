// 표시 형식만 바꾼다. 금액 값 자체는 서버가 준 그대로이며 여기서 계산하지 않는다.

const wonFormatter = new Intl.NumberFormat('ko-KR')

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
