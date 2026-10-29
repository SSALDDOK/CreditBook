package com.creditbook.prepaid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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

	// ===== TC-42 항목 1·2·3·5 — 인증·정상 취소·사유 필수·대상 검증 =====

	@Test
	@DisplayName("로그인 쿠키 없이 사용 취소를 요청하면 401이고 장부는 그대로다")
	@Tag("TC-42")
	void cancel_without_session_is_rejected_with_401() throws Exception {
		// given — 잔액 7,000원 (10,000 충전 → 3,000 사용)
		Cookie session = loginAsNewStaff("tc42-401-staff");
		RegisteredCustomer customer = customerService.register("TC42인증", "01042420001");
		String useId = chargeThenUse(session, customer, "tc42-401");

		// when — 쿠키 없이 취소
		mockMvc.perform(post("/api/customers/{customerId}/uses/{useEntryId}/cancel",
						customer.customerId(), useId)
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", "tc42-401-cancel")
				.content(reasonBody("주문 착오")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		// then — 취소 거래 없음, 잔액 그대로
		entityManager.flush();
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("7000");
		assertThat(countEntries(customer.accountId(), "USE_CANCEL")).isZero();
	}

	@Test
	@DisplayName("STAFF가 사용(USE) 건에 사유를 입력해 취소하면 원 거래는 남고 반제(USE_CANCEL) 거래가 추가되어 잔액이 사용 금액만큼 복구된다")
	@Tag("TC-42")
	@Tag("REQ-16")
	void staff_cancels_use_and_balance_is_restored() throws Exception {
		// given — STAFF 로그인, 잔액 7,000원
		Cookie session = loginAsNewStaff("tc42-ok-staff");
		RegisteredCustomer customer = customerService.register("TC42정상", "01042420002");
		String useId = chargeThenUse(session, customer, "tc42-ok");

		// when — 사유 넣어 취소
		mockMvc.perform(withSession(post("/api/customers/{customerId}/uses/{useEntryId}/cancel",
						customer.customerId(), useId), session)
				.header("Idempotency-Key", "tc42-ok-cancel")
				.content(reasonBody("주문 착오")))
				.andExpect(status().isCreated())
				// 응답: 반제 거래 1건 — 원 사용 건을 가리키고 잔액이 복구됨
				.andExpect(jsonPath("$.type").value("USE_CANCEL"))
				.andExpect(jsonPath("$.reversesId").value(useId))
				.andExpect(jsonPath("$.amount").value(3000))
				.andExpect(jsonPath("$.balanceAfter").value(10000))
				.andExpect(jsonPath("$.reason").value("주문 착오"));

		// then — 잔액 10,000원, USE_CANCEL 1건 추가, 원 USE는 지워지지 않고 1건 그대로 (append-only)
		entityManager.flush();
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("10000");
		assertThat(countEntries(customer.accountId(), "USE_CANCEL")).isEqualTo(1);
		assertThat(countEntries(customer.accountId(), "USE")).isEqualTo(1);
	}

	@ParameterizedTest(name = "사유 [{0}]")
	@ValueSource(strings = { "", "   " })
	@DisplayName("사유 메모 없이는 취소할 수 없다 — 빈 값·공백은 400 INVALID_INPUT")
	@Tag("TC-42")
	void cancel_with_blank_reason_is_rejected_with_400(String reason) throws Exception {
		// given — 잔액 7,000원
		Cookie session = loginAsNewStaff("tc42-blank-staff");
		RegisteredCustomer customer = customerService.register("TC42사유", "01042420003");
		String useId = chargeThenUse(session, customer, "tc42-blank");

		// when — 빈 사유로 취소
		mockMvc.perform(withSession(post("/api/customers/{customerId}/uses/{useEntryId}/cancel",
						customer.customerId(), useId), session)
				.header("Idempotency-Key", "tc42-blank-cancel")
				.content(reasonBody(reason)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));

		// then — 장부 변화 없음
		entityManager.flush();
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("7000");
		assertThat(countEntries(customer.accountId(), "USE_CANCEL")).isZero();
	}

	@Test
	@DisplayName("사용(USE)이 아닌 충전 거래를 사용 취소하면 400 INVALID_CANCEL_TARGET이고 장부는 그대로다")
	@Tag("TC-42")
	void cancel_of_charge_entry_is_rejected_with_400() throws Exception {
		// given — 충전 거래 ID를 꺼내 둔다
		Cookie session = loginAsNewStaff("tc42-charge-staff");
		RegisteredCustomer customer = customerService.register("TC42충전", "01042420004");
		String chargeBody = mockMvc.perform(withSession(post("/api/customers/{id}/charges", customer.customerId()),
						session)
				.header("Idempotency-Key", "tc42-charge-charge")
				.content("{\"amount\": 10000}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String chargeId = idOf(chargeBody);

		// when — 충전 거래를 사용 취소 경로로
		mockMvc.perform(withSession(post("/api/customers/{customerId}/uses/{useEntryId}/cancel",
						customer.customerId(), chargeId), session)
				.header("Idempotency-Key", "tc42-charge-cancel")
				.content(reasonBody("주문 착오")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CANCEL_TARGET"));

		// then — 잔액 10,000원 그대로, 취소 거래 없음
		entityManager.flush();
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("10000");
		assertThat(countEntries(customer.accountId(), "USE_CANCEL")).isZero();
	}

	@Test
	@DisplayName("다른 계좌의 사용 건은 취소할 수 없다 — 400 INVALID_CANCEL_TARGET, 두 고객 장부 그대로")
	@Tag("TC-42")
	@Tag("REQ-27")
	@Tag("TC-21")
	void cancel_of_other_customers_use_is_rejected_with_400() throws Exception {
		// given — 고객 A·B 각각 잔액 7,000원, B의 사용 거래 ID
		Cookie session = loginAsNewStaff("tc42-idor-staff");
		RegisteredCustomer customerA = customerService.register("TC42고객A", "01042420005");
		RegisteredCustomer customerB = customerService.register("TC42고객B", "01042420006");
		chargeThenUse(session, customerA, "tc42-idor-a");
		String useIdOfB = chargeThenUse(session, customerB, "tc42-idor-b");

		// when — A 경로에 B의 거래 ID를 끼워 넣기 (IDOR 시도)
		mockMvc.perform(withSession(post("/api/customers/{customerId}/uses/{useEntryId}/cancel",
						customerA.customerId(), useIdOfB), session)
				.header("Idempotency-Key", "tc42-idor-cancel")
				.content(reasonBody("주문 착오")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CANCEL_TARGET"));

		// then — 두 고객 모두 잔액 그대로, 취소 거래 없음
		entityManager.flush();
		assertThat(balanceOf(customerA.accountId())).isEqualByComparingTo("7000");
		assertThat(balanceOf(customerB.accountId())).isEqualByComparingTo("7000");
		assertThat(countEntries(customerA.accountId(), "USE_CANCEL")).isZero();
		assertThat(countEntries(customerB.accountId(), "USE_CANCEL")).isZero();
	}

	@Test
	@DisplayName("이미 취소된 사용 건은 다시 취소할 수 없다 — 409 ALREADY_CANCELLED, 잔액은 한 번만 복구")
	@Tag("TC-42")
	void cancel_twice_is_rejected_with_409() throws Exception {
		// given — 사용 건을 한 번 취소해 잔액 10,000원
		Cookie session = loginAsNewStaff("tc42-twice-staff");
		RegisteredCustomer customer = customerService.register("TC42재취소", "01042420007");
		String useId = chargeThenUse(session, customer, "tc42-twice");
		mockMvc.perform(withSession(post("/api/customers/{customerId}/uses/{useEntryId}/cancel",
						customer.customerId(), useId), session)
				.header("Idempotency-Key", "tc42-twice-cancel-1")
				.content(reasonBody("주문 착오")))
				.andExpect(status().isCreated());

		// when — 다른 키로 같은 사용 건을 또 취소 (같은 키면 재응답이라 새 키를 쓴다)
		mockMvc.perform(withSession(post("/api/customers/{customerId}/uses/{useEntryId}/cancel",
						customer.customerId(), useId), session)
				.header("Idempotency-Key", "tc42-twice-cancel-2")
				.content(reasonBody("주문 착오")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"));

		// then — 잔액이 두 번 복구되지 않음 (13,000 아님), 취소 거래 1건뿐
		entityManager.flush();
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("10000");
		assertThat(countEntries(customer.accountId(), "USE_CANCEL")).isEqualTo(1);
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

	// 10,000원 충전 → 3,000원 사용 (잔액 7,000원), 사용 거래 ID 돌려주기. 키는 prefix로 테스트마다 다르게
	private String chargeThenUse(Cookie session, RegisteredCustomer customer, String keyPrefix) throws Exception {
		mockMvc.perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
				.header("Idempotency-Key", keyPrefix + "-charge")
				.content("{\"amount\": 10000}"))
				.andExpect(status().isCreated());
		String useBody = mockMvc.perform(withSession(post("/api/customers/{id}/uses", customer.customerId()), session)
				.header("Idempotency-Key", keyPrefix + "-use")
				.content("{\"amount\": 3000}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return idOf(useBody);
	}

	// 취소 요청 본문 — JSON은 손으로 이어 붙이지 않고 JsonMapper로
	private static String reasonBody(String reason) {
		return JSON.writeValueAsString(Map.of("reason", reason));
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
