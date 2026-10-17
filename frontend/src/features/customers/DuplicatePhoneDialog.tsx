import { ChevronRight, Loader2 } from 'lucide-react'
import type { DuplicatePhoneCustomer } from '@/api/customers'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'
import { ActiveBadge } from './ActiveBadge'
import { InactiveBadge } from './InactiveBadge'

// 고객 등록(SCR-4)의 같은 번호 확인 창 — 서버가 409 DUPLICATE_PHONE 을 주면 폼 위에 뜬다.
// 행 순서는 서버 응답(details.existingCustomers — 활성 먼저·최근 등록순) 그대로다. 연락처는 응답에 없어
// 부제의 번호는 직원이 입력한 값을 화면이 형식만 바꿔 보여 준다.
//
// - 모두 비활성: "같은 번호의 비활성 고객이 있습니다" — 한 명이면 [닫기] [새로 등록] [해당 고객으로 이동],
//   여럿이면 [해당 고객으로 이동] 대신 각 행을 눌러 그 고객 상세로 간다([닫기] [새로 등록])
// - 활성이 하나라도: "같은 번호의 고객이 있습니다" — [닫기] [새로 등록]
// [새로 등록]은 같은 입력으로 confirmDuplicatePhone: true 를 다시 보낸다(보내는 일은 등록 화면이 한다).
// shadcn/ui Dialog(Radix) — 포커스 가두기·Esc 닫기·닫을 때 포커스 복원은 Radix 가 맡는다(CancelDialog 와 같은 방식).

const BUTTON_CLASS = 'h-12 min-w-28 rounded-xl px-6 text-lg font-bold'

export function DuplicatePhoneDialog({
  customers,
  phoneDisplay,
  pending,
  onClose,
  onRegisterAnyway,
  onGoToCustomer,
}: {
  /** 같은 번호 고객(서버 순서). null 이면 창이 닫혀 있다 */
  customers: DuplicatePhoneCustomer[] | null
  /** 부제에 쓸 번호(입력값을 010-0000-0000 형식으로 바꾼 것) */
  phoneDisplay: string
  /** [새로 등록] 요청 중 — 버튼을 막고 Esc 로 닫히지 않게 한다 */
  pending: boolean
  /** [닫기]·Esc — 창만 닫고 입력은 그대로 */
  onClose: () => void
  onRegisterAnyway: () => void
  onGoToCustomer: (customerId: string) => void
}) {
  const open = customers !== null && customers.length > 0

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next && !pending) onClose()
      }}
    >
      {open && (
        <DuplicatePhoneDialogBody
          customers={customers}
          phoneDisplay={phoneDisplay}
          pending={pending}
          onClose={onClose}
          onRegisterAnyway={onRegisterAnyway}
          onGoToCustomer={onGoToCustomer}
        />
      )}
    </Dialog>
  )
}

function DuplicatePhoneDialogBody({
  customers,
  phoneDisplay,
  pending,
  onClose,
  onRegisterAnyway,
  onGoToCustomer,
}: {
  customers: DuplicatePhoneCustomer[]
  phoneDisplay: string
  pending: boolean
  onClose: () => void
  onRegisterAnyway: () => void
  onGoToCustomer: (customerId: string) => void
}) {
  const allInactive = customers.every((c) => !c.active)
  // 비활성 고객이 여럿이면 어느 고객인지 직원이 고르게 한다 — 행 자체가 이동 버튼이 된다
  const rowsAreLinks = allInactive && customers.length > 1
  const single = allInactive && customers.length === 1 ? customers[0] : null

  const registerButton = (
    <Button
      type="button"
      // 오른쪽 끝 버튼이 기본(파란) 버튼 — [해당 고객으로 이동]이 있을 때만 보조 버튼
      variant={single ? 'outline' : 'default'}
      onClick={onRegisterAnyway}
      disabled={pending}
      aria-label={pending ? '새로 등록 처리 중' : undefined}
      data-state={pending ? 'pending' : 'idle'}
      className={BUTTON_CLASS}
      data-testid="duplicate-phone-register-anyway"
    >
      {pending ? <Loader2 aria-hidden="true" className="size-6 animate-spin" /> : '새로 등록'}
    </Button>
  )

  const closeButton = (
    <Button
      type="button"
      variant="outline"
      onClick={onClose}
      disabled={pending}
      className="h-12 min-w-24 rounded-xl px-6 text-lg font-bold"
      data-testid="duplicate-phone-close"
    >
      닫기
    </Button>
  )

  return (
    <DialogContent
      showCloseButton={false}
      data-testid="duplicate-phone-dialog"
      data-variant={allInactive ? 'inactive' : 'active'}
      data-state={pending ? 'pending' : 'idle'}
      aria-busy={pending}
      onEscapeKeyDown={(e) => {
        if (pending) e.preventDefault()
      }}
      // 다른 모달(충전·사용·취소)과 같이 바깥 누르기로는 닫지 않는다([닫기]·Esc 로 닫는다)
      onInteractOutside={(e) => e.preventDefault()}
      className="max-h-[calc(100dvh-2rem)] gap-0 overflow-y-auto rounded-3xl p-0 text-base sm:max-w-[32rem]"
    >
      <div className="flex flex-col gap-1 px-6 pt-7">
        <DialogTitle className="text-2xl font-bold">
          {allInactive ? '같은 번호의 비활성 고객이 있습니다' : '같은 번호의 고객이 있습니다'}
        </DialogTitle>
        <DialogDescription className="text-base text-muted-foreground" data-testid="duplicate-phone-description">
          {allInactive ? '다시 오신 손님이면 해당 고객에게 충전하세요' : `${phoneDisplay} 번호로 이미 등록된 고객`}
        </DialogDescription>
      </div>

      <ul className="flex flex-col gap-2 px-6 pt-5" aria-label="같은 번호 고객" data-testid="duplicate-phone-list">
        {customers.map((c) => {
          const content = (
            <>
              <span className={cn('truncate text-lg font-bold', !c.active && 'text-muted-foreground')}>{c.name}</span>
              {c.active ? (
                <ActiveBadge testId="duplicate-phone-active-badge" />
              ) : (
                <InactiveBadge testId="duplicate-phone-inactive-badge" />
              )}
            </>
          )
          return (
            <li key={c.id} data-testid="duplicate-phone-row" data-customer-id={c.id} data-active={c.active}>
              {rowsAreLinks ? (
                <button
                  type="button"
                  onClick={() => onGoToCustomer(c.id)}
                  disabled={pending}
                  data-testid="duplicate-phone-row-link"
                  className={cn(
                    'flex w-full items-center gap-3 rounded-2xl border px-5 py-4 text-left transition-colors',
                    'outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50',
                  )}
                >
                  {content}
                  <span className="sr-only">고객으로 이동</span>
                  <ChevronRight aria-hidden="true" className="ml-auto size-5 text-muted-foreground" />
                </button>
              ) : (
                <div className="flex items-center gap-3 rounded-2xl border px-5 py-4">{content}</div>
              )}
            </li>
          )
        })}
      </ul>

      <div className="flex justify-end gap-3 px-6 pt-6 pb-6">
        {closeButton}
        {registerButton}
        {single && (
          <Button
            type="button"
            onClick={() => onGoToCustomer(single.id)}
            disabled={pending}
            className={BUTTON_CLASS}
            data-testid="duplicate-phone-go-to-customer"
          >
            해당 고객으로 이동
          </Button>
        )}
      </div>
    </DialogContent>
  )
}
