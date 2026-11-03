import type { Role } from './auth'
import { apiRequest } from './client'
import { USE_MOCK } from './config'
import {
  addMockEmployee,
  deactivateMockEmployee,
  listMockEmployees,
  resetMockEmployeePassword,
} from './mock/employees'

// 백엔드 직원 관리 API (EmployeeListResponse·AddEmployeeRequest·AddEmployeeResponse·ResetPasswordResponse) 와 필드 이름을 맞춘다.
// 모든 경로가 사장(ADMIN) 전용이다 — STAFF 가 부르면 403 FORBIDDEN.
// 초기 비밀번호는 응답에서 한 번만 온다. 화면은 상태(메모리)에만 잠깐 두고 저장소·주소·로그에 남기지 않는다.

export type EmployeeListItem = {
  employeeId: string
  name: string
  loginId: string
  role: Role
  active: boolean
  /** 마지막 로그인 시각 (ISO-8601 Instant). 한 번도 로그인하지 않았으면 null */
  lastLoginAt: string | null
}

/** 사장 → 활성 직원 → 비활성 직원 순(서버 순서 그대로 표시한다) */
export type EmployeeListResponse = { items: EmployeeListItem[] }

/** 직원 추가 요청 — 이름·아이디만. 권한은 STAFF 고정이고 비밀번호는 서버가 만든다(다른 항목을 넣으면 서버가 400) */
export type AddEmployeeRequest = { name: string; loginId: string }

export type AddEmployeeResponse = {
  employeeId: string
  name: string
  loginId: string
  /** 항상 STAFF */
  role: Role
  /** 초기 비밀번호 — 이 응답에서 한 번만 보여 준다 */
  initialPassword: string
}

export type ResetPasswordResponse = { employeeId: string; initialPassword: string }

const JSON_HEADERS = { 'Content-Type': 'application/json' }

/** GET /api/employees — 직원 목록(비활성 포함, 페이지 없음) */
export function listEmployees(signal?: AbortSignal): Promise<EmployeeListResponse> {
  if (USE_MOCK) return listMockEmployees(signal)
  return apiRequest<EmployeeListResponse>('/api/employees', { signal })
}

/** POST /api/employees — 직원(STAFF) 추가. 201. 같은 아이디(비활성 포함, 대소문자 구분)가 있으면 409 DUPLICATE_LOGIN_ID */
export function addEmployee(request: AddEmployeeRequest): Promise<AddEmployeeResponse> {
  if (USE_MOCK) return addMockEmployee(request)
  return apiRequest<AddEmployeeResponse>('/api/employees', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ name: request.name, loginId: request.loginId }),
  })
}

/** POST /api/employees/{id}/reset-password — 본문 없음. 없으면 404 EMPLOYEE_NOT_FOUND, 사장 계정이면 409 ADMIN_ACCOUNT_PROTECTED */
export function resetEmployeePassword(employeeId: string): Promise<ResetPasswordResponse> {
  if (USE_MOCK) return resetMockEmployeePassword(employeeId)
  return apiRequest<ResetPasswordResponse>(`/api/employees/${encodeURIComponent(employeeId)}/reset-password`, {
    method: 'POST',
  })
}

/** PATCH /api/employees/{id}/deactivate — 본문 없음, 204(이미 비활성이어도 204). 오류 코드는 초기화와 같다 */
export function deactivateEmployee(employeeId: string): Promise<void> {
  if (USE_MOCK) return deactivateMockEmployee(employeeId)
  return apiRequest<void>(`/api/employees/${encodeURIComponent(employeeId)}/deactivate`, { method: 'PATCH' })
}
