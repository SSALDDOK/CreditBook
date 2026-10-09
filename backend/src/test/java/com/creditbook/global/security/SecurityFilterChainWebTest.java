package com.creditbook.global.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.creditbook.auth.application.AuthService;
import com.creditbook.auth.application.AuthenticatedEmployee;
import com.creditbook.auth.controller.AuthController;
import com.creditbook.auth.domain.EmployeeAuthStatus;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.Role;

import jakarta.servlet.http.Cookie;

/**
 * 리소스 서버 DSL 이 자동으로 더하는 경로·필터가 공개 범위를 넓히지 않는지 본다.
 * <ul>
 * <li>보호 리소스 메타데이터 경로는 다른 없는 경로처럼 401 이다 (공개 경로는 REQ-22 목록뿐)</li>
 * <li>유효한 토큰이라도 쿠키가 아니라 Authorization 헤더(Bearer·DPoP)로 보내면 인증되지 않는다</li>
 * </ul>
 */
@WebMvcTest(AuthController.class)
@Import(WebSecurityTestConfig.class)
@Tag("REQ-22")
class SecurityFilterChainWebTest {

	private static final UUID EMPLOYEE_ID = UUID.fromString("7d0c2f4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtTokenService tokenService;

	@MockitoBean
	AuthService authService;

	@MockitoBean
	EmployeeRepository employeeRepository;

	private String validToken;

	@BeforeEach
	void issueValidToken() {
		validToken = tokenService.issue(EMPLOYEE_ID).value();
		given(employeeRepository.findAuthStatus(any(), any()))
				.willReturn(Optional.of(new EmployeeAuthStatus(true, Role.ADMIN, false)));
		given(authService.session(EMPLOYEE_ID))
				.willReturn(new AuthenticatedEmployee(EMPLOYEE_ID, "김사장", Role.ADMIN, false));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/api" })
	@DisplayName("보호 리소스 메타데이터 경로는 인증 없이 열리지 않고 401 UNAUTHENTICATED 로 거절된다")
	void protected_resource_metadata_endpoint_is_not_public(String path) throws Exception {
		// when / then
		mockMvc.perform(get(path))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("유효한 토큰을 세션 쿠키로 보내면 인증된다 (아래 헤더 거절 테스트의 대조군)")
	void valid_token_in_cookie_is_authenticated() throws Exception {
		// when / then
		mockMvc.perform(get("/api/auth/session").cookie(new Cookie(SessionCookies.NAME, validToken)))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("유효한 토큰이라도 Authorization: Bearer 헤더로 보내면 401 이다")
	void valid_token_in_bearer_header_is_rejected() throws Exception {
		// when / then
		mockMvc.perform(get("/api/auth/session").header("Authorization", "Bearer " + validToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("유효한 토큰이라도 Authorization: DPoP 헤더와 증명 헤더로 보내면 401 이다")
	void valid_token_in_dpop_header_is_rejected() throws Exception {
		// when / then
		mockMvc.perform(get("/api/auth/session")
				.header("Authorization", "DPoP " + validToken)
				.header("DPoP", "forged.proof.jwt"))
				.andExpect(status().isUnauthorized());
	}

}
