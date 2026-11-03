import { Loader2 } from 'lucide-react'
import { useId, useRef, useState, type FormEvent, type RefObject } from 'react'
import { ApiError, toUserMessage } from '@/api/client'
import { addEmployee, type AddEmployeeResponse } from '@/api/employees'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { isValidLoginId, isValidName, LOGIN_ID_MAX_LENGTH, NAME_MAX_LENGTH } from '@/features/auth/credentialRules'
import { cn } from '@/lib/utils'
import {
  FIELD_ERROR_CLASS,
  FIELD_HELP_CLASS,
  FIELD_INPUT_CLASS,
  FIELD_LABEL_CLASS,
  REQUIRED_MARK_CLASS,
} from '@/features/auth/fieldStyles'
import {
  DIALOG_CANCEL_BUTTON_CLASS,
  DIALOG_CONTENT_CLASS,
  DIALOG_DESCRIPTION_CLASS,
  DIALOG_FOOTER_CLASS,
  DIALOG_OVERLAY_CLASS,
  DIALOG_PRIMARY_BUTTON_CLASS,
  DIALOG_TITLE_CLASS,
} from './employeeDialogStyles'

// 직원 추가 창(SCR-12). 이름·아이디만 받는다 — 권한은 직원(STAFF) 고정, 비밀번호는 서버가 만든다.
// 아이디 중복은 이미 받아 둔 목록(비활성 포함)과 대소문자를 구분해 바로 안내하고, 서버 409 DUPLICATE_LOGIN_ID 도 같은 자리에 보인다.
// [추가]는 POST 1번 — 자동 재시도 없음. [취소]·Esc 로만 닫힌다(바깥 누르기로 닫지 않는다).

export const DUPLICATE_LOGIN_ID_MESSAGE = '이미 사용 중인 아이디입니다.'
const LOGIN_ID_HELP = '영문·숫자·마침표 4–20자'

export function AddEmployeeDialog({
  open,
  existingLoginIds,
  onClose,
  onAdded,
  onDuplicate,
}: {
  open: boolean
  /** 이미 받아 둔 직원 목록의 아이디(비활성 포함) */
  existingLoginIds: readonly string[]
  onClose: () => void
  /** 추가 성공 — 호출하는 쪽이 이 창을 닫고 초기 비밀번호 창을 연다 */
  onAdded: (response: AddEmployeeResponse) => void
  /** 서버가 아이디 중복으로 거절 — 목록이 낡았을 수 있어 호출하는 쪽이 다시 불러온다 */
  onDuplicate: () => void
}) {
  const pendingRef = useRef(false)
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next && !pendingRef.current) onClose()
      }}
    >
      {open && (
        <AddEmployeeBody
          existingLoginIds={existingLoginIds}
          pendingRef={pendingRef}
          onClose={onClose}
          onAdded={onAdded}
          onDuplicate={onDuplicate}
        />
      )}
    </Dialog>
  )
}

type ServerErrors = { name?: string; loginId?: string; form?: string }

