import { Search } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { toUserMessage } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatMonthDay, formatWon } from '@/lib/format'
import { cn } from '@/lib/utils'
import { InactiveBadge } from './InactiveBadge'
import { useCustomerList, useDebouncedValue } from './useCustomerList'
import { useFitPageSize } from './useFitPageSize'

/**
 * SCR-3 고객 목록. 잔액·연락처는 서버 응답 값을 그대로 표시한다.
 * '비활성 포함'은 보기 필터다 — 켜면 includeInactive=true 로 조회할 뿐 고객 상태를 바꾸지 않는다(STAFF·ADMIN 모두).
 *
 * 검색어·페이지·비활성 포함은 주소(?q=&page=&inactive=1)에 둔다 — 상세에서 브레드크럼 '고객'이나 브라우저 뒤로가기로
 * 돌아오면 보던 검색 결과·페이지가 그대로 보인다. 주소는 replace 로 바꿔 페이지를 넘길 때마다 방문 기록이 쌓이지 않게 한다.
 */
export function CustomerListPage() {
  const searchId = useId()
  const includeInactiveId = useId()
  const navigate = useNavigate()
  const { search } = useLocation()
  const [params, setParams] = useSearchParams()
  const urlQuery = params.get('q') ?? ''
  const includeInactive = params.get('inactive') === '1'
  const page = toPage(params.get('page'))
  const [query, setQuery] = useState(urlQuery)
  const [reloadKey, setReloadKey] = useState(0)
  const debouncedQuery = useDebouncedValue(query.trim(), 300)

  // 주소 조건을 바꾼다. 값이 기본값(빈 검색어·0페이지·비활성 제외)이면 주소에서 뺀다
  const updateParams = (next: { q?: string; page?: number; inactive?: boolean }) => {
    const q = next.q ?? urlQuery
    const p = next.page ?? page
    const inactive = next.inactive ?? includeInactive
    const updated = new URLSearchParams()
    if (q) updated.set('q', q)
    if (p > 0) updated.set('page', String(p))
    if (inactive) updated.set('inactive', '1')
    setParams(updated, { replace: true })
  }
  const setPage = (p: number) => updateParams({ page: p })

  // 입력이 멈춘 검색어를 주소에 반영하고 1페이지로 돌아간다
  useEffect(() => {
    if (debouncedQuery === urlQuery) return
    const updated = new URLSearchParams(params)
    if (debouncedQuery) updated.set('q', debouncedQuery)
    else updated.delete('q')
    updated.delete('page')
    setParams(updated, { replace: true })
  }, [debouncedQuery, urlQuery, params, setParams])

  // 상세로 갈 때 지금 목록 주소를 넘긴다 — 상세의 브레드크럼 '고객'이 이 목록으로 돌아온다
  const detailState = { listSearch: search }

  // 한 페이지 행 수는 표 영역 높이에 맞춘다 — 태블릿(1024×768)은 10명, 더 큰 화면은 더 많이
  const listRef = useRef<HTMLElement>(null)
  const fit = useFitPageSize(listRef, { headerHeight: HEADER_HEIGHT, minRowHeight: MIN_ROW_HEIGHT, min: 5, max: 30 })
  const pageSize = fit?.size ?? null

  // 창 크기가 바뀌어 행 수가 달라지면, 보던 첫 고객이 들어 있는 페이지로 옮긴다
  const prevPageSize = useRef<number | null>(null)
  useEffect(() => {
    const prev = prevPageSize.current
    prevPageSize.current = pageSize
    if (prev === null || pageSize === null || prev === pageSize || page === 0) return
    const next = Math.floor((page * prev) / pageSize)
    if (next !== page) updateParams({ page: next })
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 행 수가 바뀔 때만 옮긴다
  }, [pageSize])

  const state = useCustomerList(urlQuery, page, pageSize, reloadKey, includeInactive)
  const data = state.status === 'error' ? undefined : state.data
  const loading = state.status === 'loading'
  const listState = state.status === 'loading' ? 'loading' : state.status === 'error' ? 'error' : 'ready'

  // 페이지 범위 표시용(금액 아님): 이 페이지의 첫 행·마지막 행 번호
  const rangeStart = data && data.content.length > 0 ? data.page * data.size + 1 : 0
  const rangeEnd = data ? data.page * data.size + data.content.length : 0

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-4">
      <div className="flex items-center gap-4">
        <div className="relative flex-1">
          <label htmlFor={searchId} className="sr-only">
            고객 검색
          </label>
          <Search
            aria-hidden="true"
            className="pointer-events-none absolute top-1/2 left-4 size-5 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            id={searchId}
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="이름 또는 연락처 뒷자리로 검색"
            // 서버 검색어 길이 제한(20자)과 맞춘다
            maxLength={20}
            autoComplete="off"
            data-testid="customer-search-input"
            className="h-12 rounded-xl bg-background pl-12 text-base md:text-base"
          />
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <input
            id={includeInactiveId}
            type="checkbox"
            checked={includeInactive}
            onChange={(e) => updateParams({ inactive: e.target.checked, page: 0 })}
            data-testid="customer-include-inactive"
            className="size-5 accent-primary"
          />
          <label htmlFor={includeInactiveId} className="text-base text-muted-foreground">
            비활성 포함
          </label>
        </div>
        {/* 고객 등록 화면(SCR-4)은 다음 스프린트 — 자리만 둔다 */}
        <Button
          type="button"
          size="lg"
          disabled
          title="고객 등록은 준비 중입니다"
          data-testid="customer-register-button"
          className="h-12 rounded-xl px-6 text-base"
        >
          고객 등록
        </Button>
      </div>

      <section
        aria-label="고객 목록"
        aria-busy={loading}
        data-testid="customer-list"
        data-state={listState}
        data-page-size={pageSize ?? undefined}
        ref={listRef}
        className="min-h-0 flex-1 overflow-hidden rounded-2xl border bg-background"
      >
        {state.status === 'error' ? (
          <div className="flex flex-col items-start gap-3 p-8">
            <p role="alert" data-testid="error-message" className="text-destructive">
              {toUserMessage(state.error)}
            </p>
            <Button
              type="button"
              variant="outline"
              onClick={() => setReloadKey((k) => k + 1)}
              data-testid="customer-list-retry"
            >
              다시 시도
            </Button>
          </div>
        ) : (
          <Table className={cn('text-base', loading && 'opacity-60')}>
            <TableCaption className="sr-only">고객 목록 — 이름, 연락처, 잔액, 등록일</TableCaption>
            <TableHeader className="bg-muted/60">
              <TableRow>
                <TableHead scope="col" className="h-14 px-8 text-muted-foreground">
                  이름
                </TableHead>
                <TableHead scope="col" className="px-4 text-muted-foreground">
                  연락처
                </TableHead>
                <TableHead scope="col" className="px-4 text-right text-muted-foreground">
                  잔액
                </TableHead>
                <TableHead scope="col" className="px-8 text-right text-muted-foreground">
                  등록일
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data?.content.map((customer) => (
                <TableRow
                  key={customer.id}
                  data-testid="customer-row"
                  data-customer-id={customer.id}
                  data-active={customer.active}
                  // 행 어디를 눌러도 상세로 간다(마우스 편의). 키보드·보조기기는 이름 링크로 간다
                  onClick={(e) => {
                    if (e.target instanceof Element && e.target.closest('a')) return
                    navigate(`/customers/${customer.id}`, { state: detailState })
                  }}
                  className="cursor-pointer"
                  // 표 영역 높이를 행 수로 나눈 높이 — 표 아래에 빈 공간이 남지 않게
                  style={fit ? { height: fit.rowHeight } : undefined}
                >
                  <TableCell className="h-12 px-8 font-semibold">
                    <span className="flex items-center gap-3">
                      <Link
                        to={`/customers/${customer.id}`}
                        state={detailState}
                        data-testid="customer-name"
                        className={cn(
                          'rounded outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50',
                          !customer.active && 'text-muted-foreground',
                        )}
                      >
                        {customer.name}
                      </Link>
                      {!customer.active && <InactiveBadge testId="customer-inactive-badge" />}
                    </span>
                  </TableCell>
                  <TableCell className="px-4 text-muted-foreground tabular-nums" data-testid="customer-phone">
                    {customer.maskedPhone}
                  </TableCell>
                  <TableCell className="px-4 text-right font-bold tabular-nums" data-testid="customer-balance">
                    {formatWon(customer.balance)}
                  </TableCell>
                  <TableCell className="px-8 text-right text-muted-foreground" data-testid="customer-created-at">
                    {formatMonthDay(customer.createdAt)}
                  </TableCell>
                </TableRow>
              ))}
              {data && data.content.length === 0 && (
                <TableRow>
                  <TableCell colSpan={4} className="h-32 text-center text-muted-foreground" data-testid="customer-empty">
                    {urlQuery ? '검색 결과가 없습니다.' : '등록된 고객이 없습니다.'}
                    {urlQuery && !includeInactive && (
                      <p className="mt-3 text-sm" data-testid="customer-include-inactive-hint">
                        비활성 고객까지 찾으려면 '비활성 포함'을 켜세요
                      </p>
                    )}
                  </TableCell>
                </TableRow>
              )}
              {!data && (
                <TableRow>
                  <TableCell colSpan={4} className="h-32 text-center text-muted-foreground" data-testid="customer-loading">
                    불러오는 중…
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        )}
      </section>

      <div className="flex items-center justify-between">
        <p className="text-muted-foreground" aria-live="polite" data-testid="customer-total">
          {data ? `전체 ${data.totalElements}명 · ${rangeStart}–${rangeEnd} 표시` : ' '}
        </p>
        <nav aria-label="페이지 이동" className="flex gap-3">
          <Button
            type="button"
            variant="outline"
            size="lg"
            className="h-11 rounded-xl bg-background px-6 text-base"
            disabled={loading || !data || data.page === 0}
            onClick={() => setPage(Math.max(0, page - 1))}
            data-testid="page-prev"
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
            data-testid="page-next"
          >
            다음
          </Button>
        </nav>
      </div>
    </div>
  )
}

// 표 머리글(h-14 + 아래 테두리) 높이 — 실제 머리글을 재기 전에만 쓴다. 데이터 행은 최소 48px(테두리 포함)
const HEADER_HEIGHT = 57
const MIN_ROW_HEIGHT = 48

/** 주소의 page 값(0부터). 숫자가 아니거나 음수면 0페이지로 본다 */
function toPage(value: string | null): number {
  const n = Number(value)
  return Number.isInteger(n) && n > 0 ? n : 0
}
