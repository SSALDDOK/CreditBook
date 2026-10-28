import { expect, test } from '@playwright/test'
import { chargeByApi, createCustomer, getCustomerDetail, loginByApi } from './support/api'
import { staffAccount } from './support/credentials'
import { CustomerDetailPage } from './pages/CustomerDetailPage'
import { UseDialog } from './pages/UseDialog'

// 사용 모달(SCR-7) E2E — 잔액 부족(TC-14)
test('잔액 부족 사용 시도 시 오류 메시지 노출 E2E @TC-14', async ({ page }) => {
  // 사전조건 — STAFF 로그인, 잔액 3,000원 고객 1명 (API로 준비)
  await loginByApi(page.request, staffAccount())
  const customer = await createCustomer(page.request, 'E2E잔액부족')
  await chargeByApi(page.request, customer.id, 3_000)

  const detail = new CustomerDetailPage(page)
  const useDialog = new UseDialog(page)
  await detail.goto(customer.id)
  await detail.expectBalance(3_000)

  await test.step('1. [사용] → 5,000원 입력 → 부족 안내, [사용] 비활성', async () => {
    await detail.useButton.click()
    await useDialog.expectStep('input')
    await useDialog.fillAmount(5_000)

    // 치는 즉시 부족액과 현재 잔액이 같이 뜬다 — 문장은 서버 INSUFFICIENT_BALANCE 안내와 같다
    await expect(useDialog.amountError).toHaveText('잔액이 2,000원 부족합니다 (현재 잔액 3,000원)')
    await expect(useDialog.nextButton).toBeDisabled()
  })

  await test.step('2. 모달이 닫히지 않고 금액을 고칠 수 있다 — 3,000원(잔액과 같음)이면 다시 진행 가능', async () => {
    await expect(useDialog.root).toBeVisible()
    await expect(useDialog.amountInput).toBeEditable()

    // 잔액과 같은 금액은 허용(잔액 0원이 됨) — 부족 안내가 사라지고 [사용]이 다시 켜진다
    await useDialog.fillAmount(3_000)
    await expect(useDialog.amountError).toBeHidden()
    await expect(useDialog.nextButton).toBeEnabled()
  })

  await test.step('3. 보내지 않고 닫기 → 잔액 3,000원 그대로, 거래도 충전 1건뿐', async () => {
    await useDialog.cancelButton.click()
    await expect(useDialog.root).toBeHidden()
    await detail.expectBalance(3_000)

    // 화면 값이 아니라 서버 값으로도 — 사용 거래가 생기지 않았다
    const after = await getCustomerDetail(page.request, customer.id)
    expect(after.balance).toBe(3_000)
    expect(after.recentEntries.map((e) => e.type)).toEqual(['CHARGE'])
  })
})
