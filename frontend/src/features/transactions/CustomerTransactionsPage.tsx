import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useParams, useSearchParams } from 'react-router'
import { ApiError, toUserMessage } from '@/api/client'
import type { CustomerDetailResponse } from '@/api/customers'
import type { TransactionResponse } from '@/api/transactions'
import { AppLayout } from '@/components/layout/AppLayout'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { CancelDialog } from '@/features/ledger-cancel/CancelDialog'
import { TransactionCancelButton } from '@/features/ledger-cancel/TransactionCancelButton'
import { CustomerBreadcrumb } from '@/features/customers/CustomerDetailBreadcrumb'
import { InactiveBadge } from '@/features/customers/InactiveBadge'
import { useCustomerDetail } from '@/features/customers/useCustomerDetail'
import { TransactionRow } from '@/features/ledger/TransactionRow'
import { useFitPageSize, useKeepFirstRowOnResize } from '@/hooks/useFitPageSize'
import { formatWon } from '@/lib/format'
import { cn } from '@/lib/utils'
import { useCustomerTransactions } from './useCustomerTransactions'

/**
 * SCR-8 거래 이력 (/customers/:customerId/transactions).
 * 제목 줄의 경로 표시(고객 › 이름 › 거래 이력)에 고객 이름이 들어가므로, 이 화면은 레이아웃까지 직접 그린다.
 * 고객 줄(이름·마스킹 연락처·현재 잔액)은 고객 상세 API(GET /api/customers/{id}), 표는 거래 이력 API 값을 그대로 표시한다
 * — 잔액·거래 후 잔액을 화면에서 계산하지 않는다. STAFF·ADMIN 화면이 같다.
 *
 * 페이지는 주소(?page=)에 두고 replace 로 바꾼다 — 상세로 갔다가 뒤로가기로 돌아오면 보던 페이지가 보인다.
 * 한 페이지 행 수는 표 영역 높이에 맞춘다(고객 목록과 같은 useFitPageSize — 태블릿 1024×768 에서 10행).
 *
 * 행 맨 오른쪽 [취소](CB-18)는 서버의 cancellable·cancelled 값만 보고 그리고, 누르면 취소 사유 창(사용 취소·충전 취소)을 연다.
 * 취소가 끝나면(또는 거절·이미 취소됨이면) 이력과 고객 상세(현재 잔액)를 서버에서 다시 받는다 — 화면에서 고치지 않는다.
 * 원거래 행은 지우거나 숨기지 않는다('취소됨' 배지 + 비활성 [취소], 새 반제 행이 맨 위에 생긴다).
 */
export function CustomerTransactionsScreen() {
  const { customerId = '' } = useParams()
  const customer = useCustomerDetail(customerId)
  const name = customer.state.status === 'error' ? null : (customer.state.data?.name ?? null)

  return (
    <AppLayout title={<CustomerBreadcrumb customer={{ id: customerId, name }} current="거래 이력" />}>
      <CustomerTransactionsPage customerId={customerId} customer={customer} />
    </AppLayout>
  )
}

