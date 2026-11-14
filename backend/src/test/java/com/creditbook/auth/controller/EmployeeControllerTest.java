package com.creditbook.auth.controller;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.creditbook.auth.application.EmployeeManagementService;
import com.creditbook.auth.application.EmployeeSummary;
import com.creditbook.auth.application.PasswordResetResult;
import com.creditbook.auth.application.StaffAddedResult;
import com.creditbook.auth.domain.AdminAccountProtectedException;
import com.creditbook.auth.domain.DuplicateLoginIdException;
import com.creditbook.auth.domain.EmployeeAuthStatus;
import com.creditbook.auth.domain.EmployeeNotFoundException;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.Role;
import com.creditbook.global.security.CurrentEmployee;
import com.creditbook.global.security.JwtTokenService;
import com.creditbook.global.security.WebSecurityTestConfig;
import com.creditbook.support.WithMockEmployee;

import jakarta.servlet.http.Cookie;

/**
 * 직원 계정 관리 API 의 권한·응답 형태 (REQ-30). 서비스는 목이고 보안 구성은 운영과 같다 (WebSecurityTestConfig).
 * 비밀번호 변경 필요 계정의 차단은 실제 쿠키 토큰 + 직원 상태 조회(목 저장소)로 본다.
 */
@WebMvcTest(EmployeeController.class)
@Import(WebSecurityTestConfig.class)
@Tag("REQ-30")
class EmployeeControllerTest {

	private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000005001");

	private static final String ADMIN_ID = "00000000-0000-0000-0000-00000000a001";
	private static final UUID OWNER = UUID.fromString(ADMIN_ID);
	private static final UUID STAFF_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final String INITIAL = "Rv7m-Kq2x-9Tn4";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtTokenService tokenService;

	@MockitoBean
	EmployeeManagementService employeeManagementService;

	@MockitoBean
	CurrentEmployee currentEmployee;

	@MockitoBean
	EmployeeRepository employeeRepository;

	@BeforeEach
	void setUp() {
		given(currentEmployee.id()).willReturn(OWNER);
		given(currentEmployee.storeId()).willReturn(STORE_ID);
	}

