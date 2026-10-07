package com.creditbook.global.security;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * 로그인 정보(SecurityContext)에서 현재 직원을 꺼낸다. 싱글턴이며 호출할 때마다 현재 요청의 인증 정보를 읽는다.
 * 인증이 없으면 null 을 돌려주고, 호출한 쪽이 {@link UnauthenticatedException}(401)으로 거절한다.
 */
@Component
class SecurityContextCurrentEmployee implements CurrentEmployee {

	@Override
	public UUID id() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken jwtAuthentication && jwtAuthentication.isAuthenticated()) {
			// sub 는 EmployeeJwtAuthenticationConverter 가 UUID 형식과 직원 존재를 이미 확인했다
			return UUID.fromString(jwtAuthentication.getToken().getSubject());
		}
		return null;
	}

}
