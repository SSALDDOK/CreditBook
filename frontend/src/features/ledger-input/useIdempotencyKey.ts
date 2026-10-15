import { useCallback, useMemo, useRef } from 'react'

// 요청 키(Idempotency-Key, REQ-10) 관리 — 충전·사용 모달이 같이 쓴다.
// 규칙(사용자 승인): 확인 화면에 "들어갈 때" 새 키를 만들고, 그 화면에서 [확인]을 다시 누를 때(네트워크 오류 뒤 등)는
// 같은 키를 쓴다. [이전]으로 나가면 키를 버린다. 그래서 응답만 끊긴 요청을 다시 보내도 거래는 하나만 생긴다.

/** 서버 형식 ^[A-Za-z0-9-]{1,64}$ 에 맞는 UUID. randomUUID 가 없는 환경(안전하지 않은 출처)에서는 getRandomValues 로 만든다 */
export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export type IdempotencyKeyControl = {
  /** 확인 화면에 들어갈 때 — 새 키를 만든다 */
  issue: () => void
  /** 확인 화면을 떠날 때([이전]) — 키를 버린다 */
  discard: () => void
  /** 보낼 때 쓸 키. issue 전이면 새로 만든다 */
  current: () => string
}

export function useIdempotencyKey(): IdempotencyKeyControl {
  const keyRef = useRef<string | null>(null)
  const issue = useCallback(() => {
    keyRef.current = newIdempotencyKey()
  }, [])
  const discard = useCallback(() => {
    keyRef.current = null
  }, [])
  const current = useCallback(() => {
    keyRef.current ??= newIdempotencyKey()
    return keyRef.current
  }, [])
  return useMemo(() => ({ issue, discard, current }), [issue, discard, current])
}
