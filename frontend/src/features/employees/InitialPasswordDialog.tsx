import { Check } from 'lucide-react'
import { useId, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'
import {
  DIALOG_CONTENT_CLASS,
  DIALOG_DESCRIPTION_CLASS,
  DIALOG_FOOTER_CLASS,
  DIALOG_OVERLAY_CLASS,
  DIALOG_PRIMARY_BUTTON_CLASS,
  DIALOG_TITLE_CLASS,
} from './employeeDialogStyles'

// 초기 비밀번호 1회 표시 창(SCR-12) — 직원 추가·비밀번호 초기화가 같이 쓴다(제목만 다르다).
// 값은 호출하는 쪽의 상태(메모리)에만 있고, 창을 닫으면 호출하는 쪽이 지운다. 저장소·주소·로그에 두지 않는다.
// [전달했어요, 닫기]·Esc 로 닫힌다(바깥 누르기로 닫지 않는다 — 잘못 눌러 값을 잃지 않게).

export type InitialPasswordNotice = {
  title: string
  loginId: string
  initialPassword: string
}

// 주의 상자 문구. 화면설계 이미지는 앞 두 줄이고, 하이픈 안내는 화면 문구 표에 따라 셋째 줄로 둔다
const GUIDE_LINES = [
  '이 창을 닫으면 초기 비밀번호를 다시 볼 수 없습니다.',
  '직원에게 직접 전달하세요. 첫 로그인 때 비밀번호를 바꿔야 합니다.',
  '하이픈(-)까지 그대로 입력합니다.',
]

export function InitialPasswordDialog({
  notice,
  onClose,
  onCloseAutoFocus,
}: {
  /** null 이면 창이 닫혀 있다 */
  notice: InitialPasswordNotice | null
  onClose: () => void
  /** 창이 닫힐 때 포커스를 돌려줄 곳을 정한다 */
  onCloseAutoFocus?: (e: Event) => void
}) {
  return (
    <Dialog
      open={notice !== null}
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
    >
      {notice && <InitialPasswordBody
          key={notice.initialPassword}
          notice={notice}
          onClose={onClose}
          onCloseAutoFocus={onCloseAutoFocus}
        />}
    </Dialog>
  )
}

type CopyState = 'idle' | 'copied' | 'failed'

function InitialPasswordBody({
  notice,
  onClose,
  onCloseAutoFocus,
}: {
  notice: InitialPasswordNotice
  onClose: () => void
  onCloseAutoFocus?: (e: Event) => void
}) {
  const [copy, setCopy] = useState<CopyState>('idle')
  const labelId = useId()

  async function handleCopy() {
    try {
      // 클립보드는 보안 컨텍스트(https·localhost)에서만 있다. 실패해도 값은 화면에 그대로 보인다
      if (!navigator.clipboard) throw new Error('clipboard unavailable')
      await navigator.clipboard.writeText(notice.initialPassword)
      setCopy('copied')
    } catch {
      setCopy('failed')
    }
  }

  return (
    <DialogContent
      showCloseButton={false}
      data-testid="initial-password-dialog"
      onInteractOutside={(e) => e.preventDefault()}
      onCloseAutoFocus={onCloseAutoFocus}
      overlayClassName={DIALOG_OVERLAY_CLASS}
      className={DIALOG_CONTENT_CLASS}
    >
      <DialogTitle className={DIALOG_TITLE_CLASS} data-testid="initial-password-title">
        {notice.title}
      </DialogTitle>
      <DialogDescription className={DIALOG_DESCRIPTION_CLASS} data-testid="initial-password-subtitle">
        아이디 {notice.loginId} · 직원(STAFF)
      </DialogDescription>

      <p id={labelId} className="mt-[21.5px] text-[13px] leading-[18px] font-bold text-[#141B34]">
        초기 비밀번호
      </p>
      <div className="mt-[7.5px] flex gap-2">
        <output
          aria-labelledby={labelId}
          data-testid="initial-password-value"
          className="flex h-[50px] min-w-0 flex-1 items-center rounded-[9px] border border-[#DDE0E8] bg-[#F7F8FB] px-[17px] text-[20px] font-bold text-[#141B34] select-all"
        >
          {notice.initialPassword}
        </output>
        <Button
          type="button"
          variant="outline"
          onClick={handleCopy}
          data-testid="initial-password-copy"
          data-state={copy}
          className="h-[50px] w-20 shrink-0 rounded-[9px] border-[#DDE0E8] bg-white text-[14px] font-bold text-[#141B34]"
        >
          {copy === 'copied' && <Check aria-hidden="true" />}
          복사
        </Button>
      </div>
      {/* 복사 결과 — 결과가 있을 때만 한 줄 */}
      <p role="status" className="text-[12px] leading-4" data-testid="initial-password-copy-status">
        {copy === 'copied' && <span className="mt-1 block text-[#1B7A4B]">복사했습니다</span>}
        {copy === 'failed' && (
          <span className="mt-1 block text-[#C62828]">복사하지 못했습니다. 화면의 값을 직접 전달해 주세요</span>
        )}
      </p>

      <p
        className="mt-[14px] rounded-[9px] border border-[#F2C46B] bg-[#FFF6E5] px-[15px] py-[10px] text-[12.5px] leading-[19px] font-bold text-[#8A5300] break-keep"
        data-testid="initial-password-guide"
      >
        {GUIDE_LINES.map((line) => (
          <span key={line} className="block">
            {line}
          </span>
        ))}
      </p>

      <div className={DIALOG_FOOTER_CLASS}>
        <Button
          type="button"
          onClick={onClose}
          className={`${DIALOG_PRIMARY_BUTTON_CLASS} min-w-[150px]`}
          data-testid="initial-password-close"
        >
          전달했어요, 닫기
        </Button>
      </div>
    </DialogContent>
  )
}
