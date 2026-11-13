package com.creditbook.global.security;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 직원 인증 정보. 토큰 검증과 같은 요청의 DB 조회에서 읽은 직원의 매장(storeId)을 함께 담는다 (REQ-36).
 * 매장은 토큰 클레임이 아니라 DB 에서 온다 — 클라이언트가 바꿀 수 없다. 주체 이름은 직원 ID 다.
 */
public class EmployeeAuthenticationToken extends JwtAuthenticationToken {

	private static final long serialVersionUID = 1L;

	private final UUID storeId;

	public EmployeeAuthenticationToken(Jwt jwt, Collection<? extends GrantedAuthority> authorities, UUID employeeId,
			UUID storeId) {
		super(jwt, authorities, Objects.requireNonNull(employeeId, "employeeId").toString());
		this.storeId = Objects.requireNonNull(storeId, "storeId");
	}

	/** 직원이 속한 매장. null 이 아니다. */
	public UUID getStoreId() {
		return storeId;
	}

}
