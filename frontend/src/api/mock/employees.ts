import { ApiError, notifyPasswordChangeRequired, notifyUnauthorized } from '../client'
import type {
  AddEmployeeRequest,
  AddEmployeeResponse,
  EmployeeListItem,
  EmployeeListResponse,
  ResetPasswordResponse,
} from '../employees'
import { mockCurrentSession, mockExpireSession } from './auth'

// 화면 개발용 직원 관리 목(SCR-12). 실제 서버처럼 사장(ADMIN)만 쓸 수 있다 — 목 로그인 아이디 'owner'(박사장)으로 본다.
// 기본 목 세션(김직원, STAFF)으로 부르면 403 FORBIDDEN, 비밀번호를 바꿔야 하는 세션이면 403 PASSWORD_CHANGE_REQUIRED.
// 목록은 이 모듈의 메모리에만 둔다(새로고침하면 처음 상태).
//
// - 박사장  owner     사장(ADMIN) — [⋯] 없이 '본인'
// - 김직원  kim.staff 활성, 오늘 로그인
// - 최알바  choi.pt   활성, 며칠 전 로그인
// - 이신입  new.staff 활성, 로그인한 적 없음(마지막 로그인 '-')
// - 정퇴사  jung.old  비활성 — 회색 행, 메뉴 없음
// 아이디 'kim.staff'·'jung.old' 로 추가하면 409 DUPLICATE_LOGIN_ID(화면 미리 검사를 피하려면 대소문자를 바꿔 본다 — 목도 대소문자를 구분한다)
// 초기 비밀번호는 매번 무작위 4-4-4 형식의 가짜 값이다.

const MOCK_DELAY_MS = 350

type MockEmployee = EmployeeListItem & { seq: number }

function employeeId(n: number): string {
  return `00000000-0000-4000-9000-${String(n).padStart(12, '0')}`
}

function hoursAgo(hours: number): string {
  return new Date(Date.now() - hours * 60 * 60 * 1000).toISOString()
}

let nextSeq = 6

const employees: MockEmployee[] = [
  { seq: 1, employeeId: employeeId(2), name: '박사장', loginId: 'owner', role: 'ADMIN', active: true, lastLoginAt: hoursAgo(0.2) },
  { seq: 2, employeeId: employeeId(1), name: '김직원', loginId: 'kim.staff', role: 'STAFF', active: true, lastLoginAt: hoursAgo(0.5) },
  { seq: 3, employeeId: employeeId(4), name: '최알바', loginId: 'choi.pt', role: 'STAFF', active: true, lastLoginAt: hoursAgo(80) },
  { seq: 4, employeeId: employeeId(3), name: '이신입', loginId: 'new.staff', role: 'STAFF', active: true, lastLoginAt: null },
  { seq: 5, employeeId: employeeId(5), name: '정퇴사', loginId: 'jung.old', role: 'STAFF', active: false, lastLoginAt: hoursAgo(24 * 40) },
]

// 헷갈리는 글자(0 O 1 l I)를 뺀 글자 — 서버 InitialPasswordGenerator 와 같은 모양의 가짜 값
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'

function fakeInitialPassword(): string {
  const groups: string[] = []
  for (let g = 0; g < 3; g++) {
    let group = ''
    for (let i = 0; i < 4; i++) group += ALPHABET[Math.floor(Math.random() * ALPHABET.length)]
    groups.push(group)
  }
  return groups.join('-')
}

/** 서버 순서: 사장 먼저 → 활성 먼저 → 만든 순서 */
function sorted(): EmployeeListItem[] {
  return [...employees]
    .sort((a, b) => {
      if (a.role !== b.role) return a.role === 'ADMIN' ? -1 : 1
      if (a.active !== b.active) return a.active ? -1 : 1
      return a.seq - b.seq
    })
    .map(({ seq: _seq, ...item }) => item)
}

/** 사장만 — 로그인 안 됨 401, 비밀번호 변경 필요 403 PASSWORD_CHANGE_REQUIRED, 직원 403 FORBIDDEN */
function requireAdmin(): void {
  const session = mockCurrentSession()
  if (!session) {
    mockExpireSession()
    notifyUnauthorized()
    throw new ApiError(401, 'UNAUTHENTICATED', '로그인이 필요합니다.')
  }
  if (session.mustChangePassword) {
    notifyPasswordChangeRequired()
    throw new ApiError(403, 'PASSWORD_CHANGE_REQUIRED', '비밀번호를 변경한 뒤 이용해 주세요.')
  }
  if (session.role !== 'ADMIN') throw new ApiError(403, 'FORBIDDEN', '권한이 없습니다.')
}

function managedEmployee(id: string): MockEmployee {
  const target = employees.find((e) => e.employeeId === id)
  if (!target) throw new ApiError(404, 'EMPLOYEE_NOT_FOUND', '직원을 찾을 수 없습니다.')
  if (target.role === 'ADMIN') {
    throw new ApiError(409, 'ADMIN_ACCOUNT_PROTECTED', '사장님 계정은 비활성화하거나 비밀번호를 초기화할 수 없습니다.')
  }
  return target
}

function delayed<T>(produce: () => T, signal?: AbortSignal): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      try {
        resolve(produce())
      } catch (e: unknown) {
        reject(e)
      }
    }, MOCK_DELAY_MS)
    signal?.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(new DOMException('Aborted', 'AbortError'))
    })
  })
}

export function listMockEmployees(signal?: AbortSignal): Promise<EmployeeListResponse> {
  return delayed(() => {
    requireAdmin()
    return { items: sorted() }
  }, signal)
}

export function addMockEmployee({ name, loginId }: AddEmployeeRequest): Promise<AddEmployeeResponse> {
  return delayed(() => {
    requireAdmin()
    const fieldErrors = []
    const trimmed = name.trim()
    if (trimmed.length < 1 || trimmed.length > 50) fieldErrors.push({ field: 'name', message: '이름은 1–50자여야 합니다.' })
    if (!/^[A-Za-z0-9.]{4,20}$/.test(loginId)) {
      fieldErrors.push({ field: 'loginId', message: '아이디는 영문·숫자·마침표 4–20자여야 합니다.' })
    }
    if (fieldErrors.length > 0) throw new ApiError(400, 'INVALID_INPUT', '입력값을 확인해 주세요.', fieldErrors)
    if (employees.some((e) => e.loginId === loginId)) {
      throw new ApiError(409, 'DUPLICATE_LOGIN_ID', '이미 사용 중인 아이디입니다.')
    }
    const added: MockEmployee = {
      seq: nextSeq++,
      employeeId: employeeId(100 + nextSeq),
      name: trimmed,
      loginId,
      role: 'STAFF',
      active: true,
      lastLoginAt: null,
    }
    employees.push(added)
    return {
      employeeId: added.employeeId,
      name: added.name,
      loginId: added.loginId,
      role: added.role,
      initialPassword: fakeInitialPassword(),
    }
  })
}

export function resetMockEmployeePassword(id: string): Promise<ResetPasswordResponse> {
  return delayed(() => {
    requireAdmin()
    const target = managedEmployee(id)
    return { employeeId: target.employeeId, initialPassword: fakeInitialPassword() }
  })
}

export function deactivateMockEmployee(id: string): Promise<void> {
  return delayed(() => {
    requireAdmin()
    managedEmployee(id).active = false
  })
}
