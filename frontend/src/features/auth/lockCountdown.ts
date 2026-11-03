import { useEffect, useState } from 'react'

// 423 ACCOUNT_LOCKED 남은 시간 계산 — 로그인(SCR-1)과 비밀번호 변경(SCR-13)이 같이 쓴다.
// 남은 시간은 details.retryAfterSeconds(서버가 올림한 초)로 센다. 금액과 무관한 시각 계산이다.

/** 잠금 구간 — until 은 잠금이 풀리는 시각(ms), startedAt 은 423 응답을 받은 시각(ms) */
export type LockWindow = { until: number; startedAt: number }

/** details.retryAfterSeconds 가 양의 유한한 숫자일 때만 돌려준다(아니면 남은 시간을 표시하지 않는다). */
export function retryAfterSeconds(details: unknown): number | null {
  if (typeof details !== 'object' || details === null) return null
  const value = (details as { retryAfterSeconds?: unknown }).retryAfterSeconds
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null
}

/** 423 응답의 details 로 잠금 구간을 만든다. 남은 시간을 알 수 없으면 null */
export function toLockWindow(details: unknown, receivedAt: number): LockWindow | null {
  const seconds = retryAfterSeconds(details)
  return seconds === null ? null : { until: receivedAt + seconds * 1000, startedAt: receivedAt }
}

/** 남은 초를 '남은 시간 N분 N초'로. 1분 미만이어도 '0분 45초'로 쓴다(화면 문구 표 형식). */
export function formatRemaining(totalSeconds: number): string {
  return `남은 시간 ${Math.floor(totalSeconds / 60)}분 ${totalSeconds % 60}초`
}

/**
 * 잠금이 풀릴 때까지 남은 초. 잠금이 없으면 null, 풀렸으면 0.
 * 1초마다 Date.now() 로 기준 시각을 다시 잡는다 — 누적 감소(초마다 -1)는 탭이 백그라운드일 때 타이머가 늦게 돌아 실제 시각과 어긋난다.
 * 응답을 받은 시각(startedAt)보다 이른 기준 시각은 쓰지 않으므로, 타이머가 처음 돌기 전에도 남은 시간이 맞다.
 */
export function useLockCountdown(lock: LockWindow | null): number | null {
  const [now, setNow] = useState(() => Date.now())
  const until = lock?.until ?? null

  useEffect(() => {
    if (until === null) return
    const timer = setInterval(() => {
      const current = Date.now()
      setNow(current)
      if (current >= until) clearInterval(timer)
    }, 1000)
    return () => clearInterval(timer)
  }, [until])

  if (lock === null) return null
  return Math.max(0, Math.ceil((lock.until - Math.max(now, lock.startedAt)) / 1000))
}
