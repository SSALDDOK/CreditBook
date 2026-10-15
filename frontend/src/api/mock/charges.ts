import { ApiError, notifyUnauthorized } from '../client'
import type { ChargeRequest, ChargeResponse } from '../charges'
import { mockExpireSession } from './auth'
import { recordMockCharge } from './customers'
import { MOCK_LEDGER_INPUT_SETTINGS } from './settings'

// 화면 개발용 충전 목. 실제 서버처럼 요청 키(Idempotency-Key)를 기억해, 같은 키 + 같은 요청이면 처음 결과를 다시 준다.
//
// 오류 흐름을 개발 서버에서 볼 수 있게 아래 금액은 정해진 응답을 준다(그 밖의 금액은 성공).
// - 7,777원   : 처음 한 번은 서버에서 처리됐는데 응답만 끊긴 것처럼 네트워크 오류를 낸다.
//               같은 확인 화면에서 [확인]을 다시 누르면 같은 키라서 거래가 하나만 생긴다(재전송 응답).
// - 4,010원   : 세션 만료(401) — 로그인 화면으로 간다. 다시 로그인하면 입력하던 금액이 복구된다(REQ-17)
// - 4,090원   : 409 CONCURRENT_MODIFICATION
// - 4,220원   : 422 IDEMPOTENCY_KEY_REUSED
// - 5,050원   : 500 INTERNAL_ERROR
// - 한도 초과 : 400 CHARGE_LIMIT_EXCEEDED (화면이 먼저 막으므로 보통은 오지 않는다)

const MOCK_DELAY_MS = 500
const KEY_PATTERN = /^[A-Za-z0-9-]{1,64}$/

type Stored = { fingerprint: string; response: ChargeResponse }
const processed = new Map<string, Stored>()
// 응답 끊김 시나리오를 키마다 한 번만 내기 위한 기록
const droppedOnce = new Set<string>()

function wait(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, MOCK_DELAY_MS))
}

export async function createMockCharge(
  customerId: string,
  { amount, memo }: ChargeRequest,
  idempotencyKey: string,
): Promise<ChargeResponse> {
  await wait()
  if (!KEY_PATTERN.test(idempotencyKey)) {
    throw new ApiError(400, 'INVALID_IDEMPOTENCY_KEY', '요청 키(Idempotency-Key)를 확인해 주세요.')
  }
  const fingerprint = JSON.stringify([customerId, 'CHARGE', amount, memo ?? null])
  const stored = processed.get(idempotencyKey)
  if (stored) {
    if (stored.fingerprint !== fingerprint) {
      throw new ApiError(422, 'IDEMPOTENCY_KEY_REUSED', '같은 요청 키로 다른 거래를 요청했습니다. 새로 시도해 주세요.')
    }
    return { ...stored.response }
  }

  switch (amount) {
    case 4010:
      mockExpireSession()
      notifyUnauthorized()
      throw new ApiError(401, 'UNAUTHENTICATED', '로그인이 필요합니다.')
    case 4090:
      throw new ApiError(409, 'CONCURRENT_MODIFICATION', '다른 요청이 먼저 처리되었습니다. 다시 시도해 주세요.')
    case 4220:
      throw new ApiError(422, 'IDEMPOTENCY_KEY_REUSED', '같은 요청 키로 다른 거래를 요청했습니다. 새로 시도해 주세요.')
    case 5050:
      throw new ApiError(500, 'INTERNAL_ERROR', '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.')
  }
  if (!Number.isSafeInteger(amount) || amount < 1) {
    throw new ApiError(400, 'INVALID_AMOUNT', '금액은 1원 이상이어야 합니다.')
  }
  if (amount > MOCK_LEDGER_INPUT_SETTINGS.chargeMaxAmount) {
    throw new ApiError(
      400,
      'CHARGE_LIMIT_EXCEEDED',
      `1회 충전 한도(${MOCK_LEDGER_INPUT_SETTINGS.chargeMaxAmount}원)를 넘었습니다.`,
    )
  }

  const { entryId, balanceAfter, performedAt } = recordMockCharge(customerId, amount, memo ?? null)
  const response: ChargeResponse = {
    id: entryId,
    customerId,
    type: 'CHARGE',
    amount,
    balanceAfter,
    memo: memo ?? null,
    performedAt,
  }
  processed.set(idempotencyKey, { fingerprint, response })

  if (amount === 7777 && !droppedOnce.has(idempotencyKey)) {
    droppedOnce.add(idempotencyKey)
    // fetch 가 응답을 받지 못했을 때와 같은 오류
    throw new TypeError('Failed to fetch')
  }
  return { ...response }
}
