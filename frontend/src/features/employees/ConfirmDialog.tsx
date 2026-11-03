import { Loader2 } from 'lucide-react'
import { useRef, useState, type RefObject } from 'react'
import { ApiError, toUserMessage } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'
import {
  DIALOG_CANCEL_BUTTON_CLASS,
  DIALOG_CONTENT_CLASS,
  DIALOG_DESCRIPTION_CLASS,
  DIALOG_FOOTER_CLASS,
  DIALOG_OVERLAY_CLASS,
  DIALOG_PRIMARY_BUTTON_CLASS,
  DIALOG_TITLE_CLASS,
} from './employeeDialogStyles'

// 직원 비밀번호 초기화·비활성화 확인 창(SCR-12). [취소]·Esc 로만 닫힌다(바깥 누르기로 닫지 않는다).
// [초기화]·[비활성화]를 누르면 onConfirm 을 한 번 부른다 — 변경 요청이라 자동 재시도는 없다.
// 서버 거절(409 ADMIN_ACCOUNT_PROTECTED·404 EMPLOYEE_NOT_FOUND 등 4xx)은 창 안에 서버 message 를 보여 주고 확인 버튼을 막는다.
// 네트워크 오류·5xx 는 안내만 하고 다시 누를 수 있게 둔다.

export type ConfirmDialogContent = {
  /** 창을 구분하는 값(data-kind) — reset-password·deactivate */
  kind: string
  title: string
  description: string
  confirmLabel: string
  pendingLabel: string
  destructive?: boolean
}

export function ConfirmDialog({
  content,
  onConfirm,
  onClose,
  onRejected,
  onCloseAutoFocus,
}: {
  /** null 이면 창이 닫혀 있다 */
  content: ConfirmDialogContent | null
  /** 창이 닫힐 때 포커스를 돌려줄 곳을 정한다(창을 연 메뉴 항목은 이미 사라졌다) */
  onCloseAutoFocus?: (e: Event) => void
  /** 확인 — 성공하면 호출하는 쪽이 창을 닫는다. 실패하면 오류를 던진다 */
  onConfirm: () => Promise<void>
  onClose: () => void
  /** 서버가 거절함 — 목록이 바뀌었을 수 있어 호출하는 쪽이 다시 불러온다. 창은 열린 채 */
  onRejected: () => void
}) {
  const pendingRef = useRef(false)
  return (
    <Dialog
      open={content !== null}
      onOpenChange={(next) => {
        if (!next && !pendingRef.current) onClose()
      }}
    >
      {content && (
        <ConfirmDialogBody
          key={`${content.kind}:${content.title}`}
          content={content}
          pendingRef={pendingRef}
          onConfirm={onConfirm}
          onClose={onClose}
          onRejected={onRejected}
          onCloseAutoFocus={onCloseAutoFocus}
        />
      )}
    </Dialog>
  )
}

function ConfirmDialogBody({
  content,
  pendingRef,
  onConfirm,
  onClose,
  onRejected,
  onCloseAutoFocus,
}: {
  content: ConfirmDialogContent
  pendingRef: RefObject<boolean>
  onCloseAutoFocus?: (e: Event) => void
  onConfirm: () => Promise<void>
  onClose: () => void
  onRejected: () => void
}) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<{ message: string; final: boolean } | null>(null)

  async function submit() {
    if (pendingRef.current || error?.final) return // 더블클릭해도 요청은 1번
    pendingRef.current = true
    setPending(true)
    setError(null)
    try {
      await onConfirm()
    } catch (e: unknown) {
      if (e instanceof ApiError && e.status === 401) return // 로그인 화면 이동은 공통 401 처리가 한다
      const rejected = e instanceof ApiError && e.status >= 400 && e.status < 500
      setError({ message: toUserMessage(e), final: rejected })
      if (rejected) onRejected()
    } finally {
      pendingRef.current = false
      setPending(false)
    }
  }

  const state = pending ? 'pending' : error ? (error.final ? 'rejected' : 'error') : 'idle'

  return (
    <DialogContent
      showCloseButton={false}
      data-testid="confirm-dialog"
      data-kind={content.kind}
      data-state={state}
      aria-busy={pending}
      onEscapeKeyDown={(e) => {
        if (pendingRef.current) e.preventDefault()
      }}
      onInteractOutside={(e) => e.preventDefault()}
      onCloseAutoFocus={onCloseAutoFocus}
      overlayClassName={DIALOG_OVERLAY_CLASS}
      className={DIALOG_CONTENT_CLASS}
    >
      <DialogTitle className={DIALOG_TITLE_CLASS}>{content.title}</DialogTitle>
      <DialogDescription className={DIALOG_DESCRIPTION_CLASS}>{content.description}</DialogDescription>

      {error && (
        <p
          role="alert"
          data-testid="confirm-dialog-error"
          className="mt-4 rounded-[9px] border border-[#C62828]/30 bg-[#C62828]/10 px-[15px] py-2 text-[12.5px] text-[#C62828]"
        >
          {error.message}
        </p>
      )}

      <div className={DIALOG_FOOTER_CLASS}>
        <Button
          type="button"
          variant="outline"
          onClick={onClose}
          disabled={pending}
          className={DIALOG_CANCEL_BUTTON_CLASS}
          data-testid="confirm-dialog-cancel"
        >
          취소
        </Button>
        <Button
          type="button"
          onClick={submit}
          disabled={pending || error?.final === true}
          aria-label={pending ? content.pendingLabel : undefined}
          data-state={pending ? 'pending' : 'idle'}
          className={cn(
            DIALOG_PRIMARY_BUTTON_CLASS,
            content.destructive && 'bg-[#C62828] text-white hover:bg-[#C62828]/90',
          )}
          data-testid="confirm-dialog-submit"
        >
          {pending ? <Loader2 aria-hidden="true" className="size-6 animate-spin" /> : content.confirmLabel}
        </Button>
      </div>
    </DialogContent>
  )
}
