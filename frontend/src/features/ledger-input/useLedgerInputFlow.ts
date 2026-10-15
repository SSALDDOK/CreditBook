import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { ApiError, COMMON_ERROR_MESSAGE, NETWORK_ERROR_MESSAGE, toUserMessage } from '@/api/client'
import type { LedgerInputSettingsResponse } from '@/api/settings'
import { AMOUNT_ERROR_MESSAGES, checkAmount, type AmountCheck } from './amountInput'
import type { LedgerDraft, LedgerDraftKind } from './draftStorage'
import type { LedgerInputStep } from './LedgerInputDialog'
import { joinMemo, selectedMemoChips } from './memoChips'
import { useIdempotencyKey } from './useIdempotencyKey'
import { useSaveDraftOnUnauthorized } from './useSaveDraftOnUnauthorized'

// 충전(SCR-6)·사용(SCR-7) 모달의 공통 흐름 — 1 입력 → 2 확인 → [확인] 한 번에 POST 1번.
// 입력값·단계·처리 중 표시·요청 키·401 직전 입력 저장·공통 오류 처리를 맡는다.
// 모달마다 다른 것(금액 칸 문구, 화면별 오류 code)은 호출하는 쪽이 정한다.
//
// 요청 키(REQ-10): 확인 화면에 들어갈 때 새로 만들고, 그 화면에서 다시 [확인]하면 같은 키, [이전]이면 버린다.
// 자동 재시도는 없다 — 다시 보내는 것은 직원이 [확인]을 다시 누를 때뿐이다.

export type LedgerRequest = { amount: number; memo?: string }

/** 화면별 오류 처리(handleOwnError)에서 쓸 수 있는 동작 */
export type LedgerInputFlowActions = {
  /** 입력 화면으로 돌아간다(요청 키를 버린다) */
  backToInput: () => void
  /** 금액 칸 아래 오류로 보여 준다(금액을 고치면 사라진다) */
  showAmountError: (message: string) => void
  /** 모달 아래쪽 오류 띠로 보여 준다 */
  showFormError: (message: string) => void
}

