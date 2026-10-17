import { Search } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { toUserMessage } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatMonthDay, formatWon } from '@/lib/format'
import { cn } from '@/lib/utils'
import type { CustomerRegisterLocationState } from './CustomerRegisterPage'
import type { CustomerListLocationState, CustomerListReturn, CustomerLocationState } from './customerListReturn'
import { InactiveBadge } from './InactiveBadge'
import { useCustomerList, useDebouncedValue } from './useCustomerList'
import { useFitPageSize, useKeepFirstRowOnResize } from '@/hooks/useFitPageSize'

/**
 * SCR-3 고객 목록. 잔액·연락처는 서버 응답 값을 그대로 표시한다.
 * '비활성 포함'은 보기 필터다 — 켜면 includeInactive=true 로 조회할 뿐 고객 상태를 바꾸지 않는다(STAFF·ADMIN 모두).
 *
 * 페이지·비활성 포함은 주소(?page=&inactive=1)에, 검색어는 주소가 아니라 이 화면의 방문 기록 상태(history state)에 둔다
 * — 이름·연락처가 브라우저 기록·서버 로그에 남지 않게(customerListReturn.ts). 상세·등록으로 갈 때 돌아올 목록(listReturn)을
 * 함께 넘겨, 브레드크럼 '고객'·[취소]·브라우저 뒤로가기로 돌아오면 보던 검색 결과·페이지가 그대로 보인다.
 * 주소·상태는 replace 로 바꿔 페이지를 넘길 때마다 방문 기록이 쌓이지 않게 한다. 새로고침·왼쪽 메뉴로 들어오면 처음 목록이다.
 */
