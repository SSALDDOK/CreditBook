import type { ReactNode, Ref } from 'react'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'

// 두 단계(1 입력 → 2 확인) 모달 틀 — 충전(SCR-6)·사용(SCR-7) 모달이 같이 쓴다.
// shadcn/ui Dialog(Radix)라서 포커스 가두기·Esc 닫기·닫을 때 포커스 복원은 Radix 가 맡는다.
//
// 쓰는 법:
//   <LedgerInputDialog open={open} onOpenChange={...}>
//     {open && <MyBody />}            ← 닫으면 내용 컴포넌트가 사라져 입력값이 남지 않는다
//   </LedgerInputDialog>
//   MyBody 안에서 <LedgerInputDialogContent step=... title=... footer=...>입력칸들</LedgerInputDialogContent>

export type LedgerInputStep = 'input' | 'confirm'

export function LedgerInputDialog({
  open,
  onOpenChange,
  children,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  children: ReactNode
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {children}
    </Dialog>
  )
}

const STEPS: Array<{ step: LedgerInputStep; label: string }> = [
  { step: 'input', label: '1 입력' },
  { step: 'confirm', label: '2 확인' },
]

/** 오른쪽 위 단계 표시 "1 입력 — 2 확인". 지금 단계는 남색 배지, 나머지는 회색 배지, aria-current="step" */
function StepIndicator({ current }: { current: LedgerInputStep }) {
  return (
    <ol aria-label="진행 단계" className="flex shrink-0 items-center gap-2 text-base" data-testid="ledger-input-steps">
      {STEPS.map(({ step, label }, index) => (
        <li key={step} className="flex items-center gap-2">
          {index > 0 && (
            <span aria-hidden="true" className="text-muted-foreground">
              —
            </span>
          )}
          <span
            aria-current={step === current ? 'step' : undefined}
            className={cn(
              'rounded-full px-3 py-1 text-sm font-bold',
              step === current ? 'bg-sidebar text-sidebar-foreground' : 'bg-muted text-muted-foreground',
            )}
          >
            {label}
          </span>
        </li>
      ))}
    </ol>
  )
}

export function LedgerInputDialogContent({
  step,
  title,
  description,
  banner,
  children,
  footer,
  busy,
  testId,
  titleRef,
  onOpenAutoFocus,
}: {
  step: LedgerInputStep
  /** 모달 제목 — getByRole('dialog', { name: 제목 }) 으로 찾는다 */
  title: string
  description: ReactNode
  /** 제목 아래 안내 띠(복구 안내 등) */
  banner?: ReactNode
  children: ReactNode
  footer: ReactNode
  /** 처리 중이면 Esc 로 닫히지 않는다(응답을 받기 전에 모달이 사라지지 않게) */
  busy: boolean
  testId: string
  /** 단계가 바뀔 때 포커스를 옮길 제목(tabIndex -1) */
  titleRef?: Ref<HTMLHeadingElement>
  onOpenAutoFocus?: (event: Event) => void
}) {
  return (
    <DialogContent
      showCloseButton={false}
      data-testid={testId}
      data-step={step}
      aria-busy={busy}
      onOpenAutoFocus={onOpenAutoFocus}
      onEscapeKeyDown={(e) => {
        if (busy) e.preventDefault()
      }}
      // 태블릿에서 바깥을 잘못 눌러 입력이 사라지지 않게 바깥 누르기로는 닫지 않는다([취소]·Esc 로 닫는다)
      onInteractOutside={(e) => e.preventDefault()}
      className="max-h-[calc(100dvh-2rem)] gap-0 overflow-y-auto rounded-3xl p-0 text-base sm:max-w-[40rem]"
    >
      <div className="flex items-start justify-between gap-6 px-8 pt-7">
        <div className="flex flex-col gap-1">
          <DialogTitle ref={titleRef} tabIndex={-1} className="text-2xl font-bold outline-none">
            {title}
          </DialogTitle>
          <DialogDescription className="text-base text-muted-foreground">{description}</DialogDescription>
        </div>
        <StepIndicator current={step} />
      </div>
      {banner && <div className="px-8 pt-4">{banner}</div>}
      <div className="flex flex-col gap-6 px-8 py-6">{children}</div>
      <div className="flex justify-end gap-3 border-t bg-muted/50 px-8 py-5">{footer}</div>
    </DialogContent>
  )
}
