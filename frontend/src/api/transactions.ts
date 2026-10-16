import { apiRequest } from './client'
import { USE_MOCK } from './config'
import type { LedgerEntryType } from './customers'
import { getMockTransactions } from './mock/transactions'

// 백엔드 거래 이력 응답(GET /api/customers/{customerId}/transactions)과 필드 이름을 맞춘다.

export type TransactionResponse = {
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
  /** 반제 행(충전 취소·사용 취소)이면 되돌린 원거래 id, 그 밖은 null */
  reversesId: string | null
  /**
   * 지금 취소할 수 있는 거래면 true — 서버가 판단한 값(충전 당일 여부·이미 취소됨·반제 행 여부 등).
   * 화면은 날짜를 계산해 취소 가능 여부를 정하지 않고 이 값만 본다
   */
  cancellable: boolean
}

export type TransactionListResponse = {
  /** 최신순(seq 내림차순) */
  content: TransactionResponse[]
  /** 0부터 시작하는 페이지 번호 */
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

export type TransactionListParams = {
  page: number
  size: number
}

/**
 * GET /api/customers/{customerId}/transactions?page=&size= — 고객 한 명의 거래 이력(최신순).
 * 404 CUSTOMER_NOT_FOUND, 400 INVALID_INPUT, 401 UNAUTHENTICATED
 */
export function getTransactions(
  customerId: string,
  { page, size }: TransactionListParams,
  signal?: AbortSignal,
): Promise<TransactionListResponse> {
  if (USE_MOCK) {
    return getMockTransactions(customerId, { page, size }, signal)
  }
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  return apiRequest<TransactionListResponse>(
    `/api/customers/${encodeURIComponent(customerId)}/transactions?${params.toString()}`,
    { signal },
  )
}
