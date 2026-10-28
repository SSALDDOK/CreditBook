import { expect, type Locator, type Page } from '@playwright/test'

// 고객 상세 화면(SCR-5) Page Object — 화면의 "어디를 누르고 무엇을 읽는지"를 한 곳에 모은다.
// testid·문구가 바뀌면 테스트 여러 개가 아니라 이 파일 하나만 고치면 된다.
export class CustomerDetailPage {
  readonly page: Page
  readonly root: Locator
  readonly balance: Locator
  readonly chargeButton: Locator
  readonly useButton: Locator

  constructor(page: Page) {
    this.page = page
    this.root = page.getByTestId('customer-detail')
    this.balance = page.getByTestId('customer-detail-balance')
    this.chargeButton = page.getByTestId('customer-charge-button')
    this.useButton = page.getByTestId('customer-use-button')
  }

  /** 주소로 바로 들어가서 다 그려질 때까지 기다린다 (data-state 가 loading → ready) */
  async goto(customerId: string): Promise<void> {
    await this.page.goto(`/customers/${customerId}`)
    await expect(this.root).toHaveAttribute('data-state', 'ready')
  }

  /** 화면에 보이는 잔액 — data-balance 에 서버 값(숫자)이 들어 있다 */
  async expectBalance(won: number): Promise<void> {
    await expect(this.balance).toHaveAttribute('data-balance', String(won))
  }
}
