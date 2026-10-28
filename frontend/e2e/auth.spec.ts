import { expect, test } from '@playwright/test'
import { staffAccount } from './support/credentials'

// TC-19 로그인 → 보호 화면 → 로그아웃 → 뒤로가기 차단
test('로그인 → 보호 화면 → 로그아웃 → 뒤로가기 차단 @TC-19', async ({ page }) => {
    const account = staffAccount() //로그인 안한 직원

    //로그인 안했을 시 고객목록 X -> 로그인 화면으로
    await page.goto('/customers?inactive=1')

    await expect(page).toHaveURL(/\/login/) //따옴표 없이 필수.
    await expect(page.getByLabel('아이디')).toBeVisible()

    //로그인 시도
    await page.getByLabel('아이디').fill(account.loginId)
    await page.getByLabel('비밀번호').fill(account.password)
    await page.getByRole('button', { name: '로그인' }).click()

    //로그인 -> 원래 가려던 고객목록 화면 (비활성화 토글ON)으로 복귀
    await expect(page).toHaveURL(/\/customers\?inactive=1/)
    await expect(page.getByTestId('customer-list')).toBeVisible()

    await page.goto('/customers/new') //뒤로가기로 고객 목록에 돌아가 보기 위해
    //로그아웃
    await page.getByRole('button', { name: '로그아웃' }).click()
    await expect(page).toHaveURL(/\/login/)
    //로그아웃 후 뒤로가기 -> 고객목록 X ,로그인화면
    await page.goBack()
    await expect(page).toHaveURL(/\/login/)
    await expect(page.getByTestId('customer-list')).toBeHidden()

})