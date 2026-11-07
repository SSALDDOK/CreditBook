import { expect, test } from '@playwright/test'
import { chargeByApi, createCustomer, loginByApi } from './support/api'
import { staffAccount } from './support/credentials'
import { CustomerDetailPage } from './pages/CustomerDetailPage'
import { UseDialog } from './pages/UseDialog'
import { ChargeDialog } from './pages/ChargeDialog'

// TC-59 충전·사용 창을 다시 열어도 금액 칸에 포커스가 가고 바로 입력된다(SCR-6·7, BUG-6 재발 방지)
// 기존 자동 확인은 매번 페이지를 새로 열어 첫 열기만 봐서 놓쳤다 — 새로고침 없이 두 번째 열기까지 본다
// 금액은 fill 로 넣지 않는다 — fill 은 스스로 칸을 눌러 포커스를 주므로 버그가 있어도 통과한다
test('충전·사용 창을 다시 열어도 금액 칸에 포커스가 가고 바로 입력된다 E2E @TC-59', async ({ page }) => {
  // 사전조건 — STAFF 로그인, [사용]이 켜지도록 잔액이 있는 고객 1명 (API로 준비)
  await loginByApi(page.request, staffAccount())
  const customer = await createCustomer(page.request, 'E2E포커스')
  await chargeByApi(page.request, customer.id, 10_000)

  const detail = new CustomerDetailPage(page)
  const chargeDialog = new ChargeDialog(page)
  const useDialog = new UseDialog(page)

  await test.step('1. 고객 상세에서 [충전] 창을 열고 닫는다', async () => {
    // 고객 상세로 이동
    await detail.goto(customer.id)
    await detail.expectBalance(10_000)

    // [충전] → 창이 열림 → 닫기
    await detail.chargeButton.click()
    await expect(chargeDialog.root).toBeVisible()
    await chargeDialog.root.getByTestId('charge-cancel').click()
    await expect(chargeDialog.root).toBeHidden()
  })

  await test.step('2. 새로고침 없이 [충전]을 다시 열고, 아무 곳도 누르지 않은 채 숫자를 입력한다', async () => {
    // [충전]
    await detail.chargeButton.click()
    await expect(chargeDialog.root).toBeVisible()
    // 기대결과: 금액 칸에 포커스
    await expect(chargeDialog.amountInput).toBeFocused()
    // 키보드로 숫자 입력 → 기대결과: 입력한 숫자가 금액 칸에 보인다
    await page.keyboard.type('5000') //특정 칸을 지정하지 않고 지금 포커스가 있는 곳에 입력 시도
    await expect(chargeDialog.amountInput).toHaveValue('5,000')
    // 창 닫기
    await chargeDialog.root.getByTestId('charge-cancel').click()
    await expect(chargeDialog.root).toBeHidden()
  })

  await test.step('3. [사용]도 같은 순서로 한다 — 열고 닫은 뒤 다시 열어 숫자 입력', async () => {
    // [사용] → 닫기
    await detail.useButton.click()
    await expect(useDialog.root).toBeVisible()
    await useDialog.root.getByTestId('use-cancel').click()
    await expect(useDialog.root).toBeHidden()

    // [사용] 다시 → 기대결과: 금액 칸에 포커스
    await detail.useButton.click()
    await expect(useDialog.root).toBeVisible()
    await expect(useDialog.amountInput).toBeFocused()

    // 키보드로 숫자 입력 → 기대결과: 입력한 숫자가 금액 칸에 보인다
    await page.keyboard.type('5000') //특정 칸을 지정하지 않고 지금 포커스가 있는 곳에 입력 시도
    await expect(useDialog.amountInput).toHaveValue('5,000')
    await useDialog.root.getByTestId('use-cancel').click()
    await expect(useDialog.root).toBeHidden()
  })
})
