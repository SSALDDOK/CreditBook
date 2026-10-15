import { apiRequest } from './client'
import { USE_MOCK } from './config'
import { getMockCustomer, searchMockCustomers } from './mock/customers'

// 백엔드 CustomerListItemResponse·CustomerListResponse·CustomerDetailResponse 와 필드 이름을 맞춘다.

export type CustomerResponse = {
  id: string
  name: string
  /** 서버가 가운데 자리를 가린 연락처 (예: 010-****-5678). 화면은 그대로 표시만 한다 */
  maskedPhone: string
  /** 선결제 잔액 — 원 단위 정수. 표시만 하고 계산하지 않는다 */
  balance: number
  /** 등록 시각 (ISO-8601 Instant) */
  createdAt: string
  /** 활성 고객이면 true. 비활성 포함(includeInactive=true) 목록에서 비활성 고객을 구분한다 */
  active: boolean
}

export type CustomerListResponse = {
  content: CustomerResponse[]
  /** 0부터 시작하는 페이지 번호 */
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

export type CustomerSearchParams = {
  /** 이름 일부 또는 연락처 뒷자리. 비우면 전체 */
  q?: string
  page?: number
  size?: number
  /** true 면 비활성 고객도 목록·검색·건수에 포함한다(보기 필터일 뿐 — 고객 상태를 바꾸지 않는다) */
  includeInactive?: boolean
}

// 기준 해상도 1024×768(화면설계서 §0.1)에서 스크롤 없이 한 화면에 들어가는 행 수 — 더 보려면 이전·다음으로 넘긴다
export const CUSTOMER_PAGE_SIZE = 10

/** GET /api/customers?q=&page=&size=&includeInactive= — 고객 목록·검색 (최근 등록순, 기본은 활성 고객만) */
export function searchCustomers(
  { q, page = 0, size = CUSTOMER_PAGE_SIZE, includeInactive = false }: CustomerSearchParams,
  signal?: AbortSignal,
): Promise<CustomerListResponse> {
  if (USE_MOCK) {
    return searchMockCustomers({ q, page, size, includeInactive }, signal)
  }
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  const trimmed = q?.trim()
  if (trimmed) params.set('q', trimmed)
  if (includeInactive) params.set('includeInactive', 'true')
  return apiRequest<CustomerListResponse>(`/api/customers?${params.toString()}`, { signal })
}

/** 거래 유형 — 백엔드 LedgerEntryType 과 같은 이름 */
export type LedgerEntryType = 'CHARGE' | 'USE' | 'CHARGE_CANCEL' | 'USE_CANCEL'

export type RecentLedgerEntryResponse = {
  id: string
  type: LedgerEntryType
  /** 거래 금액(항상 양수, 원 단위 정수) */
  amount: number
  /** 잔액 변동 방향이 붙은 금액(서버 생성 컬럼 signed_amount). 부호·색은 이 값 그대로 쓴다 */
  signedAmount: number
  /** 이 거래 뒤 잔액 — 서버 값 그대로 표시한다 */
  balanceAfter: number
  memo: string | null
  /** 처리 시각 (ISO-8601 Instant) */
  performedAt: string
  performedByName: string
  /** 반제(취소)된 원거래면 true */
  cancelled: boolean
}

export type CustomerDetailResponse = {
  id: string
  name: string
  maskedPhone: string
  /** 현재 잔액 — 서버 값. 충전·사용 뒤에도 화면에서 더하거나 빼지 않고 다시 조회한다 */
  balance: number
  active: boolean
  createdAt: string
  /** 최근 거래 최대 5건, 최신순(seq 내림차순). 없으면 [] */
  recentEntries: RecentLedgerEntryResponse[]
}

/** GET /api/customers/{id} — 고객 상세(잔액·최근 거래). 404 CUSTOMER_NOT_FOUND, 400 INVALID_INPUT(id 형식) */
export function getCustomer(customerId: string, signal?: AbortSignal): Promise<CustomerDetailResponse> {
  if (USE_MOCK) {
    return getMockCustomer(customerId, signal)
  }
  return apiRequest<CustomerDetailResponse>(`/api/customers/${encodeURIComponent(customerId)}`, { signal })
}
