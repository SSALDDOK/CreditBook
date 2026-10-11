import { useEffect, useState } from 'react'
import { searchCustomers, type CustomerListResponse } from '@/api/customers'

export type CustomerListState =
  | { status: 'loading'; data?: CustomerListResponse }
  | { status: 'success'; data: CustomerListResponse }
  | { status: 'error'; error: unknown }

/** 입력이 멈춘 뒤 delay(ms)가 지나면 값을 넘긴다 — 검색어를 칠 때마다 조회하지 않게 한다. */
export function useDebouncedValue<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])
  return debounced
}

/**
 * 고객 목록을 조회한다. 조건이 바뀌면 이전 요청을 취소(AbortController)해 늦게 온 응답이 화면을 덮지 않게 한다.
 * 조회(GET)라 자동 재시도는 하지 않고, 실패하면 "다시 시도"로 reloadKey 를 올려 다시 부른다.
 */
export function useCustomerList(q: string, page: number, reloadKey: number): CustomerListState {
  // 응답을 요청 조건(key)과 함께 저장하고, 현재 조건과 다르면 "불러오는 중"으로 본다(effect 안에서 동기 setState 없음).
  const key = JSON.stringify([q, page, reloadKey])
  const [result, setResult] = useState<Settled | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    searchCustomers({ q, page }, controller.signal)
      .then((data) => setResult({ key, ok: true, data }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setResult({ key, ok: false, error })
      })
    return () => controller.abort()
  }, [key, q, page])

  if (result?.key === key) {
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', error: result.error }
  }
  // 다시 불러오는 동안에는 직전 목록을 흐리게 유지한다(깜빡임 방지). 실패했던 목록(이전 잔액)은 남기지 않는다
  return { status: 'loading', data: result?.ok ? result.data : undefined }
}

type Settled =
  | { key: string; ok: true; data: CustomerListResponse }
  | { key: string; ok: false; error: unknown }
