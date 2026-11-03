package com.creditbook.auth.application;

import java.util.UUID;

import com.creditbook.auth.domain.Role;

/**
 * 직원 추가 결과 (REQ-30). 초기 비밀번호 원문은 이 응답으로 한 번만 나간다 — DB 에는 해시만 있다.
 * {@link #toString()} 은 초기 비밀번호를 가린다.
 *
 * @param role 항상 STAFF
 */
public record StaffAddedResult(UUID employeeId, String name, String loginId, Role role, String initialPassword) {

	@Override
	public String toString() {
		return "StaffAddedResult{employeeId=" + employeeId + ", role=" + role + ", initialPassword=[PROTECTED]}";
	}

}
