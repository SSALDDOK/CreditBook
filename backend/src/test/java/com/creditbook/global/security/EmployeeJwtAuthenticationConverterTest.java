package com.creditbook.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import com.creditbook.auth.domain.EmployeeAuthStatus;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.Role;

/**
 * 서명·만료를 통과한 토큰에 대해 직원 상태·폐기 여부를 확인하고 DB 역할로 권한을 붙인다.
 */
@Tag("REQ-15")
@Tag("REQ-22")
class EmployeeJwtAuthenticationConverterTest {

	private static final UUID EMPLOYEE_ID = UUID.fromString("7d0c2f4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f");
	private static final UUID JTI = UUID.fromString("1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e");
	private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");

	private final EmployeeRepository repository = mock(EmployeeRepository.class);
	private final EmployeeJwtAuthenticationConverter converter = new EmployeeJwtAuthenticationConverter(repository);

	private static Jwt jwt(String sub, String jti) {
		return Jwt.withTokenValue("t").header("alg", "HS256").subject(sub).jti(jti)
				.issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();
	}

	@ParameterizedTest(name = "[{index}] {0} → {1}")
	@CsvSource({ "ADMIN, ROLE_ADMIN", "STAFF, ROLE_STAFF" })
	@DisplayName("활성 직원의 폐기되지 않은 토큰이면 DB 역할로 권한을 붙여 인증한다")
	void active_employee_is_authenticated_with_db_role(Role role, String authority) {
		// given
		given(repository.findAuthStatus(EMPLOYEE_ID, JTI)).willReturn(Optional.of(new EmployeeAuthStatus(true, role, false)));

		// when
		AbstractAuthenticationToken authentication = converter.convert(jwt(EMPLOYEE_ID.toString(), JTI.toString()));

		// then
		assertThat(authentication.isAuthenticated()).isTrue();
		assertThat(authentication.getName()).isEqualTo(EMPLOYEE_ID.toString());
		assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly(authority);
	}

	@Test
	@DisplayName("비활성 직원의 토큰은 거절한다")
	void inactive_employee_is_rejected() {
		// given
		given(repository.findAuthStatus(EMPLOYEE_ID, JTI))
				.willReturn(Optional.of(new EmployeeAuthStatus(false, Role.ADMIN, false)));

		// when / then
		assertThatThrownBy(() -> converter.convert(jwt(EMPLOYEE_ID.toString(), JTI.toString())))
				.isInstanceOf(InvalidBearerTokenException.class);
	}

	@Test
	@DisplayName("로그아웃으로 폐기된 토큰은 거절한다")
	void revoked_token_is_rejected() {
		// given
		given(repository.findAuthStatus(EMPLOYEE_ID, JTI))
				.willReturn(Optional.of(new EmployeeAuthStatus(true, Role.STAFF, true)));

		// when / then
		assertThatThrownBy(() -> converter.convert(jwt(EMPLOYEE_ID.toString(), JTI.toString())))
				.isInstanceOf(InvalidBearerTokenException.class);
	}

	@Test
	@DisplayName("없는 직원의 토큰은 거절한다")
	void unknown_employee_is_rejected() {
		// given
		given(repository.findAuthStatus(any(), any())).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> converter.convert(jwt(EMPLOYEE_ID.toString(), JTI.toString())))
				.isInstanceOf(InvalidBearerTokenException.class);
	}

	@ParameterizedTest(name = "[{index}] sub={0}, jti={1}")
	@CsvSource({
			"not-a-uuid, 1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e",
			"7d0c2f4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f, not-a-uuid",
			"'', 1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e" })
	@DisplayName("sub·jti 가 UUID 형식이 아니면 DB 를 조회하지 않고 인증 실패(401)로 거절한다")
	void non_uuid_claims_are_rejected_as_authentication_failure(String sub, String jti) {
		// when / then
		assertThatThrownBy(() -> converter.convert(jwt(sub, jti))).isInstanceOf(InvalidBearerTokenException.class);
		verify(repository, never()).findAuthStatus(any(), any());
	}

}
