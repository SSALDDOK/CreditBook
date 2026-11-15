import { Link } from 'react-router'
import { ApiError, CUSTOMER_NOT_FOUND_MESSAGE, toUserMessage } from '@/api/client'
import { Button } from '@/components/ui/button'

/**
 * 고객 상세(SCR-5)·고객 정보 수정(SCR-9)의 조회 실패 카드 — 공통 오류(SCR-10) 규칙대로 내용 자리 대신 카드 하나로 보여 준다.
 * 없는 고객이면 SCR-10 문구 + [고객 목록으로], 그 밖(403·500·네트워크 등)은 toUserMessage 문구 + [다시 시도] [고객 목록으로].
 * 버튼 testid 는 `${testIdPrefix}-retry`·`${testIdPrefix}-back`.
 */
export function CustomerLoadError({
  error,
  onRetry,
  testIdPrefix,
}: {
  error: unknown
  onRetry: () => void
  testIdPrefix: string
}) {
  // 분기는 code 로만 한다
  const notFound = error instanceof ApiError && error.code === 'CUSTOMER_NOT_FOUND'
  return (
    <div className="flex flex-col items-start gap-4 rounded-2xl border bg-background p-8">
      <p role="alert" data-testid={notFound ? 'customer-not-found' : 'error-message'} className="text-destructive">
        {notFound ? CUSTOMER_NOT_FOUND_MESSAGE : toUserMessage(error)}
      </p>
      <div className="flex gap-3">
        {!notFound && (
          <Button type="button" variant="outline" onClick={onRetry} data-testid={`${testIdPrefix}-retry`}>
            다시 시도
          </Button>
        )}
        <Button asChild variant="outline">
          <Link to="/customers" data-testid={`${testIdPrefix}-back`}>
            고객 목록으로
          </Link>
        </Button>
      </div>
    </div>
  )
}
