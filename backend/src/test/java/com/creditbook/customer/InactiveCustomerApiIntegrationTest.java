package com.creditbook.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
@Transactional
@Tag("REQ-33")
class InactiveCustomerApiIntegrationTest {
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

	@Test
	@DisplayName("비활성화된 고객에 대해 새로운 충전을 등록하면 고객이 자동으로 활성화되고 충전 거래가 정상 처리된다")
	@Tag("TC-41")
	void charge_to_inactive_customer_reactivates_customer() throws Exception {
		// given 직원 로그인 -> 고객 등록 (0원) / 고객 비활성화(active=false)
		Cookie session = loginAsNewStaff("tc41-staff");
		Employee admin = saveAdmin("tc41-admin"); // 비활성화는 ADMIN만 하므로 처리자도 ADMIN으로
		RegisteredCustomer customer = registerInactiveCustomer("TC41", "01012347979", admin);
		entityManager.flush();
		assertThat(isActive(customer.customerId())).isFalse(); // 고객비활성화 확인
		// when 50000 충전
		mockMvc.perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
				.header("Idempotency-Key", "tc41-charge")
				.content("{\"amount\": 50000}"))
				.andExpect(status().isCreated());
		// then 201, 잔액 50,000, CHARGE 1건, 고객 활성화(active=true)
		entityManager.flush();
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("50000");
		assertThat(countEntries(customer.accountId(), "CHARGE")).isEqualTo(1);
		assertThat(isActive(customer.customerId())).isTrue();
	}

	@Test
	@DisplayName("비활성 고객의 사용 요청은 409 CUSTOMER_INACTIVE로 거부되고 장부·잔액은 바뀌지 않는다")
	@Tag("TC-41")
	void use_by_inactive_customer_is_rejected_with_409() throws Exception {
		// given 직원 로그인 -> 고객 등록 (0원) / 고객 비활성화(active=false)
		Cookie session = loginAsNewStaff("tc41-staff");
		Employee admin = saveAdmin("tc41-admin"); // 비활성화는 ADMIN만 하므로 처리자도 ADMIN으로
		RegisteredCustomer customer = registerInactiveCustomer("TC41", "01012347979", admin);
		entityManager.flush();
		assertThat(isActive(customer.customerId())).isFalse(); // 고객비활성화 확인
		// when 1,000 사용 요청
		mockMvc.perform(withSession(post("/api/customers/{id}/uses", customer.customerId()), session)
				.header("Idempotency-Key", "tc-41-use")
				.content("{\"amount\": 1000}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));
		// then 409 CUSTOMER_INACTIVE, USE 0건 ,고객 비활성화(active=false)
		entityManager.flush();
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("0");
		assertThat(countEntries(customer.accountId(), "USE")).isEqualTo(0);
		assertThat(isActive(customer.customerId())).isFalse();
	}

	@Test
	@DisplayName("잔액을 모두 사용한 뒤 비활성화한 고객의 그 사용 건을 취소하면 잔액이 복구되고 다시 활성화되어 기본 고객 목록에 보인다")
	@Tag("TC-41")
	@Tag("REQ-34")
	void use_cancel_for_inactive_customer_reactivates_and_shows_in_default_list() throws Exception {
		// given 직원이 10,000 충전 -> 10,000 전부 사용(잔액 0) -> 사장님이 비활성화
		Cookie session = loginAsNewStaff("tc41-staff"); // 충전·사용·취소·목록 조회는 직원
		Cookie admin = loginAsAdmin("tc41-admin"); // 비활성화는 사장님만 가능
		RegisteredCustomer customer = customerService.register("TC41", "01012347979");

		mockMvc.perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
				.header("Idempotency-Key", "tc41-charge")
				.content("{\"amount\": 10000}"))
				.andExpect(status().isCreated());

		String useMoney = mockMvc.perform(withSession(post("/api/customers/{id}/uses", customer.customerId()), session)
				.header("Idempotency-Key", "tc-41-use")
				.content("{\"amount\": 10000}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		String useId = idOf(useMoney);

		mockMvc.perform(withSession(patch("/api/customers/{id}/deactivate", customer.customerId()), admin))
				.andExpect(status().isNoContent()); // 비활성화 성공은 본문 없는 204

		entityManager.flush();
		assertThat(isActive(customer.customerId())).isFalse(); // 고객비활성화 확인
		// when 직원이 그 사용 건을 사유 넣어 취소
		mockMvc.perform(
				withSession(post("/api/customers/{customerId}/uses/{useEntryId}/cancel", customer.customerId(), useId),
						session)
						.header("Idempotency-Key", "tc41-use-cancel")
						.content(reasonBody("사용취소")))
				.andExpect(status().isCreated());
		// then 201, 잔액 10,000, USE_CANCEL 1건, active=true, 기본 목록(q=이름)에 보임
		entityManager.flush();
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("10000");
		assertThat(countEntries(customer.accountId(), "USE_CANCEL")).isEqualTo(1);
		assertThat(isActive(customer.customerId())).isTrue(); // 다시 활성화 확인
		// 기본 목록(includeInactive 없음)에 다시 보이는지 — 목록 조회도 로그인 필요
		mockMvc.perform(withSession(get("/api/customers"), session).param("q", "TC41"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].id").value(customer.customerId().toString())) // 응답 id는 문자열
				.andExpect(jsonPath("$.content[0].active").value(true));
	}

	// Admin 저장 → 진짜 토큰 발급 → 로그인 쿠키(CB_SESSION)에 담기 (고객 비활성화 처리를 위해)
	private Cookie loginAsAdmin(String loginId) {
		Employee admin = saveAdmin(loginId);
		return new Cookie(SessionCookies.NAME, jwtTokenService.issue(admin.getId()).value());
	}

	// ADMIN 직원만 저장 (서비스로 비활성화할 때 처리자 ID용)
	private Employee saveAdmin(String loginId) {
		Employee admin = Employee.create(loginId, passwordEncoder.encode("test5678!"), "테스트사장", Role.ADMIN,
				clock.instant());
		employeeRepository.add(admin);
		return admin;
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

	private boolean isActive(UUID customerId) {
		return jdbcTemplate.queryForObject("SELECT active FROM customers WHERE id = ?", Boolean.class, customerId);
	}

	// 고객 등록 → 바로 비활성화 (잔액 0원이라 가능). 비활성화는 사전 준비라 API 대신 서비스로
	private RegisteredCustomer registerInactiveCustomer(String name, String phone, Employee admin) {
		RegisteredCustomer customer = customerService.register(name, phone);
		customerService.deactivate(customer.customerId(), admin.getId());
		return customer;
	}

}
