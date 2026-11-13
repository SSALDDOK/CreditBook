package com.creditbook.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.creditbook.auth.domain.EmployeeAuthStatus;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.Role;

/**
 * 현재 직원의 매장 (REQ-36). 매장은 토큰 클레임이 아니라 인증할 때 DB 에서 읽은 직원의 store_id 이고, 매장을 알 수 없으면 인증하지 않는다.
 */
@Tag("REQ-36")
class CurrentEmployeeStoreTest {

	private static final UUID EMPLOYEE_ID = UUID.fromString("7d0c2f4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f");
	private static final UUID JTI = UUID.fromString("1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e");
	private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000005001");
	private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

	private final EmployeeRepository repository = mock(EmployeeRepository.class);
	private final EmployeeJwtAuthenticationConverter converter = new EmployeeJwtAuthenticationConverter(repository);

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private static Jwt jwt() {
		return Jwt.withTokenValue("t").header("alg", "HS256").subject(EMPLOYEE_ID.toString()).jti(JTI.toString())
				.issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();
	}

	@Test
	@DisplayName("인증 정보에는 DB 에서 읽은 직원의 매장이 담기고, 현재 직원의 매장은 그 값이다")
	void authenticated_employee_carries_store_from_db() {
		// given
		given(repository.findAuthStatus(EMPLOYEE_ID, JTI))
				.willReturn(Optional.of(new EmployeeAuthStatus(STORE_ID, true, Role.STAFF, false, false)));

		// when
		AbstractAuthenticationToken authentication = converter.convert(jwt());
		SecurityContextHolder.getContext().setAuthentication(authentication);

		// then
		assertThat(authentication).isInstanceOf(EmployeeAuthenticationToken.class);
		assertThat(((EmployeeAuthenticationToken) authentication).getStoreId()).isEqualTo(STORE_ID);
		assertThat(authentication.getName()).isEqualTo(EMPLOYEE_ID.toString());
		SecurityContextCurrentEmployee current = new SecurityContextCurrentEmployee();
		assertThat(current.id()).isEqualTo(EMPLOYEE_ID);
		assertThat(current.storeId()).isEqualTo(STORE_ID);
	}

	@Test
	@DisplayName("직원의 매장을 읽지 못하면 토큰을 거절한다 (401)")
	void unknown_store_is_rejected() {
		// given
		given(repository.findAuthStatus(EMPLOYEE_ID, JTI))
				.willReturn(Optional.of(new EmployeeAuthStatus(null, true, Role.ADMIN, false, false)));

		// when / then
		assertThatThrownBy(() -> converter.convert(jwt())).isInstanceOf(InvalidBearerTokenException.class);
	}

	@Test
	@DisplayName("매장이 없는 인증 정보이거나 로그인하지 않았으면 현재 직원의 매장은 없다")
	void store_is_null_without_employee_authentication() {
		// given — 매장이 없는 일반 JWT 인증
		SecurityContextHolder.getContext().setAuthentication(
				new JwtAuthenticationToken(jwt(), List.of(new SimpleGrantedAuthority("ROLE_STAFF")), EMPLOYEE_ID.toString()));

		// when / then
		assertThat(new SecurityContextCurrentEmployee().storeId()).isNull();

		SecurityContextHolder.clearContext();
		assertThat(new SecurityContextCurrentEmployee().storeId()).isNull();
	}

	@Test
	@DisplayName("매장 없이 직원 인증 정보를 만들 수 없다")
	void token_requires_store() {
		assertThatThrownBy(() -> new EmployeeAuthenticationToken(jwt(), List.of(), EMPLOYEE_ID, null))
				.isInstanceOf(NullPointerException.class);
	}

}
