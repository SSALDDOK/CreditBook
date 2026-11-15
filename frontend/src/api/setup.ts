import type { Role } from './auth'
import { apiRequest } from './client'
import { USE_MOCK } from './config'
import { mockSetUp } from './mock/setup'

// 백엔드 매장 개설 API (SetupRequest·SetupResponse) 와 필드 이름을 맞춘다.
// 로그인 없이 부르는 경로다. 받은 초대 코드로 새 매장과 그 매장의 사장 계정을 만든다(REQ-29).

export type SetupRequest = {
  /** 받은 초대 코드. 대소문자·하이픈은 서버가 가리지 않으므로 화면은 형식을 검사하지 않는다 */
  inviteCode: string
  storeName: string
  ownerName: string
  loginId: string
  password: string
  passwordConfirm: string
}

export type SetupResponse = { storeName: string; loginId: string; name: string; role: Role }

const JSON_HEADERS = { 'Content-Type': 'application/json' }

/**
 * POST /api/setup — 초대 코드로 매장과 사장 계정을 만든다. 201, 자동 로그인 없음(화면이 이어서 로그인을 부른다).
 * 400 INVALID_INVITE_CODE(없는·꺼진·다 쓴 코드를 구분하지 않음), 409 DUPLICATE_LOGIN_ID(아이디는 모든 매장에서 유일),
 * 429 SIGNUP_RATE_LIMITED, 형식 오류는 400 INVALID_INPUT(fieldErrors).
 * 변경 요청이라 자동 재시도하지 않는다.
 */
export function setUp(request: SetupRequest): Promise<SetupResponse> {
  if (USE_MOCK) return mockSetUp(request)
  return apiRequest<SetupResponse>(
    '/api/setup',
    { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(request) },
    { redirectOnUnauthorized: false },
  )
}
