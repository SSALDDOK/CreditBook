package com.creditbook.auth.controller.dto;

import java.util.UUID;

import com.creditbook.auth.application.StaffAddedResult;
import com.creditbook.auth.domain.Role;

/**
 * 직원 추가 결과 (REQ-30). 초기 비밀번호는 이 응답에서 한 번만 보여 준다 — 다시 조회할 방법이 없다.
 * {@link #toString()} 은 초기 비밀번호를 가린다.
 *
 * @param role 항상 STAFF
 */
public record AddEmployeeResponse(UUID employeeId, String name, String loginId, Role role, String initialPassword) {

	public static AddEmployeeResponse from(StaffAddedResult result) {
		return new AddEmployeeResponse(result.employeeId(), result.name(), result.loginId(), result.role(),
				result.initialPassword());
	}

	@Override
	public String toString() {
		return "AddEmployeeResponse{employeeId=" + employeeId + ", role=" + role + ", initialPassword=[PROTECTED]}";
	}

}
