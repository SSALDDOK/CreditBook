import { expect, test } from '@playwright/test'
import { getCustomerDetail, uniquePhone } from './support/api'
import { staffAccount } from './support/credentials'
import { CustomerDetailPage } from './pages/CustomerDetailPage'
import { ChargeDialog } from './pages/ChargeDialog'

// TC-13 주경로(해피 패스) — 직원이 하루에 가장 많이 하는 일을 처음부터 끝까지 화면으로만 한다.
// 이 테스트가 깨지면 배포하지 않는다. 금액 규칙은 단위·통합이 증명하므로 여기서는 화면이 이어지는지와 잔액만 본다.
test('[주경로] 로그인 → 고객 등록 → 충전 → 사용 → 이력 확인 E2E @TC-13', async ({ page }) => {
  const account = staffAccount()
  const phone = uniquePhone() // 같은 DB를 여러 테스트가 같이 써서 연락처는 매번 다르게 — 같으면 중복 확인 창이 끼어든다
  const detail = new CustomerDetailPage(page)
  const chargeDialog = new ChargeDialog(page)
  let customerId = ''

  await test.step('1. /login 에서 STAFF 로그인 → 고객 목록', async () => {
    // 준비가 아니라 주경로의 첫 단계라 API 로그인 대신 화면에서 한다
    await page.goto('/login')
    await page.getByLabel('아이디').fill(account.loginId)
    await page.getByLabel('비밀번호').fill(account.password)
    await page.getByRole('button', { name: '로그인' }).click()
    // /login 으로 바로 들어오면 로그인 뒤 기본 화면(고객 목록)으로 간다
    await expect(page).toHaveURL(/\/customers$/)
    await expect(page.getByTestId('customer-list')).toBeVisible()
  })

  await test.step('2. [고객 등록] → 이름·연락처 입력 → [등록] → 고객 상세', async () => {
    await page.getByTestId('customer-register-button').click()
    await expect(page).toHaveURL(/\/customers\/new$/)
    await page.getByLabel('이름').fill('테스트고객')
    await page.getByLabel('연락처').fill(phone)
    // '등록 후 바로 충전하기'는 기본으로 켜져 있다 — 직원이 실제로 쓰는 그대로 둔다
    await expect(page.getByTestId('customer-register-charge-after')).toBeChecked()
    await page.getByTestId('customer-register-submit').click()

    // 등록되면 그 고객의 상세로 간다. 주소에서 고객 id를 꺼내 마지막에 서버 값을 확인할 때 쓴다
    await expect(page).toHaveURL(/\/customers\/[0-9a-f-]{36}$/)
    customerId = new URL(page.url()).pathname.split('/').pop() ?? ''
    await expect(detail.root).toHaveAttribute('data-state', 'ready')
    await expect(detail.root).toContainText('테스트고객')
    await detail.expectBalance(0)
  })

  await test.step('3. 충전 50,000원 → 확인 → 잔액 50,000원', async () => {
    // '등록 후 바로 충전하기' 덕분에 충전 모달이 이미 열려 있다 — [충전]을 누르지 않는다
    await chargeDialog.expectStep('input')
    await chargeDialog.fillAmount(50_000)
    await chargeDialog.nextButton.click()
    await chargeDialog.expectStep('confirm')
    await chargeDialog.confirmButton.click()
    await expect(chargeDialog.root).toBeHidden()
    await detail.expectBalance(50_000)
  })

  await test.step('4. [사용] → 4,500원 → 확인 → 새로고침 없이 잔액 45,500원', async () => {
    await detail.useButton.click()
    const useDialog = page.getByTestId('use-dialog')
    await expect(useDialog).toHaveAttribute('data-step', 'input')
    await useDialog.getByTestId('use-amount-input').fill('4500')
    await useDialog.getByTestId('use-next').click()
    await expect(useDialog).toHaveAttribute('data-step', 'confirm')
    await useDialog.getByTestId('use-confirm').click()
    await expect(useDialog).toBeHidden()
    await detail.expectBalance(45_500)
  })

  await test.step('5. "전체 이력 보기" → 최신순 USE·CHARGE 2건, 거래 후 잔액 45,500 / 50,000', async () => {
    await detail.historyLink.click()
    const transactionsPage = page.getByTestId('transactions-page')
    await expect(transactionsPage).toHaveAttribute('data-state', 'ready')

    // 최신순이라 위가 사용, 아래가 충전
    const rows = transactionsPage.getByTestId('transaction-row')
    await expect(rows).toHaveCount(2)
    await expect(rows.nth(0)).toHaveAttribute('data-type', 'USE')
    await expect(rows.nth(0).getByTestId('transaction-balance-after')).toHaveText('45,500원')
    await expect(rows.nth(1)).toHaveAttribute('data-type', 'CHARGE')
    await expect(rows.nth(1).getByTestId('transaction-balance-after')).toHaveText('50,000원')

    // 화면에 보인 잔액이 서버 값과 같은지 마지막에 한 번 더
    const after = await getCustomerDetail(page.request, customerId)
    expect(after.balance).toBe(45_500)
  })
})
