import { ApiError } from '../client'
import type { SetupRequest, SetupResponse } from '../setup'

// 화면 개발용 매장 개설 목(SCR-11). 초대 코드만 있으면 몇 번이든 새 매장을 연다(목은 매장을 기억하지 않는다).
//
// 오류 흐름은 아래 값을 그대로 넣으면 볼 수 있다.
// - 초대 코드 bad        400 INVALID_INVITE_CODE — 초대 코드 칸 아래 빨간 글씨
// - 아이디 taken.owner   409 DUPLICATE_LOGIN_ID — 아이디 칸 아래 빨간 글씨
// - 아이디 too.many      429 SIGNUP_RATE_LIMITED — 카드 위 노란 상자
// - 아이디 bad.input     400 INVALID_INPUT — fieldErrors 를 칸 아래에 표시
// - 아이디 server.down   500
// - 아이디 login.fail    개설은 되고 이어지는 로그인이 실패(mock/auth.ts) — 로그인 화면으로 이동, 아이디 미리 채움

const MOCK_DELAY_MS = 350

function delay(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, MOCK_DELAY_MS))
}

export async function mockSetUp({ inviteCode, storeName, ownerName, loginId }: SetupRequest): Promise<SetupResponse> {
  await delay()
  if (inviteCode.trim().toLowerCase() === 'bad') {
    throw new ApiError(400, 'INVALID_INVITE_CODE', '초대 코드를 확인해 주세요.')
  }
  if (loginId === 'taken.owner') {
    throw new ApiError(409, 'DUPLICATE_LOGIN_ID', '이미 사용 중인 아이디입니다.')
  }
  if (loginId === 'too.many') {
    throw new ApiError(429, 'SIGNUP_RATE_LIMITED', '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.')
  }
  if (loginId === 'bad.input') {
    throw new ApiError(400, 'INVALID_INPUT', '입력값을 확인해 주세요.', [
      { field: 'loginId', message: '아이디는 영문·숫자·마침표 4–20자여야 합니다.' },
    ])
  }
  if (loginId === 'server.down') {
    throw new ApiError(500, 'INTERNAL_ERROR', '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.')
  }
  return { storeName: storeName.trim(), loginId, name: ownerName.trim(), role: 'ADMIN' }
}
