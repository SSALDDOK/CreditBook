import { useCallback, useEffect, useState } from 'react'
import { getLedgerInputSettings, peekLedgerInputSettings, type LedgerInputSettingsResponse } from '@/api/settings'

export type LedgerInputSettingsState =
  | { status: 'loading' }
  | { status: 'success'; data: LedgerInputSettingsResponse }
  | { status: 'error'; error: unknown }

type Settled = { attempt: number; ok: true; data: LedgerInputSettingsResponse } | { attempt: number; ok: false; error: unknown }

/**
 * 충전·사용 입력 설정(한도·메모 칩·메모 길이). 앱에서 한 번 받은 값은 캐시를 쓰므로 두 번째 모달부터는 바로 그린다.
 * 실패하면 retry 로 다시 요청한다(조회라 다시 보내도 안전하다. 자동 재시도는 하지 않는다).
 */
export function useLedgerInputSettings(): { state: LedgerInputSettingsState; retry: () => void } {
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState<Settled | null>(() => {
    const cached = peekLedgerInputSettings()
    return cached ? { attempt: 0, ok: true, data: cached } : null
  })

  useEffect(() => {
    // 처음 그릴 때 캐시가 있었으면 이미 결과로 쓰고 있다 — 다시 묻지 않는다
    if (attempt === 0 && peekLedgerInputSettings()) return
    let alive = true
    getLedgerInputSettings()
      .then((data) => {
        if (alive) setResult({ attempt, ok: true, data })
      })
      .catch((error: unknown) => {
        if (alive) setResult({ attempt, ok: false, error })
      })
    return () => {
      alive = false
    }
  }, [attempt])

  const retry = useCallback(() => setAttempt((n) => n + 1), [])

  if (result?.attempt === attempt) {
    return { state: result.ok ? { status: 'success', data: result.data } : { status: 'error', error: result.error }, retry }
  }
  return { state: { status: 'loading' }, retry }
}
