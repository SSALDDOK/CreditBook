import { ApiError, notifyUnauthorized } from '../client'
import type { UsageRequest, UsageResponse } from '../uses'
import { mockExpireSession } from './auth'
import { mockBalanceOf, recordMockUse } from './customers'
import {
  mockFingerprint,
  mockWait,
  rememberMockIdempotency,
  replayMockIdempotency,
  shouldDropResponseOnce,
} from './idempotency'

// 화면 개발용 사용 목. 충전 목과 같은 요청 키 기록(mock/idempotency.ts)을 써서, 같은 키 + 같은 요청이면 처음 결과를 다시 준다.
// 잔액보다 큰 금액은 실제 서버처럼 400 INSUFFICIENT_BALANCE(details.balance·shortage)를 준다(화면이 먼저 막으므로 보통은 오지 않는다).
// 비활성 고객은 409 CUSTOMER_INACTIVE.
//
// 오류 흐름을 개발 서버에서 볼 수 있게 아래 금액은 정해진 응답을 준다(그 밖의 금액은 잔액 안이면 성공).
// - 4,000원 : 다른 기기에서 먼저 사용된 경우 — 서버 잔액이 3,000원이 되도록 '박사장' 이름의 사용을 먼저 기록한 뒤
//             400 INSUFFICIENT_BALANCE(balance 3,000, shortage 1,000). 현재 잔액이 3,000원 이하면 그냥 잔액 부족이다
// - 7,777원 : 처음 한 번은 서버에서 처리됐는데 응답만 끊긴 것처럼 네트워크 오류. 같은 확인 화면에서 [확인]을 다시 누르면
//             같은 키라서 거래가 하나만 생긴다(재전송 응답)
// - 4,010원 : 세션 만료(401) — 로그인 화면으로 간다. 다시 로그인하면 입력하던 금액이 복구된다(REQ-17)
// - 4,090원 : 409 CONCURRENT_MODIFICATION
// - 4,093원 : 409 CUSTOMER_INACTIVE (다른 기기에서 비활성화된 경우의 응답만 흉내 낸다 — 목의 고객 상태는 바꾸지 않는다)
// - 4,220원 : 422 IDEMPOTENCY_KEY_REUSED
// - 5,050원 : 500 INTERNAL_ERROR
//
// 잔액 부족 화면(TC-14)은 잔액 3,000원 고객(박채원, …0008)에 5,000원을 넣으면 볼 수 있다.

const OTHER_DEVICE_BALANCE = 3000

export async function createMockUse(
  customerId: string,
  { amount, memo }: UsageRequest,
  idempotencyKey: string,
): Promise<UsageResponse> {
  await mockWait()
  const fingerprint = mockFingerprint(customerId, 'USE', amount, memo)
  const replayed = replayMockIdempotency<UsageResponse>(idempotencyKey, fingerprint)
  if (replayed) return replayed

  switch (amount) {
    case 4010:
      mockExpireSession()
      notifyUnauthorized()
      throw new ApiError(401, 'UNAUTHENTICATED', '로그인이 필요합니다.')
    case 4090:
      throw new ApiError(409, 'CONCURRENT_MODIFICATION', '다른 요청이 먼저 처리되었습니다. 다시 시도해 주세요.')
    case 4093:
      throw new ApiError(409, 'CUSTOMER_INACTIVE', '비활성 고객입니다. 충전하면 다시 활성화됩니다.')
    case 4220:
      throw new ApiError(422, 'IDEMPOTENCY_KEY_REUSED', '같은 요청 키로 다른 거래를 요청했습니다. 새로 시도해 주세요.')
    case 5050:
      throw new ApiError(500, 'INTERNAL_ERROR', '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.')
    case 4000: {
      // 다른 기기의 사용을 먼저 기록한다(가짜 서버 쪽 장부). 그 뒤 아래 recordMockUse 가 잔액 부족으로 거절한다
      const balance = mockBalanceOf(customerId)
      if (balance !== null && balance > OTHER_DEVICE_BALANCE) {
        recordMockUse(customerId, balance - OTHER_DEVICE_BALANCE, '다른 기기에서 사용', '박사장')
      }
      break
    }
  }
  if (!Number.isSafeInteger(amount) || amount < 1) {
    throw new ApiError(400, 'INVALID_AMOUNT', '금액은 1원 이상이어야 합니다.')
  }

  // 잔액 부족·비활성 고객 판단은 가짜 서버(recordMockUse)가 한다
  const { entryId, balanceAfter, performedAt } = recordMockUse(customerId, amount, memo ?? null)
  const response: UsageResponse = {
    id: entryId,
    customerId,
    type: 'USE',
    amount,
    balanceAfter,
    memo: memo ?? null,
    performedAt,
  }
  rememberMockIdempotency(idempotencyKey, fingerprint, response)

  if (amount === 7777 && shouldDropResponseOnce(idempotencyKey)) {
    // fetch 가 응답을 받지 못했을 때와 같은 오류
    throw new TypeError('Failed to fetch')
  }
  return { ...response }
}
