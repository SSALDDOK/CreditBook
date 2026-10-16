import { useEffect, useState } from 'react'
import { getTransactions, type TransactionListResponse } from '@/api/transactions'

export type CustomerTransactionsState =
  | { status: 'loading'; data?: TransactionListResponse }
  | { status: 'success'; data: TransactionListResponse }
  | { status: 'error'; error: unknown }

type Settled =
  | { key: string; customerId: string; ok: true; data: TransactionListResponse }
  | { key: string; customerId: string; ok: false; error: unknown }

/**
 * 고객 한 명의 거래 이력 한 페이지를 조회한다(GET /api/customers/{id}/transactions).
 * 조건이 바뀌면 이전 요청을 취소(AbortController)해 늦게 온 응답이 화면을 덮지 않게 한다.
 * 한 페이지 행 수(size)를 아직 재지 못했으면(null) 조회하지 않는다.
 * 조회라 자동 재시도는 하지 않는다 — 실패하면 화면의 "다시 시도"가 reloadKey 를 올린다.
 */
export function useCustomerTransactions(
  customerId: string,
  page: number,
  size: number | null,
  reloadKey: number,
): CustomerTransactionsState {
  const key = JSON.stringify([customerId, page, size, reloadKey])
  const [result, setResult] = useState<Settled | null>(null)

  useEffect(() => {
    if (size === null) return
    const controller = new AbortController()
    getTransactions(customerId, { page, size }, controller.signal)
      .then((data) => setResult({ key, customerId, ok: true, data }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setResult({ key, customerId, ok: false, error })
      })
    return () => controller.abort()
  }, [key, customerId, page, size])

  if (result?.key === key) {
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', error: result.error }
  }
  // 같은 고객의 다른 페이지를 불러오는 동안에는 직전 페이지를 흐리게 유지한다(깜빡임 방지).
  // 다른 고객의 거래나 실패했던 응답은 남기지 않는다
  const previous = result?.ok && result.customerId === customerId ? result.data : undefined
  return { status: 'loading', data: previous }
}
