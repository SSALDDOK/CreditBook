package com.creditbook.auth.controller;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.creditbook.auth.application.AuthService;
import com.creditbook.auth.application.AuthenticatedEmployee;
import com.creditbook.auth.application.LoginResult;
import com.creditbook.auth.domain.EmployeeAuthStatus;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.InvalidCredentialsException;
import com.creditbook.auth.domain.Role;
import com.creditbook.global.security.JwtTestSupport;
import com.creditbook.global.security.JwtTokenService;
import com.creditbook.global.security.JwtTokenService.IssuedToken;
import com.creditbook.global.security.WebSecurityTestConfig;
import com.creditbook.support.WithMockEmployee;

import jakarta.servlet.http.Cookie;

/**
 * 로그인·로그아웃·세션 조회 API 와 보안 필터의 웹 계층 동작. 서비스는 목이고, 보안 구성·JWT 검증은 운영과 같다(키는 실행마다 무작위).
 * 쿠키 토큰 인증을 보는 테스트는 직원 상태 조회(EmployeeRepository)를 목으로 정한다.
 */
@WebMvcTest(AuthController.class)
@Import(WebSecurityTestConfig.class)
@Tag("REQ-15")
@Tag("REQ-22")
class AuthControllerTest {

	private static final UUID EMPLOYEE_ID = UUID.fromString("7d0c2f4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f");
	private static final Instant EXPIRES_AT = Instant.parse("2026-10-08T06:30:00Z");
	private static final String TOKEN_VALUE = "header.payload.signature";
	private static final AuthenticatedEmployee OWNER = new AuthenticatedEmployee(EMPLOYEE_ID, "김사장", Role.ADMIN, false);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtTokenService tokenService;

	@Autowired
	JwtEncoder jwtEncoder;

	@MockitoBean
	AuthService authService;

	@MockitoBean
	EmployeeRepository employeeRepository;

