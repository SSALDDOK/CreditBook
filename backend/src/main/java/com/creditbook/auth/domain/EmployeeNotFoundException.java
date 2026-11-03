package com.creditbook.auth.domain;

import java.util.UUID;

/**
 * 관리 대상 직원이 없음 (REQ-30). 오류 코드 EMPLOYEE_NOT_FOUND 와 짝이다. 문구에는 직원 ID 를 싣지 않는다.
 */
public class EmployeeNotFoundException extends RuntimeException {

	private final UUID employeeId;

	public EmployeeNotFoundException(UUID employeeId) {
		super("직원을 찾을 수 없습니다.");
		this.employeeId = employeeId;
	}

	public UUID getEmployeeId() {
		return employeeId;
	}

}
