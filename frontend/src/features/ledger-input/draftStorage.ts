// 재로그인 후 입력 복구 (REQ-17, TC-46).
// 충전·사용 모달에서 요청이 401(세션 만료)을 받아 로그인 화면으로 가기 직전에 입력하던 금액을 저장하고,
// 같은 직원이 다시 로그인해 같은 고객 상세로 돌아오면 모달을 입력 화면으로 다시 열어 채운다(자동 전송은 하지 않는다).
//
// - 저장소는 sessionStorage(탭을 닫으면 사라진다). 인증 토큰이 아니라 입력 보조 값이다 — 토큰은 httpOnly 쿠키에만 있다
// - 저장하는 것은 금액과 메모 칩 선택뿐이다. 직접 친 메모 글자·고객 이름·연락처는 저장하지 않는다
// - 키에 직원 ID·고객 ID·모달 종류를 넣는다. 다른 직원이 로그인하면 그 직원 것이 아닌 저장값을 지운다
// - 전송 성공·[취소]·로그아웃 때 지운다
// - 저장소를 쓸 수 없으면(사생활 보호 모드 등) 조용히 건너뛴다 — 복구는 편의 기능이고 장부와 무관하다

export type LedgerDraftKind = 'charge' | 'use'

export type LedgerDraft = {
  /** 입력하던 금액(원 단위 정수). 형식이 틀려 숫자가 아니었으면 null */
  amount: number | null
  /** 선택돼 있던 메모 칩 문구(칩 목록 순서) */
  memoChips: string[]
}

const PREFIX = 'creditbook.ledger-draft.v1:'

function storageKey(employeeId: string, customerId: string, kind: LedgerDraftKind): string {
  return `${PREFIX}${employeeId}:${customerId}:${kind}`
}

function storage(): Storage | null {
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

function isDraft(value: unknown): value is LedgerDraft {
  if (typeof value !== 'object' || value === null) return false
  const { amount, memoChips } = value as LedgerDraft
  const amountOk = amount === null || (Number.isSafeInteger(amount) && amount > 0)
  return amountOk && Array.isArray(memoChips) && memoChips.every((chip) => typeof chip === 'string')
}

/** 입력하던 값을 저장한다. 금액도 칩도 없으면 저장하지 않고 이전 값을 지운다 */
export function saveLedgerDraft(employeeId: string, customerId: string, kind: LedgerDraftKind, draft: LedgerDraft): void {
  const store = storage()
  if (!store) return
  const key = storageKey(employeeId, customerId, kind)
  try {
    if (draft.amount === null && draft.memoChips.length === 0) {
      store.removeItem(key)
      return
    }
    store.setItem(key, JSON.stringify({ amount: draft.amount, memoChips: draft.memoChips }))
  } catch {
    // 용량 초과 등 — 복구만 안 될 뿐 진행에는 영향이 없다
  }
}

/** 저장값을 읽는다. 없거나 형식이 깨졌으면 null(깨진 값은 지운다) */
export function loadLedgerDraft(employeeId: string, customerId: string, kind: LedgerDraftKind): LedgerDraft | null {
  const store = storage()
  if (!store) return null
  const key = storageKey(employeeId, customerId, kind)
  try {
    const raw = store.getItem(key)
    if (raw === null) return null
    const parsed: unknown = JSON.parse(raw)
    if (isDraft(parsed)) return { amount: parsed.amount, memoChips: [...parsed.memoChips] }
    store.removeItem(key)
  } catch {
    // 읽기 실패·JSON 오류 — 복구하지 않는다
  }
  return null
}

export function clearLedgerDraft(employeeId: string, customerId: string, kind: LedgerDraftKind): void {
  try {
    storage()?.removeItem(storageKey(employeeId, customerId, kind))
  } catch {
    // 무시 — 위 설명
  }
}

function removeDrafts(shouldRemove: (key: string) => boolean): void {
  const store = storage()
  if (!store) return
  try {
    const keys: string[] = []
    for (let i = 0; i < store.length; i += 1) {
      const key = store.key(i)
      if (key?.startsWith(PREFIX) && shouldRemove(key)) keys.push(key)
    }
    keys.forEach((key) => store.removeItem(key))
  } catch {
    // 무시 — 위 설명
  }
}

/** 로그인한 직원이 아닌 직원의 저장값을 모두 지운다(다른 직원에게는 복구하지 않는다) */
export function clearLedgerDraftsExcept(employeeId: string): void {
  removeDrafts((key) => !key.startsWith(`${PREFIX}${employeeId}:`))
}

/** 저장값을 모두 지운다(로그아웃) */
export function clearAllLedgerDrafts(): void {
  removeDrafts(() => true)
}
