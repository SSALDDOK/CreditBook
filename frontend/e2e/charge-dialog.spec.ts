import { expect, test } from '@playwright/test'
import { chargeByApi, createCustomer, getCustomerDetail, loginByApi } from './support/api'
import { staffAccount } from './support/credentials'
import { ChargeDialog } from './pages/ChargeDialog'
import { CustomerDetailPage } from './pages/CustomerDetailPage'

// TC-50 (REQ-6, SCR-6·SCR-5) — 화면에만 있는 동작을 본다: 상한 초과는 입력 화면에서 막히고, 확인 화면을 거쳐야 기록된다.
// 금액 규칙 자체(300,000 허용·300,001 거부)는 단위 TC-5가 증명하므로 여기서는 화면 흐름만.
test('충전 모달 — 상한 초과는 입력 화면에서 멈추고, 확인 화면을 거쳐야 기록된다 @TC-50', async ({ page }) => {
  // 사전조건 — STAFF 로그인, 잔액 10,000원 고객 1명 (화면이 아니라 API로 빠르게 준비)
  await loginByApi(page.request, staffAccount())
  const customer = await createCustomer(page.request, 'E2E충전')
  await chargeByApi(page.request, customer.id, 10_000)

  const detail = new CustomerDetailPage(page)
  const dialog = new ChargeDialog(page)
  await detail.goto(customer.id)
  await detail.expectBalance(10_000)

  await test.step('1. 300,001원 입력 → 한도 안내, [충전] 비활성', async () => {
    await detail.chargeButton.click()
    await dialog.expectStep('input')
    await dialog.fillAmount(300_001)
    // 치는 즉시 칸 아래 안내가 뜨고 확인 화면으로 갈 수 없다
    await expect(dialog.amountError).toHaveText('한 번에 충전할 수 있는 금액은 300,000원까지입니다.')
    await expect(dialog.nextButton).toBeDisabled()
  })

  await test.step('2. 300,000원으로 고치고 [충전] → 확인 화면, 아직 기록 없음', async () => {
    await dialog.fillAmount(300_000)
    await expect(dialog.amountError).toBeHidden()
    await dialog.nextButton.click()
    await dialog.expectStep('confirm')
    // 제목·부제는 역할(dialog)의 이름·설명으로 읽는다 — 화면 낭독기가 읽는 것과 같은 값
    await expect(page.getByRole('dialog', { name: '충전 내용 확인' })).toBeVisible()
    await expect(dialog.root).toContainText(`${customer.name} 고객 · 아래 내용으로 충전합니다`)
    await expect(dialog.currentBalance).toHaveText('10,000원')
    await expect(dialog.expectedBalance).toHaveText('310,000원')
    await expect(dialog.expectedKorean).toHaveText('삼십일만원으로 늘어납니다')
    // '상세 내용'은 접힌 상태
    await expect(dialog.detailsToggle).toHaveAttribute('aria-expanded', 'false')
    await expect(dialog.details).toBeHidden()
    // 확인 화면까지 왔어도 서버에는 아직 아무 거래도 없다 (사전조건의 충전 1건만)
    const before = await getCustomerDetail(page.request, customer.id)
    expect(before.balance).toBe(10_000)
    expect(before.recentEntries).toHaveLength(1)
  })

  await test.step('3. [이전] → 입력 화면, 300,000원 그대로', async () => {
    await dialog.backButton.click()
    await dialog.expectStep('input')
    await expect(dialog.amountInput).toHaveValue('300,000')
  })

  await test.step('4. [충전] → [확인] → 모달 닫힘, 잔액 310,000원, 충전 1건 추가', async () => {
    await dialog.nextButton.click()
    await dialog.expectStep('confirm')
    await dialog.confirmButton.click()
    await expect(dialog.root).toBeHidden()
    // 화면 잔액 — 새로고침 없이 바뀐다
    await detail.expectBalance(310_000)
    // 서버 값으로 다시 확인 (TC 기대결과: 잔액은 GET /api/customers/{id} 응답으로)
    const after = await getCustomerDetail(page.request, customer.id)
    expect(after.balance).toBe(310_000)
    expect(after.recentEntries).toHaveLength(2)
    expect(after.recentEntries[0]).toMatchObject({ type: 'CHARGE', signedAmount: 300_000, balanceAfter: 310_000 })
  })
})
