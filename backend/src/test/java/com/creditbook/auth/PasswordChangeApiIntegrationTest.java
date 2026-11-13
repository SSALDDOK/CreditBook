package com.creditbook.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.global.security.SessionCookies;
import com.creditbook.support.RequiresDocker;
import com.creditbook.support.TestEmployees;
import com.creditbook.support.TestStores;
import com.jayway.jsonpath.JsonPath;

/**
 * 비밀번호 변경 API 통합 테스트 (REQ-30·REQ-15·TC-56).
 * <p>
 * 현재 비밀번호를 틀려도 실패 횟수가 DB에 남는지(롤백되지 않는지)를 봐야 해서 클래스에 @Transactional 을 붙이지 않는다 —
 * 데이터는 실제로
 * 커밋되고 테스트가 끝날 때 지운다. 직원은 사장이 API로 추가하고 받은 초기 비밀번호로 로그인해서, TC 사전조건과 같은 상태로
 * 시작한다.
 */
@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PasswordChangeApiIntegrationTest {

	private static final String OWNER_PASSWORD = "owner1234!";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	EmployeeRepository employeeRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	Clock clock;

	// 매장 (REQ-36) — 직원·고객은 매장이 있어야 저장된다. 테스트마다 새 매장
	UUID storeId;

	@BeforeEach
	void createStore() {
		storeId = TestStores.create(jdbcTemplate);
	}

	// 이 클래스가 만든 직원만 지움 (커밋된 데이터라 롤백되지 않음)
	@AfterEach
	void deleteEmployees() {
		jdbcTemplate.update("DELETE FROM employees WHERE login_id LIKE 'tc56.%'");
	}

	// 절차 1·2 강제 변경 계정 차단 (403 PASSWORD_CHANGE_REQUIRED, 세션 조회는 200)
	@Test
	@DisplayName("비밀번호 변경이 필요한 직원 — 고객 목록은 403 PASSWORD_CHANGE_REQUIRED, 세션 조회는 200이고 mustChangePassword가 true")
	@Tag("TC-56")
	@Tag("REQ-30")
	@Tag("REQ-15")
	void staff_must_change_password_before_using_other_apis() throws Exception {
		// given ADMIN 1명(매장 개설 계정, 비밀번호 변경 필요 없음), ADMIN이 추가한 STAFF 1명(초기 비밀번호, 비밀번호 변경
		// 필요)
		Cookie owner = ownerSession("tc56.owner");
		AddedStaff staffAccount = addStaff(owner, "tc56.staff");
		Cookie staff = sessionOf(staffAccount.loginId, staffAccount.initialPassword);

		// when STAFF가 초기 비밀번호로 로그인한 뒤 고객 목록(GET /api/customers)을 호출한다
		mockMvc.perform(withSession(get("/api/customers"), staff))
				// then 403 PASSWORD_CHANGE_REQUIRED '비밀번호를 변경한 뒤 이용해 주세요.'
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
				.andExpect(jsonPath("$.message").value("비밀번호를 변경한 뒤 이용해 주세요."));

		// when STAFF가 초기 비밀번호로 로그인한 뒤 세션 조회(GET /api/auth/session) 호출한다
		mockMvc.perform(withSession(get("/api/auth/session"), staff))
				// then 200,mustChangePassword = true
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mustChangePassword").value(true));
	}

	// 절차 3·4·5·6 — 변경 규칙 (틀린 현재 비밀번호, 같은 비밀번호, 확인 불일치, 정상 변경)
	@Test
	@DisplayName("비밀번호 변경 — 현재 비밀번호 틀림·같은 비밀번호·확인 불일치는 400, 올바르게 바꾸면 200이고 고객 목록이 열린다")
	@Tag("TC-56")
	@Tag("REQ-30")
	@Tag("REQ-15")
	void change_password_rejects_invalid_requests_then_succeeds() throws Exception {
		// given ADMIN 1명(매장 개설 계정), ADMIN이 추가한 STAFF 1명(초기 비밀번호, 비밀번호 변경 전)
		Cookie owner = ownerSession("tc56.owner");
		AddedStaff staffAccount = addStaff(owner, "tc56.staff");
		String initial = staffAccount.initialPassword;
		Cookie staff = sessionOf(staffAccount.loginId, initial);
		String wrongPassword = "wrong.password";

		// when 3 (틀린 값, NEW_PASSWORD, NEW_PASSWORD)
		changePassword(staff, wrongPassword, "tc56.password", "tc56.password")
				// then 400 INVALID_CURRENT_PASSWORD '현재 비밀번호가 올바르지 않습니다.'
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"))
				.andExpect(jsonPath("$.message").value("현재 비밀번호가 올바르지 않습니다."));

		// when 4 (initial, initial, initial)
		changePassword(staff, initial, initial, initial)
				// then 400 PASSWORD_UNCHANGED '현재 비밀번호와 다른 비밀번호를 입력해 주세요.'
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PASSWORD_UNCHANGED"))
				.andExpect(jsonPath("$.message").value("현재 비밀번호와 다른 비밀번호를 입력해 주세요."));

		// when 5 (initial, NEW_PASSWORD, 다른 값)
		changePassword(staff, initial, "tc56.password", wrongPassword)
				// then 400 INVALID_INPUT, $.fieldErrors[0].field: newPasswordConfirm
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("newPasswordConfirm"));

		// when 6 (initial, NEW_PASSWORD, NEW_PASSWORD)
		changePassword(staff, initial, "tc56.password", "tc56.password")
				// then 200, $.mustChangePassword: false
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mustChangePassword").value(false))
				.andExpect(content().string(not(containsString("passwordHash"))))
				.andExpect(content().string(not(containsString("tc56.password"))));

		// then 고객 목록 200
		mockMvc.perform(withSession(get("/api/customers"), staff))
				.andExpect(status().isOk());

		// then 실패 횟수 0 확인
		assertThat(failedCountOf(staffAccount.loginId)).isZero();
	}

	// 절차 7 — 사장이 초기화하면 기존 세션도 다음 요청부터 403
	@Test
	@DisplayName("사장이 비밀번호를 초기화하면 직원의 기존 세션은 다음 요청부터 403 PASSWORD_CHANGE_REQUIRED")
	@Tag("TC-56")
	@Tag("REQ-30")
	@Tag("REQ-15")
	void password_reset_blocks_existing_session_on_next_request() throws Exception {
		// given ADMIN 1명(매장 개설 계정), ADMIN이 추가한 STAFF 1명(초기 비밀번호, 비밀번호 변경 완료)
		Cookie owner = ownerSession("tc56.owner");
		AddedStaff staffAccount = addStaff(owner, "tc56.staff");
		Cookie staff = sessionOf(staffAccount.loginId, staffAccount.initialPassword);
		// STAFF 비밀번호 변경
		changePassword(staff, staffAccount.initialPassword, "tc56.password", "tc56.password")
				.andExpect(status().isOk());

		// STAFF 비밀번호 변경 후 고객 목록(GET /api/customers)을 호출한다 200
		mockMvc.perform(withSession(get("/api/customers"), staff))
				.andExpect(status().isOk());

		// when 사장이 staff 비밀번호 초기화 (POST /api/employees/{id}/reset-password) ,200
		mockMvc.perform(withSession(post("/api/employees/{id}/reset-password", staffAccount.employeeId), owner))
				.andExpect(status().isOk());

		// when STAFF가 고객 목록 (GET /api/customers)을 호출한다 403
		mockMvc.perform(withSession(get("/api/customers"), staff))
				// then 403 PASSWORD_CHANGE_REQUIRED '비밀번호를 변경한 뒤 이용해 주세요.'
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
				.andExpect(jsonPath("$.message").value("비밀번호를 변경한 뒤 이용해 주세요."));
	}

	// 절차 8 — 5회 연속 실패 잠금 (변경·로그인 모두 423)
	@Test
	@DisplayName("현재 비밀번호 5회 연속 실패 — 1~4회는 400, 5번째부터 423이고 잠긴 동안 올바른 비밀번호로 변경·로그인 모두 423")
	@Tag("TC-56")
	@Tag("REQ-30")
	@Tag("REQ-15")
	void five_wrong_current_passwords_lock_account_for_change_and_login() throws Exception {
		// given ADMIN 1명(매장 개설 계정), ADMIN이 추가한 STAFF 1명(초기 비밀번호, 비밀번호 변경 전)
		Cookie owner = ownerSession("tc56.owner");
		AddedStaff staffAccount = addStaff(owner, "tc56.staff");
		Cookie staff = sessionOf(staffAccount.loginId, staffAccount.initialPassword);
		String wrongPassword = "wrong.password";

		// when STAFF 현재 비밀번호 4회 연속 실패 (비밀번호 변경시)
		for (int i = 0; i < 4; i++) {
			changePassword(staff, wrongPassword, "tc56.password", "tc56.password")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
		}
		// STAFF 현재 비밀번호 5회 연속 실패 (비밀번호 변경시)
		changePassword(staff, wrongPassword, "tc56.password", "tc56.password")
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
				.andExpect(jsonPath("$.details.retryAfterSeconds").exists())
				.andExpect(header().exists("Retry-After"));

		// then STAFF 잠긴 후 올바른 초기 비밀번호 넣기(비밀번호 변경시) , 423
		changePassword(staff, staffAccount.initialPassword, "tc56.password", "tc56.password")
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

		// then STAFF 잠긴 후 올바른 초기 비밀번호 넣기(로그인 시) , 423
		login("tc56.staff", staffAccount.initialPassword)
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

	}

	// 사장 저장 후 로그인 쿠키까지 (트랜잭션을 열어 저장하고 바로 커밋)
	private Cookie ownerSession(String loginId) throws Exception {
		transactionTemplate.executeWithoutResult(status -> {
			Employee owner = TestEmployees.owner(storeId, loginId, passwordEncoder.encode(OWNER_PASSWORD), "테스트사장",
					clock.instant());
			employeeRepository.add(owner);
		});
		return sessionOf(loginId, OWNER_PASSWORD);
	}

	// 사장이 API로 직원 추가 → 응답의 직원 ID·초기 비밀번호 꺼내기
	private AddedStaff addStaff(Cookie owner, String loginId) throws Exception {
		String body = mockMvc.perform(withSession(post("/api/employees"), owner)
				.content("""
						{"name": "새직원", "loginId": "%s"}
						""".formatted(loginId)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return new AddedStaff(JsonPath.read(body, "$.employeeId"), loginId, JsonPath.read(body, "$.initialPassword"));
	}

	// 로그인 요청만 (응답을 직접 확인할 때)
	private ResultActions login(String loginId, String password) throws Exception {
		return mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"loginId": "%s", "password": "%s"}
						""".formatted(loginId, password)));
	}

	// 실제 로그인 → 응답의 세션 쿠키 꺼내기
	private Cookie sessionOf(String loginId, String password) throws Exception {
		return login(loginId, password)
				.andExpect(status().isOk())
				.andReturn().getResponse().getCookie(SessionCookies.NAME);
	}

	private ResultActions changePassword(Cookie session, String current, String next, String confirm) throws Exception {
		return mockMvc.perform(withSession(put("/api/auth/password"), session)
				.content("""
						{"currentPassword": "%s", "newPassword": "%s", "newPasswordConfirm": "%s"}
						""".formatted(current, next, confirm)));
	}

	// DB 칸 직접 읽기
	private Integer failedCountOf(String loginId) {
		return jdbcTemplate.queryForObject("SELECT failed_login_count FROM employees WHERE login_id = ?",
				Integer.class, loginId);
	}

	private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder request, Cookie session) {
		return request.cookie(session).contentType(MediaType.APPLICATION_JSON);
	}

	private record AddedStaff(String employeeId, String loginId, String initialPassword) {
	}

}
