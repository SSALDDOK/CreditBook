import { cn } from '@/lib/utils'
import { NOTICE_TEXT } from './LockedNotice'

// 429 요청 과다 안내 — 로그인(SCR-1, LOGIN_RATE_LIMITED)과 매장 개설(SCR-11, SIGNUP_RATE_LIMITED)이 같이 쓴다.
// 남은 시간은 보여 주지 않는다(서버도 details 를 주지 않는다).

/** 화면 표준 문구 — 두 화면 같은 글자 */
export const RATE_LIMITED_MESSAGE = '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.'

/** 카드 위쪽 노란 상자 */
export function RateLimitedNotice({ testId, className }: { testId: string; className?: string }) {
  return (
    <p role="alert" data-testid={testId} className={cn(NOTICE_TEXT, 'border-amber-300 bg-amber-50 text-amber-800', className)}>
      {RATE_LIMITED_MESSAGE}
    </p>
  )
}