export function useLedgerInputFlow<R>({
  kind,
  customerId,
  employeeId,
  restoredDraft,
  settings,
  memoPresets,
  maxAmount,
  isAmountBlocked,
  send,
  onSuccess,
  onConflict,
  handleOwnError,
}: {
  kind: LedgerDraftKind
  customerId: string
  employeeId: string
  /** 재로그인 후 복구할 입력(REQ-17). 있으면 입력 화면을 이 값으로 채운다 */
  restoredDraft: LedgerDraft | null
  /** 입력 설정. 받기 전이면 null */
  settings: LedgerInputSettingsResponse | null
  /** 이 모달의 메모 칩(설정값) — 401 직전 저장에서 칩 선택을 고르는 데 쓴다 */
  memoPresets: string[] | undefined
  /** 1회 한도(충전). 넘으면 check.error 가 OVER_MAX */
  maxAmount?: number
  /** 형식은 맞지만 이 화면에서 더 진행하지 못하는 금액이면 true (사용: 잔액 부족 표시 중). 편의일 뿐 최종 판단은 서버 */
  isAmountBlocked?: (amount: number) => boolean
  send: (request: LedgerRequest, idempotencyKey: string) => Promise<R>
  onSuccess: (response: R) => void
  /** 409(동시 처리 등) — 호출하는 쪽이 상세를 다시 불러온다. 모달은 열린 채, 키는 유지 */
  onConflict: () => void
  /** 화면별 오류 code 처리. 처리했으면 true. 공통 처리(네트워크·401·5xx)보다 뒤, 공통 code 처리보다 먼저 불린다 */
  handleOwnError?: (e: ApiError, actions: LedgerInputFlowActions) => boolean
}) {
  const [step, setStep] = useState<LedgerInputStep>('input')
  const [amountInput, setAmountInput] = useState(() =>
    restoredDraft?.amount != null ? String(restoredDraft.amount) : '',
  )
  const [amountTouched, setAmountTouched] = useState(restoredDraft?.amount != null)
  const [amountServerError, setAmountServerError] = useState<string | null>(null)
  const [memo, setMemo] = useState(() => joinMemo(restoredDraft?.memoChips ?? []))
  const [memoServerError, setMemoServerError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const pendingRef = useRef(false)
  const idempotencyKey = useIdempotencyKey()

  const amountRef = useRef<HTMLInputElement>(null)
  const titleRef = useRef<HTMLHeadingElement>(null)
  const formId = useId()

  const check: AmountCheck = checkAmount(amountInput, maxAmount)
  const blocked = check.error === null && check.amount !== null && (isAmountBlocked?.(check.amount) ?? false)
  /** 입력 화면의 주 버튼([충전]·[사용])을 누를 수 있는가 */
  const canProceed = settings !== null && check.error === null && check.amount !== null && !blocked

  // 401 로 로그인 화면에 가기 직전에 금액·칩 선택만 저장한다(REQ-17). 직접 친 메모 글자는 저장하지 않는다
  useSaveDraftOnUnauthorized(employeeId, customerId, kind, () => ({
    amount: check.amount,
    memoChips: memoPresets ? selectedMemoChips(memo, memoPresets) : (restoredDraft?.memoChips ?? []),
  }))

  // 단계가 바뀌면 포커스를 옮긴다: 입력 화면은 금액 칸, 확인 화면은 제목(스크린리더가 새 화면을 읽게)
  const settingsReady = settings !== null
  useEffect(() => {
    if (step === 'input' && settingsReady) amountRef.current?.focus()
    if (step === 'confirm') titleRef.current?.focus()
  }, [step, settingsReady])

  /** 형식 오류 문구(비었음·0원·소수·숫자 아님). 금액 칸을 건드린 뒤에만. 한도 초과(OVER_MAX)는 모달마다 문구가 달라 제외 */
  const amountFormatError =
    check.error && check.error !== 'OVER_MAX' && amountTouched ? AMOUNT_ERROR_MESSAGES[check.error] : null

  function changeAmount(next: string) {
    setAmountInput(next)
    setAmountTouched(true)
    setAmountServerError(null)
  }

  function changeMemo(next: string) {
    setMemo(next)
    setMemoServerError(null)
  }

  function goToConfirm(e?: FormEvent) {
    e?.preventDefault()
    if (!canProceed) {
      setAmountTouched(true)
      return
    }
    idempotencyKey.issue() // 확인 화면에 들어갈 때 새 키
    setFormError(null)
    setStep('confirm')
  }

  function backToInput() {
    idempotencyKey.discard() // [이전]으로 나가면 키를 버린다
    setFormError(null)
    setStep('input')
  }

  const actions: LedgerInputFlowActions = {
    backToInput,
    showAmountError: setAmountServerError,
    showFormError: setFormError,
  }

  async function submit() {
    if (pendingRef.current || !canProceed || check.amount === null) return // 더블클릭해도 요청은 1번
    pendingRef.current = true
    setPending(true)
    setFormError(null)
    const trimmedMemo = memo.trim()
    try {
      const response = await send(
        { amount: check.amount, ...(trimmedMemo ? { memo: trimmedMemo } : {}) },
        idempotencyKey.current(),
      )
      onSuccess(response)
    } catch (e: unknown) {
      handleError(e)
    } finally {
      pendingRef.current = false
      setPending(false)
    }
  }

  // 분기는 code(·status)로만 한다. 문구는 서버 message → 5xx·응답 없음이면 공통 문구
  function handleError(e: unknown) {
    if (!(e instanceof ApiError)) {
      // 응답을 못 받았다 — 서버에서 처리됐을 수 있으므로 같은 키를 유지한다. 다시 [확인]하면 같은 키로 보낸다
      setFormError(`${NETWORK_ERROR_MESSAGE} [확인]을 다시 누르면 같은 요청으로 다시 보냅니다.`)
      return
    }
    if (e.status === 401) return // 로그인 화면 이동은 공통 401 처리가 한다(입력은 그 직전에 저장됨)
    if (e.status >= 500) {
      setFormError(COMMON_ERROR_MESSAGE) // 같은 키 유지
      return
    }
    if (handleOwnError?.(e, actions)) return
    switch (e.code) {
      case 'INVALID_AMOUNT':
        backToInput()
        setAmountServerError(e.message)
        return
      case 'INVALID_INPUT': {
        const amountField = e.fieldErrors.find((f) => f.field === 'amount')
        const memoField = e.fieldErrors.find((f) => f.field === 'memo')
        backToInput()
        if (amountField) setAmountServerError(amountField.message)
        if (memoField) setMemoServerError(memoField.message)
        if (!amountField && !memoField) setFormError(toUserMessage(e))
        return
      }
      case 'IDEMPOTENCY_KEY_REUSED':
      case 'INVALID_IDEMPOTENCY_KEY':
        // 이 키는 더 쓸 수 없다 — 새 키를 만들고, 직원이 내용을 확인한 뒤 [확인]을 다시 누르게 한다
        idempotencyKey.issue()
        setFormError(toUserMessage(e))
        return
    }
    if (e.status === 409) {
      // 다른 요청이 먼저 처리됨 등 — 안내하고 상세(현재 잔액)를 서버에서 다시 받는다. 키는 유지한다
      setFormError(toUserMessage(e))
      onConflict()
      return
    }
    setFormError(toUserMessage(e)) // CUSTOMER_NOT_FOUND·403 등
  }

  return {
    step,
    /** 확인 화면을 보여 줄 금액. 입력 화면이면 null */
    confirmingAmount: step === 'confirm' ? check.amount : null,
    check,
    canProceed,
    amountInput,
    changeAmount,
    amountServerError,
    amountFormatError,
    memo,
    changeMemo,
    memoServerError,
    formError,
    pending,
    goToConfirm,
    backToInput,
    submit,
    amountRef,
    titleRef,
    formId,
  }
}
