package com.creditbook.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.Role;
import com.creditbook.customer.application.CustomerService;
import com.creditbook.customer.application.RegisteredCustomer;
import com.creditbook.global.security.JwtTokenService;
import com.creditbook.global.security.SessionCookies;
import com.creditbook.support.RequiresDocker;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

// 보안 점검(REQ-27) 중 자동으로 매번 확인할 두 가지
// TC-23 조회 응답에 비밀번호 해시·토큰·연락처 원문 같은 게 섞여 나가지 않는다
// TC-24 오류 응답(400·404·500)에 스택트레이스·SQL·파일 경로가 나가지 않는다
@AutoConfigureMockMvc
@RequiresDocker
@Import({ TestcontainersConfiguration.class, SecurityExposureApiIntegrationTest.BoomController.class })
@SpringBootTest
@Transactional
@ExtendWith(OutputCaptureExtension.class)
@Tag("REQ-27")
class SecurityExposureApiIntegrationTest {

	// 응답 본문에 있으면 안 되는 내부 정보 흔적
	private static final List<String> INTERNAL_TRACES = List.of(
			"Exception", "at com.", "at org.", "SELECT", "INSERT", ".java", "creditbook/", "trace");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	EmployeeRepository employeeRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JwtTokenService jwtTokenService;

	@Autowired
	CustomerService customerService;

	@Autowired
	EntityManager entityManager;

	@Autowired
	Clock clock;

	@Test
	@Tag("TC-23")
	@DisplayName("주요 조회 API 5개 응답에 비밀번호 해시·토큰·연락처 원문이 없다")
	void read_apis_do_not_expose_secrets_or_raw_phone() throws Exception {
		// given 직원 로그인, 고객 1명 등록하고 10,000원 충전 (연락처 01012342323)
		Employee staff = saveStaff("tc23-staff");
		Cookie session = loginAs(staff);
		RegisteredCustomer customer = customerService.register("TC23노출", "01012342323");
		charge(session, customer.customerId());
		entityManager.flush();

		// when 조회 API 5개를 부르고 본문을 모은다
		List<String> bodies = List.of(
				bodyOf(get("/api/auth/session"), session),
				bodyOf(get("/api/customers"), session),
				bodyOf(get("/api/customers/{id}", customer.customerId()), session),
				bodyOf(get("/api/customers/{id}/transactions", customer.customerId()), session),
				bodyOf(get("/api/settings/ledger-input"), session));

		// then 어느 응답에도 금지 항목이 없다
		for (String body : bodies) {
			assertThat(body)
					.doesNotContain("\"password\"", "passwordHash", "$2a$", "secret", "\"token\"", "\"version\"")
					.doesNotContain(staff.getPasswordHash()) // 이 직원의 실제 해시값
					.doesNotContain("01012342323", "010-1234-2323") // 연락처 원문 (하이픈 있든 없든)
					.doesNotContainPattern("\"01[0-9]{8,9}\""); // 값 전체가 연락처 원문인 칸
		}
		// 목록·상세에는 가린 번호만 나간다
		assertThat(bodies.get(1)).contains("010-****-2323");
		assertThat(bodies.get(2)).contains("010-****-2323");
	}

