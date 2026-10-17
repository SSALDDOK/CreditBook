// 확인 화면의 "처리 후 잔액"·입력 화면의 "부족액"·취소 창의 "취소 후 잔액" 예상값 — 표시 전용
// (2026-10-15 사용자 승인: SCR-6 확인 화면, SCR-7 확인 화면·'잔액 부족' 상태.
//  2026-10-16 사용자 승인: SCR-8 취소 창의 잔액 변화 상자도 같은 규칙으로 이 파일을 쓴다).
//
// 이 프로젝트에서 화면이 금액을 더하고 빼는 곳은 여기 하나뿐이다. 지켜야 할 것:
// - "이렇게 바뀝니다"·"이만큼 모자랍니다"를 미리 보여 주는 데만 쓴다. 서버로 보내거나, 상태에 저장하거나, 처리 뒤 잔액 표시에 쓰지 않는다
// - 처리 뒤 잔액은 서버 응답(balanceAfter)과 고객 상세 재조회 값만 쓴다
// - 실제 잔액·한도·부족 판단은 서버(PrepaidAccount)가 한다. 예상값과 서버 결과가 다르면 서버 값이 맞다
//   (서버가 잔액 부족을 주면 그 details.balance·shortage 를 그대로 보여 준다)
//
// 예:
//   previewBalanceAfterCharge(45500, 50000) → 95500
//   previewBalanceAfterUse(45500, 4500)     → 41000
//   previewBalanceAfterUse(3000, 3000)      → 0        // 잔액과 같은 금액은 쓸 수 있다
//   previewShortage(3000, 5000)             → 2000
//   previewShortage(3000, 3000)             → null     // 같으면 부족하지 않다
//   previewShortage(3000, 3001)             → 1
//   previewBalanceAfterUseCancel(95500, 4500)     → 100000
//   previewBalanceAfterChargeCancel(95500, 50000) → 45500
//   previewBalanceAfterChargeCancel(0, 30000)     → null   // 잔액이 모자라면 예상값을 만들지 않는다(거절 판단은 서버)

/** 충전 확인 화면 표시용 예상 잔액(현재 잔액 + 충전 금액). 원 단위 정수끼리의 덧셈이다 */
export function previewBalanceAfterCharge(currentBalance: number, chargeAmount: number): number {
  return currentBalance + chargeAmount
}

/** 사용 확인 화면 표시용 예상 잔액(현재 잔액 - 사용 금액). 부족하면(previewShortage 가 null 이 아니면) 쓰지 않는다 */
export function previewBalanceAfterUse(currentBalance: number, useAmount: number): number {
  return currentBalance - useAmount
}

/** 사용 금액이 현재 잔액보다 크면 표시용 부족액, 아니면 null. 잔액과 같은 금액은 부족하지 않다(잔액 0원이 된다) */
export function previewShortage(currentBalance: number, useAmount: number): number | null {
  return useAmount > currentBalance ? useAmount - currentBalance : null
}

/** 사용 취소 창 표시용 예상 잔액(현재 잔액 + 원 사용 금액). 사용한 금액이 그대로 돌아온다 */
export function previewBalanceAfterUseCancel(currentBalance: number, useAmount: number): number {
  return currentBalance + useAmount
}

/**
 * 충전 취소 창 표시용 예상 잔액(현재 잔액 - 원 충전 금액). 잔액이 충전 금액보다 적으면 null — 음수 예상값을 보여 주지 않는다.
 * null 이어도 [취소하기]를 막지 않는다: 취소할 수 있는지는 서버가 판단하고(CHARGE_ALREADY_USED), 화면은 그 응답을 보여 준다
 */
export function previewBalanceAfterChargeCancel(currentBalance: number, chargeAmount: number): number | null {
  return chargeAmount > currentBalance ? null : currentBalance - chargeAmount
}
