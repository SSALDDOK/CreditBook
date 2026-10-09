package com.creditbook.auth.controller.dto;

import java.time.Instant;
import java.util.UUID;

import com.creditbook.auth.application.AuthenticatedEmployee;
import com.creditbook.auth.domain.Role;

/**
 * 로그인·세션 조회 응답. 토큰은 본문에 싣지 않는다 (HttpOnly 쿠키에만).
 *
 * @param expiresAt 세션(토큰) 만료 시각
 */
public record SessionResponse(UUID employeeId, String name, Role role, boolean mustChangePassword, Instant expiresAt) {

	public static SessionResponse of(AuthenticatedEmployee employee, Instant expiresAt) {
		return new SessionResponse(employee.employeeId(), employee.name(), employee.role(),
				employee.mustChangePassword(), expiresAt);
	}

}
