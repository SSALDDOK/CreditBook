package com.creditbook.auth.domain;

/**
 * 직원 역할 (employees.role, ck_employees_role). 권한 확인은 DB 에 저장된 이 값으로 한다 — 토큰에 역할을 싣지 않는다 (REQ-16).
 */
public enum Role {

	/** 사장. 고객 비활성화·직원 관리 등 관리 기능을 쓸 수 있다. */
	ADMIN,

	/** 직원. 충전·사용 등 일상 업무만 한다. */
	STAFF;

	/** Spring Security 권한 이름 (예: ROLE_ADMIN). */
	public String authority() {
		return "ROLE_" + name();
	}

}
