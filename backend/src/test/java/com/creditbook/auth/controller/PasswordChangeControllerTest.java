package com.creditbook.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.creditbook.auth.application.AuthService;
import com.creditbook.auth.application.AuthenticatedEmployee;
import com.creditbook.auth.domain.AccountLockedException;
import com.creditbook.auth.domain.EmployeeAuthStatus;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.InvalidCurrentPasswordException;
import com.creditbook.auth.domain.PasswordUnchangedException;
import com.creditbook.auth.domain.Role;
import com.creditbook.global.security.JwtTokenService;
import com.creditbook.global.security.JwtTokenService.IssuedToken;
import com.creditbook.global.security.WebSecurityTestConfig;

import jakarta.servlet.http.Cookie;

/**
 * 비밀번호 변경 API 와 비밀번호 변경 필요 계정의 차단 (REQ-30). 서비스는 목, 보안 구성·JWT 검증은 운영과 같다.
 * 쿠키 토큰 + 직원 상태 조회(목 저장소)로 인증해, 변경 필요 여부가 권한에 반영되는 경로까지 본다.
 */
@WebMvcTest(AuthController.class)
@Import(WebSecurityTestConfig.class)
@Tag("REQ-30")
class PasswordChangeControllerTest {

	private static final UUID EMPLOYEE_ID = UUID.fromString("7d0c2f4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f");
	private static final String VALID_BODY = """
			{"currentPassword":"Rv7m-Kq2x-9Tn4","newPassword":"my-new-pass-77","newPasswordConfirm":"my-new-pass-77"}
			""";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtTokenService tokenService;

	@MockitoBean
	AuthService authService;

	@MockitoBean
	EmployeeRepository employeeRepository;

	private IssuedToken token;
	private Cookie session;

	@BeforeEach
	void setUp() {
		token = tokenService.issue(EMPLOYEE_ID);
		session = new Cookie("CB_SESSION", token.value());
	}

	private void givenStatus(Role role, boolean mustChangePassword) {
		given(employeeRepository.findAuthStatus(any(), any()))
				.willReturn(Optional.of(new EmployeeAuthStatus(true, role, false, mustChangePassword)));
	}

