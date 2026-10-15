// 금액을 한글로 읽어 주는 표시 전용 함수. 값은 바꾸지 않고 읽는 방법만 만든다.
//
// 예: 50000 → "오만", 95500 → "구만오천오백", 41000 → "사만일천", 4500 → "사천오백", 10000 → "만",
//     1000 → "천", 110000 → "십일만", 300000 → "삼십만", 1 → "일", 0 → "영",
//     11000 → "만일천", 100000000 → "일억", 123456789 → "일억이천삼백사십오만육천칠백팔십구"
// 규칙: 맨 앞자리가 1이고 그 자리에 십·백·천 또는 만이 붙으면 '일'을 생략한다(만, 천, 십일만). 억은 "일억".
//       그 뒤의 1은 읽는다(사만일천). 일의 자리 1은 항상 "일". 억 단위(1조 미만)까지 읽는다.
// 0 은 "영"을 돌려준다 — "잔액이 0원이 됩니다"처럼 0을 다르게 말하는 화면은 호출하는 쪽이 처리한다.

const DIGITS = ['', '일', '이', '삼', '사', '오', '육', '칠', '팔', '구']
const SMALL_UNITS = ['', '십', '백', '천']
const BIG_UNITS = ['', '만', '억']
const GROUP = 10000
const LIMIT = GROUP ** BIG_UNITS.length // 1조

/** 원 단위 정수 금액을 한글로 읽는다. 0 이상 1조 미만의 정수가 아니면 숫자 그대로 돌려준다 */
export function toKoreanAmount(amount: number): string {
  if (!Number.isSafeInteger(amount) || amount < 0 || amount >= LIMIT) {
    return String(amount)
  }
  if (amount === 0) return '영'

  let out = ''
  for (let big = BIG_UNITS.length - 1; big >= 0; big -= 1) {
    const group = Math.floor(amount / GROUP ** big) % GROUP
    if (group === 0) continue
    for (let small = 3; small >= 0; small -= 1) {
      const digit = Math.floor(group / 10 ** small) % 10
      if (digit === 0) continue
      const isFirst = out === ''
      // 맨 앞의 1 뒤에 십·백·천·만이 붙으면 '일'을 읽지 않는다. 억은 "일억"으로 읽는다
      const dropsOne = small > 0 || big === 1
      out += (digit === 1 && isFirst && dropsOne ? '' : DIGITS[digit]) + SMALL_UNITS[small]
    }
    out += BIG_UNITS[big]
  }
  return out
}
