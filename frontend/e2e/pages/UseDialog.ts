import { expect, type Locator, type Page } from '@playwright/test'

// 사용 모달(SCR-7) Page Object. 충전 모달처럼 입력 화면 → 확인 화면 두 단계로 바뀐다(data-step 이 input / confirm).
export class UseDialog {
  readonly root: Locator
  // 입력 화면
  readonly amountInput: Locator
  readonly amountError: Locator // 잔액 부족·형식 오류 문구(빨간 글자)
  readonly memoInput: Locator
  readonly memoChips: Locator // 메모 문구 칩 — 이름으로 고를 때는 memoChip('음료')
  readonly nextButton: Locator // [사용]
  readonly cancelButton: Locator // [취소] — 모달 닫기
  // 확인 화면
  readonly confirmButton: Locator // [확인]

  constructor(page: Page) {
    this.root = page.getByTestId('use-dialog')
    this.amountInput = this.root.getByTestId('use-amount-input')
    this.amountError = this.root.getByTestId('use-amount-error')
    this.memoInput = this.root.getByTestId('use-memo-input')
    this.memoChips = this.root.getByTestId('use-memo-chip')
    this.nextButton = this.root.getByTestId('use-next')
    this.cancelButton = this.root.getByTestId('use-cancel')
    this.confirmButton = this.root.getByTestId('use-confirm')
  }

  /** 지금 어느 단계인지 확인 — 'input'(입력) 또는 'confirm'(확인) */
  async expectStep(step: 'input' | 'confirm'): Promise<void> {
    await expect(this.root).toHaveAttribute('data-step', step)
  }

  /** 금액 칸을 비우고 숫자만 입력 (화면이 알아서 쉼표를 붙인다) */
  async fillAmount(won: number): Promise<void> {
    await this.amountInput.fill(String(won))
  }

  /** 이름으로 메모 칩 하나 고르기 (예: '음료') — 글자 포함이 아니라 data-chip 이 정확히 같은 칩 */
  memoChip(label: string): Locator {
    return this.root.locator(`[data-testid="use-memo-chip"][data-chip="${label}"]`)
  }
}