	@Test
	@Tag("TC-24")
	@DisplayName("깨진 JSON·타입 오류는 400, 없는 경로는 404 — 표준 문구만 나가고 내부 정보는 없다")
	void client_errors_return_standard_message_without_internals() throws Exception {
		// given 직원 로그인, 고객 1명
		Cookie session = loginAsNewStaff("tc24-staff");
		RegisteredCustomer customer = customerService.register("TC24오류", "01012342424");
		entityManager.flush();

		// when·then 깨진 JSON → 400 MALFORMED_REQUEST
		String brokenJson = errorBodyOf(
				chargeRequest(session, customer.customerId(), "{\"amount\":")
						.andExpect(status().isBadRequest())
						.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
						.andExpect(jsonPath("$.message").value("요청 본문을 읽을 수 없습니다.")));

		// when·then 금액 자리에 글자 → 400 MALFORMED_REQUEST
		String wrongType = errorBodyOf(
				chargeRequest(session, customer.customerId(), "{\"amount\":\"abc\"}")
						.andExpect(status().isBadRequest())
						.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST")));

		// when·then 없는 경로 → 404 NOT_FOUND
		String noPath = errorBodyOf(
				mockMvc.perform(withSession(get("/api/nope"), session))
						.andExpect(status().isNotFound())
						.andExpect(jsonPath("$.code").value("NOT_FOUND"))
						.andExpect(jsonPath("$.message").value("요청한 경로를 찾을 수 없습니다.")));

		// then 세 응답 모두 내부 정보 흔적이 없다
		for (String body : List.of(brokenJson, wrongType, noPath)) {
			assertThat(body).doesNotContain(INTERNAL_TRACES);
		}
	}

	@Test
	@Tag("TC-24")
	@DisplayName("서버 내부 오류(500)는 표준 문구만 응답하고, 스택트레이스는 서버 로그에만 남는다")
	void server_error_hides_internals_but_logs_stack_trace(CapturedOutput output) throws Exception {
		// given 직원 로그인
		Cookie session = loginAsNewStaff("tc24-boom");

		// when 일부러 예외를 던지는 테스트 전용 경로 호출
		String body = errorBodyOf(
				mockMvc.perform(withSession(get(BoomController.PATH), session))
						.andExpect(status().isInternalServerError())
						.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
						.andExpect(jsonPath("$.message").value("일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.")));

		// then 응답에는 예외 이름·메시지·스택이 없다
		assertThat(body).doesNotContain(INTERNAL_TRACES).doesNotContain(BoomController.SECRET_DETAIL);
		// then 서버 로그에는 같은 예외가 스택트레이스와 함께 남아 있다
		assertThat(output.getOut())
				.contains("unexpected error")
				.contains(BoomController.SECRET_DETAIL)
				.contains("at com.creditbook.global.SecurityExposureApiIntegrationTest$BoomController");
	}

	@Test
	@Tag("TC-55")
	@DisplayName("충전 요청에 balance 를 끼워 넣으면 400 INVALID_INPUT 로 거절하고, 잔액은 그대로이며 시도가 WARN 로그에 남는다")
	void unknown_field_in_request_is_rejected_and_logged(CapturedOutput output) throws Exception {
		// given 직원 로그인, 고객 1명 10,000원 충전
		Cookie session = loginAsNewStaff("unknown-field");
		RegisteredCustomer customer = customerService.register("끼워넣기", "01012342525");
		charge(session, customer.customerId());
		entityManager.flush();

		// when 충전 본문에 balance 를 끼워 보낸다
		chargeRequest(session, customer.customerId(), "{\"amount\": 1000, \"balance\": 999999}")
				// then 400 INVALID_INPUT, 어느 항목인지 알려 준다
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("balance"));

		// then 잔액은 10,000원 그대로 (충전도 안 됨)
		mockMvc.perform(withSession(get("/api/customers/{id}", customer.customerId()), session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balance").value(10000));
		// then 서버 로그에 시도가 남는다 — 항목 이름만, 끼운 값은 없다
		assertThat(output.getOut())
				.contains("unknown field rejected: method=POST path=/api/customers/" + customer.customerId() + "/charges field=balance")
				.doesNotContain("999999");
	}

	// 테스트에서만 등록되는 경로 — 운영 코드에는 없다
	@RestController
	static class BoomController {
		static final String PATH = "/api/test-only/boom";
		static final String SECRET_DETAIL = "SELECT * FROM employees -- 내부 정보";

		@GetMapping(PATH)
		String boom() {
			throw new IllegalStateException(SECRET_DETAIL);
		}
	}

	// 충전 API 호출 (조회할 거래를 만들려고)
	private void charge(Cookie session, UUID customerId) throws Exception {
		chargeRequest(session, customerId, "{\"amount\": 10000}").andExpect(status().isCreated());
	}

	// 충전 API에 본문을 그대로 보낸다 (깨진 JSON 시험용)
	private ResultActions chargeRequest(Cookie session, UUID customerId, String content) throws Exception {
		return mockMvc.perform(withSession(post("/api/customers/{id}/charges", customerId), session)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.content(content));
	}

	// 조회 → 200 확인 → 본문 문자열
	private String bodyOf(MockHttpServletRequestBuilder request, Cookie session) throws Exception {
		return mockMvc.perform(withSession(request, session))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private static String errorBodyOf(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	// 직원 저장 (해시값을 응답과 비교하려고 직원 객체를 돌려준다)
	private Employee saveStaff(String loginId) {
		Employee employee = Employee.create(loginId, passwordEncoder.encode("test1234!"), "테스트직원", Role.STAFF,
				clock.instant());
		employeeRepository.add(employee);
		return employee;
	}

	// 직원 저장 → 진짜 토큰 발급 → 로그인 쿠키(CB_SESSION)에 담기
	private Cookie loginAsNewStaff(String loginId) {
		return loginAs(saveStaff(loginId));
	}

	// 이미 저장한 직원으로 로그인 쿠키 만들기 (TC-23 은 그 직원의 해시값을 응답과 비교해야 해서 따로 둔다)
	private Cookie loginAs(Employee employee) {
		return new Cookie(SessionCookies.NAME, jwtTokenService.issue(employee.getId()).value());
	}

	private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder request, Cookie session) {
		return request.cookie(session).contentType(MediaType.APPLICATION_JSON);
	}
}
