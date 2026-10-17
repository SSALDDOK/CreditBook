// 고객 목록으로 "돌아가기"에 필요한 값을 화면 사이에 넘기는 규칙.
//
// 검색어(이름·연락처 일부)는 주소(URL)에 두지 않는다 — 브라우저 기록·서버 로그에 남지 않게. 목록 화면의 방문 기록
// 상태(history state)에만 두고, 상세·등록·거래 이력으로 갈 때는 화면 이동 상태(react-router state)로 함께 넘긴다.
// 페이지·비활성 포함은 개인정보가 아니라서 주소(?page=&inactive=1)에 그대로 둔다.
// 새로고침·주소 직접 열기·왼쪽 메뉴 '고객'으로 들어오면 상태가 없어 처음 목록이 된다.

/** 돌아갈 목록 — search 는 목록 주소의 ?page=&inactive= 부분(검색어 없음), q 는 검색어 */
export type CustomerListReturn = { search: string; q: string }

/** 고객 목록 화면(/customers)의 방문 기록 상태 — 검색어만 둔다 */
export type CustomerListLocationState = { q?: string } | null

/**
 * 고객 화면(상세·등록·거래 이력) 사이에 넘기는 화면 이동 상태.
 * - listReturn: 브레드크럼 '고객'·[취소]가 돌아갈 목록(검색어·페이지·비활성 포함)
 * - openCharge: 고객 상세가 열리자마자 충전 모달을 연다(고객 등록의 "등록 후 바로 충전하기")
 */
export type CustomerLocationState = { listReturn?: CustomerListReturn; openCharge?: boolean } | null

/** 화면 이동 상태에서 돌아갈 목록을 꺼낸다. 없거나 모양이 다르면 undefined(처음 목록) */
export function listReturnOf(state: unknown): CustomerListReturn | undefined {
  const r = (state as CustomerLocationState)?.listReturn
  return r && typeof r.search === 'string' && typeof r.q === 'string' ? r : undefined
}

/** 목록으로 돌아가는 Link·navigate 대상 — 주소에는 page·inactive 만, 검색어는 state 로 */
export function customerListTarget(r: CustomerListReturn | undefined): {
  to: string
  state: CustomerListLocationState
} {
  return { to: `/customers${r?.search ?? ''}`, state: r?.q ? { q: r.q } : null }
}