	private ResultActions addEmployee(String body) throws Exception {
		return mockMvc.perform(post("/api/employees").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	// ---- 목록

	@Test
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("사장이 직원 목록을 조회하면 items 에 이름·아이디·권한·활성 여부·마지막 로그인을 순서대로 싣는다")
	void admin_lists_employees() throws Exception {
		// given
		Instant lastLogin = Instant.parse("2026-11-03T00:00:00Z");
		given(employeeManagementService.list(STORE_ID)).willReturn(List.of(
				new EmployeeSummary(OWNER, "김사장", "kim.owner", Role.ADMIN, true, lastLogin),
				new EmployeeSummary(STAFF_ID, "이직원", "lee.staff", Role.STAFF, false, null)));

		// when / then
		mockMvc.perform(get("/api/employees"))
				.andExpect(status().isOk())
				.andExpect(content().json("""
						{"items":[
						  {"employeeId":"00000000-0000-0000-0000-00000000a001","name":"김사장","loginId":"kim.owner",
						   "role":"ADMIN","active":true,"lastLoginAt":"2026-11-03T00:00:00Z"},
						  {"employeeId":"0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c","name":"이직원","loginId":"lee.staff",
						   "role":"STAFF","active":false,"lastLoginAt":null}
						]}
						""", org.springframework.test.json.JsonCompareMode.STRICT));
	}

	// ---- 권한

	@Test
	@WithMockEmployee(role = "STAFF")
	@DisplayName("직원(STAFF)이 직원 관리 API 를 부르면 모두 403 FORBIDDEN 으로 거절하고 서비스를 부르지 않는다")
	void staff_is_forbidden() throws Exception {
		mockMvc.perform(get("/api/employees"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		addEmployee("{\"name\":\"박직원\",\"loginId\":\"park.staff\"}")
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mockMvc.perform(post("/api/employees/{id}/reset-password", STAFF_ID))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mockMvc.perform(patch("/api/employees/{id}/deactivate", STAFF_ID))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		verifyNoInteractions(employeeManagementService);
	}

	@Test
	@DisplayName("로그인하지 않고 부르면 401 로 거절한다")
	void unauthenticated_is_rejected() throws Exception {
		mockMvc.perform(get("/api/employees"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		verifyNoInteractions(employeeManagementService);
	}

	@Test
	@DisplayName("비밀번호를 바꿔야 하는 계정(사장 포함)은 직원 관리 API 를 쓸 수 없고 403 PASSWORD_CHANGE_REQUIRED 로 거절된다")
	void password_change_required_account_is_blocked() throws Exception {
		// given
		Cookie session = new Cookie("CB_SESSION", tokenService.issue(OWNER).value());
		given(employeeRepository.findAuthStatus(any(), any()))
				.willReturn(Optional.of(new EmployeeAuthStatus(STORE_ID, true, Role.ADMIN, false, true)));

		// when / then
		mockMvc.perform(get("/api/employees").cookie(session))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
				.andExpect(jsonPath("$.message").value("비밀번호를 변경한 뒤 이용해 주세요."));
		verifyNoInteractions(employeeManagementService);
	}

	@Test
	@DisplayName("쿠키 토큰으로 인증한 사장은 직원 관리 API 를 쓸 수 있다")
	void ready_admin_with_cookie_can_list() throws Exception {
		// given
		Cookie session = new Cookie("CB_SESSION", tokenService.issue(OWNER).value());
		given(employeeRepository.findAuthStatus(any(), any()))
				.willReturn(Optional.of(new EmployeeAuthStatus(STORE_ID, true, Role.ADMIN, false, false)));
		given(employeeManagementService.list(STORE_ID)).willReturn(List.of());

		// when / then
		mockMvc.perform(get("/api/employees").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());
	}

	// ---- 직원 추가

	@Test
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("직원을 추가하면 201 과 STAFF 권한·초기 비밀번호를 돌려주고 Location 헤더는 없다")
	void admin_adds_staff() throws Exception {
		// given
		given(employeeManagementService.addStaff(STORE_ID, "박직원", "park.staff", OWNER))
				.willReturn(new StaffAddedResult(STAFF_ID, "박직원", "park.staff", Role.STAFF, INITIAL));

		// when / then
		addEmployee("{\"name\":\"박직원\",\"loginId\":\"park.staff\"}")
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist(HttpHeaders.LOCATION))
				.andExpect(content().json("""
						{"employeeId":"0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c","name":"박직원","loginId":"park.staff",
						 "role":"STAFF","initialPassword":"Rv7m-Kq2x-9Tn4"}
						""", org.springframework.test.json.JsonCompareMode.STRICT));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = {
			"{\"loginId\":\"park.staff\"}",
			"{\"name\":\"   \",\"loginId\":\"park.staff\"}",
			"{\"name\":\"박직원\"}",
			"{\"name\":\"박직원\",\"loginId\":\"abc\"}",
			"{\"name\":\"박직원\",\"loginId\":\"a_b_c_d\"}",
			"{\"name\":\"박직원\",\"loginId\":\"abcdefghijklmnopqrstu\"}" })
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("이름이 없거나 비었거나, 아이디가 영문·숫자·마침표 4–20자가 아니면 400 INVALID_INPUT 으로 거절한다")
	void invalid_add_request_is_rejected(String body) throws Exception {
		addEmployee(body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		verify(employeeManagementService, never()).addStaff(any(), any(), any(), any());
	}

	@Test
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("이름이 51자이면 매장 개설과 같은 문구로 거절한다")
	void name_longer_than_50_is_rejected() throws Exception {
		addEmployee("{\"name\":\"" + "가".repeat(51) + "\",\"loginId\":\"park.staff\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("이름은 1–50자여야 합니다."));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "role", "password", "active" })
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("권한·비밀번호처럼 받지 않는 항목을 보내면 400 으로 거절한다 (권한은 STAFF 로 고정)")
	void unknown_fields_are_rejected(String field) throws Exception {
		addEmployee("{\"name\":\"박직원\",\"loginId\":\"park.staff\",\"" + field + "\":\"ADMIN\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value(field));
		verify(employeeManagementService, never()).addStaff(any(), any(), any(), any());
	}

	@Test
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("같은 아이디가 있으면 409 DUPLICATE_LOGIN_ID 로 응답한다")
	void duplicate_login_id_is_409() throws Exception {
		// given
		given(employeeManagementService.addStaff(any(), any(), any(), any())).willThrow(new DuplicateLoginIdException());

		// when / then
		addEmployee("{\"name\":\"박직원\",\"loginId\":\"lee.staff\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_LOGIN_ID"))
				.andExpect(jsonPath("$.message").value("이미 사용 중인 아이디입니다."));
	}

	// ---- 비밀번호 초기화

	@Test
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("비밀번호를 초기화하면 200 과 새 초기 비밀번호를 돌려준다 (요청 본문 없음)")
	void admin_resets_password() throws Exception {
		// given
		given(employeeManagementService.resetPassword(STORE_ID, STAFF_ID, OWNER))
				.willReturn(new PasswordResetResult(STAFF_ID, INITIAL));

		// when / then
		mockMvc.perform(post("/api/employees/{id}/reset-password", STAFF_ID))
				.andExpect(status().isOk())
				.andExpect(content().json("""
						{"employeeId":"0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c","initialPassword":"Rv7m-Kq2x-9Tn4"}
						""", org.springframework.test.json.JsonCompareMode.STRICT));
	}

	@Test
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("없는 직원이면 404 EMPLOYEE_NOT_FOUND, 사장·본인 계정이면 409 ADMIN_ACCOUNT_PROTECTED 로 응답한다")
	void reset_errors_are_mapped() throws Exception {
		// given
		UUID unknown = UUID.randomUUID();
		given(employeeManagementService.resetPassword(STORE_ID, unknown, OWNER)).willThrow(new EmployeeNotFoundException(unknown));
		given(employeeManagementService.resetPassword(STORE_ID, OWNER, OWNER)).willThrow(new AdminAccountProtectedException());

		// when / then
		mockMvc.perform(post("/api/employees/{id}/reset-password", unknown))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("EMPLOYEE_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("직원을 찾을 수 없습니다."))
				.andExpect(content().string(not(containsString(unknown.toString()))));
		mockMvc.perform(post("/api/employees/{id}/reset-password", OWNER))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ADMIN_ACCOUNT_PROTECTED"))
				.andExpect(jsonPath("$.message").value("사장님 계정은 비활성화하거나 비밀번호를 초기화할 수 없습니다."));
	}

	@Test
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("직원 ID 가 UUID 형식이 아니면 400 INVALID_INPUT 으로 거절한다")
	void non_uuid_id_is_400() throws Exception {
		mockMvc.perform(post("/api/employees/{id}/reset-password", "not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		mockMvc.perform(patch("/api/employees/{id}/deactivate", "not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		verifyNoInteractions(employeeManagementService);
	}

	// ---- 비활성화

	@Test
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("직원을 비활성화하면 204 로 응답하고 본문이 없다")
	void admin_deactivates_staff() throws Exception {
		mockMvc.perform(patch("/api/employees/{id}/deactivate", STAFF_ID))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
		verify(employeeManagementService).deactivate(STORE_ID, STAFF_ID, OWNER);
	}

	@Test
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("사장·본인 계정 비활성화는 409 ADMIN_ACCOUNT_PROTECTED 로 응답한다")
	void deactivate_protected_is_409() throws Exception {
		// given
		willThrow(new AdminAccountProtectedException()).given(employeeManagementService).deactivate(STORE_ID, OWNER, OWNER);

		// when / then
		mockMvc.perform(patch("/api/employees/{id}/deactivate", OWNER))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ADMIN_ACCOUNT_PROTECTED"));
	}

	@Test
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("현재 직원을 알 수 없으면 401 로 거절하고 처리하지 않는다")
	void unknown_current_employee_is_401() throws Exception {
		// given
		given(currentEmployee.id()).willReturn(null);

		// when / then
		mockMvc.perform(patch("/api/employees/{id}/deactivate", STAFF_ID))
				.andExpect(status().isUnauthorized());
		verify(employeeManagementService, never()).deactivate(any(), any(), any());
	}

}
