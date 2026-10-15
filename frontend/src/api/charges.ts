import { apiRequest } from './client'
import { USE_MOCK } from './config'
import { createMockCharge } from './mock/charges'

// 백엔드 ChargeRequest·ChargeResponse 와 필드 이름을 맞춘다.

export type ChargeRequest = {
  /** 충전 금액 — 원 단위 양의 정수 */
  amount: number
  /** 메모(선택). 비어 있으면 보내지 않는다 */
  memo?: string
}

export type ChargeResponse = {
  id: string
  customerId: string
  type: 'CHARGE'
  amount: number
  /** 충전 후 잔액 — 서버가 계산한 값. 화면은 이 값 또는 상세 재조회 값만 쓴다 */
  balanceAfter: number
  memo: string | null
  /** 처리 시각 (ISO-8601 Instant) */
  performedAt: string
}

/**
 * POST /api/customers/{customerId}/charges — 충전 (REQ-5).
 * idempotencyKey(REQ-10)는 호출하는 쪽이 정한다: 확인 화면에 들어갈 때 만들고, 그 화면에서 다시 보낼 때는 같은 키를 쓴다.
 * 자동 재시도는 하지 않는다 — 다시 보내는 것은 직원이 [확인]을 다시 누를 때뿐이다.
 */
export function createCharge(
  customerId: string,
  request: ChargeRequest,
  idempotencyKey: string,
): Promise<ChargeResponse> {
  if (USE_MOCK) return createMockCharge(customerId, request, idempotencyKey)
  return apiRequest<ChargeResponse>(`/api/customers/${encodeURIComponent(customerId)}/charges`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(request),
  })
}