	private ResultActions changePassword(String body) throws Exception {
		return mockMvc.perform(put("/api/auth/password").cookie(session)
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@ParameterizedTest(name = "[{index}] role={0}, mustChange={1}")
	@CsvSource({ "STAFF, true", "STAFF, false", "ADMIN, false" })
	@DisplayName("로그인한 직원은 비밀번호를 바꿀 수 있고, 세션 조회와 같은 모양(만료 시각은 지금 토큰의 만료)으로 응답한다")
	void change_password_returns_session(Role role, boolean mustChange) throws Exception {
		// given
		givenStatus(role, mustChange);
		given(authService.changePassword(EMPLOYEE_ID, "Rv7m-Kq2x-9Tn4", "my-new-pass-77"))
				.willReturn(new AuthenticatedEmployee(EMPLOYEE_ID, "이직원", role, false));

		// when / then
		changePassword(VALID_BODY)
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
				.andExpect(content().json("""
						{"employeeId":"7d0c2f4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f","name":"이직원","role":"%s",
						 "mustChangePassword":false,"expiresAt":"%s"}
						""".formatted(role, token.expiresAt()), org.springframework.test.json.JsonCompareMode.STRICT));
	}

	@Test
	@DisplayName("비밀번호를 바꿔야 하는 계정도 세션 조회와 로그아웃은 할 수 있다")
	void must_change_account_can_read_session_and_logout() throws Exception {
		// given
		givenStatus(Role.STAFF, true);
		given(authService.session(EMPLOYEE_ID)).willReturn(new AuthenticatedEmployee(EMPLOYEE_ID, "이직원", Role.STAFF, true));

		// when / then
		mockMvc.perform(get("/api/auth/session").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mustChangePassword").value(true));
		mockMvc.perform(post("/api/auth/logout").cookie(session))
				.andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("로그인하지 않고 비밀번호를 바꾸려 하면 401 로 거절한다")
	void unauthenticated_is_rejected() throws Exception {
		mockMvc.perform(put("/api/auth/password").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		verify(authService, never()).changePassword(any(), any(), any());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@CsvSource(delimiter = '|', value = {
			"currentPassword|{\"newPassword\":\"my-new-pass-77\",\"newPasswordConfirm\":\"my-new-pass-77\"}",
			"currentPassword|{\"currentPassword\":\"\",\"newPassword\":\"my-new-pass-77\",\"newPasswordConfirm\":\"my-new-pass-77\"}",
			"newPassword|{\"currentPassword\":\"Rv7m-Kq2x-9Tn4\",\"newPassword\":\"short77\",\"newPasswordConfirm\":\"short77\"}",
			"newPasswordConfirm|{\"currentPassword\":\"Rv7m-Kq2x-9Tn4\",\"newPassword\":\"my-new-pass-77\",\"newPasswordConfirm\":\"my-new-pass-78\"}",
			"newPasswordConfirm|{\"currentPassword\":\"Rv7m-Kq2x-9Tn4\",\"newPassword\":\"my-new-pass-77\"}",
			"password|{\"currentPassword\":\"Rv7m-Kq2x-9Tn4\",\"password\":\"x\",\"newPassword\":\"my-new-pass-77\",\"newPasswordConfirm\":\"my-new-pass-77\"}" })
	@DisplayName("현재 비밀번호가 비었거나, 새 비밀번호가 8자 미만이거나, 확인이 다르거나, 받지 않는 항목이면 400 INVALID_INPUT 의 그 항목으로 거절한다")
	void invalid_request_is_rejected(String field, String body) throws Exception {
		// given
		givenStatus(Role.STAFF, true);

		// when / then
		changePassword(body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value(field));
		verify(authService, never()).changePassword(any(), any(), any());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@CsvSource({ "currentPassword", "newPassword" })
	@DisplayName("현재·새 비밀번호가 72바이트를 넘으면 500 이 아니라 400 으로 거절한다 (한글 25자 = 75바이트)")
	void password_over_72_bytes_is_400(String field) throws Exception {
		// given
		givenStatus(Role.STAFF, true);
		String tooLong = "가".repeat(25);
		String current = field.equals("currentPassword") ? tooLong : "Rv7m-Kq2x-9Tn4";
		String next = field.equals("newPassword") ? tooLong : "my-new-pass-77";

		// when / then
		changePassword("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next
				+ "\",\"newPasswordConfirm\":\"" + next + "\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value(field));
		verify(authService, never()).changePassword(any(), any(), any());
	}

	@Test
	@DisplayName("현재 비밀번호가 틀리면 400 INVALID_CURRENT_PASSWORD, 새 비밀번호가 같으면 400 PASSWORD_UNCHANGED 로 응답한다")
	void service_rejections_are_mapped() throws Exception {
		// given
		givenStatus(Role.STAFF, true);
		given(authService.changePassword(any(), any(), any()))
				.willThrow(new InvalidCurrentPasswordException())
				.willThrow(new PasswordUnchangedException());

		// when / then
		changePassword(VALID_BODY)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"))
				.andExpect(jsonPath("$.message").value("현재 비밀번호가 올바르지 않습니다."));
		changePassword(VALID_BODY)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PASSWORD_UNCHANGED"))
				.andExpect(jsonPath("$.message").value("현재 비밀번호와 다른 비밀번호를 입력해 주세요."));
	}

	@Test
	@DisplayName("잠긴 계정이면 423 ACCOUNT_LOCKED 와 남은 초를 details·Retry-After 에 싣는다")
	void locked_account_is_423() throws Exception {
		// given
		givenStatus(Role.STAFF, false);
		given(authService.changePassword(any(), any(), any()))
				.willThrow(new AccountLockedException(Duration.ofSeconds(299).plusMillis(1)));

		// when / then
		changePassword(VALID_BODY)
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
				.andExpect(jsonPath("$.details.retryAfterSeconds").value(300))
				.andExpect(header().string(HttpHeaders.RETRY_AFTER, "300"));
	}

	@Test
	@DisplayName("비밀번호를 바꿔야 하는 계정이 다른 기능을 부르면 403 PASSWORD_CHANGE_REQUIRED 로 거절된다")
	void must_change_account_is_blocked_elsewhere() throws Exception {
		// given
		givenStatus(Role.STAFF, true);

		// when / then — 다른 로그인 필요 경로(이 슬라이스에는 컨트롤러가 없어도 권한 단계에서 먼저 막힌다)
		mockMvc.perform(get("/api/customers").cookie(session))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
				.andExpect(jsonPath("$.message").value("비밀번호를 변경한 뒤 이용해 주세요."));
		mockMvc.perform(get("/api/settings/ledger-input").cookie(session))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
	}

	@Test
	@DisplayName("비밀번호 변경이 끝난 계정은 다른 로그인 필요 경로에서 차단되지 않는다 (없는 경로는 404)")
	void ready_account_is_not_blocked() throws Exception {
		// given
		givenStatus(Role.STAFF, false);

		// when / then — 이 슬라이스에는 고객 컨트롤러가 없으므로 권한을 통과하면 404
		mockMvc.perform(get("/api/customers").cookie(session))
				.andExpect(status().isNotFound());
	}

}
