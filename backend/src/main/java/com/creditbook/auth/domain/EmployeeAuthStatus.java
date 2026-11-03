package com.creditbook.auth.domain;

import java.util.Objects;

/**
 * 요청마다 확인하는 직원 인증 상태. 역할은 토큰이 아니라 여기(DB)에서 온다 — 역할이 바뀌거나 비활성화되면 다음 요청부터 반영된다.
 *
 * @param active 직원 활성 여부
 * @param role DB 에 저장된 역할
 * @param tokenRevoked 이 토큰(jti)이 로그아웃으로 폐기됐는가
 * @param mustChangePassword 비밀번호를 바꿔야 하는 계정인가 (REQ-30). 참이면 비밀번호 변경·세션 조회·로그아웃 말고는 쓸 수 없다
 */
public record EmployeeAuthStatus(boolean active, Role role, boolean tokenRevoked, boolean mustChangePassword) {

	public EmployeeAuthStatus {
		Objects.requireNonNull(role, "role");
	}

	/** 이 토큰으로 요청을 처리해도 되는가. 비활성 직원이거나 로그아웃한 토큰이면 거절한다. */
	public boolean canAuthenticate() {
		return active && !tokenRevoked;
	}

}
