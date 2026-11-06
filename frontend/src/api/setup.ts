import type { Role } from './auth'
import { apiRequest } from './client'
import { USE_MOCK } from './config'
import { mockGetSetupStatus, mockSetUp } from './mock/setup'

// 백엔드 매장 개설 API (SetupStatusResponse·SetupRequest·SetupResponse) 와 필드 이름을 맞춘다.
// 로그인 없이 부르는 경로다. 직원 계정이 하나도 없을 때만 열린다(REQ-29).

export type SetupStatusResponse = { available: boolean }

export type SetupRequest = {
  storeName: string
  ownerName: string
  loginId: string
  password: string
  passwordConfirm: string
}

export type SetupResponse = { storeName: string; loginId: string; name: string; role: Role }

const JSON_HEADERS = { 'Content-Type': 'application/json' }

/** GET /api/setup/status — 매장 개설 가능 여부 */
export function getSetupStatus(signal?: AbortSignal): Promise<SetupStatusResponse> {
  if (USE_MOCK) return mockGetSetupStatus(signal)
  return apiRequest<SetupStatusResponse>('/api/setup/status', { signal }, { redirectOnUnauthorized: false })
}

/**
 * POST /api/setup — 매장과 첫 사장 계정을 만든다. 201, 자동 로그인 없음(화면이 이어서 로그인을 부른다).
 * 계정이 이미 있으면 403 SETUP_ALREADY_COMPLETED, 형식 오류는 400 INVALID_INPUT(fieldErrors).
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
