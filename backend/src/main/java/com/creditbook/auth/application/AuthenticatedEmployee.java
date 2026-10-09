package com.creditbook.auth.application;

import java.util.UUID;

import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.Role;

/**
 * 로그인한 직원의 공개 정보. 비밀번호 해시·로그인 아이디는 담지 않는다.
 */
public record AuthenticatedEmployee(UUID employeeId, String name, Role role, boolean mustChangePassword) {

	static AuthenticatedEmployee from(Employee employee) {
		return new AuthenticatedEmployee(employee.getId(), employee.getName(), employee.getRole(),
				employee.mustChangePassword());
	}

}
