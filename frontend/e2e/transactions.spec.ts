import { expect, test } from '@playwright/test'
import { chargeByApi, createCustomer, getCustomerDetail, loginByApi, useByApi } from './support/api'
import { staffAccount } from './support/credentials'

// 거래 이력(SCR-8) E2E — 충전 취소 거절(TC-51)
// 거절 규칙 자체(잔액 < 충전액이면 거절)는 통합 TC-43이 본다. 여기서는 거절됐을 때 화면이 어떻게 보이는지만 본다
test('거래 이력 — 충전 취소 거절이 사유 창 안에 표시되고 장부·잔액은 그대로 E2E @TC-51', async ({ page }) => {
  // 사전조건 — STAFF 로그인, 오늘 30,000원 충전 → 10,000원 사용(잔액 20,000원) 고객 1명 (API로 빠르게 준비)
  // 지금 충전하니 오늘(한국 날짜) 건이라 시계를 옮기지 않아도 된다
  await loginByApi(page.request, staffAccount())
  const customer = await createCustomer(page.request, 'E2E충전취소거절')
  await chargeByApi(page.request, customer.id, 30_000)
  await useByApi(page.request, customer.id, 10_000)

  //거래 이력 화면 - 주소로 바로 이동
  await page.goto(`/customers/${customer.id}/transactions`)
  const transactionsPage = page.getByTestId('transactions-page')
  await expect(transactionsPage).toHaveAttribute('data-state', 'ready')

  //거래 이력 화면 - 충전·사용 2건으로 시작
  const rows = transactionsPage.getByTestId('transaction-row')
  const chargeRow = transactionsPage.locator('[data-testid="transaction-row"][data-type="CHARGE"]')
  await expect(rows).toHaveCount(2)

  //충전 취소 사유 창
  const cancelDialog = page.getByTestId('cancel-dialog')

  await test.step('1. 충전 행 [취소] → 사유 입력 → [취소하기]', async () => {
    //거래 이력 화면 - 충전 행 취소 버튼 클릭
    await chargeRow.getByRole('button', { name: '충전 30,000원 취소' }).click()
    //충전 취소 창이 열림 (사용 취소 창이 아닌지 확인)
    await expect(cancelDialog).toHaveAttribute('data-kind', 'charge')

    //충전 취소 창 - 사유 입력 → 취소하기 클릭
    await cancelDialog.getByTestId('cancel-reason-input').fill('금액 잘못 입력')
    await cancelDialog.getByTestId('cancel-confirm').click()
  })

  await test.step('2. 창이 닫히지 않고 빨간 상자에 거절 문구·숫자, [취소하기] 비활성', async () => {
    //서버가 거절 → 창 상태가 rejected 로 바뀜, 창은 닫히지 않음
    await expect(cancelDialog).toHaveAttribute('data-state', 'rejected')
    await expect(cancelDialog).toBeVisible()

    //빨간 상자 - 거절 문구
    const error = cancelDialog.getByTestId('cancel-error')
    await expect(error).toContainText('잔액이 부족해 취소할 수 없어요. 먼저 사용 건을 취소해 주세요.')
    //빨간 상자 - 현재 잔액·취소하려는 충전 금액
    await expect(cancelDialog.getByTestId('cancel-error-detail')).toHaveText('현재 잔액 20,000원 · 취소하려는 충전 30,000원')
    //사유 칸 아래 안내 - 장부·잔액 안 바뀜
    await expect(cancelDialog.getByTestId('cancel-note')).toHaveText('장부와 잔액은 바뀌지 않았습니다.')

    //취소하기 버튼 비활성화, 닫기 버튼은 누를 수 있음
    await expect(cancelDialog.getByTestId('cancel-confirm')).toBeDisabled()
    await expect(cancelDialog.getByTestId('cancel-close')).toBeEnabled()
  })

  await test.step('3. [닫기] → 행 수·잔액 20,000원 그대로, 충전 행에 취소됨 없음', async () => {
    //충전 취소 창 - 닫기 클릭 → 창 닫힘
    await cancelDialog.getByTestId('cancel-close').click()
    await expect(cancelDialog).toBeHidden()

    //거래 이력 화면 - 충전 취소 행이 새로 생기지 않음 (2건 그대로)
    await expect(rows).toHaveCount(2)
    //거래 이력 화면 - 잔액 20,000원 그대로
    await expect(transactionsPage.getByTestId('transactions-customer-balance')).toHaveAttribute('data-balance', '20000')
    //충전 행 - 취소 안 됨, '취소됨' 표시 없음
    await expect(chargeRow).toHaveAttribute('data-cancelled', 'false')
    await expect(chargeRow).not.toContainText('취소됨')

    //서버쪽 확인.
    const after = await getCustomerDetail(page.request, customer.id)
    expect(after.balance).toBe(20_000)
  })
})
