package com.creditbook.support;

import java.time.Instant;

import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.Role;

/**
 * 통합 테스트용 직원 만들기. 운영 코드에는 역할을 고르는 생성 경로가 없으므로(사장은 매장 개설, 직원은 사장의 직원 추가) 테스트도 같은 경로로 만든다.
 * <ul>
 * <li>{@link #owner} — 매장 개설 계정과 같다 (ADMIN, 비밀번호 변경 필요 없음)</li>
 * <li>{@link #readyStaff} — 직원 추가 후 첫 비밀번호 변경까지 마친 직원 (STAFF, 비밀번호 변경 필요 없음). 비밀번호 해시는 받은 값 그대로다</li>
 * </ul>
 * 비밀번호 변경 필요 상태의 직원이 필요하면 {@link Employee#createStaff} 를 직접 쓴다.
 */
public final class TestEmployees {

	private TestEmployees() {
	}

	public static Employee owner(String loginId, String passwordHash, String name, Instant createdAt) {
		return Employee.createOwner(loginId, passwordHash, name, createdAt);
	}

	public static Employee readyStaff(String loginId, String passwordHash, String name, Instant createdAt) {
		Employee staff = Employee.createStaff(loginId, passwordHash, name, createdAt);
		staff.changePassword(passwordHash, createdAt);
		return staff;
	}

	/** 역할을 인자로 받는 테스트용. ADMIN 이면 {@link #owner}, STAFF 면 {@link #readyStaff}. */
	public static Employee ready(String loginId, String passwordHash, String name, Role role, Instant createdAt) {
		return role == Role.ADMIN ? owner(loginId, passwordHash, name, createdAt)
				: readyStaff(loginId, passwordHash, name, createdAt);
	}

}
