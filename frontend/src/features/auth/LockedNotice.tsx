import { cn } from '@/lib/utils'
import { formatRemaining } from './lockCountdown'

// 423 ACCOUNT_LOCKED 안내 — 로그인(SCR-1)과 비밀번호 변경(SCR-13)이 같이 쓴다.
// 잠금 문구는 서버 message, 남은 시간은 details.retryAfterSeconds(서버가 올림한 초)로 센다.

/** 카드 위 안내 상자(만료·잠금·429) 공통. 글자는 화면설계 13px, 한글이 단어 중간에서 끊기지 않게 keep-all */
export const NOTICE_TEXT = 'mt-6 rounded-xl border px-4 py-4 text-[13px] font-semibold break-keep'

/** 잠금 안내 상자. remainingSeconds 가 null 이면 남은 시간 줄 없이 문구만 */
export function LockedNotice({
  message,
  remainingSeconds,
  testId,
  remainingTestId,
}: {
  message: string
  remainingSeconds: number | null
  testId: string
  remainingTestId: string
}) {
  return (
    <div data-testid={testId} className={cn(NOTICE_TEXT, 'border-destructive/30 bg-destructive/10 text-destructive')}>
      {/* 잠금 문구만 알림으로 한 번 읽힌다. 남은 시간 줄은 알림 영역 밖의 role="timer"(기본 aria-live 꺼짐)라
          매초 바뀌어도 화면낭독기가 다시 읽지 않는다 */}
      {/* 문장마다 줄을 바꾼다 — '잠겼습니다. 잠시 후'가 한 줄에 붙지 않게 */}
      <p role="alert">
        {message.split(/(?<=\.)\s+/).map((sentence) => (
          <span key={sentence} className="block">
            {sentence}
          </span>
        ))}
      </p>
      {remainingSeconds !== null && remainingSeconds > 0 && (
        <p role="timer" data-testid={remainingTestId} className="mt-1 tabular-nums">
          {formatRemaining(remainingSeconds)}
        </p>
      )}
    </div>
  )
}
