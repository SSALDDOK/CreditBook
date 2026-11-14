package com.creditbook.global.security;

import java.util.UUID;

/**
 * 지금 요청을 처리하는 직원. 거래의 처리 직원(ledger_entries.performed_by)과 권한 확인의 출처다.
 * <p>
 * 직원 ID 를 요청 본문·헤더로 받지 않는다 — 클라이언트가 처리 직원을 마음대로 적을 수 있으면 감사 기록(REQ-20)을 믿을 수 없다.
 * 구현은 로그인(인증) 정보에서 직원을 꺼낸다.
 */
public interface CurrentEmployee {

	/**
	 * 현재 직원의 ID (employees.id).
	 *
	 * @return null 이 아닌 직원 ID
	 */
	UUID id();

	/**
	 * 현재 직원이 속한 매장 (employees.store_id, REQ-36). 고객·계좌·거래·직원 조회의 '내 매장' 조건이 여기서 온다.
	 * 요청 본문·경로·헤더로 받지 않는다.
	 *
	 * @return 매장 ID. 인증이 없거나 매장을 알 수 없으면 null — 호출한 쪽이 401 로 거절한다
	 */
	UUID storeId();

}
