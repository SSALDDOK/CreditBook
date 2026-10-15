// 확인 화면의 "처리 후 잔액" 예상값 — 표시 전용 (2026-10-15 사용자 승인, SCR-6 확인 화면).
//
// 이 프로젝트에서 화면이 금액을 더하는 곳은 여기 하나뿐이다. 지켜야 할 것:
// - 확인 화면에서 "이렇게 바뀝니다"를 미리 보여 주는 데만 쓴다. 서버로 보내거나, 상태에 저장하거나, 처리 뒤 잔액 표시에 쓰지 않는다
// - 처리 뒤 잔액은 서버 응답(balanceAfter)과 고객 상세 재조회 값만 쓴다
// - 실제 잔액·한도·부족 판단은 서버(PrepaidAccount)가 한다. 예상값과 서버 결과가 다르면 서버 값이 맞다

/** 충전 확인 화면 표시용 예상 잔액(현재 잔액 + 충전 금액). 원 단위 정수끼리의 덧셈이다 */
export function previewBalanceAfterCharge(currentBalance: number, chargeAmount: number): number {
  return currentBalance + chargeAmount
}
