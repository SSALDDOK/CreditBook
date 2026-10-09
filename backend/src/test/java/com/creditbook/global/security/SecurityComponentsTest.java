package com.creditbook.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;

/**
 * 보안 필터 부품: 401·403 응답 본문, 쿠키 토큰 읽기, 현재 직원 꺼내기.
 */
@Tag("REQ-15")
@Tag("REQ-16")
class SecurityComponentsTest {

	private final SecurityErrorResponseWriter writer = new SecurityErrorResponseWriter(JsonMapper.builder().build());

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("인증이 없으면 401 과 UNAUTHENTICATED 오류 본문으로 응답한다")
	void entry_point_writes_401_error_response() throws Exception {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/customers");
		MockHttpServletResponse response = new MockHttpServletResponse();

		// when
		new RestAuthenticationEntryPoint(writer).commence(request, response,
				new InsufficientAuthenticationException("Full authentication is required"));

		// then
		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentType()).startsWith("application/json");
		assertThat(response.getContentAsString(StandardCharsets.UTF_8))
				.isEqualTo("{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\",\"fieldErrors\":[]}");
	}

	@Test
	@DisplayName("토큰이 유효하지 않아도 같은 401 본문으로 응답한다")
	void entry_point_writes_same_body_for_invalid_token() throws Exception {
		// given
		MockHttpServletResponse response = new MockHttpServletResponse();

		// when
		new RestAuthenticationEntryPoint(writer).commence(new MockHttpServletRequest("GET", "/api/x"), response,
				new InvalidBearerTokenException("token revoked"));

		// then
		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":\"UNAUTHENTICATED\"");
	}

	@Test
	@DisplayName("권한이 없으면 403 과 FORBIDDEN 오류 본문으로 응답한다")
	void access_denied_handler_writes_403_error_response() throws Exception {
		// given
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
				"7d0c2f4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f", null, List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
		MockHttpServletResponse response = new MockHttpServletResponse();

		// when
		new RestAccessDeniedHandler(writer).handle(new MockHttpServletRequest("POST", "/api/admin"), response,
				new AccessDeniedException("Access Denied"));

		// then
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getContentAsString(StandardCharsets.UTF_8))
				.isEqualTo("{\"code\":\"FORBIDDEN\",\"message\":\"권한이 없습니다.\",\"fieldErrors\":[]}");
	}

	@Test
	@DisplayName("토큰은 CB_SESSION 쿠키에서 읽는다")
	void resolver_reads_session_cookie() {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/customers");
		request.setCookies(new Cookie("other", "x"), new Cookie("CB_SESSION", "token-value"));

		// when / then
		assertThat(new CookieBearerTokenResolver(SecurityConfig.PUBLIC_ENDPOINTS).resolve(request))
				.isEqualTo("token-value");
	}

	@Test
	@DisplayName("Authorization 헤더의 토큰은 읽지 않는다")
	void resolver_ignores_authorization_header() {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/customers");
		request.addHeader("Authorization", "Bearer token-value");

		// when / then
		assertThat(new CookieBearerTokenResolver(SecurityConfig.PUBLIC_ENDPOINTS).resolve(request)).isNull();
	}

	@ParameterizedTest(name = "[{index}] {0} {1}")
	@CsvSource({ "POST, /api/auth/login", "POST, /api/auth/logout", "GET, /actuator/health" })
	@DisplayName("인증이 필요 없는 경로에서는 쿠키가 있어도 토큰을 읽지 않는다")
	void resolver_skips_public_endpoints(String method, String path) {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setCookies(new Cookie("CB_SESSION", "stale-token"));

		// when / then
		assertThat(new CookieBearerTokenResolver(SecurityConfig.PUBLIC_ENDPOINTS).resolve(request)).isNull();
	}

	@Test
	@DisplayName("빈 쿠키 값은 토큰이 없는 것으로 본다")
	void resolver_ignores_blank_cookie() {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/customers");
		request.setCookies(new Cookie("CB_SESSION", ""));

		// when / then
		assertThat(new CookieBearerTokenResolver(SecurityConfig.PUBLIC_ENDPOINTS).resolve(request)).isNull();
	}

	@Test
	@DisplayName("현재 직원은 로그인 토큰의 직원 ID 다")
	void current_employee_comes_from_authenticated_token() {
		// given
		UUID employeeId = UUID.randomUUID();
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(employeeId.toString())
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
		SecurityContextHolder.getContext().setAuthentication(
				new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_STAFF")), employeeId.toString()));

		// when / then
		assertThat(new SecurityContextCurrentEmployee().id()).isEqualTo(employeeId);
	}

	@Test
	@DisplayName("로그인하지 않았으면 현재 직원은 없다")
	void current_employee_is_null_without_authentication() {
		assertThat(new SecurityContextCurrentEmployee().id()).isNull();
	}

}