	private ResultActions login(String body) throws Exception {
		return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	// ---- 로그인

	@Test
	@DisplayName("로그인에 성공하면 HttpOnly·Secure·SameSite=Strict 세션 쿠키를 심고, 본문에는 토큰 없이 직원 정보와 만료 시각을 준다")
	void login_sets_session_cookie_and_returns_employee() throws Exception {
		// given
		given(authService.login("owner", "correct-horse-9"))
				.willReturn(new LoginResult(OWNER, new IssuedToken(TOKEN_VALUE, EXPIRES_AT, Duration.ofHours(16))));

		// when / then
		login("{\"loginId\":\"owner\",\"password\":\"correct-horse-9\"}")
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
						containsString("CB_SESSION=" + TOKEN_VALUE),
						containsString("Path=/api"),
						containsString("Max-Age=57600"),
						containsString("HttpOnly"),
						containsString("Secure"),
						containsString("SameSite=Strict"))))
				.andExpect(jsonPath("$.employeeId").value(EMPLOYEE_ID.toString()))
				.andExpect(jsonPath("$.name").value("김사장"))
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.mustChangePassword").value(false))
				.andExpect(jsonPath("$.expiresAt").value("2026-10-08T06:30:00Z"))
				.andExpect(content().string(not(containsString(TOKEN_VALUE))));
	}

	@Test
	@DisplayName("아이디·비밀번호가 맞지 않으면 사유와 관계없이 401 INVALID_CREDENTIALS 로 응답하고 쿠키를 심지 않는다")
	void login_failure_returns_401_without_cookie() throws Exception {
		// given
		given(authService.login(anyString(), anyString())).willThrow(new InvalidCredentialsException());

		// when / then
		login("{\"loginId\":\"owner\",\"password\":\"wrong\"}")
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
				.andExpect(content().json("""
						{"code":"INVALID_CREDENTIALS","message":"아이디 또는 비밀번호가 올바르지 않습니다.","fieldErrors":[]}
						""", org.springframework.test.json.JsonCompareMode.STRICT));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = {
			"{}",
			"{\"loginId\":\"\",\"password\":\"x\"}",
			"{\"loginId\":\"owner\",\"password\":\"\"}",
			"{\"loginId\":\"owner\"}",
			"{\"password\":\"x\"}" })
	@DisplayName("아이디나 비밀번호를 입력하지 않으면 400 으로 거절하고 로그인을 시도하지 않는다")
	void login_rejects_blank_fields(String body) throws Exception {
		// when / then
		login(body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors").isNotEmpty());
		verify(authService, never()).login(any(), any());
	}

	@Test
	@DisplayName("아이디가 50자를 넘으면 400 으로 거절한다")
	void login_rejects_too_long_login_id() throws Exception {
		// when / then
		login("{\"loginId\":\"" + "a".repeat(51) + "\",\"password\":\"x\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("loginId"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("아이디는 50자 이하여야 합니다."));
		verify(authService, never()).login(any(), any());
	}

	@Test
	@DisplayName("만료되거나 폐기된 쿠키가 남아 있어도 다시 로그인할 수 있다")
	void login_ignores_stale_cookie() throws Exception {
		// given
		given(authService.login("owner", "correct-horse-9"))
				.willReturn(new LoginResult(OWNER, new IssuedToken(TOKEN_VALUE, EXPIRES_AT, Duration.ofHours(16))));

		// when / then
		mockMvc.perform(post("/api/auth/login")
				.cookie(new Cookie("CB_SESSION", "stale.invalid.token"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"loginId\":\"owner\",\"password\":\"correct-horse-9\"}"))
				.andExpect(status().isOk());
	}

	// ---- 로그아웃

	@Test
	@DisplayName("로그아웃하면 쿠키의 토큰을 폐기하고 204 와 쿠키 삭제로 응답한다")
	void logout_revokes_cookie_token_and_clears_cookie() throws Exception {
		// when / then
		mockMvc.perform(post("/api/auth/logout").cookie(new Cookie("CB_SESSION", "some.session.token")))
				.andExpect(status().isNoContent())
				.andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
						containsString("CB_SESSION=;"),
						containsString("Path=/api"),
						containsString("Max-Age=0"))));
		verify(authService).logout("some.session.token");
	}

	@Test
	@DisplayName("쿠키 없이 로그아웃해도 204 로 응답한다")
	void logout_without_cookie_returns_204() throws Exception {
		// when / then
		mockMvc.perform(post("/api/auth/logout"))
				.andExpect(status().isNoContent())
				.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
		verify(authService).logout(null);
	}

	// ---- 세션 조회와 쿠키 토큰 인증

	@Test
	@DisplayName("로그인하지 않고 세션을 조회하면 401 UNAUTHENTICATED 로 응답한다")
	void session_without_login_returns_401() throws Exception {
		// when / then
		mockMvc.perform(get("/api/auth/session"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().json("""
						{"code":"UNAUTHENTICATED","message":"로그인이 필요합니다.","fieldErrors":[]}
						""", org.springframework.test.json.JsonCompareMode.STRICT));
	}

	@Test
	@DisplayName("유효한 세션 쿠키로 세션을 조회하면 직원 정보와 토큰 만료 시각을 준다")
	void session_with_valid_cookie_returns_employee() throws Exception {
		// given
		IssuedToken token = tokenService.issue(EMPLOYEE_ID);
		given(employeeRepository.findAuthStatus(any(), any()))
				.willReturn(Optional.of(new EmployeeAuthStatus(true, Role.ADMIN, false)));
		given(authService.session(EMPLOYEE_ID)).willReturn(OWNER);

		// when / then
		mockMvc.perform(get("/api/auth/session").cookie(new Cookie("CB_SESSION", token.value())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.employeeId").value(EMPLOYEE_ID.toString()))
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.expiresAt").value(token.expiresAt().toString()));
	}

	@Test
	@WithMockEmployee(id = "7d0c2f4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f", role = "ADMIN")
	@DisplayName("로그인한 직원으로 세션을 조회할 수 있다")
	void session_with_mock_employee_returns_employee() throws Exception {
		// given
		given(authService.session(EMPLOYEE_ID)).willReturn(OWNER);

		// when / then
		mockMvc.perform(get("/api/auth/session"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("김사장"));
	}

	@ParameterizedTest(name = "[{index}] active={0}, revoked={1}")
	@org.junit.jupiter.params.provider.CsvSource({ "false, false", "true, true" })
	@DisplayName("비활성 직원의 토큰이나 로그아웃한 토큰으로 요청하면 401 로 거절한다")
	void inactive_or_revoked_token_is_rejected(boolean active, boolean revoked) throws Exception {
		// given
		IssuedToken token = tokenService.issue(EMPLOYEE_ID);
		given(employeeRepository.findAuthStatus(any(), any()))
				.willReturn(Optional.of(new EmployeeAuthStatus(active, Role.STAFF, revoked)));

		// when / then
		mockMvc.perform(get("/api/auth/session").cookie(new Cookie("CB_SESSION", token.value())))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		verify(authService, never()).session(any());
	}

	@Test
	@DisplayName("없는 직원의 토큰으로 요청하면 401 로 거절한다")
	void token_of_unknown_employee_is_rejected() throws Exception {
		// given: 목 저장소는 기본으로 빈 Optional 을 돌려준다
		IssuedToken token = tokenService.issue(EMPLOYEE_ID);

		// when / then
		mockMvc.perform(get("/api/auth/session").cookie(new Cookie("CB_SESSION", token.value())))
				.andExpect(status().isUnauthorized());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "garbage", "a.b.c", "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0." })
	@DisplayName("형식이 깨진 토큰으로 요청하면 500 이 아니라 401 로 거절한다")
	void malformed_token_is_rejected_with_401(String value) throws Exception {
		mockMvc.perform(get("/api/auth/session").cookie(new Cookie("CB_SESSION", value)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("서명을 바꾼 토큰으로 요청하면 401 로 거절한다")
	void tampered_token_is_rejected_with_401() throws Exception {
		// given
		given(employeeRepository.findAuthStatus(any(), any()))
				.willReturn(Optional.of(new EmployeeAuthStatus(true, Role.ADMIN, false)));
		String value = tokenService.issue(EMPLOYEE_ID).value();
		char last = value.charAt(value.length() - 1);
		String tampered = value.substring(0, value.length() - 1) + (last == 'A' ? 'B' : 'A');

		// when / then
		mockMvc.perform(get("/api/auth/session").cookie(new Cookie("CB_SESSION", tampered)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("다른 키로 서명한 토큰으로 요청하면 401 로 거절한다")
	void token_signed_with_other_key_is_rejected_with_401() throws Exception {
		// given
		given(employeeRepository.findAuthStatus(any(), any()))
				.willReturn(Optional.of(new EmployeeAuthStatus(true, Role.ADMIN, false)));
		String foreign = JwtTestSupport.tokenService(JwtTestSupport.randomProperties(), java.time.Clock.systemUTC())
				.issue(EMPLOYEE_ID).value();

		// when / then
		mockMvc.perform(get("/api/auth/session").cookie(new Cookie("CB_SESSION", foreign)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("만료된 토큰으로 요청하면 401 로 거절한다")
	void expired_token_is_rejected_with_401() throws Exception {
		// given
		given(employeeRepository.findAuthStatus(any(), any()))
				.willReturn(Optional.of(new EmployeeAuthStatus(true, Role.ADMIN, false)));
		Instant past = Instant.now().minus(Duration.ofHours(17));
		String expired = encode(JwtClaimsSet.builder().subject(EMPLOYEE_ID.toString()).id(UUID.randomUUID().toString())
				.issuedAt(past).expiresAt(past.plus(Duration.ofHours(16))).build());

		// when / then
		mockMvc.perform(get("/api/auth/session").cookie(new Cookie("CB_SESSION", expired)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("서명은 맞지만 sub 가 UUID 가 아닌 토큰으로 요청하면 500 이 아니라 401 로 거절한다")
	void token_with_non_uuid_sub_is_rejected_with_401() throws Exception {
		// given
		Instant now = Instant.now();
		String value = encode(JwtClaimsSet.builder().subject("not-a-uuid").id(UUID.randomUUID().toString())
				.issuedAt(now).expiresAt(now.plusSeconds(600)).build());

		// when / then
		mockMvc.perform(get("/api/auth/session").cookie(new Cookie("CB_SESSION", value)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("Authorization 헤더로 보낸 토큰은 받지 않는다")
	void bearer_header_is_not_accepted() throws Exception {
		// given
		given(employeeRepository.findAuthStatus(any(), any()))
				.willReturn(Optional.of(new EmployeeAuthStatus(true, Role.ADMIN, false)));
		String value = tokenService.issue(EMPLOYEE_ID).value();

		// when / then
		mockMvc.perform(get("/api/auth/session").header(HttpHeaders.AUTHORIZATION, "Bearer " + value))
				.andExpect(status().isUnauthorized());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "/api/no-such-path", "/api/auth/login", "/api/auth/logout", "/actuator/env" })
	@DisplayName("공개 경로가 아닌 곳(없는 경로·GET 로그인 포함)을 로그인 없이 부르면 401 로 거절한다")
	void non_public_paths_require_login(String path) throws Exception {
		mockMvc.perform(get(path))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	private String encode(JwtClaimsSet claims) {
		return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

}
