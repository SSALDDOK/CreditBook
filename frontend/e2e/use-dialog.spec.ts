import { expect, test } from '@playwright/test'
import { chargeByApi, createCustomer, getCustomerDetail, loginByApi } from './support/api'
import { staffAccount } from './support/credentials'
import { CustomerDetailPage } from './pages/CustomerDetailPage'
import { UseDialog } from './pages/UseDialog'

// 사용 모달(SCR-7) E2E — 잔액 부족(TC-14), 메모 문구 칩(TC-28)
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

test('주문 메모 문구 칩 — 채움 · 이어붙기 · 토글 · 길이 상한 E2E @TC-28', async ({ page }) => {
  // 사전조건 — STAFF 로그인, 잔액 10,000원 고객 1명 (API로 준비)
  await loginByApi(page.request, staffAccount())
  const customer = await createCustomer(page.request, 'E2E메모칩')
  await chargeByApi(page.request, customer.id, 10_000)

  const detail = new CustomerDetailPage(page)
  const useDialog = new UseDialog(page)
  await detail.goto(customer.id)
  await detail.useButton.click()
  await useDialog.expectStep('input')

  await test.step('2~4. 음료 → 베이커리 → 음료 다시: 채움 · 쉼표로 이어붙기 · 토글로 빠짐', async () => {
    await useDialog.memoChip('음료').click()
    await expect(useDialog.memoInput).toHaveValue('음료')
    await expect(useDialog.memoChip('음료')).toHaveAttribute('aria-pressed', 'true')

    await useDialog.memoChip('베이커리').click()
    await expect(useDialog.memoInput).toHaveValue('음료, 베이커리')

    // 같은 칩을 다시 누르면 빠진다 — 쉼표가 앞에 남으면(', 베이커리') 전형적인 버그
    await useDialog.memoChip('음료').click()
    await expect(useDialog.memoInput).toHaveValue('베이커리')
    await expect(useDialog.memoChip('음료')).toHaveAttribute('aria-pressed', 'false')
  })

  await test.step('5. 칩을 누른 뒤에도 직접 타이핑으로 고칠 수 있다', async () => {
    // 칸 끝으로 가서 이어 친다 (fill 은 통째로 바꾸므로 '이어서 고치기'가 아니다)
    await useDialog.memoInput.press('End')
    await useDialog.memoInput.pressSequentially(', 단골')
    await expect(useDialog.memoInput).toHaveValue('베이커리, 단골')
    // 직접 친 글자가 있어도 칩 표시는 그대로
    await expect(useDialog.memoChip('베이커리')).toHaveAttribute('aria-pressed', 'true')
  })

  // 칩은 누를 때마다 붙었다 빠졌다 하므로 칩만으로는 200자를 채울 수 없다 → 195자를 미리 채우고 칩을 붙인다
  const filler = '가'.repeat(195)
  const memoAt199 = `${filler}, 원두` // 195 + ', 원두'(4자) = 199자
  const memoAt200 = `${memoAt199}끝` // 200자

  await test.step('6. 200자 상한 — 넘치는 칩은 붙지 않고 안내, 직접 입력도 200자에서 멈춘다', async () => {
    await useDialog.memoInput.fill(filler)
    await useDialog.memoChip('원두').click()
    await expect(useDialog.memoInput).toHaveValue(memoAt199)

    // 디저트를 붙이면 199 + ', 디저트'(5자) = 204자 → 붙이지 않고 그대로, 200자 상한 안내만 확인
    // (칩 이름이 들어간 문장 전체는 화면설계에 없는 구현 문구라 고정하지 않는다)
    await useDialog.memoChip('디저트').click()
    await expect(useDialog.memoInput).toHaveValue(memoAt199)
    await expect(useDialog.memoNotice).toContainText('메모는 200자까지 입력할 수 있')

    // 직접 치면 200자에서 멈춘다 — '끝' 다음 '더'는 들어가지 않는다
    await useDialog.memoInput.press('End')
    await useDialog.memoInput.pressSequentially('끝더')
    await expect(useDialog.memoInput).toHaveValue(memoAt200)
    await expect(useDialog.memoNotice).toHaveText('메모는 200자까지 입력할 수 있습니다.')
  })

  await test.step('저장 → 거래 이력 메모 칸에 같은 200자가 보인다 (잘리거나 넘치지 않음)', async () => {
    await useDialog.fillAmount(1_000)
    await useDialog.nextButton.click()
    await useDialog.expectStep('confirm')
    await useDialog.confirmButton.click()
    await expect(useDialog.root).toBeHidden()
    await detail.expectBalance(9_000)

    await detail.historyLink.click()
    const transactionsPage = page.getByTestId('transactions-page')
    await expect(transactionsPage).toHaveAttribute('data-state', 'ready')
    const useRow = transactionsPage.locator('[data-testid="transaction-row"][data-type="USE"]')
    // 칸이 좁아 화면에는 말줄임(…)으로 보이지만 글자는 200자 그대로 들어 있다
    await expect(useRow.getByTestId('transaction-memo')).toHaveText(memoAt200)
  })
})
