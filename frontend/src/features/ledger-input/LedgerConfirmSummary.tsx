import { useId, useState } from 'react'
import type { CustomerDetailResponse } from '@/api/customers'
import { formatNumber, formatWon } from '@/lib/format'
import { toKoreanAmount } from '@/lib/koreanAmount'

// 확인 화면 본문 — 충전·사용 모달이 같이 쓴다.
// 연한 파랑 상자("현재 잔액 → 처리 후 잔액" + 한글 안내), 상세 내용 토글(기본 접힘), "[확인]을 누르면 …" 안내.
// 처리 후 잔액은 balancePreview.ts 의 표시 전용 예상값을 호출하는 쪽이 넘긴다. 처리 뒤에는 서버 값만 쓴다.

export function LedgerConfirmSummary({
  testIdPrefix,
  summaryLabel,
  customer,
  expectedBalance,
  expectedCaption,
  problem,
  amountLabel,
  amount,
  memo,
}: {
  /** data-testid 앞부분 (예: 'charge' → charge-confirm-summary) */
  testIdPrefix: string
  /** 상자의 접근 가능한 이름 (예: '충전 후 잔액') */
  summaryLabel: string
  customer: CustomerDetailResponse
  /** 처리 후 예상 잔액(표시 전용) */
  expectedBalance: number
  /** 상자 둘째 줄 (예: '구만오천오백원으로 늘어납니다') */
  expectedCaption: string
  /** 확인 화면에 있는 동안 상세를 다시 받아 진행할 수 없게 됐을 때(사용: 잔액 부족) — 화살표 대신 이 문구를 보여 준다 */
  problem?: string | null
  amountLabel: string
  amount: number
  memo: string
}) {
  const [detailsOpen, setDetailsOpen] = useState(false)
  const detailsId = useId()
  const p = `${testIdPrefix}-confirm`

  return (
    <>
      <section aria-label={summaryLabel} className="rounded-2xl bg-primary/10 px-6 py-5 text-sidebar" data-testid={`${p}-summary`}>
        {problem ? (
          <p role="alert" className="text-lg font-semibold text-destructive" data-testid={`${p}-problem`}>
            {problem}
          </p>
        ) : (
          <>
            <p className="text-2xl font-bold tabular-nums">
              <span data-testid={`${p}-current-balance`}>{formatWon(customer.balance)}</span>
              <span aria-hidden="true" className="mx-3">
                →
              </span>
              <span className="sr-only">에서</span>
              <span data-testid={`${p}-expected-balance`} data-balance={expectedBalance}>
                {formatWon(expectedBalance)}
              </span>
            </p>
            <p className="mt-2 text-lg" data-testid={`${p}-expected-korean`}>
              {expectedCaption}
            </p>
          </>
        )}
      </section>

      <div>
        <button
          type="button"
          aria-expanded={detailsOpen}
          aria-controls={detailsId}
          onClick={() => setDetailsOpen((v) => !v)}
          className="flex h-11 items-center gap-2 rounded-lg px-1 text-base font-semibold focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
          data-testid={`${p}-details-toggle`}
        >
          <span aria-hidden="true">{detailsOpen ? '▼' : '▶'}</span>
          상세 내용
        </button>
        <dl
          id={detailsId}
          hidden={!detailsOpen}
          className="mt-2 grid grid-cols-[7rem_1fr] gap-x-4 gap-y-2 rounded-xl border px-5 py-4"
          data-testid={`${p}-details`}
        >
          <dt className="text-muted-foreground">고객</dt>
          <dd data-testid={`${p}-customer`}>
            {customer.name} · <span className="tabular-nums">{customer.maskedPhone}</span>
          </dd>
          <dt className="text-muted-foreground">{amountLabel}</dt>
          <dd className="tabular-nums" data-testid={`${p}-amount`}>
            {formatNumber(amount)}원 ({toKoreanAmount(amount)}원)
          </dd>
          <dt className="text-muted-foreground">메모</dt>
          <dd className="break-all" data-testid={`${p}-memo`}>
            {memo || '(없음)'}
          </dd>
        </dl>
      </div>

      <p className="text-base text-muted-foreground">[확인]을 누르면 바로 장부에 기록됩니다.</p>
    </>
  )
}
