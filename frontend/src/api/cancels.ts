import { apiRequest } from './client'
import { USE_MOCK } from './config'
import { cancelMockCharge, cancelMockUse } from './mock/cancels'

// 백엔드 UseCancelRequest·UseCancelResponse·ChargeCancelRequest·ChargeCancelResponse 와 필드 이름을 맞춘다.

/** 취소 사유 최대 길이 — 사유는 거래 memo 칸에 저장되므로 서버 LedgerEntry.MEMO_MAX_LENGTH 와 같다 */
export const CANCEL_REASON_MAX_LENGTH = 200

export type CancelRequest = {
  /** 취소 사유(필수). 앞뒤 공백을 뺀 값을 보낸다 */
  reason: string
}

export type CancelResponse = {
  /** 만들어진 반제(취소) 거래 id */
  id: string
  customerId: string
  type: 'USE_CANCEL' | 'CHARGE_CANCEL'
  /** 되돌린 금액(원 거래 금액과 같다, 양수) */
  amount: number
  /** 취소 후 잔액 — 서버가 계산한 값. 화면은 이 값 또는 재조회 값만 쓴다 */
  balanceAfter: number
  reason: string
  /** 취소한 원 거래 id */
  reversesId: string
  /** 처리 시각 (ISO-8601 Instant) */
  performedAt: string
}

/** 409 CHARGE_ALREADY_USED 의 details — 서버(ErrorResponse.ChargeAlreadyUsedDetails) 값 그대로 */
export type ChargeAlreadyUsedDetails = {
  /** 요청 시점의 현재 잔액 */
  balance: number
  /** 취소하려던 충전 금액 */
  chargeAmount: number
}

/** ApiError.details 를 CHARGE_ALREADY_USED 정보로 읽는다. 모양이 다르면 null(서버 message 만 보여 준다) */
export function readChargeAlreadyUsedDetails(details: unknown): ChargeAlreadyUsedDetails | null {
  if (typeof details !== 'object' || details === null) return null
  const { balance, chargeAmount } = details as Record<string, unknown>
  if (typeof balance !== 'number' || typeof chargeAmount !== 'number') return null
  if (!Number.isSafeInteger(balance) || !Number.isSafeInteger(chargeAmount)) return null
  return { balance, chargeAmount }
}

/**
 * POST /api/customers/{customerId}/uses/{useEntryId}/cancel — 사용 취소 (REQ-34).
 * idempotencyKey(REQ-10)는 취소 창을 열 때 만들고, 같은 창에서 다시 보낼 때는 같은 키를 쓴다.
 * 자동 재시도는 하지 않는다 — 다시 보내는 것은 직원이 [취소하기]를 다시 누를 때뿐이다.
 */
export function cancelUse(
  customerId: string,
  useEntryId: string,
  request: CancelRequest,
  idempotencyKey: string,
): Promise<CancelResponse> {
  if (USE_MOCK) return cancelMockUse(customerId, useEntryId, request, idempotencyKey)
  return apiRequest<CancelResponse>(
    `/api/customers/${encodeURIComponent(customerId)}/uses/${encodeURIComponent(useEntryId)}/cancel`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(request),
    },
  )
}

/**
 * POST /api/customers/{customerId}/charges/{chargeEntryId}/cancel — 충전 취소 (REQ-7).
 * 요청 키 규칙은 사용 취소와 같다.
 */
export function cancelCharge(
  customerId: string,
  chargeEntryId: string,
  request: CancelRequest,
  idempotencyKey: string,
): Promise<CancelResponse> {
  if (USE_MOCK) return cancelMockCharge(customerId, chargeEntryId, request, idempotencyKey)
  return apiRequest<CancelResponse>(
    `/api/customers/${encodeURIComponent(customerId)}/charges/${encodeURIComponent(chargeEntryId)}/cancel`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(request),
    },
  )
}
