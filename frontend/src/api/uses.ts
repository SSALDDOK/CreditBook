import { apiRequest } from './client'
import { USE_MOCK } from './config'
import { createMockUse } from './mock/uses'

// 백엔드 UsageRequest·UsageResponse 와 필드 이름을 맞춘다.

export type UsageRequest = {
  /** 사용 금액 — 원 단위 양의 정수 */
  amount: number
  /** 주문 메모(선택). 비어 있으면 보내지 않는다 */
  memo?: string
}

export type UsageResponse = {
  id: string
  customerId: string
  type: 'USE'
  amount: number
  /** 사용 후 잔액 — 서버가 계산한 값. 화면은 이 값 또는 상세 재조회 값만 쓴다 */
  balanceAfter: number
  memo: string | null
  /** 처리 시각 (ISO-8601 Instant) */
  performedAt: string
}

/** 400 INSUFFICIENT_BALANCE 의 details — 서버(ErrorResponse.InsufficientBalanceDetails)가 계산한 값 그대로 */
export type InsufficientBalanceDetails = {
  /** 요청 시점의 현재 잔액 */
  balance: number
  /** 부족액 */
  shortage: number
}

/** ApiError.details 를 잔액 부족 정보로 읽는다. 모양이 다르면 null(호출하는 쪽이 서버 message 로 안내한다) */
export function readInsufficientBalanceDetails(details: unknown): InsufficientBalanceDetails | null {
  if (typeof details !== 'object' || details === null) return null
  const { balance, shortage } = details as Record<string, unknown>
  if (typeof balance !== 'number' || typeof shortage !== 'number') return null
  if (!Number.isSafeInteger(balance) || !Number.isSafeInteger(shortage)) return null
  return { balance, shortage }
}

/**
 * POST /api/customers/{customerId}/uses — 사용 (REQ-8·9).
 * idempotencyKey(REQ-10)는 호출하는 쪽이 정한다: 확인 화면에 들어갈 때 만들고, 그 화면에서 다시 보낼 때는 같은 키를 쓴다.
 * 자동 재시도는 하지 않는다 — 다시 보내는 것은 직원이 [확인]을 다시 누를 때뿐이다.
 */
export function createUse(customerId: string, request: UsageRequest, idempotencyKey: string): Promise<UsageResponse> {
  if (USE_MOCK) return createMockUse(customerId, request, idempotencyKey)
  return apiRequest<UsageResponse>(`/api/customers/${encodeURIComponent(customerId)}/uses`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(request),
  })
}
