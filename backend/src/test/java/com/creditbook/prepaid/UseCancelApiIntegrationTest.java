package com.creditbook.prepaid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional // 테스트(파라미터 1회)마다 롤백 → 매번 새 USE·새 키
@Tag("REQ-34")
class UseCancelApiIntegrationTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();

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
	JdbcTemplate jdbcTemplate;

	@Autowired
	EntityManager entityManager;

	@Autowired
	Clock clock;

	@ParameterizedTest(name = "사유 {0}자 → {1}")
	@CsvSource({
			// 사유 길이, 기대 상태, 기대 잔액, USE_CANCEL 건수
			"200, 201, 10000, 1",
			"201, 400,  7000, 0"
	})
	@DisplayName("사용 취소 사유 길이 경계 — 200자는 취소되고 201자는 400으로 거절된다")
	@Tag("TC-42")
	void cancel_reason_length_boundary(int length, int expectedStatus, String expectedBalance, int expectedCancels)
			throws Exception {
		// given — 직원 로그인, 고객 충전 10,000 → 사용 3,000, 응답에서 사용 거래 id 꺼내기
		Cookie session = loginAsNewStaff("tc42-staff");
		RegisteredCustomer customer = customerService.register("TC42", "01012345678");
		mockMvc.perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
				.header("Idempotency-Key", "tc42-charge")
				.content("{\"amount\": 10000}"))
				.andExpect(status().isCreated());

		String first = mockMvc.perform(withSession(post("/api/customers/{id}/uses", customer.customerId()), session)
				.header("Idempotency-Key", "tc-42-use")
				.content("{\"amount\": 3000}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String useId = idOf(first);

		// when — POST /api/customers/{customerId}/uses/{useEntryId}/cancel
		// Idempotency-Key 새 값, body {"reason": "가".repeat(length)}
		String reason = "가".repeat(length); // 문자열 만들기
		mockMvc.perform(
				withSession(post("/api/customers/{customerId}/uses/{useEntryId}/cancel", customer.customerId(), useId),
						session)
						.header("Idempotency-Key", "tc42-use-cancel")
						.content(JSON.writeValueAsString(Map.of("reason", reason))))
				.andExpect(status().is(expectedStatus));
		// then — 상태 코드 = expectedStatus
		// 201이면: USE_CANCEL 1건, 잔액 10,000
		// 400이면: USE_CANCEL 0건, 잔액 7,000 (장부 변화 없음)
		entityManager.flush();
		// BigDecimal은 isEqualByComparingTo
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo(expectedBalance);
		assertThat(countEntries(customer.accountId(), "USE_CANCEL")).isEqualTo(expectedCancels);
	}


	// 직원 저장 → 진짜 토큰 발급 → 로그인 쿠키(CB_SESSION)에 담기 (거래에 처리 직원이 실제로 있어야 해서)
	private Cookie loginAsNewStaff(String loginId) {
		Employee employee = Employee.create(loginId, passwordEncoder.encode("test1234!"), "테스트직원", Role.STAFF,
				clock.instant());
		employeeRepository.add(employee);
		return new Cookie(SessionCookies.NAME, jwtTokenService.issue(employee.getId()).value());
	}

	private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder request, Cookie session) {
		return request.cookie(session).contentType(MediaType.APPLICATION_JSON);
	}

	// 응답 JSON에서 "id" 값만 꺼내기
	private static String idOf(String responseBody) {
		JsonNode body = JSON.readTree(responseBody);
		return body.get("id").asString();
	}

	private BigDecimal balanceOf(UUID accountId) {
		return jdbcTemplate.queryForObject("SELECT balance FROM prepaid_accounts WHERE id = ?", BigDecimal.class,
				accountId);
	}

	private int countEntries(UUID accountId, String type) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries WHERE account_id = ? AND type = ?",
				Integer.class, accountId, type);
	}
}
