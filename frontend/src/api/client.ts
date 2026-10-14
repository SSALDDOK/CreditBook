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

// 보호 API 가 401 을 주면(세션 만료·로그아웃된 토큰) 부를 함수. 인증 상태를 가진 AuthProvider 가 등록한다.
// 401 처리를 화면마다 쓰지 않고 이 한 곳으로 모은다.
let unauthorizedHandler: (() => void) | null = null

/** 401 처리 함수를 등록하고, 해제 함수를 돌려준다. */
export function setUnauthorizedHandler(handler: () => void): () => void {
  unauthorizedHandler = handler
  return () => {
    if (unauthorizedHandler === handler) unauthorizedHandler = null
  }
}

export type ApiRequestOptions = {
  /**
   * false 면 401 을 받아도 로그인 화면으로 보내지 않고 오류만 던진다.
   * 로그인(401 = 자격증명 오류)·세션 확인(401 = 아직 로그인 안 함)처럼 401 이 정상 응답인 호출에 쓴다.
   */
  redirectOnUnauthorized?: boolean
}

export async function apiRequest<T>(
  path: string,
  init: RequestInit = {},
  { redirectOnUnauthorized = true }: ApiRequestOptions = {},
): Promise<T> {
  // 인증은 서버가 내려 주는 httpOnly 쿠키로 한다. 화면은 토큰을 읽거나 저장하지 않는다.
  const res = await fetch(`${import.meta.env.VITE_API_BASE_URL ?? ''}${path}`, {
    ...init,
    credentials: 'include',
    headers: { Accept: 'application/json', ...init.headers },
  })
  if (res.ok) {
    return (res.status === 204 ? undefined : await res.json()) as T
  }

  if (res.status === 401 && redirectOnUnauthorized) {
    unauthorizedHandler?.()
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
