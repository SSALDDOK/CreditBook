import { useEffect, useRef } from 'react'
import { addBeforeUnauthorizedListener } from '@/api/client'
import { saveLedgerDraft, type LedgerDraft, type LedgerDraftKind } from './draftStorage'

/**
 * 모달이 열려 있는 동안, 어느 요청이든 401 을 받아 로그인 화면으로 가기 직전에 지금 입력값을 저장한다(REQ-17).
 * getDraft 는 매 렌더의 최신 값을 읽도록 ref 에 담아 둔다 — 등록은 한 번만 한다.
 */
export function useSaveDraftOnUnauthorized(
  employeeId: string,
  customerId: string,
  kind: LedgerDraftKind,
  getDraft: () => LedgerDraft,
): void {
  const getDraftRef = useRef(getDraft)
  useEffect(() => {
    getDraftRef.current = getDraft
  })

  useEffect(
    () =>
      addBeforeUnauthorizedListener(() => {
        saveLedgerDraft(employeeId, customerId, kind, getDraftRef.current())
      }),
    [employeeId, customerId, kind],
  )
}
