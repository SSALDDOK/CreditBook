import { ApiError } from '../client'

// 충전·사용 목이 같이 쓰는 요청 키(Idempotency-Key, REQ-10) 기록 — 실제 서버처럼 키 하나에 거래 하나다.
// 같은 키 + 같은 요청(고객·유형·금액·메모)이면 처음 응답을 다시 주고, 같은 키 + 다른 요청이면 422 를 던진다.
// 충전과 사용이 한 저장소를 쓰므로, 충전에 쓴 키로 사용을 보내도 422 다(서버와 같다).

const KEY_PATTERN = /^[A-Za-z0-9-]{1,64}$/

type Stored = { fingerprint: string; response: unknown }
const processed = new Map<string, Stored>()

/** 요청 지문 — 고객·유형·금액·메모 */
export function mockFingerprint(customerId: string, type: string, amount: number, memo: string | undefined): string {
  return JSON.stringify([customerId, type, amount, memo ?? null])
}

/** 키 형식을 보고, 이미 처리한 키면 저장된 응답(복사본)을 돌려준다. 처음 보는 키면 null */
export function replayMockIdempotency<T extends object>(idempotencyKey: string, fingerprint: string): T | null {
  if (!KEY_PATTERN.test(idempotencyKey)) {
    throw new ApiError(400, 'INVALID_IDEMPOTENCY_KEY', '요청 키(Idempotency-Key)를 확인해 주세요.')
  }
  const stored = processed.get(idempotencyKey)
  if (!stored) return null
  if (stored.fingerprint !== fingerprint) {
    throw new ApiError(422, 'IDEMPOTENCY_KEY_REUSED', '같은 요청 키로 다른 거래를 요청했습니다. 새로 시도해 주세요.')
  }
  return { ...(stored.response as T) }
}

/** 처리한 요청의 응답을 키와 함께 기억한다 */
export function rememberMockIdempotency(idempotencyKey: string, fingerprint: string, response: object): void {
  processed.set(idempotencyKey, { fingerprint, response: { ...response } })
}

// 응답 끊김 시나리오(7,777원)를 키마다 한 번만 내기 위한 기록
const droppedOnce = new Set<string>()

/** 이 키로 아직 응답을 끊은 적이 없으면 기록하고 true — 서버에서는 처리됐는데 응답만 못 받은 상황을 흉내 낸다 */
export function shouldDropResponseOnce(idempotencyKey: string): boolean {
  if (droppedOnce.has(idempotencyKey)) return false
  droppedOnce.add(idempotencyKey)
  return true
}

const MOCK_DELAY_MS = 500

export function mockWait(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, MOCK_DELAY_MS))
}
