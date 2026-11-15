import { expect, test } from '@playwright/test'
import { CustomerDetailPage } from './pages/CustomerDetailPage'
import { createCustomer, loginByApi } from './support/api'
import { staffAccount } from './support/credentials'

// TC-16 STAFF 계정 - 고객 비활성화 메뉴 비노출 및 주소 직접 접근(SCR-5·9, REQ-16)
// 화면에서 비활성화를 숨기는 것만 본다 - STAFF의 비활성화 API 403은 서버 통합 테스트(TC-8)가 증명한다
// 범위 밖: ADMIN의 비활성화 확인 창, 잔액이 남은 고객 거절
test('STAFF는 고객 상세·수정 화면에 비활성화가 보이지 않고 주소로 들어가도 빈 화면이 아니다 E2E @TC-16', async ({ page }) => {
  // 어느 단계에서든 화면 스크립트 오류(잡히지 않은 JS 오류)가 나면 모아 두었다가 마지막에 확인한다
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))

  // 사전조건 - 직원(STAFF) 로그인, 잔액 0원 고객 1명(비활성화 조건을 채운 고객이라도 STAFF에게는 안 보여야 한다)
  await loginByApi(page.request, staffAccount())
  const customer = await createCustomer(page.request, 'E2E권한')
  const detail = new CustomerDetailPage(page)

  await test.step('1·2. 고객 상세에 비활성화 메뉴가 없고 [정보 수정]은 보인다', async () => {
    await detail.goto(customer.id)
    // 기대결과: 비활성화 버튼은 없고, 수정 화면으로 가는 링크는 보인다(권한 표에서 고객 수정은 STAFF도 O)
    await expect(page.getByTestId('customer-deactivate-button')).toHaveCount(0)
    await expect(detail.editLink).toBeVisible()
  })

  await test.step('3. 주소창에 수정 화면 주소를 직접 넣어 들어간다', async () => {
    await page.goto(`/customers/${customer.id}/edit`)
    // 기대결과: 빈 화면이 아니라 수정 폼이 다 그려진다
    await expect(page.getByTestId('customer-edit')).toHaveAttribute('data-state', 'ready')
    await expect(page.getByTestId('customer-edit-form-card')).toBeVisible()
    await expect(page.getByTestId('customer-edit-name-input')).toHaveValue(customer.name)
    // 기대결과: 위험 구역(비활성화 영역)은 숨김이 아니라 DOM 에 아예 없다
    await expect(page.getByTestId('customer-edit-danger-zone')).toHaveCount(0)
    await expect(page.getByTestId('customer-deactivate-button')).toHaveCount(0)
  })

  // 기대결과: 어느 단계에서도 JS 오류가 없다
  expect(errors, 'JS 오류').toEqual([])
})
