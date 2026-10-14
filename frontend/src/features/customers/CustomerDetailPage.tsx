import { useId } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, toUserMessage } from '@/api/client'
import type { CustomerDetailResponse } from '@/api/customers'
import { Button } from '@/components/ui/button'
import { LedgerEntryRow } from '@/features/ledger/LedgerEntryRow'
import { formatEntryTime, formatNumber } from '@/lib/format'
import { cn } from '@/lib/utils'
import { InactiveBadge } from './InactiveBadge'
import { useCustomerDetail } from './useCustomerDetail'

/**
 * SCR-5 고객 상세. 잔액·최근 거래는 서버 응답(GET /api/customers/{id}) 값을 그대로 표시한다.
 * 고객 정보 수정·비활성화 링크는 두지 않는다(ADMIN 도 같은 화면).
 */
export function CustomerDetailPage() {
  const { customerId = '' } = useParams()
  // 충전·사용 모달(CB-36·37)이 생기면 성공 뒤 refetch 를 불러 잔액·최근 거래를 서버에서 다시 받는다
  const { state, refetch } = useCustomerDetail(customerId)

  const notFound = state.status === 'error' && state.error instanceof ApiError && state.error.code === 'CUSTOMER_NOT_FOUND'
  const viewState =
    state.status === 'loading' ? 'loading' : state.status === 'success' ? 'ready' : notFound ? 'not-found' : 'error'

  return (
    <div
      className="flex flex-1 flex-col gap-6"
      data-testid="customer-detail"
      data-state={viewState}
      aria-busy={state.status === 'loading'}
    >
      {state.status === 'error' ? (
        <div className="flex flex-col items-start gap-4 rounded-2xl border bg-background p-8">
          <p role="alert" data-testid={notFound ? 'customer-not-found' : 'error-message'} className="text-destructive">
            {notFound ? '고객을 찾을 수 없습니다' : toUserMessage(state.error)}
          </p>
          <div className="flex gap-3">
            {!notFound && (
              <Button type="button" variant="outline" onClick={refetch} data-testid="customer-detail-retry">
                다시 시도
              </Button>
            )}
            <Button asChild variant="outline">
              <Link to="/customers" data-testid="customer-detail-back">
                고객 목록으로 돌아가기
              </Link>
            </Button>
          </div>
        </div>
      ) : state.data ? (
        <CustomerDetailView customer={state.data} loading={state.status === 'loading'} />
      ) : (
        <p role="status" className="p-8 text-muted-foreground" data-testid="customer-detail-loading">
          불러오는 중…
        </p>
      )}
    </div>
  )
}

function CustomerDetailView({ customer, loading }: { customer: CustomerDetailResponse; loading: boolean }) {
  const noticeId = useId()
  const useHintId = useId()
  const recentHeadingId = useId()
  const lastEntry = customer.recentEntries[0]
  const noBalance = customer.balance === 0
  // 사용 가능 여부의 최종 판단은 서버가 한다(비활성 고객 사용 거부·잔액 부족). 여기서는 누를 필요가 없는 경우만 막는 편의다
  const useDisabled = !customer.active || noBalance
  const useDescribedBy = !customer.active ? noticeId : noBalance ? useHintId : undefined

  return (
    <div className={cn('flex flex-col gap-6', loading && 'opacity-60')}>
      {!customer.active && (
        <div
          id={noticeId}
          data-testid="customer-inactive-notice"
          className="rounded-2xl border border-amber-300 bg-amber-50 px-8 py-5 text-amber-900"
        >
          <strong className="font-bold">비활성 고객입니다.</strong> 사용은 할 수 없고, 충전하면 다시 활성화됩니다.
        </div>
      )}

      <div className="flex flex-wrap items-center gap-4">
        <h2
          className={cn('text-3xl font-bold', !customer.active && 'text-muted-foreground')}
          data-testid="customer-detail-name"
        >
          {customer.name}
        </h2>
        {!customer.active && <InactiveBadge testId="customer-detail-inactive-badge" />}
        <span className="text-lg text-muted-foreground tabular-nums" data-testid="customer-detail-phone">
          {customer.maskedPhone}
        </span>
      </div>

      <section
        aria-label="현재 잔액"
        className="flex flex-wrap items-center justify-between gap-6 rounded-3xl bg-sidebar px-12 py-10 text-white"
      >
        <div>
          <p className="text-sidebar-foreground">현재 잔액</p>
          <p
            className={cn('mt-2 font-bold tabular-nums', noBalance && 'text-white/60')}
            data-testid="customer-detail-balance"
            data-balance={customer.balance}
          >
            <span className="text-6xl">{formatNumber(customer.balance)}</span>
            <span className="ml-2 text-3xl">원</span>
          </p>
          {lastEntry && (
            <p className="mt-2 text-sidebar-foreground" data-testid="customer-last-entry">
              마지막 거래 {formatEntryTime(lastEntry.performedAt)} · {lastEntry.performedByName}
            </p>
          )}
        </div>

        <div className="flex flex-col items-end gap-2">
          <div className="flex gap-4">
            {/* 충전 모달(CB-36)·사용 화면(CB-37) 전까지는 누르면 아무 동작도 하지 않는다 */}
            <Button
              type="button"
              variant="outline"
              className="h-14 rounded-xl border-white bg-white px-10 text-lg font-bold text-sidebar hover:bg-white/90"
              data-testid="customer-charge-button"
            >
              충전
            </Button>
            <Button
              type="button"
              disabled={useDisabled}
              aria-describedby={useDescribedBy}
              className="h-14 rounded-xl px-10 text-lg font-bold disabled:bg-sidebar-accent disabled:text-white/60 disabled:opacity-100"
              data-testid="customer-use-button"
            >
              사용
            </Button>
          </div>
          {customer.active && noBalance && (
            <p id={useHintId} className="text-sidebar-foreground" data-testid="customer-use-hint">
              잔액이 없습니다
            </p>
          )}
        </div>
      </section>

      <section aria-labelledby={recentHeadingId} className="rounded-2xl border bg-background px-10 py-8">
        <div className="flex items-center justify-between">
          <h3 id={recentHeadingId} className="text-xl font-bold">
            최근 거래
          </h3>
          {/* "전체 이력 보기"는 거래 이력 화면이 생기면 그 화면으로 가는 Link 로 넣는다 */}
        </div>
        {customer.recentEntries.length > 0 ? (
          <ul className="mt-2" data-testid="recent-entries">
            {customer.recentEntries.map((entry) => (
              <LedgerEntryRow key={entry.id} entry={entry} />
            ))}
          </ul>
        ) : (
          <p className="py-10 text-center text-muted-foreground" data-testid="recent-entries-empty">
            아직 거래가 없습니다.
          </p>
        )}
      </section>
    </div>
  )
}
