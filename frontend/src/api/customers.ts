import { apiRequest } from './client'
import { USE_MOCK } from './config'
import { searchMockCustomers } from './mock/customers'

// 백엔드 CustomerResponse·CustomerListResponse 와 필드 이름을 맞춘다.

export type CustomerResponse = {
  id: string
  name: string
  /** 서버가 가운데 자리를 가린 연락처 (예: 010-****-5678). 화면은 그대로 표시만 한다 */
  maskedPhone: string
  /** 선결제 잔액 — 원 단위 정수. 표시만 하고 계산하지 않는다 */
  balance: number
  /** 등록 시각 (ISO-8601 Instant) */
  createdAt: string
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
}

export const CUSTOMER_PAGE_SIZE = 20

/** GET /api/customers?q=&page=&size= — 활성 고객 목록·검색 (최근 등록순) */
export function searchCustomers(
  { q, page = 0, size = CUSTOMER_PAGE_SIZE }: CustomerSearchParams,
  signal?: AbortSignal,
): Promise<CustomerListResponse> {
  if (USE_MOCK) {
    return searchMockCustomers({ q, page, size }, signal)
  }
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  const trimmed = q?.trim()
  if (trimmed) params.set('q', trimmed)
  return apiRequest<CustomerListResponse>(`/api/customers?${params.toString()}`, { signal })
}