function CustomerTransactionsPage({
  customerId,
  customer,
}: {
  customerId: string
  customer: ReturnType<typeof useCustomerDetail>
}) {
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const page = toPage(params.get('page'))
  const [reloadKey, setReloadKey] = useState(0)
  // 취소 사유 창을 연 원거래(열 때의 행 값). null 이면 닫혀 있다
  const [cancelTarget, setCancelTarget] = useState<TransactionResponse | null>(null)

  const setPage = (p: number) => {
    const updated = new URLSearchParams(params)
    if (p > 0) updated.set('page', String(p))
    else updated.delete('page')
    // 고객 목록에서 가져온 상태(listSearch — 브레드크럼 '고객'이 돌아갈 목록 주소)를 유지한다
    setParams(updated, { replace: true, state: location.state })
  }

  // 한 페이지 행 수는 표 영역 높이에 맞춘다. 표 카드의 위아래 테두리(2px)는 행이 쓸 수 없다
  const fitRef = useRef<HTMLDivElement>(null)
  const fit = useFitPageSize(fitRef, {
    headerHeight: HEADER_HEIGHT,
    minRowHeight: MIN_ROW_HEIGHT,
    min: 5,
    max: 30,
    reservedHeight: CARD_BORDER_HEIGHT,
  })
  const pageSize = fit?.size ?? null
  // 창 크기가 바뀌어 행 수가 달라지면, 보던 첫 거래가 들어 있는 페이지로 옮긴다
  useKeepFirstRowOnResize(pageSize, page, setPage)

  const transactions = useCustomerTransactions(customerId, page, pageSize, reloadKey)
  const data = transactions.status === 'error' ? undefined : transactions.data
  const loading = transactions.status === 'loading'

  // 주소의 page 가 마지막 페이지를 넘으면(직접 입력·그사이 행 수 변화) 마지막 페이지로 옮긴다
  useEffect(() => {
    if (data && data.content.length === 0 && data.totalElements > 0 && page > 0) {
      setPage(Math.max(0, data.totalPages - 1))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 응답이 바뀔 때만 확인한다
  }, [data])

  const customerError = customer.state.status === 'error' ? customer.state.error : null
  const transactionsError = transactions.status === 'error' ? transactions.error : null
  const notFound = isNotFound(customerError) || isNotFound(transactionsError)
  const viewState = notFound
    ? 'not-found'
    : customerError || transactionsError
      ? 'error'
      : customer.state.status === 'success' && transactions.status === 'success'
        ? 'ready'
        : 'loading'

  // 장부가 바뀌었을 수 있을 때(취소 성공·거절·이미 취소됨) 이력과 현재 잔액을 서버에서 다시 받는다
  const reloadLedger = () => {
    customer.refetch()
    setReloadKey((k) => k + 1)
  }
  const customerData = customer.state.status === 'error' ? undefined : customer.state.data

  const retry = () => {
    if (customerError) customer.refetch()
    if (transactionsError) setReloadKey((k) => k + 1)
  }

  // 페이지 범위 표시용(금액 아님): 이 페이지의 첫 행·마지막 행 번호
  const rangeStart = data && data.content.length > 0 ? data.page * data.size + 1 : 0
  const rangeEnd = data ? data.page * data.size + data.content.length : 0
  const empty = data?.totalElements === 0

  if (notFound) {
    // 고객 상세의 404 처리와 같은 모양
    return (
      <div className="flex flex-1 flex-col gap-5" data-testid="transactions-page" data-state={viewState}>
        <div className="flex flex-col items-start gap-4 rounded-2xl border bg-background p-8">
          <p role="alert" data-testid="customer-not-found" className="text-destructive">
            고객을 찾을 수 없습니다
          </p>
          <Button asChild variant="outline">
            <Link to="/customers" data-testid="transactions-back">
              고객 목록으로 돌아가기
            </Link>
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div
      className="flex min-h-0 flex-1 flex-col gap-4"
      data-testid="transactions-page"
      data-state={viewState}
      aria-busy={viewState === 'loading'}
    >
      <CustomerLine customer={customerData} />

      {customerData && (
        <CancelDialog
          target={cancelTarget}
          customer={customerData}
          onClose={() => setCancelTarget(null)}
          onCancelled={() => {
            setCancelTarget(null)
            reloadLedger() // 반제 행·'취소됨'·현재 잔액은 서버에서 다시 받는다
          }}
          onLedgerChanged={reloadLedger}
        />
      )}

      {/* 행 수를 재는 영역. 빈 상태에서는 표 카드만 짧아지고 이 영역의 높이는 그대로라 행 수가 흔들리지 않는다 */}
      <div ref={fitRef} className="flex min-h-0 flex-1 flex-col" data-page-size={pageSize ?? undefined}>
        <section
          aria-label="거래 이력"
          aria-busy={loading}
          data-testid="transactions-list"
          className={cn('overflow-hidden rounded-2xl border bg-background', !empty && 'min-h-0 flex-1')}
        >
          {customerError || transactionsError ? (
            <div className="flex flex-col items-start gap-3 p-8">
              <p role="alert" data-testid="error-message" className="text-destructive">
                {toUserMessage(customerError ?? transactionsError)}
              </p>
              <Button type="button" variant="outline" onClick={retry} data-testid="transactions-retry">
                다시 시도
              </Button>
            </div>
          ) : (
            <Table className={cn('table-fixed text-sm', loading && 'opacity-60')}>
              <TableCaption className="sr-only">
                거래 이력 — 일시, 유형, 메모, 금액, 거래 후 잔액, 처리 직원 (최신순)
              </TableCaption>
              <colgroup>
                <col className="w-[160px]" />
                <col className="w-[82px]" />
                <col />
                <col className="w-[96px]" />
                <col className="w-[96px]" />
                <col className="w-[72px]" />
                <col className="w-[72px]" />
              </colgroup>
              <TableHeader className="bg-muted/60">
                <TableRow className="hover:bg-transparent">
                  <TableHead scope="col" className="h-14 pr-2 pl-5 font-semibold text-muted-foreground">
                    일시
                  </TableHead>
                  <TableHead scope="col" className="px-2 font-semibold text-muted-foreground">
                    유형
                  </TableHead>
                  <TableHead scope="col" className="px-2 font-semibold text-muted-foreground">
                    메모
                  </TableHead>
                  <TableHead scope="col" className="px-2 text-right font-semibold text-muted-foreground">
                    금액
                  </TableHead>
                  <TableHead scope="col" className="px-2 text-right font-semibold text-muted-foreground">
                    거래 후 잔액
                  </TableHead>
                  <TableHead scope="col" className="px-2 font-semibold text-muted-foreground">
                    처리
                  </TableHead>
                  <TableHead scope="col" className="pr-3 pl-1">
                    {/* [취소] 버튼 칸 — 머리글 글자는 화면에 보이지 않는다 */}
                    <span className="sr-only">취소</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data?.content.map((entry) => (
                  <TransactionRow
                    key={entry.id}
                    entry={entry}
                    height={fit?.rowHeight}
                    action={<TransactionCancelButton entry={entry} onCancel={setCancelTarget} />}
                  />
                ))}
                {empty && (
                  <TableRow className="hover:bg-transparent">
                    <TableCell colSpan={7} className="h-72 text-center whitespace-normal" data-testid="transactions-empty">
                      <p className="text-lg font-bold">아직 거래가 없습니다</p>
                      <p className="mt-2 text-base text-muted-foreground">고객 상세에서 충전하면 여기에 기록됩니다</p>
                    </TableCell>
                  </TableRow>
                )}
                {!data && (
                  <TableRow className="hover:bg-transparent">
                    <TableCell
                      colSpan={7}
                      className="h-32 text-center text-base text-muted-foreground"
                      data-testid="transactions-loading"
                    >
                      불러오는 중…
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          )}
        </section>
      </div>

      {/* 빈 상태에서는 화면설계대로 아래 줄을 그리지 않는다. 같은 높이(h-11)의 빈 자리를 남겨 표 영역 높이·행 수가 바뀌지 않게 한다 */}
      {empty ? (
        <div className="h-11 shrink-0" aria-hidden="true" />
      ) : (
        <div className="flex items-center justify-between">
          <p className="text-muted-foreground" aria-live="polite" data-testid="transactions-range">
            {data ? `전체 ${data.totalElements}건 · ${rangeStart}–${rangeEnd} 표시` : ' '}
          </p>
          <nav aria-label="페이지 이동" className="flex gap-3">
            <Button
              type="button"
              variant="outline"
              size="lg"
              className="h-11 rounded-xl bg-background px-6 text-base"
              disabled={loading || !data || data.page === 0}
              onClick={() => setPage(Math.max(0, page - 1))}
              data-testid="transactions-prev"
            >
              이전
            </Button>
            <Button
              type="button"
              variant="outline"
              size="lg"
              className="h-11 rounded-xl bg-background px-6 text-base"
              disabled={loading || !data || !data.hasNext}
              onClick={() => setPage(page + 1)}
              data-testid="transactions-next"
            >
              다음
            </Button>
          </nav>
        </div>
      )}
    </div>
  )
}

/** 고객 줄: 이름·마스킹 연락처(서버 값 그대로)와 오른쪽 현재 잔액(서버 값 그대로) */
function CustomerLine({ customer }: { customer?: CustomerDetailResponse }) {
  return (
    <div className="flex h-10 shrink-0 items-center justify-between gap-6">
      <div className="flex min-w-0 items-center gap-4">
        <h2
          className={cn('truncate text-3xl font-bold', customer && !customer.active && 'text-muted-foreground')}
          data-testid="transactions-customer-name"
        >
          {customer?.name ?? ' '}
        </h2>
        {customer && !customer.active && <InactiveBadge testId="transactions-inactive-badge" />}
        <span className="text-lg text-muted-foreground tabular-nums" data-testid="transactions-customer-phone">
          {customer?.maskedPhone}
        </span>
      </div>
      {customer && (
        <p className="flex shrink-0 items-baseline gap-3">
          <span className="text-muted-foreground">현재 잔액</span>
          <strong
            className={cn('text-3xl font-bold tabular-nums', customer.balance === 0 && 'text-foreground/70')}
            data-testid="transactions-customer-balance"
            data-balance={customer.balance}
          >
            {formatWon(customer.balance)}
          </strong>
        </p>
      )}
    </div>
  )
}

function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'CUSTOMER_NOT_FOUND'
}

// 표 머리글(h-14 + 아래 테두리) 높이 — 실제 머리글을 재기 전에만 쓴다. 데이터 행은 최소 48px — 고객 목록과 같다
const HEADER_HEIGHT = 57
const MIN_ROW_HEIGHT = 48
const CARD_BORDER_HEIGHT = 2

/** 주소의 page 값(0부터). 숫자가 아니거나 음수면 0페이지로 본다 */
function toPage(value: string | null): number {
  const n = Number(value)
  return Number.isInteger(n) && n > 0 ? n : 0
}
