import { Loader2 } from 'lucide-react'
import { useRef, useState, type RefObject } from 'react'
import { ApiError, COMMON_ERROR_MESSAGE, NETWORK_ERROR_MESSAGE, toUserMessage } from '@/api/client'
import { deactivateCustomer, readCustomerBalanceNotZeroDetails } from '@/api/customers'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'
import {
  LEDGER_DIALOG_BUTTON_CLASS,
  LEDGER_DIALOG_PRIMARY_BUTTON_CLASS,
} from '@/features/ledger-input/ledgerDialogStyles'
import { formatWon } from '@/lib/format'
import { cn } from '@/lib/utils'

// 고객 정보 수정(SCR-9) 위험 구역의 비활성화 확인 창 (REQ-4, ADMIN 만 이 창을 열 수 있다 — 최종 판단은 서버의 403).
// 사유 칸 없이 [비활성화] 한 번에 PATCH 1번. 비활성화할 수 있는지(잔액 0원)는 서버가 판단하고, 화면은 그 응답을 보여 준다
// — 화면이 잔액을 보고 [비활성화]를 미리 막지 않는다.
//
// 모양은 거래 이력의 취소 창(CancelDialog)과 같다: 바깥 누르기로 닫히지 않고 Esc·[닫기]로만, 거절되면 창 안 빨간 상자 +
// '장부와 잔액은 바뀌지 않았습니다.' + [비활성화] 회색 비활성 + [닫기]가 기본 버튼. 자동 재시도는 없다
// (비활성화는 같은 요청을 다시 보내도 204 라서, 네트워크 오류·5xx 뒤에는 직원이 다시 누를 수 있게 둔다).

const REJECTED_NOTE = '장부와 잔액은 바뀌지 않았습니다.'

export function DeactivateCustomerDialog({
  customer,
  onClose,
  onDeactivated,
  onConflict,
  onNotFound,
}: {
  /** 비활성화할 고객. null 이면 창이 닫혀 있다 */
  customer: { id: string; name: string } | null
  /** [닫기]·Esc */
  onClose: () => void
  /** 204 — 호출하는 쪽이 고객 상세로 간다 */
  onDeactivated: () => void
  /** 409 CONCURRENT_MODIFICATION — 호출하는 쪽이 창을 닫고 최신 값을 다시 불러온다 */
  onConflict: () => void
  /** 404 CUSTOMER_NOT_FOUND — 호출하는 쪽이 창을 닫고 다시 불러와 공통 오류 카드를 보인다 */
  onNotFound: () => void
}) {
  // 처리 중에는 Esc 로 닫히지 않게 본문이 알려 준다
  const pendingRef = useRef(false)
  return (
    <Dialog
      open={customer !== null}
      onOpenChange={(next) => {
        if (!next && !pendingRef.current) onClose()
      }}
    >
      {customer && (
        <DeactivateCustomerDialogBody
          key={customer.id}
          customer={customer}
          pendingRef={pendingRef}
          onClose={onClose}
          onDeactivated={onDeactivated}
          onConflict={onConflict}
          onNotFound={onNotFound}
        />
      )}
    </Dialog>
  )
}

type Rejection = {
  /** 서버 message 그대로(굵게) */
  message: string
  /** 잔액이 남아 거절됨 — 안내 줄과 남은 잔액(서버 details.balance, 없으면 null)을 더 보인다 */
  balanceNotZero: { balance: number | null } | null
}

