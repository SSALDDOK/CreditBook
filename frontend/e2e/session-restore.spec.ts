import { expect, test } from '@playwright/test'
import { createCustomer, getCustomerDetail, loginByApi } from './support/api'
import { staffAccount } from './support/credentials'
import { CustomerDetailPage } from './pages/CustomerDetailPage'
import { ChargeDialog } from './pages/ChargeDialog'

// 세션 만료 후 재로그인(REQ-17) E2E — 충전 모달로 대표 확인(TC-46)
// 가장 중요한 것: 재로그인 후 금액만 채워 두고 자동으로 다시 보내지 않는다(중복 충전 방지)
test('세션 만료 후 재로그인 — 작성 중이던 금액 복구, 자동 전송 없음 E2E @TC-46', async ({ page }) => {
  // 사전조건 — STAFF 로그인, 잔액 0원 고객 1명 (API로 빠르게 준비)
  const account = staffAccount()
  await loginByApi(page.request, account)
  const customer = await createCustomer(page.request, 'E2E재로그인')

  //고객 상세 화면 , 충전 모달
  const detail = new CustomerDetailPage(page)
  const dialog = new ChargeDialog(page)
  await detail.goto(customer.id)
  await detail.expectBalance(0)

  await test.step('1. 고객 상세 → 충전 모달에 30,000원 입력 → 확인 화면', async () => {
    //고객 상세 - 충전 버튼 클릭 → 30,000원 입력 → 충전 클릭
    await detail.chargeButton.click()
    await dialog.expectStep('input')
    await dialog.fillAmount(30_000)
    await dialog.nextButton.click()
    //충전 모달 - 확인 화면 (아직 보내지 않음)
    await dialog.expectStep('confirm')
  })

  await test.step('2. 세션 쿠키 삭제(만료 흉내) → [확인] → 로그인 화면, 만료 안내', async () => {
    //세션 쿠키만 지움 → 서버 입장에서는 로그인 안 된 요청
    await page.context().clearCookies({ name: 'CB_SESSION' })
    //충전 모달 - 확인 클릭 → 서버가 401 → 로그인 화면으로
    await dialog.confirmButton.click()
    await expect(page).toHaveURL(/\/login\?/)
    //로그인 화면 - 세션 만료 안내
    await expect(page.getByTestId('login-expired-notice')).toHaveText('로그인이 만료되었습니다. 다시 로그인해 주세요')
  })

  await test.step('3. 같은 STAFF로 재로그인 → 충전 모달 입력 화면에 30,000원, 자동 전송 없음', async () => {
    //로그인 화면 - 같은 직원으로 로그인
    await page.getByLabel('아이디').fill(account.loginId)
    await page.getByLabel('비밀번호').fill(account.password)
    await page.getByRole('button', { name: '로그인' }).click()

    //원래 보던 고객 상세로 돌아옴
    await expect(page).toHaveURL(new RegExp(`/customers/${customer.id}$`))
    //충전 모달 - 입력 화면으로 다시 열림, 30,000원 채워져 있음 + 복구 안내
    await dialog.expectStep('input')
    await expect(dialog.amountInput).toHaveValue('30,000')
    await expect(dialog.restoredBanner).toHaveText('입력하던 금액을 불러왔습니다. 확인 후 충전하세요.')

    //고객 상세 화면 확인 - 잔액 0원 그대로 (자동으로 다시 보내지 않음)
    await detail.expectBalance(0)
    //서버쪽 확인. 잔액 0원, 거래 없음
    const before = await getCustomerDetail(page.request, customer.id)
    expect(before.balance).toBe(0)
    expect(before.recentEntries).toHaveLength(0)
  })

  await test.step('4. [충전] → [확인] 다시 → 충전 성공, 잔액 30,000원, 충전 거래 1건만', async () => {
    //충전 모달 - 충전 클릭 → 확인 화면 → 확인 클릭
    await dialog.nextButton.click()
    await dialog.expectStep('confirm')
    await dialog.confirmButton.click()
    //모달 화면 닫힘
    await expect(dialog.root).toBeHidden()
    //고객 상세 화면 확인
    await detail.expectBalance(30_000)

    //서버쪽 확인. 잔액 30,000원, 충전 1건만 (만료 전 요청이 몰래 처리되지 않았다)
    const after = await getCustomerDetail(page.request, customer.id)
    expect(after.balance).toBe(30_000)
    expect(after.recentEntries.map((e) => e.type)).toEqual(['CHARGE'])
  })
})
