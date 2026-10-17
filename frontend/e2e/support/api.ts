import { expect, type APIRequestContext } from '@playwright/test'
import type { E2eAccount } from './credentials'

// 테스트 준비·확인용 API 도우미.
// 화면으로 할 필요가 없는 일(고객 만들기, 미리 충전해 두기, 마지막에 서버 값 확인)은 API로 한다 — 빠르고 덜 흔들린다.
// page.request 를 넘기면 브라우저와 쿠키를 같이 쓴다 → 여기서 로그인하면 화면도 로그인된 상태가 된다.

/** API로 로그인. 세션 쿠키(CB_SESSION)가 request 의 쿠키 저장소에 들어간다 */
export async function loginByApi(request: APIRequestContext, account: E2eAccount): Promise<void> {
  const res = await request.post('/api/auth/login', { data: account })
  expect(res.status(), '로그인 API').toBe(200)
}

/** 테스트마다 겹치지 않는 연락처 (010 + 8자리) — 같은 DB를 여러 테스트가 같이 쓰므로 */
export function uniquePhone(): string {
  const digits = `${Date.now()}${Math.floor(Math.random() * 1000)}`.slice(-8)
  return `010${digits}`
}

export type TestCustomer = { id: string; name: string; phone: string }

/** API로 고객 등록 (잔액 0원). 이름 뒤에 짧은 꼬리를 붙여 겹치지 않게 한다 */
export async function createCustomer(request: APIRequestContext, namePrefix: string): Promise<TestCustomer> {
  const name = `${namePrefix}${Math.random().toString(36).slice(2, 6)}` // 이름은 20자 이하
  const phone = uniquePhone()
  const res = await request.post('/api/customers', {
    // 같은 번호 확인은 이 준비 단계에서 볼 게 아니라서 확인 표시를 붙여 둔다(우연히 번호가 겹쳐도 준비가 실패하지 않게)
    data: { name, phone, confirmDuplicatePhone: true },
  })
  expect(res.status(), '고객 등록 API').toBe(201)
  const body = await res.json()
  return { id: body.id, name, phone }
}

/** API로 충전 — 멱등키는 매번 새로 */
export async function chargeByApi(request: APIRequestContext, customerId: string, amount: number): Promise<void> {
  const res = await request.post(`/api/customers/${customerId}/charges`, {
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    data: { amount },
  })
  expect(res.status(), '충전 API').toBe(201)
}

export type CustomerDetail = {
  balance: number
  recentEntries: Array<{ id: string; type: string; signedAmount: number; balanceAfter: number }>
}

/** 고객 상세 API — 화면에 보이는 값이 아니라 서버가 가진 값으로 확인할 때 */
export async function getCustomerDetail(request: APIRequestContext, customerId: string): Promise<CustomerDetail> {
  const res = await request.get(`/api/customers/${customerId}`)
  expect(res.status(), '고객 상세 API').toBe(200)
  return res.json()
}
