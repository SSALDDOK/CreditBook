import { useEffect, useState } from 'react'
import { listEmployees, type EmployeeListResponse } from '@/api/employees'

export type EmployeeListState =
  | { status: 'loading'; data?: EmployeeListResponse }
  | { status: 'success'; data: EmployeeListResponse }
  | { status: 'error'; error: unknown }

type Settled = { key: number; ok: true; data: EmployeeListResponse } | { key: number; ok: false; error: unknown }

/**
 * 직원 목록을 조회한다. enabled 가 false 면(사장이 아니면) API 를 부르지 않는다.
 * 조회(GET)라 자동 재시도는 하지 않고, 실패하면 [다시 시도]·추가·비활성화 뒤에 reloadKey 를 올려 다시 부른다.
 * 다시 불러오는 동안에는 직전 목록을 흐리게 유지한다(깜빡임 방지).
 */
export function useEmployeeList(enabled: boolean, reloadKey: number): EmployeeListState {
  const [result, setResult] = useState<Settled | null>(null)

  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    listEmployees(controller.signal)
      .then((data) => setResult({ key: reloadKey, ok: true, data }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setResult({ key: reloadKey, ok: false, error })
      })
    return () => controller.abort()
  }, [enabled, reloadKey])

  if (result?.key === reloadKey) {
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', error: result.error }
  }
  return { status: 'loading', data: result?.ok ? result.data : undefined }
}
