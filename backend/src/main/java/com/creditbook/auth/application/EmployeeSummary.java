package com.creditbook.auth.application;

import java.time.Instant;
import java.util.UUID;

import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.Role;

/**
 * 직원 관리 목록의 한 행 (REQ-30). 비밀번호 해시·실패 횟수·잠금 시각은 담지 않는다.
 *
 * @param lastLoginAt 마지막 로그인 시각. 한 번도 로그인하지 않았으면 null
 */
public record EmployeeSummary(UUID employeeId, String name, String loginId, Role role, boolean active,
		Instant lastLoginAt) {

	static EmployeeSummary from(Employee employee) {
		return new EmployeeSummary(employee.getId(), employee.getName(), employee.getLoginId(), employee.getRole(),
				employee.isActive(), employee.getLastLoginAt());
	}

}
