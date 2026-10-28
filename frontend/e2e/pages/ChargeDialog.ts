import { expect, type Locator, type Page } from '@playwright/test'

// 충전 모달(SCR-6) Page Object. 한 모달 안에서 입력 화면 → 확인 화면 두 단계로 바뀐다(data-step 이 input / confirm).
export class ChargeDialog {
  readonly root: Locator
  // 입력 화면
  readonly amountInput: Locator
  readonly amountError: Locator
  readonly nextButton: Locator // [충전]
  readonly restoredBanner: Locator // 재로그인 후 '입력하던 금액을 불러왔습니다' 안내
  // 확인 화면 — testid 앞부분이 'charge-confirm-'
  readonly currentBalance: Locator
  readonly expectedBalance: Locator
  readonly expectedKorean: Locator
  readonly detailsToggle: Locator
  readonly details: Locator
  readonly backButton: Locator // [이전]
  readonly confirmButton: Locator // [확인]

  constructor(page: Page) {
    this.root = page.getByTestId('charge-dialog')
    this.amountInput = this.root.getByTestId('charge-amount-input')
    this.amountError = this.root.getByTestId('charge-amount-error')
    this.nextButton = this.root.getByTestId('charge-next')
    this.restoredBanner = this.root.getByTestId('charge-restored-banner')
    this.currentBalance = this.root.getByTestId('charge-confirm-current-balance')
    this.expectedBalance = this.root.getByTestId('charge-confirm-expected-balance')
    this.expectedKorean = this.root.getByTestId('charge-confirm-expected-korean')
    this.detailsToggle = this.root.getByTestId('charge-confirm-details-toggle')
    this.details = this.root.getByTestId('charge-confirm-details')
    this.backButton = this.root.getByTestId('charge-back')
    this.confirmButton = this.root.getByTestId('charge-confirm')
  }

  /** 지금 어느 단계인지 확인 — 'input'(입력) 또는 'confirm'(확인) */
  async expectStep(step: 'input' | 'confirm'): Promise<void> {
    await expect(this.root).toHaveAttribute('data-step', step)
  }

  /** 금액 칸을 비우고 숫자만 입력 (화면이 알아서 300,000 처럼 쉼표를 붙인다) */
  async fillAmount(won: number): Promise<void> {
    await this.amountInput.fill(String(won))
  }
}