export function CustomerListPage() {
  const searchId = useId()
  const includeInactiveId = useId()
  const navigate = useNavigate()
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  // 예전 주소(?q=)로 들어온 경우만 주소의 검색어를 한 번 읽고, 아래 effect 가 주소에서 지워 상태로 옮긴다
  const legacyQuery = params.get('q')
  const activeQuery = ((location.state as CustomerListLocationState)?.q ?? legacyQuery ?? '').trim()
  const includeInactive = params.get('inactive') === '1'
  const page = toPage(params.get('page'))
  const [query, setQuery] = useState(activeQuery)
  const [reloadKey, setReloadKey] = useState(0)
  const debouncedQuery = useDebouncedValue(query.trim(), 300)

  // 화면 밖에서 검색어 상태가 바뀌면(왼쪽 메뉴 '고객'으로 처음 목록 등) 입력칸도 맞춘다.
  // 이 화면이 방금 쓴 검색어(= 입력이 멈춘 값)면 그대로 둔다
  const [seenQuery, setSeenQuery] = useState(activeQuery)
  if (seenQuery !== activeQuery) {
    setSeenQuery(activeQuery)
    if (activeQuery !== debouncedQuery) setQuery(activeQuery)
  }

  // 목록 조건을 바꾼다 — 주소에는 page·inactive 만(기본값이면 뺀다), 검색어는 방문 기록 상태로
  const writeList = (next: { q?: string; page?: number; inactive?: boolean }) => {
    const q = next.q ?? activeQuery
    setParams(listParams(next.page ?? page, next.inactive ?? includeInactive), {
      replace: true,
      state: (q ? { q } : null) satisfies CustomerListLocationState,
    })
  }
  const setPage = (p: number) => writeList({ page: p })

  // 예전 주소의 ?q= 를 지우고 상태로 옮긴다(검색어가 주소에 남지 않게)
  useEffect(() => {
    if (legacyQuery === null) return
    const updated = new URLSearchParams(params)
    updated.delete('q')
    const q = legacyQuery.trim()
    setParams(updated, { replace: true, state: (q ? { q } : null) satisfies CustomerListLocationState })
  }, [legacyQuery, params, setParams])

  // 입력이 멈춘 검색어를 방문 기록 상태에 반영하고 1페이지로 돌아간다
  useEffect(() => {
    if (debouncedQuery !== query.trim()) return // 아직 입력 중이거나, 밖에서 입력칸을 바꾼 직후
    if (debouncedQuery === activeQuery) return
    setParams(listParams(0, includeInactive), {
      replace: true,
      state: (debouncedQuery ? { q: debouncedQuery } : null) satisfies CustomerListLocationState,
    })
  }, [debouncedQuery, query, activeQuery, includeInactive, setParams])

  // 상세·등록으로 갈 때 돌아올 목록을 넘긴다 — 브레드크럼 '고객'·[취소]가 이 목록(검색어 포함)으로 돌아온다
  const listQueryString = listParams(page, includeInactive).toString()
  const listReturn: CustomerListReturn = { search: listQueryString ? `?${listQueryString}` : '', q: activeQuery }
  const detailState: CustomerLocationState = { listReturn }
  const registerState: CustomerRegisterLocationState = { listReturn }
  // 검색 0건의 [바로 등록] — 검색어를 화면 이동 상태로 넘긴다(주소에는 넣지 않는다). 어느 칸에 채울지는 등록 화면이 정한다
  const registerFromSearch = () =>
    navigate('/customers/new', {
      state: { listReturn, prefillQuery: activeQuery } satisfies CustomerRegisterLocationState,
    })

  // 한 페이지 행 수는 표 영역 높이에 맞춘다 — 태블릿(1024×768)은 10명, 더 큰 화면은 더 많이
  const listRef = useRef<HTMLElement>(null)
  const fit = useFitPageSize(listRef, { headerHeight: HEADER_HEIGHT, minRowHeight: MIN_ROW_HEIGHT, min: 5, max: 30 })
  const pageSize = fit?.size ?? null

  // 창 크기가 바뀌어 행 수가 달라지면, 보던 첫 고객이 들어 있는 페이지로 옮긴다
  useKeepFirstRowOnResize(pageSize, page, setPage)

  const state = useCustomerList(activeQuery, page, pageSize, reloadKey, includeInactive)
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
            onChange={(e) => writeList({ inactive: e.target.checked, page: 0 })}
            data-testid="customer-include-inactive"
            className="size-5 accent-primary"
          />
          <label htmlFor={includeInactiveId} className="text-base text-muted-foreground">
            비활성 포함
          </label>
        </div>
        {/* 고객 등록(SCR-4)은 빈 칸으로 연다. 지금 목록 조건을 넘겨 [취소]·브레드크럼 '고객'이 이 목록으로 돌아오게 한다 */}
        <Button asChild size="lg" className="h-12 rounded-xl px-6 text-base">
          <Link to="/customers/new" state={registerState} data-testid="customer-register-button">
            고객 등록
          </Link>
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
                  <TableCell colSpan={4} className="py-12 text-center text-muted-foreground" data-testid="customer-empty">
                    {activeQuery ? (
                      <div className="flex flex-col items-center gap-4" data-testid="customer-search-empty">
                        <p className="text-lg">
                          <span className="font-bold text-foreground">'{activeQuery}'</span> — 등록되지 않은 고객입니다
                        </p>
                        <Button
                          type="button"
                          size="lg"
                          onClick={registerFromSearch}
                          className="h-11 rounded-xl px-6 text-base"
                          data-testid="customer-register-from-search"
                        >
                          바로 등록
                        </Button>
                        {!includeInactive && (
                          <p className="text-sm" data-testid="customer-include-inactive-hint">
                            비활성 고객까지 찾으려면 '비활성 포함'을 켜세요
                          </p>
                        )}
                      </div>
                    ) : (
                      <div className="flex flex-col items-center gap-4" data-testid="customer-list-empty">
                        <p className="text-lg">아직 등록된 고객이 없습니다</p>
                        <Button asChild size="lg" className="h-11 rounded-xl px-6 text-base">
                          <Link to="/customers/new" state={registerState} data-testid="customer-register-from-empty">
                            고객 등록
                          </Link>
                        </Button>
                      </div>
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

/** 목록 주소의 조건 — page·inactive 만(기본값이면 뺀다). 검색어는 넣지 않는다 */
function listParams(page: number, includeInactive: boolean): URLSearchParams {
  const params = new URLSearchParams()
  if (page > 0) params.set('page', String(page))
  if (includeInactive) params.set('inactive', '1')
  return params
}

/** 주소의 page 값(0부터). 숫자가 아니거나 음수면 0페이지로 본다 */
function toPage(value: string | null): number {
  const n = Number(value)
  return Number.isInteger(n) && n > 0 ? n : 0
}
