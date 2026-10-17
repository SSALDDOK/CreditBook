import type { CancelRequest, CancelResponse } from '../cancels'
import { CANCEL_REASON_MAX_LENGTH } from '../cancels'
import { ApiError, notifyUnauthorized } from '../client'
import { mockExpireSession } from './auth'
import { recordMockCancel, type MockCancelKind } from './customers'
import {
  mockReversalFingerprint,
  mockWait,
  rememberMockIdempotency,
  replayMockIdempotency,
  shouldDropResponseOnce,
} from './idempotency'

// 화면 개발용 취소 목 — 사용 취소(REQ-34)·충전 취소(REQ-7). 요청 키 기록은 충전·사용 목과 같이 쓴다(mock/idempotency.ts).
// 같은 키 + 같은 요청(고객·유형·대상 거래·사유)이면 처음 결과를 다시 준다. 대상 거래 판단·잔액은 가짜 서버(recordMockCancel)가 한다.
//
// 실제 규칙으로 나는 거절:
// - 이준호(…002)의 10-15 충전 30,000원 취소 → 409 CHARGE_ALREADY_USED (현재 잔액 0원 · 충전 30,000원)
// - 같은 건을 두 번 취소(다른 탭 등) → 409 ALREADY_CANCELLED
// - 사유가 비었거나 200자를 넘으면 → 400 INVALID_INPUT (fieldErrors.reason)
//
// 오류 흐름을 개발 서버에서 볼 수 있게, 취소 사유를 아래 낱말 "그대로" 넣으면 정해진 응답을 준다(충전·사용 목의 특수 금액과 같은 역할).
// - 응답끊김 : 처음 한 번은 서버에서 처리됐는데 응답만 끊긴 것처럼 네트워크 오류. 같은 창에서 [취소하기]를 다시 누르면
//             같은 키라서 취소 거래가 하나만 생긴다(재전송 응답)
// - 먼저취소 : 다른 기기에서 먼저 취소된 경우 — '박사장' 이름의 취소를 먼저 기록한 뒤 409 ALREADY_CANCELLED
// - 세션만료 : 401 — 로그인 화면으로 간다(사유 복구는 하지 않는다)
// - 동시수정 : 409 CONCURRENT_MODIFICATION
// - 당일아님 : (충전 취소) 409 CHARGE_CANCEL_NOT_SAME_DAY
// - 대상오류 : 400 INVALID_CANCEL_TARGET
// - 사유오류 : 400 INVALID_INPUT (fieldErrors.reason)
// - 키재사용 : 422 IDEMPOTENCY_KEY_REUSED
// - 서버오류 : 500 INTERNAL_ERROR

const DROP_RESPONSE = '응답끊김'
const CANCELLED_ELSEWHERE = '먼저취소'

export function cancelMockUse(
  customerId: string,
  useEntryId: string,
  request: CancelRequest,
  idempotencyKey: string,
): Promise<CancelResponse> {
  return cancelMock('use', customerId, useEntryId, request, idempotencyKey)
}

export function cancelMockCharge(
  customerId: string,
  chargeEntryId: string,
  request: CancelRequest,
  idempotencyKey: string,
): Promise<CancelResponse> {
  return cancelMock('charge', customerId, chargeEntryId, request, idempotencyKey)
}

async function cancelMock(
  kind: MockCancelKind,
  customerId: string,
  targetEntryId: string,
  { reason }: CancelRequest,
  idempotencyKey: string,
): Promise<CancelResponse> {
  await mockWait()
  const type = kind === 'use' ? 'USE_CANCEL' : 'CHARGE_CANCEL'
  const trimmed = (reason ?? '').trim()

  if (!trimmed) {
    throw new ApiError(400, 'INVALID_INPUT', '입력값을 확인해 주세요.', [
      { field: 'reason', message: '취소 사유를 입력해 주세요.' },
    ])
  }
  if (trimmed.length > CANCEL_REASON_MAX_LENGTH) {
    throw new ApiError(400, 'INVALID_INPUT', '입력값을 확인해 주세요.', [
      { field: 'reason', message: `취소 사유는 ${CANCEL_REASON_MAX_LENGTH}자 이하여야 합니다.` },
    ])
  }

  const fingerprint = mockReversalFingerprint(customerId, type, targetEntryId, trimmed)
  const replayed = replayMockIdempotency<CancelResponse>(idempotencyKey, fingerprint)
  if (replayed) return replayed

  switch (trimmed) {
    case '세션만료':
      mockExpireSession()
      notifyUnauthorized()
      throw new ApiError(401, 'UNAUTHENTICATED', '로그인이 필요합니다.')
    case '동시수정':
      throw new ApiError(409, 'CONCURRENT_MODIFICATION', '다른 요청이 먼저 처리되었습니다. 다시 시도해 주세요.')
    case '당일아님':
      if (kind === 'charge') {
        throw new ApiError(409, 'CHARGE_CANCEL_NOT_SAME_DAY', '당일 충전 건만 취소할 수 있습니다.')
      }
      break
    case '대상오류':
      throw new ApiError(400, 'INVALID_CANCEL_TARGET', '취소 거래는 다시 취소할 수 없습니다.')
    case '사유오류':
      throw new ApiError(400, 'INVALID_INPUT', '입력값을 확인해 주세요.', [
        { field: 'reason', message: `취소 사유는 ${CANCEL_REASON_MAX_LENGTH}자 이하여야 합니다.` },
      ])
    case '키재사용':
      throw new ApiError(422, 'IDEMPOTENCY_KEY_REUSED', '같은 요청 키로 다른 거래를 요청했습니다. 새로 시도해 주세요.')
    case '서버오류':
      throw new ApiError(500, 'INTERNAL_ERROR', '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.')
    case CANCELLED_ELSEWHERE:
      // 다른 기기의 취소를 먼저 기록한다(가짜 서버 쪽 장부). 그 뒤 아래 recordMockCancel 이 ALREADY_CANCELLED 로 거절한다
      try {
        recordMockCancel(customerId, targetEntryId, kind, '다른 기기에서 취소', '박사장')
      } catch {
        // 이미 취소됐거나 취소할 수 없는 건이면 아래에서 같은 오류가 난다
      }
      break
  }

  const entry = recordMockCancel(customerId, targetEntryId, kind, trimmed)
  const response: CancelResponse = {
    id: entry.id,
    customerId,
    type,
    amount: entry.amount,
    balanceAfter: entry.balanceAfter,
    reason: trimmed,
    reversesId: targetEntryId,
    performedAt: entry.performedAt,
  }
  rememberMockIdempotency(idempotencyKey, fingerprint, response)

  if (trimmed === DROP_RESPONSE && shouldDropResponseOnce(idempotencyKey)) {
    // fetch 가 응답을 받지 못했을 때와 같은 오류
    throw new TypeError('Failed to fetch')
  }
  return { ...response }
}
