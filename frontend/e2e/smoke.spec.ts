import { expect, test } from '@playwright/test'
import { chargeByApi, createCustomer, getCustomerDetail, loginByApi } from './support/api'
import { staffAccount } from './support/credentials'
import { CustomerDetailPage } from './pages/CustomerDetailPage'
import { ChargeDialog } from './pages/ChargeDialog'

// CB-19 스모크 + TC-30(충전·사용 직후 새로고침 없이 잔액 갱신)
test('로그인 → 충전 → 사용 → 사용 취소 스모크 E2E @TC-30', async ({ page }) => {
    // 사전조건 — STAFF 로그인, 잔액 10,000원 고객 1명 (화면이 아니라 API로 빠르게 준비)
    await loginByApi(page.request, staffAccount())
    const customer = await createCustomer(page.request, 'E2E스모크충전')
    await chargeByApi(page.request, customer.id, 10_000)

    //고객 상세 화면 , 충전 모달
    const detail = new CustomerDetailPage(page)
    const dialog = new ChargeDialog(page)
    await detail.goto(customer.id)
    await detail.expectBalance(10_000)

    await test.step('1.[충전모달] -> 30000원 -> [충전] -> 확인 -> 30,000원 ', async () => {
        //고객 상세 - 충전 버튼 클릭
        await detail.chargeButton.click()
        await dialog.expectStep('input')
        await dialog.fillAmount(30_000)
        await dialog.nextButton.click()
        //모달 충전 확인 화면
        await dialog.expectStep('confirm')
        await dialog.confirmButton.click()
        //모달 화면 닫힘
        await expect(dialog.root).toBeHidden()
        //고객 상세 화면 확인
        await detail.expectBalance(40_000)

        //서버쪽 확인.
        const afterCharge = await getCustomerDetail(page.request, customer.id)
        expect(afterCharge.balance).toBe(40_000)
    })

    await test.step('2.[사용모달] -> 4500원 -> [사용] -> [확인] -> 35,500원 ', async () => {
        //고객 상세 - 사용 버튼 클릭
        await detail.useButton.click()

        //사용 모달 - 입력 화면
        const useDialog = page.getByTestId('use-dialog')
        await expect(useDialog).toHaveAttribute('data-step', 'input')

        //사용 모달 - 사용 버튼 클릭 -> 확인 화면 
        await useDialog.getByTestId('use-amount-input').fill('4500')
        await useDialog.getByTestId('use-next').click()
        await expect(useDialog).toHaveAttribute('data-step', 'confirm')
        //사용 모달 - 확인 클릭
        await useDialog.getByTestId('use-confirm').click()
        //모달 화면 닫힘
        await expect(useDialog).toBeHidden()
        //고객 상세 화면 확인
        await detail.expectBalance(35_500)

        //서버쪽 확인.
        const afterUse = await getCustomerDetail(page.request, customer.id)
        expect(afterUse.balance).toBe(35_500)
    })
    await test.step('3. 거래 이력 → 사용 행 [취소] → 사유 창 → 취소됨[취소] 비활성·40,000원 복구 ', async () => {
        //고객 상세 화면 -> 전체 이력 보기 링크로 이동
        await page.getByTestId('customer-history-link').click()

        //거래 이력 내역 화면 
        const transactionsPage = page.getByTestId('transactions-page')
        await expect(transactionsPage).toHaveAttribute('data-state', 'ready')

        //거래 이력 내역 화면 - 사용한 거래 취소 클릭
        await transactionsPage.getByRole('button', { name: '사용 4,500원 취소' }).click()

        //사용 취소 모달 - 입력 화면 (취소 사유 필수)
        const useCancelDialog = page.getByTestId('cancel-dialog')
        //취소 사유 안적었을 때 취소버튼 비활성화
        await expect(useCancelDialog.getByTestId('cancel-confirm')).toBeDisabled()
        await useCancelDialog.getByTestId('cancel-reason-input').fill('사용취소')
        //사용 취소 모달 - 취소 클릭
        await useCancelDialog.getByTestId('cancel-confirm').click()
         //모달 화면 닫힘
        await expect(useCancelDialog).toBeHidden()
        //고객 거래 이력 내역 화면 확인
        await expect(transactionsPage.getByTestId('transactions-customer-balance')).toHaveAttribute('data-balance', '40000')
        //사용행 취소됨 확인, 비활성화 확인
        const useRow = transactionsPage.locator('[data-testid="transaction-row"][data-type="USE"]')
        await expect(useRow).toHaveAttribute('data-cancelled', 'true')
        await expect(useRow).toContainText('취소됨')
        await expect(useRow.getByTestId('transaction-cancel-button')).toBeDisabled()
        //서버쪽 확인.
        const afterUseCancel = await getCustomerDetail(page.request, customer.id)
        expect(afterUseCancel.balance).toBe(40_000)
    })
})