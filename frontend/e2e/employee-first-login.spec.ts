import { expect, test } from '@playwright/test'
import { loginByApi, randomPassword, uniqueLoginId } from './support/api'
import { adminAccount } from './support/credentials'

// TC-58 직원 추가 후 첫 로그인 비밀번호 변경(SCR-12·13, REQ-30)
// 화면에만 있는 동작을 본다 — 서버 규칙은 TC-26·27·56이 증명한다
// 범위 밖: STAFF의 403 카드, 비밀번호 초기화·비활성화 확인 창
test('직원 추가 후 첫 로그인에 비밀번호를 바꾸면 고객 목록으로 간다 E2E @TC-58', async ({ page }) => {
  // 사전조건 — 사장 로그인, 겹치지 않는 직원 아이디와 새 비밀번호
  await loginByApi(page.request, adminAccount())
  const staffName = 'E2E직원'
  const staffLoginId = uniqueLoginId('e2e')
  const newPassword = randomPassword()
  // 절차 2에서 받아 절차 3·4에 쓴다
  let initialPassword = ''

  await test.step('1. 사장이 직원 관리에서 이름·아이디로 직원을 추가한다', async () => {
    // 직원 관리 화면으로 이동
    await page.goto(`/settings/employees`)
    // 직원 추가 창에 staffName, staffLoginId 입력 후 추가
    await page.getByTestId('add-employee-button').click()
    await page.getByTestId('add-employee-name').fill(staffName)
    await page.getByTestId('add-employee-login-id').fill(staffLoginId)
    await page.getByTestId('add-employee-submit').click()
  })

  await test.step('2. 표시된 초기 비밀번호를 받는다', async () => {
    // 화면에 나온 초기 비밀번호를 initialPassword 에 담는다
    const initialPasswordDialog = page.getByTestId('initial-password-dialog')
    initialPassword = await initialPasswordDialog.getByTestId('initial-password-value').innerText()
    // 안내 창 닫기
    await initialPasswordDialog.getByTestId('initial-password-close').click()
  })

  await test.step('3. 로그아웃 후 초기 비밀번호로 로그인 → 비밀번호 변경 화면이 나온다', async () => {
    // 로그아웃
    await page.getByTestId('logout-button').click()
    // staffLoginId, initialPassword 로 로그인
    await page.getByTestId('login-id-input').fill(staffLoginId)
    await page.getByTestId('login-password-input').fill(initialPassword)
    await page.getByTestId('login-submit').click()
    // 기대결과: 고객 목록이 아니라 비밀번호 변경 화면
    await expect(page).toHaveURL(/\/password/)
    await expect(page.getByTestId('password-page')).toHaveAttribute('data-mode', 'forced')
  })

  await test.step('4. 현재·새·확인 비밀번호 입력 후 [변경하고 시작하기] → 고객 목록으로 이동한다', async () => {
    // initialPassword, newPassword, newPassword 입력 후 버튼 클릭
    await page.getByTestId('password-current').fill(initialPassword)
    await page.getByTestId('password-new').fill(newPassword)
    await page.getByTestId('password-confirm').fill(newPassword)
    await page.getByTestId('password-submit').click()
    // 기대결과: 고객 목록
    await expect(page).toHaveURL(/\/customers$/)
    await expect(page.getByTestId('customer-list')).toBeVisible()
  })
})
