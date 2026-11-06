// 직원 계정 입력 규칙 — 서버 EmployeeCredentialRules 와 같은 값이다(매장 개설·직원 추가·비밀번호 변경이 함께 쓴다).
// 화면 검사는 편의다. 최종 검증은 서버가 하고, 화면은 서버 fieldErrors 도 같은 칸 아래에 보여 준다.

export const STORE_NAME_MAX_LENGTH = 30
export const NAME_MAX_LENGTH = 50
export const LOGIN_ID_MAX_LENGTH = 20
export const LOGIN_ID_PATTERN = /^[A-Za-z0-9.]{4,20}$/
export const PASSWORD_MIN_LENGTH = 8
export const PASSWORD_MAX_LENGTH = 72
/** BCrypt 가 대조할 수 있는 최대 바이트(UTF-8). 한글은 글자당 3바이트라 글자 수가 72자 이하여도 넘을 수 있다 */
export const PASSWORD_MAX_BYTES = 72

// 서버 SetupRequest 의 검증 문구와 같은 글자다
export const STORE_NAME_INVALID_MESSAGE = '매장명은 1–30자여야 합니다.'
export const NAME_INVALID_MESSAGE = '이름은 1–50자여야 합니다.'
export const LOGIN_ID_INVALID_MESSAGE = '아이디는 영문·숫자·마침표 4–20자여야 합니다.'
export const PASSWORD_LENGTH_INVALID_MESSAGE = '비밀번호는 8–72자여야 합니다.'
export const PASSWORD_TOO_MANY_BYTES_MESSAGE = '비밀번호가 너무 깁니다. 한글 등은 글자당 여러 바이트를 차지합니다.'
export const PASSWORD_CONFIRM_MISMATCH_MESSAGE = '비밀번호 확인이 일치하지 않습니다.'

const encoder = new TextEncoder()

export function utf8ByteLength(value: string): number {
  return encoder.encode(value).length
}

/** 앞뒤 공백을 뺀 글자 수(코드 포인트) — 서버 @StrippedLength 와 같은 기준 */
function strippedLength(value: string): number {
  return Array.from(value.trim()).length
}

/** 매장명 — 앞뒤 공백을 뺀 1–30자 */
export function isValidStoreName(storeName: string): boolean {
  const length = strippedLength(storeName)
  return length >= 1 && length <= STORE_NAME_MAX_LENGTH
}

/** 이름 — 앞뒤 공백을 뺀 1–50자 */
export function isValidName(name: string): boolean {
  const length = strippedLength(name)
  return length >= 1 && length <= NAME_MAX_LENGTH
}

/** 아이디 — 영문·숫자·마침표 4–20자(대소문자 구분) */
export function isValidLoginId(loginId: string): boolean {
  return LOGIN_ID_PATTERN.test(loginId)
}

/** 새 비밀번호 글자 수 규칙(8–72자). 바이트 규칙은 따로 본다 */
export function hasValidPasswordLength(password: string): boolean {
  return password.length >= PASSWORD_MIN_LENGTH && password.length <= PASSWORD_MAX_LENGTH
}

export function exceedsPasswordBytes(password: string): boolean {
  return utf8ByteLength(password) > PASSWORD_MAX_BYTES
}
