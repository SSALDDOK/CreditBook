// 모든 API 호출이 거치는 클라이언트. 서버 오류 응답(ErrorResponse)은 평평한 형태다:
// { code, message, fieldErrors, details? } — 화면 분기는 code 로만 한다.

export type FieldError = { field: string; message: string }

export type ErrorResponse = {
  code: string
  message: string
  fieldErrors?: FieldError[]
  details?: unknown
}

export const COMMON_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'
export const NETWORK_ERROR_MESSAGE = '네트워크 연결을 확인해 주세요.'

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: FieldError[]
  readonly details?: unknown

  constructor(status: number, code: string, message: string, fieldErrors: FieldError[] = [], details?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
    this.details = details
  }
}

function isErrorResponse(body: unknown): body is ErrorResponse {
  return (
    typeof body === 'object' &&
    body !== null &&
    typeof (body as ErrorResponse).code === 'string' &&
    typeof (body as ErrorResponse).message === 'string'
  )
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  // 인증은 서버가 내려 주는 httpOnly 쿠키로 한다. 화면은 토큰을 읽거나 저장하지 않는다.
  const res = await fetch(`${import.meta.env.VITE_API_BASE_URL ?? ''}${path}`, {
    ...init,
    credentials: 'include',
    headers: { Accept: 'application/json', ...init.headers },
  })
  if (res.ok) {
    return (res.status === 204 ? undefined : await res.json()) as T
  }

  const body: unknown = await res.json().catch(() => null)
  if (isErrorResponse(body)) {
    throw new ApiError(res.status, body.code, body.message, body.fieldErrors ?? [], body.details)
  }
  throw new ApiError(res.status, 'INTERNAL_ERROR', COMMON_ERROR_MESSAGE)
}

// 화면설계서 "오류 메시지 표준" 문구가 정해지면 code 별로 채운다.
const SCREEN_MESSAGES: Partial<Record<string, string>> = {}

/** 표시 문구 우선순위: 화면 표준 문구 → 서버 message → 5xx·본문 없음이면 공통 문구. */
export function toUserMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status >= 500) return COMMON_ERROR_MESSAGE
    return SCREEN_MESSAGES[e.code] ?? e.message
  }
  return NETWORK_ERROR_MESSAGE
}
