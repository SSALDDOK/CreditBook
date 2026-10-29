package com.creditbook.prepaid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.Role;
import com.creditbook.customer.application.CustomerService;
import com.creditbook.customer.application.RegisteredCustomer;
import com.creditbook.global.security.JwtTestSupport.MutableClock;
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
@Transactional // 테스트마다 롤백 → 매번 새 고객·새 키
@Tag("REQ-7")
class ChargeCancelRejectionApiIntegrationTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();

	// 시계 고정 — '당일 아님'을 만들려고 앱 전체가 쓰는 clock 빈을 바꿔 끼운다
	// 기본은 오늘 KST 10:00 (UTC 01:00)
	private static final Instant TODAY = Instant.parse("2026-10-15T01:00:00Z");
	private static final MutableClock CLOCK = new MutableClock(TODAY);

	@TestBean(name = "clock")
	Clock clock;

	// @TestBean 이 이 이름(필드 이름 clock)의 static 메서드로 빈을 만든다
	static Clock clock() {
		return CLOCK;
	}

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

	// 시계는 static 이라 테스트끼리 공유된다 — 앞 테스트가 옮겨 둔 시각에 기대지 않게 매번 오늘로 되돌린다
	@BeforeEach
	void resetClock() {
		CLOCK.set(TODAY);
	}

	// ===== 항목 1 — 인증 =====

	@Test
	@DisplayName("로그인 쿠키 없이 충전 취소를 요청하면 401이고 장부는 그대로다")
	@Tag("TC-44")
	void cancel_without_session_is_rejected_with_401() throws Exception {
		// given — 오늘 10,000원 충전
		Cookie session = loginAsNewStaff("tc44-401-staff");
		RegisteredCustomer customer = customerService.register("TC44인증", "01044440001");
		String chargeId = charge(session, customer, 10_000, "tc44-401-charge");

		// when — 쿠키 없이 취소
		mockMvc.perform(post("/api/customers/{customerId}/charges/{chargeEntryId}/cancel",
						customer.customerId(), chargeId)
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", "tc44-401-cancel")
				.content(reasonBody("금액 잘못 입력")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		// then — 잔액 그대로, 취소 거래 없음
		entityManager.flush();
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("10000");
		assertThat(countEntries(customer.accountId(), "CHARGE_CANCEL")).isZero();
	}

	// ===== 항목 2 — 입력(사유 필수) =====

	@ParameterizedTest(name = "사유 [{0}]")
	@ValueSource(strings = { "", "   " })
	@DisplayName("사유 없이는 충전을 취소할 수 없다 — 빈 값·공백은 400 INVALID_INPUT, 장부 변화 없음")
	@Tag("TC-44")
	void cancel_with_blank_reason_is_rejected_with_400(String reason) throws Exception {
		// given — 오늘 10,000원 충전
		Cookie session = loginAsNewStaff("tc44-blank-staff");
		RegisteredCustomer customer = customerService.register("TC44사유", "01044440002");
		String chargeId = charge(session, customer, 10_000, "tc44-blank-charge");

		// when — 빈 사유로 취소
		mockMvc.perform(withSession(chargeCancel(customer, chargeId), session)
				.header("Idempotency-Key", "tc44-blank-cancel")
				.content(reasonBody(reason)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));

		// then — 장부 변화 없음
		entityManager.flush();
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("10000");
		assertThat(countEntries(customer.accountId(), "CHARGE_CANCEL")).isZero();
	}

	// ===== 항목 3 — 당일 아님 =====

	@Test
	@DisplayName("당일 등록 건만 취소할 수 있다 — 한국 날짜로 어제 충전한 건은 409 CHARGE_CANCEL_NOT_SAME_DAY, 장부 변화 없음")
	@Tag("TC-44")
	void cancel_of_yesterdays_charge_is_rejected_with_409() throws Exception {
		// given — 한국 시각 어제 23:30(UTC 10-14 14:30)에 로그인·충전
		CLOCK.set(Instant.parse("2026-10-14T14:30:00Z"));
		Cookie session = loginAsNewStaff("tc44-day-staff");
		RegisteredCustomer customer = customerService.register("TC44당일", "01044440003");
		String chargeId = charge(session, customer, 10_000, "tc44-day-charge");

		// 한 시간 뒤 = 한국 시각 오늘 00:30. UTC로는 아직 같은 날(10-14)이라 KST 기준인지도 같이 확인된다
		// (토큰 유효 16시간 안이라 같은 쿠키를 그대로 쓴다)
		CLOCK.set(Instant.parse("2026-10-14T15:30:00Z"));

		// when — 어제 충전 건 취소
		mockMvc.perform(withSession(chargeCancel(customer, chargeId), session)
				.header("Idempotency-Key", "tc44-day-cancel")
				.content(reasonBody("금액 잘못 입력")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CHARGE_CANCEL_NOT_SAME_DAY"));

		// then — 장부 변화 없음
		entityManager.flush();
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("10000");
		assertThat(countEntries(customer.accountId(), "CHARGE_CANCEL")).isZero();
	}

	// ===== 항목 4 — 대상 검증 =====

	@Test
	@DisplayName("반제 대상은 같은 계좌의 CHARGE 건만 가능하다 — 사용(USE) 건을 충전 취소하면 400 INVALID_CANCEL_TARGET, 장부 변화 없음")
	@Tag("TC-44")
	void cancel_of_use_entry_is_rejected_with_400() throws Exception {
		// given — 10,000원 충전 → 3,000원 사용(잔액 7,000원), 사용 거래 ID
		Cookie session = loginAsNewStaff("tc44-use-staff");
		RegisteredCustomer customer = customerService.register("TC44사용", "01044440004");
		charge(session, customer, 10_000, "tc44-use-charge");
		String useId = use(session, customer, 3_000, "tc44-use-use");

		// when — 사용 거래를 충전 취소 경로로
		mockMvc.perform(withSession(chargeCancel(customer, useId), session)
				.header("Idempotency-Key", "tc44-use-cancel")
				.content(reasonBody("금액 잘못 입력")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CANCEL_TARGET"));

		// then — 잔액 7,000원 그대로, 취소 거래 없음
		entityManager.flush();
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("7000");
		assertThat(countEntries(customer.accountId(), "CHARGE_CANCEL")).isZero();
	}

	@Test
	@DisplayName("다른 계좌의 충전 건은 취소할 수 없다 — 400 INVALID_CANCEL_TARGET, 두 고객 장부 그대로")
	@Tag("TC-44")
	@Tag("REQ-27")
	@Tag("TC-21")
	void cancel_of_other_customers_charge_is_rejected_with_400() throws Exception {
		// given — 고객 A·B 각각 오늘 10,000원 충전, B의 충전 거래 ID
		Cookie session = loginAsNewStaff("tc44-idor-staff");
		RegisteredCustomer customerA = customerService.register("TC44고객A", "01044440005");
		RegisteredCustomer customerB = customerService.register("TC44고객B", "01044440006");
		charge(session, customerA, 10_000, "tc44-idor-a");
		String chargeIdOfB = charge(session, customerB, 10_000, "tc44-idor-b");

		// when — A 경로에 B의 거래 ID를 끼워 넣기 (IDOR 시도)
		mockMvc.perform(withSession(chargeCancel(customerA, chargeIdOfB), session)
				.header("Idempotency-Key", "tc44-idor-cancel")
				.content(reasonBody("금액 잘못 입력")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CANCEL_TARGET"));

		// then — 두 고객 모두 잔액 그대로, 취소 거래 없음
		entityManager.flush();
		assertThat(balanceOf(customerA.accountId())).isEqualByComparingTo("10000");
		assertThat(balanceOf(customerB.accountId())).isEqualByComparingTo("10000");
		assertThat(countEntries(customerA.accountId(), "CHARGE_CANCEL")).isZero();
		assertThat(countEntries(customerB.accountId(), "CHARGE_CANCEL")).isZero();
	}

	@Test
	@DisplayName("이미 취소된 충전 건은 다시 취소할 수 없다 — 409 ALREADY_CANCELLED, 잔액은 한 번만 줄어든다")
	@Tag("TC-44")
	void cancel_twice_is_rejected_with_409() throws Exception {
		// given — 20,000원 충전 → 10,000원 충전 → 10,000원 충전 건 한 번 취소 (잔액 20,000원)
		Cookie session = loginAsNewStaff("tc44-twice-staff");
		RegisteredCustomer customer = customerService.register("TC44재취소", "01044440007");
		charge(session, customer, 20_000, "tc44-twice-charge-1");
		String chargeId = charge(session, customer, 10_000, "tc44-twice-charge-2");
		mockMvc.perform(withSession(chargeCancel(customer, chargeId), session)
				.header("Idempotency-Key", "tc44-twice-cancel-1")
				.content(reasonBody("금액 잘못 입력")))
				.andExpect(status().isCreated());

		// when — 다른 키로 같은 충전 건을 또 취소 (같은 키면 재응답이라 새 키를 쓴다)
		mockMvc.perform(withSession(chargeCancel(customer, chargeId), session)
				.header("Idempotency-Key", "tc44-twice-cancel-2")
				.content(reasonBody("금액 잘못 입력")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"))
				.andExpect(jsonPath("$.message").value("이미 취소된 충전 건입니다."));

		// then — 잔액이 두 번 줄지 않음 (10,000 아님), 취소 거래 1건뿐
		// 첫 충전 20,000원을 남겨 둔 이유: 잔액이 모자라 거절된 게 아니라 '이미 취소'로 거절된 것을 보려고
		entityManager.flush();
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("20000");
		assertThat(countEntries(customer.accountId(), "CHARGE_CANCEL")).isEqualTo(1);
	}

	@Test
	@DisplayName("반제 건은 다시 반제할 수 없다 — 충전 취소(CHARGE_CANCEL) 거래를 취소하면 400 INVALID_CANCEL_TARGET, 장부 변화 없음")
	@Tag("TC-44")
	void cancel_of_charge_cancel_entry_is_rejected_with_400() throws Exception {
		// given — 20,000원 충전 → 10,000원 충전 → 10,000원 충전 건 취소, 그 취소 거래 ID (잔액 20,000원)
		Cookie session = loginAsNewStaff("tc44-rev-staff");
		RegisteredCustomer customer = customerService.register("TC44반제", "01044440008");
		charge(session, customer, 20_000, "tc44-rev-charge-1");
		String chargeId = charge(session, customer, 10_000, "tc44-rev-charge-2");
		String cancelBody = mockMvc.perform(withSession(chargeCancel(customer, chargeId), session)
						.header("Idempotency-Key", "tc44-rev-cancel-1")
						.content(reasonBody("금액 잘못 입력")))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String chargeCancelId = idOf(cancelBody);

		// when — 취소 거래 자체를 다시 취소
		mockMvc.perform(withSession(chargeCancel(customer, chargeCancelId), session)
				.header("Idempotency-Key", "tc44-rev-cancel-2")
				.content(reasonBody("금액 잘못 입력")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CANCEL_TARGET"));

		// then — 잔액 20,000원 그대로, 취소 거래 1건뿐
		entityManager.flush();
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("20000");
		assertThat(countEntries(customer.accountId(), "CHARGE_CANCEL")).isEqualTo(1);
	}

	// 직원 저장 → 진짜 토큰 발급 → 로그인 쿠키(CB_SESSION)에 담기. 토큰 발급 시각도 고정한 시계를 따른다
	private Cookie loginAsNewStaff(String loginId) {
		Employee employee = Employee.create(loginId, passwordEncoder.encode("test1234!"), "테스트직원", Role.STAFF,
				CLOCK.instant());
		employeeRepository.add(employee);
		return new Cookie(SessionCookies.NAME, jwtTokenService.issue(employee.getId()).value());
	}

	private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder request, Cookie session) {
		return request.cookie(session).contentType(MediaType.APPLICATION_JSON);
	}

	private static MockHttpServletRequestBuilder chargeCancel(RegisteredCustomer customer, String entryId) {
		return post("/api/customers/{customerId}/charges/{chargeEntryId}/cancel", customer.customerId(), entryId);
	}

	// 충전하고 충전 거래 ID 돌려주기
	private String charge(Cookie session, RegisteredCustomer customer, int amount, String key) throws Exception {
		String body = mockMvc.perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
						.header("Idempotency-Key", key)
						.content("{\"amount\": " + amount + "}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return idOf(body);
	}

	// 사용하고 사용 거래 ID 돌려주기
	private String use(Cookie session, RegisteredCustomer customer, int amount, String key) throws Exception {
		String body = mockMvc.perform(withSession(post("/api/customers/{id}/uses", customer.customerId()), session)
						.header("Idempotency-Key", key)
						.content("{\"amount\": " + amount + "}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return idOf(body);
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
