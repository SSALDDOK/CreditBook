import { Search } from 'lucide-react'
import { useId, useState } from 'react'
import { toUserMessage } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatMonthDay, formatWon } from '@/lib/format'
import { cn } from '@/lib/utils'
import { useCustomerList, useDebouncedValue } from './useCustomerList'

/** SCR-3 고객 목록. 잔액·연락처는 서버 응답 값을 그대로 표시한다. */
export function CustomerListPage() {
  const searchId = useId()
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [reloadKey, setReloadKey] = useState(0)
  const debouncedQuery = useDebouncedValue(query.trim(), 300)

  const state = useCustomerList(debouncedQuery, page, reloadKey)
  const data = state.status === 'error' ? undefined : state.data
  const loading = state.status === 'loading'
  const listState = state.status === 'loading' ? 'loading' : state.status === 'error' ? 'error' : 'ready'

  // 페이지 범위 표시용(금액 아님): 이 페이지의 첫 행·마지막 행 번호
  const rangeStart = data && data.content.length > 0 ? data.page * data.size + 1 : 0
  const rangeEnd = data ? data.page * data.size + data.content.length : 0

  return (
    <div className="flex flex-1 flex-col gap-6">
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
            onChange={(e) => {
              setQuery(e.target.value)
              setPage(0)
            }}
            placeholder="이름 또는 연락처 뒷자리로 검색"
            // 서버 검색어 길이 제한(20자)과 맞춘다
            maxLength={20}
            autoComplete="off"
            data-testid="customer-search-input"
            className="h-12 rounded-xl bg-background pl-12 text-base md:text-base"
          />
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
        className="flex-1 overflow-hidden rounded-2xl border bg-background"
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
                <TableRow key={customer.id} data-testid="customer-row" data-customer-id={customer.id}>
                  <TableCell className="h-16 px-8 font-semibold" data-testid="customer-name">
                    {customer.name}
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
                    {debouncedQuery ? '검색 결과가 없습니다.' : '등록된 고객이 없습니다.'}
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
            onClick={() => setPage((p) => Math.max(0, p - 1))}
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
            onClick={() => setPage((p) => p + 1)}
            data-testid="page-next"
          >
            다음
          </Button>
        </nav>
      </div>
    </div>
  )
}
