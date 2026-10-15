import type { LedgerInputSettingsResponse } from '../settings'

// 화면 개발용 입력 설정 목. 서버 application.yml 의 기본값과 같은 값을 둔다.
// 일부러 조금 늦게 돌려줘 모달의 "불러오는 중" 상태를 볼 수 있게 한다.

const MOCK_DELAY_MS = 300

export const MOCK_LEDGER_INPUT_SETTINGS: LedgerInputSettingsResponse = {
  chargeMaxAmount: 300000,
  chargeMemoPresets: ['현금', '카드', '계좌이체'],
  useMemoPresets: ['음료', '베이커리', '원두', '디저트', 'MD', '기타'],
  memoMaxLength: 200,
}

export function getMockLedgerInputSettings(): Promise<LedgerInputSettingsResponse> {
  return new Promise((resolve) => {
    setTimeout(() => resolve({ ...MOCK_LEDGER_INPUT_SETTINGS }), MOCK_DELAY_MS)
  })
}
