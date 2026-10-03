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

}
