import { apiRequest } from './client'
import { USE_MOCK } from './config'
import { getMockLedgerInputSettings } from './mock/settings'

// 백엔드 LedgerInputSettingsResponse 와 필드 이름을 맞춘다.

/** 충전·사용 화면 입력 설정 (REQ-6, REQ-31). 화면은 미리 검사·칩 표시에만 쓰고, 최종 검증은 서버가 한다 */
export type LedgerInputSettingsResponse = {
  /** 1회 충전 한도 (원 단위 정수, 잔액 상한 아님) */
  chargeMaxAmount: number
  /** 충전 메모 문구 칩 */
  chargeMemoPresets: string[]
  /** 사용 메모 문구 칩 */
  useMemoPresets: string[]
  /** 메모 최대 길이 */
  memoMaxLength: number
}

// 설정은 기동 때 정해지는 값이라 한 번 받으면 앱이 떠 있는 동안 다시 묻지 않는다.
// 실패한 요청은 캐시하지 않는다(다음 호출·[다시 시도]가 새로 요청한다).
let cached: Promise<LedgerInputSettingsResponse> | null = null
let resolved: LedgerInputSettingsResponse | null = null

/** GET /api/settings/ledger-input — 한 번 받아 캐시한다 */
export function getLedgerInputSettings(): Promise<LedgerInputSettingsResponse> {
  if (!cached) {
    const request = USE_MOCK
      ? getMockLedgerInputSettings()
      : apiRequest<LedgerInputSettingsResponse>('/api/settings/ledger-input')
    cached = request
    request.then(
      (data) => {
        resolved = data
      },
      () => {
        if (cached === request) cached = null
      },
    )
  }
  return cached
}

/** 이미 받아 둔 설정. 아직 없으면 null — 두 번째 모달부터 "불러오는 중" 없이 바로 그리는 데 쓴다 */
export function peekLedgerInputSettings(): LedgerInputSettingsResponse | null {
  return resolved
}