function DeactivateCustomerDialogBody({
  customer,
  pendingRef,
  onClose,
  onDeactivated,
  onConflict,
  onNotFound,
}: {
  customer: { id: string; name: string }
  pendingRef: RefObject<boolean>
  onClose: () => void
  onDeactivated: () => void
  onConflict: () => void
  onNotFound: () => void
}) {
  const [pending, setPending] = useState(false)
  const [rejection, setRejection] = useState<Rejection | null>(null)
  const [retryError, setRetryError] = useState<string | null>(null)

  const canSubmit = !pending && rejection === null

  async function submit() {
    if (pendingRef.current || !canSubmit) return // 더블클릭해도 요청은 1번
    pendingRef.current = true
    setPending(true)
    setRetryError(null)
    let done = false
    try {
      await deactivateCustomer(customer.id)
      done = true
    } catch (e: unknown) {
      handleError(e)
    } finally {
      pendingRef.current = false
      setPending(false)
    }
    if (done) onDeactivated()
  }

  // 분기는 code(·status)로만 한다. 문구는 서버 message → 5xx·응답 없음이면 공통 문구
  function handleError(e: unknown) {
    if (!(e instanceof ApiError)) {
      setRetryError(NETWORK_ERROR_MESSAGE)
      return
    }
    if (e.status === 401) return // 로그인 화면 이동은 공통 401 처리가 한다
    if (e.status >= 500) {
      setRetryError(COMMON_ERROR_MESSAGE)
      return
    }
    switch (e.code) {
      case 'CUSTOMER_BALANCE_NOT_ZERO': {
        const details = readCustomerBalanceNotZeroDetails(e.details)
        setRejection({ message: toUserMessage(e), balanceNotZero: { balance: details?.balance ?? null } })
        return
      }
      case 'CONCURRENT_MODIFICATION':
        onConflict()
        return
      case 'CUSTOMER_NOT_FOUND':
        onNotFound()
        return
      case 'PASSWORD_CHANGE_REQUIRED':
        return // 비밀번호 변경 화면 이동은 공통 처리(client.ts)가 한다
    }
    // 403 FORBIDDEN 등 — 이 창에서 더 할 수 없다
    setRejection({ message: toUserMessage(e), balanceNotZero: null })
  }

  const state = pending ? 'pending' : rejection ? 'rejected' : retryError ? 'error' : 'idle'

  return (
    <DialogContent
      showCloseButton={false}
      data-testid="customer-deactivate-dialog"
      data-state={state}
      aria-busy={pending}
      onEscapeKeyDown={(e) => {
        if (pendingRef.current) e.preventDefault()
      }}
      // 다른 모달과 같이 바깥 누르기로는 닫지 않는다([닫기]·Esc 로 닫는다)
      onInteractOutside={(e) => e.preventDefault()}
      className="max-h-[calc(100dvh-2rem)] gap-0 overflow-y-auto rounded-3xl p-0 text-base sm:max-w-[33rem]"
    >
      <div className="flex flex-col gap-1 px-6 pt-7">
        <DialogTitle className="text-2xl font-bold">고객 비활성화</DialogTitle>
        <DialogDescription className="text-base text-muted-foreground" data-testid="customer-deactivate-description">
          {customer.name} 고객을 비활성화합니다
        </DialogDescription>
      </div>

      <div className="flex flex-col gap-4 px-6 pt-5">
        <p className="text-base break-keep" data-testid="customer-deactivate-guide">
          비활성 고객은 고객 목록에서 숨겨지고 사용할 수 없습니다.
          <br />
          거래 기록은 그대로 남고, 충전하면 다시 활성화됩니다.
        </p>

        {rejection && (
          <div
            role="alert"
            className="rounded-2xl border border-destructive/30 bg-destructive/10 px-5 py-4 text-destructive break-keep"
            data-testid="customer-deactivate-error"
          >
            <p className="text-base font-bold">{rejection.message}</p>
            {rejection.balanceNotZero && (
              <>
                <p className="mt-1 text-base">남은 금액을 고객에게 돌려드리고 잔액을 0원으로 만든 뒤 다시 시도하세요.</p>
                {rejection.balanceNotZero.balance !== null && (
                  <p
                    className="mt-1 text-base tabular-nums"
                    data-testid="customer-deactivate-error-balance"
                    data-balance={rejection.balanceNotZero.balance}
                  >
                    남은 잔액 {formatWon(rejection.balanceNotZero.balance)}
                  </p>
                )}
              </>
            )}
          </div>
        )}
        {rejection && (
          <p className="text-sm text-muted-foreground" data-testid="customer-deactivate-note">
            {REJECTED_NOTE}
          </p>
        )}

        {retryError && (
          <p
            role="alert"
            className="rounded-xl bg-destructive/10 px-4 py-3 text-base text-destructive"
            data-testid="customer-deactivate-retry-error"
          >
            {retryError}
          </p>
        )}
      </div>

      <div className="flex justify-end gap-3 px-6 pt-5 pb-6">
        <Button
          type="button"
          variant={rejection ? 'default' : 'outline'}
          onClick={onClose}
          disabled={pending}
          className={cn(LEDGER_DIALOG_BUTTON_CLASS, rejection && 'bg-sidebar text-white hover:bg-sidebar/90')}
          data-testid="customer-deactivate-close"
        >
          닫기
        </Button>
        <Button
          type="button"
          onClick={submit}
          disabled={!canSubmit}
          aria-label={pending ? '고객 비활성화 처리 중' : undefined}
          data-state={pending ? 'pending' : 'idle'}
          className={cn(LEDGER_DIALOG_PRIMARY_BUTTON_CLASS, 'bg-destructive text-white hover:bg-destructive/90')}
          data-testid="customer-deactivate-confirm"
        >
          {pending ? <Loader2 aria-hidden="true" className="size-6 animate-spin" /> : '비활성화'}
        </Button>
      </div>
    </DialogContent>
  )
}
