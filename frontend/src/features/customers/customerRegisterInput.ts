// 고객 등록(SCR-4) 입력 규칙 — 형식 검사만 한다. 같은 번호 여부 같은 판단은 서버가 한다(409 DUPLICATE_PHONE).
// 글자 수는 서버(Customer — 코드 포인트)와 같게 Array.from 으로 센다.

export const NAME_MAX_LENGTH = 20
export const MEMO_MAX_LENGTH = 200

export type RegisterField = 'name' | 'phone' | 'memo'
export type RegisterFieldErrors = Partial<Record<RegisterField, string>>

/** 글자 수(코드 포인트). 메모 칸의 "0 / 200" 표시와 길이 검사에 같이 쓴다 */
export function countChars(value: string): number {
  return Array.from(value).length
}

/** 하이픈을 뺀 숫자만. 숫자·하이픈 외 글자가 있으면 null */
function phoneDigitsOf(phone: string): string | null {
  return /^[\d-]+$/.test(phone) ? phone.replace(/-/g, '') : null
}

/** 클라이언트 형식 검사. 통과하면 빈 객체 — 하이픈 위치처럼 더 세세한 형식은 서버가 fieldErrors 로 알려 준다 */
export function validateRegisterInput({
  name,
  phone,
  memo,
}: {
  name: string
  phone: string
  memo: string
}): RegisterFieldErrors {
  const errors: RegisterFieldErrors = {}
  const trimmedName = name.trim()
  if (!trimmedName) errors.name = '이름을 입력해 주세요.'
  else if (countChars(trimmedName) > NAME_MAX_LENGTH) errors.name = `이름은 ${NAME_MAX_LENGTH}자 이하여야 합니다.`

  const trimmedPhone = phone.trim()
  if (!trimmedPhone) errors.phone = '연락처를 입력해 주세요.'
  else {
    const digits = phoneDigitsOf(trimmedPhone)
    if (digits === null || digits.length < 9 || digits.length > 11) {
      errors.phone = '연락처는 숫자 9–11자리여야 합니다.'
    }
  }

  if (countChars(memo.trim()) > MEMO_MAX_LENGTH) errors.memo = `메모는 ${MEMO_MAX_LENGTH}자 이하여야 합니다.`
  return errors
}

export type RegisterPrefill = { name: string; phone: string }

/**
 * 고객 목록 검색 0건의 [바로 등록]이 넘긴 검색어를 어느 칸에 채울지 정한다.
 * - 하이픈·공백을 뺀 값이 9–11자리 숫자 → 연락처 칸(입력한 모양 그대로)
 * - 숫자·하이픈·공백만인데 8자리 이하(또는 12자리 이상) → 채우지 않는다(뒷자리 검색이었을 뿐 번호가 아니다)
 * - 글자가 섞여 있으면 → 이름 칸
 */
export function prefillFromSearch(query: string | undefined): RegisterPrefill {
  const trimmed = query?.trim() ?? ''
  if (!trimmed) return { name: '', phone: '' }
  if (/^[\d\s-]+$/.test(trimmed)) {
    const digits = trimmed.replace(/[\s-]/g, '')
    return digits.length >= 9 && digits.length <= 11 ? { name: '', phone: trimmed.replace(/\s/g, '') } : { name: '', phone: '' }
  }
  return { name: trimmed, phone: '' }
}

/**
 * 확인 창 부제에 쓸 번호 표시 — 직원이 입력한 번호를 010-3381-4821 형식으로 보여 준다(서버는 번호를 주지 않는다).
 * 표시만 하며 저장·전송·로그에 쓰지 않는다.
 */
export function formatPhoneForDisplay(phone: string): string {
  const digits = phone.replace(/\D/g, '')
  const head = digits.startsWith('02') ? 2 : 3
  if (digits.length < head + 5) return digits
  return `${digits.slice(0, head)}-${digits.slice(head, digits.length - 4)}-${digits.slice(-4)}`
}
