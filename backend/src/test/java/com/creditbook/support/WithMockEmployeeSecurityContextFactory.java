package com.creditbook.support;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import com.creditbook.auth.domain.Role;

class WithMockEmployeeSecurityContextFactory implements WithSecurityContextFactory<WithMockEmployee> {

	@Override
	public SecurityContext createSecurityContext(WithMockEmployee annotation) {
		UUID employeeId = UUID.fromString(annotation.id());
		Role role = Role.valueOf(annotation.role());
		Instant issuedAt = Instant.now();
		Jwt jwt = Jwt.withTokenValue("mock-employee-token")
				.header("alg", "HS256")
				.subject(employeeId.toString())
				.jti(UUID.randomUUID().toString())
				.issuedAt(issuedAt)
				.expiresAt(issuedAt.plus(Duration.ofHours(16)))
				.build();
		JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt,
				List.of(new SimpleGrantedAuthority(role.authority())), employeeId.toString());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		return context;
	}

}
