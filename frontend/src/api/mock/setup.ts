import { ApiError } from '../client'
import type { SetupRequest, SetupResponse, SetupStatusResponse } from '../setup'

// 화면 개발용 매장 개설 목(SCR-11). 기본은 이미 개설된 매장(available=false)이라 지금까지의 개발 흐름이 그대로다.
// 개설 화면을 보려면 .env.local 에 VITE_MOCK_SETUP_AVAILABLE=true 를 넣고 /signup 으로 간다.
// 개설 여부는 이 모듈의 메모리에만 둔다(새로고침하면 처음 상태).
//
// 오류 흐름은 아이디를 아래 낱말 그대로 넣으면 볼 수 있다.
// - taken.owner  403 SETUP_ALREADY_COMPLETED — 가입 도중 다른 사람이 먼저 연 경우
// - bad.input    400 INVALID_INPUT — fieldErrors 를 칸 아래에 표시
// - server.down  500
// - login.fail   개설은 되고 이어지는 로그인이 실패(mock/auth.ts) — 로그인 화면으로 이동, 아이디 미리 채움

const MOCK_DELAY_MS = 350

let available = import.meta.env.VITE_MOCK_SETUP_AVAILABLE === 'true'

function delay(signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, MOCK_DELAY_MS)
    signal?.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(new DOMException('Aborted', 'AbortError'))
    })
  })
}

export async function mockGetSetupStatus(signal?: AbortSignal): Promise<SetupStatusResponse> {
  await delay(signal)
  return { available }
}

export async function mockSetUp({ storeName, ownerName, loginId }: SetupRequest): Promise<SetupResponse> {
  await delay()
  if (!available || loginId === 'taken.owner') {
    available = false
    throw new ApiError(403, 'SETUP_ALREADY_COMPLETED', '이미 개설된 매장입니다. 로그인해 주세요.')
  }
  if (loginId === 'bad.input') {
    throw new ApiError(400, 'INVALID_INPUT', '입력값을 확인해 주세요.', [
      { field: 'loginId', message: '아이디는 영문·숫자·마침표 4–20자여야 합니다.' },
    ])
  }
  if (loginId === 'server.down') {
    throw new ApiError(500, 'INTERNAL_ERROR', '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.')
  }
  available = false
  return { storeName: storeName.trim(), loginId, name: ownerName.trim(), role: 'ADMIN' }
}
