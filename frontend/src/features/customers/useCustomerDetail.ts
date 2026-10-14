import { useCallback, useEffect, useState } from 'react'
import { getCustomer, type CustomerDetailResponse } from '@/api/customers'

export type CustomerDetailState =
  | { status: 'loading'; data?: CustomerDetailResponse }
  | { status: 'success'; data: CustomerDetailResponse }
  | { status: 'error'; error: unknown }

export type UseCustomerDetail = {
  state: CustomerDetailState
  /**
   * 상세를 서버에서 다시 불러온다. 충전·사용 모달(CB-36·37)은 성공한 뒤 이 함수를 불러 잔액·최근 거래를 갱신한다
   * — 화면에서 잔액을 더하거나 빼서 고치지 않는다.
   */
  refetch: () => void
}

type Settled =
  | { key: string; customerId: string; ok: true; data: CustomerDetailResponse }
  | { key: string; customerId: string; ok: false; error: unknown }

/**
 * 고객 상세(GET /api/customers/{id})를 조회한다.
 * 고객이 바뀌거나 refetch 하면 이전 요청을 취소(AbortController)해 늦게 온 응답이 화면을 덮지 않게 한다.
 * 조회라 자동 재시도는 하지 않는다 — 실패하면 화면의 "다시 시도"가 refetch 를 부른다.
 */
export function useCustomerDetail(customerId: string): UseCustomerDetail {
  const [reloadKey, setReloadKey] = useState(0)
  const key = JSON.stringify([customerId, reloadKey])
  const [result, setResult] = useState<Settled | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    getCustomer(customerId, controller.signal)
      .then((data) => setResult({ key, customerId, ok: true, data }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setResult({ key, customerId, ok: false, error })
      })
    return () => controller.abort()
  }, [key, customerId])

  const refetch = useCallback(() => setReloadKey((k) => k + 1), [])

  if (result?.key === key) {
    const state: CustomerDetailState = result.ok
      ? { status: 'success', data: result.data }
      : { status: 'error', error: result.error }
    return { state, refetch }
  }
  // 같은 고객을 다시 불러오는 동안에는 직전 서버 응답을 흐리게 유지한다(깜빡임 방지).
  // 다른 고객의 값이나 실패했던 응답은 남기지 않는다
  const previous = result?.ok && result.customerId === customerId ? result.data : undefined
  return { state: { status: 'loading', data: previous }, refetch }
}