function AddEmployeeBody({
  existingLoginIds,
  pendingRef,
  onClose,
  onAdded,
  onDuplicate,
}: {
  existingLoginIds: readonly string[]
  pendingRef: RefObject<boolean>
  onClose: () => void
  onAdded: (response: AddEmployeeResponse) => void
  onDuplicate: () => void
}) {
  const nameId = useId()
  const loginIdId = useId()
  const nameErrorId = useId()
  const loginIdHelpId = useId()
  const loginIdErrorId = useId()
  const [name, setName] = useState('')
  const [loginId, setLoginId] = useState('')
  const [pending, setPending] = useState(false)
  const [serverErrors, setServerErrors] = useState<ServerErrors>({})

  // 대소문자를 구분한다(서버와 같다) — 'Kim.staff' 와 'kim.staff' 는 다른 아이디
  const duplicate = existingLoginIds.includes(loginId)
  const loginIdFormatInvalid = loginId !== '' && !isValidLoginId(loginId)
  const nameError = serverErrors.name
  const loginIdError = duplicate ? DUPLICATE_LOGIN_ID_MESSAGE : serverErrors.loginId

  const canSubmit = isValidName(name) && isValidLoginId(loginId) && !duplicate && !loginIdError && !pending

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (pendingRef.current || !canSubmit) return // 더블클릭해도 요청은 1번
    pendingRef.current = true
    setPending(true)
    setServerErrors({})
    try {
      const response = await addEmployee({ name: name.trim(), loginId })
      onAdded(response)
    } catch (err: unknown) {
      handleError(err)
    } finally {
      pendingRef.current = false
      setPending(false)
    }
  }

  // 분기는 code 로만 한다
  function handleError(err: unknown) {
    if (err instanceof ApiError) {
      if (err.status === 401) return // 로그인 화면 이동은 공통 401 처리가 한다
      if (err.code === 'DUPLICATE_LOGIN_ID') {
        setServerErrors({ loginId: DUPLICATE_LOGIN_ID_MESSAGE })
        onDuplicate()
        return
      }
      if (err.code === 'INVALID_INPUT') {
        const next: ServerErrors = {}
        for (const f of err.fieldErrors) {
          if (f.field === 'name' && !next.name) next.name = f.message
          else if (f.field === 'loginId' && !next.loginId) next.loginId = f.message
        }
        if (!next.name && !next.loginId) next.form = toUserMessage(err)
        setServerErrors(next)
        return
      }
    }
    // 네트워크 오류·5xx·403 등 — 창 아래에 안내. 응답이 끊긴 경우 이미 추가됐을 수 있다(다시 누르면 서버가 중복으로 알려 준다)
    setServerErrors({ form: toUserMessage(err) })
  }

  return (
    <DialogContent
      showCloseButton={false}
      data-testid="add-employee-dialog"
      data-state={pending ? 'pending' : 'idle'}
      aria-busy={pending}
      onEscapeKeyDown={(e) => {
        if (pendingRef.current) e.preventDefault()
      }}
      onInteractOutside={(e) => e.preventDefault()}
      overlayClassName={DIALOG_OVERLAY_CLASS}
      className={DIALOG_CONTENT_CLASS}
    >
      <form onSubmit={submit} noValidate aria-label="직원 추가">
        <DialogTitle className={DIALOG_TITLE_CLASS}>직원 추가</DialogTitle>
        <DialogDescription className={DIALOG_DESCRIPTION_CLASS}>추가하면 초기 비밀번호가 한 번만 표시됩니다</DialogDescription>

        <div className="mt-[21.5px] flex flex-col">
          <div className={FIELD_LABEL_CLASS}>
            <label htmlFor={nameId}>이름</label>
            <span aria-hidden="true" className={REQUIRED_MARK_CLASS}>
              필수
            </span>
          </div>
          <Input
            id={nameId}
            value={name}
            onChange={(e) => {
              setName(e.target.value)
              setServerErrors((prev) => ({ ...prev, name: undefined, form: undefined }))
            }}
            disabled={pending}
            maxLength={NAME_MAX_LENGTH}
            required
            aria-required="true"
            aria-invalid={nameError ? true : undefined}
            aria-describedby={nameError ? nameErrorId : undefined}
            autoComplete="off"
            data-testid="add-employee-name"
            className={FIELD_INPUT_CLASS}
          />
          {nameError && (
            <p id={nameErrorId} role="alert" className={FIELD_ERROR_CLASS} data-testid="add-employee-name-error">
              {nameError}
            </p>
          )}
        </div>

        <div className="mt-[14.5px] flex flex-col">
          <div className={FIELD_LABEL_CLASS}>
            <label htmlFor={loginIdId}>아이디</label>
            <span aria-hidden="true" className={REQUIRED_MARK_CLASS}>
              필수
            </span>
          </div>
          <Input
            id={loginIdId}
            value={loginId}
            onChange={(e) => {
              setLoginId(e.target.value)
              setServerErrors((prev) => ({ ...prev, loginId: undefined, form: undefined }))
            }}
            disabled={pending}
            maxLength={LOGIN_ID_MAX_LENGTH}
            required
            aria-required="true"
            aria-invalid={loginIdError || loginIdFormatInvalid ? true : undefined}
            aria-describedby={[loginIdHelpId, loginIdError ? loginIdErrorId : null].filter(Boolean).join(' ')}
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            data-testid="add-employee-login-id"
            className={FIELD_INPUT_CLASS}
          />
          <p
            id={loginIdHelpId}
            className={cn(FIELD_HELP_CLASS, loginIdFormatInvalid && 'text-[#C62828]')}
            data-testid="add-employee-login-id-help"
          >
            {LOGIN_ID_HELP}
          </p>
          {loginIdError && (
            <p id={loginIdErrorId} role="alert" className={FIELD_ERROR_CLASS} data-testid="add-employee-login-id-error">
              {loginIdError}
            </p>
          )}
        </div>

        {/* 권한 안내 상자 — 크기·모서리·글자·테두리는 화면설계 이미지대로, 배경 색만 기존 구현(bg-muted) 그대로(사용자 결정) */}
        <p
          className="mt-[14px] flex min-h-[38px] items-center rounded-[9px] border border-[#BFCBF4] bg-muted px-[15px] py-2 text-[12.5px] leading-[17px] text-[#2B4ACB]"
          data-testid="add-employee-role-note"
        >
          권한: 직원(STAFF) — 직원은 직원 권한으로만 추가됩니다
        </p>

        {serverErrors.form && (
          <p
            role="alert"
            className="mt-3 rounded-[9px] bg-[#C62828]/10 px-[15px] py-2 text-[12.5px] text-[#C62828]"
            data-testid="add-employee-error"
          >
            {serverErrors.form}
          </p>
        )}

        <div className={DIALOG_FOOTER_CLASS}>
          <Button
            type="button"
            variant="outline"
            onClick={onClose}
            disabled={pending}
            className={DIALOG_CANCEL_BUTTON_CLASS}
            data-testid="add-employee-cancel"
          >
            취소
          </Button>
          <Button
            type="submit"
            disabled={!canSubmit}
            aria-label={pending ? '직원 추가 중' : undefined}
            data-state={pending ? 'pending' : 'idle'}
            className={DIALOG_PRIMARY_BUTTON_CLASS}
            data-testid="add-employee-submit"
          >
            {pending ? <Loader2 aria-hidden="true" className="size-6 animate-spin" /> : '추가'}
          </Button>
        </div>
      </form>
    </DialogContent>
  )
}
