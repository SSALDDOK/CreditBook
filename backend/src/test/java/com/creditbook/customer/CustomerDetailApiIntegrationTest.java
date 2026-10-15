package com.creditbook.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
@Tag("REQ-11")
class CustomerDetailApiIntegrationTest {
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
	EntityManager entityManager;

	@Autowired
	Clock clock;

	@Test
	@Tag("TC-48")
	@DisplayName("고객 상세는 현재 잔액과 최근 거래 5건을 최신순으로 주고, 첫 거래의 거래 후 잔액이 현재 잔액과 같다")
	@Tag("REQ-12")
	void detail_returns_balance_and_latest_five_entries_in_order() throws Exception {
		// given 30,000 충전 → 1,000 4번 사용 → 6,000 사용 → 그 6,000 사용 취소 (거래 7건, 잔액 26,000)
		Cookie session = loginAsNewStaff("cb46-staff");
		RegisteredCustomer customer = customerService.register("CB46상세", "01012344646");
		charge(session, customer.customerId(), 30000, "cb46-charge");
		for (int i = 1; i <= 4; i++) {
			use(session, customer.customerId(), 1000, "cb46-use-" + i);
		}
		String bigUseId = use(session, customer.customerId(), 6000, "cb46-use-big");
		cancelUse(session, customer.customerId(), bigUseId, "주문 취소", "cb46-use-cancel");
		entityManager.flush();

		// when 상세 조회
		// then 잔액 26,000, 최근 5건만(충전·첫 사용은 빠짐), 최신이 맨 위
		mockMvc.perform(withSession(get("/api/customers/{id}", customer.customerId()), session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balance").value(26000))
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.maskedPhone").value("010-****-4646"))
				.andExpect(jsonPath("$.recentEntries.length()").value(5))
				// 맨 위는 사용 취소 — 거래 후 잔액이 현재 잔액과 같아야 한다 (같은 시점에 읽었는지)
				.andExpect(jsonPath("$.recentEntries[0].type").value("USE_CANCEL"))
				.andExpect(jsonPath("$.recentEntries[0].signedAmount").value(6000))
				.andExpect(jsonPath("$.recentEntries[0].balanceAfter").value(26000))
				.andExpect(jsonPath("$.recentEntries[0].memo").value("주문 취소")) // 취소 행의 메모 = 취소 사유
				.andExpect(jsonPath("$.recentEntries[0].performedByName").value("테스트직원"))
				.andExpect(jsonPath("$.recentEntries[0].cancelled").value(false)) // 취소 행 자신은 취소된 게 아님
				// 그 아래가 취소된 6,000 사용 — cancelled=true
				.andExpect(jsonPath("$.recentEntries[1].id").value(bigUseId))
				.andExpect(jsonPath("$.recentEntries[1].signedAmount").value(-6000))
				.andExpect(jsonPath("$.recentEntries[1].balanceAfter").value(20000))
				.andExpect(jsonPath("$.recentEntries[1].cancelled").value(true))
				// 나머지 3건은 1,000 사용, 최신순이라 거래 후 잔액이 26,000 → 27,000 → 28,000
				.andExpect(jsonPath("$.recentEntries[2].balanceAfter").value(26000))
				.andExpect(jsonPath("$.recentEntries[3].balanceAfter").value(27000))
				.andExpect(jsonPath("$.recentEntries[4].balanceAfter").value(28000))
				.andExpect(jsonPath("$.recentEntries[4].cancelled").value(false));
	}

	@Test
	@Tag("TC-20")
	@DisplayName("고객 상세 응답에 연락처 원문은 어디에도 없다")
	@Tag("REQ-26")
	void detail_never_contains_raw_phone() throws Exception {
		// given 고객 등록 (연락처 01012344747)
		Cookie session = loginAsNewStaff("cb46-staff");
		RegisteredCustomer customer = customerService.register("CB46마스킹", "01012344747");
		entityManager.flush();
		// when 상세 조회
		String body = mockMvc.perform(withSession(get("/api/customers/{id}", customer.customerId()), session))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		// then 가린 번호만 있고 원문 숫자는 본문 전체에 없다
		assertThat(body).contains("010-****-4747");
		assertThat(body).doesNotContain("01012344747");
		assertThat(body).doesNotContain("010-1234-4747"); // 하이픈 붙은 원문도 없어야 한다
	}

	@Test
	@Tag("TC-48")
	@DisplayName("거래가 한 건도 없는 고객은 잔액 0원과 빈 최근 거래 목록으로 조회된다")
	void detail_of_customer_without_entries_has_empty_list() throws Exception {
		// given 고객 등록만 (거래 0건)
		Cookie session = loginAsNewStaff("cb46-staff");
		RegisteredCustomer customer = customerService.register("CB46빈목록", "01012344848");
		entityManager.flush();
		// when 상세 조회
		// then 200, 잔액 0, recentEntries = [] (거래 없어도 고객 정보는 나온다)
		mockMvc.perform(withSession(get("/api/customers/{id}", customer.customerId()), session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("CB46빈목록"))
				.andExpect(jsonPath("$.balance").value(0))
				.andExpect(jsonPath("$.recentEntries.length()").value(0));
	}

	@Test
	@Tag("TC-48")
	@DisplayName("비활성 고객도 상세가 조회되고 active=false로 표시된다")
	@Tag("REQ-4")
	void inactive_customer_detail_is_readable() throws Exception {
		// given 고객 등록 (0원) → 사장님이 비활성화
		Cookie session = loginAsNewStaff("cb46-staff");
		Employee admin = saveAdmin("cb46-admin"); // 비활성화는 ADMIN만 하므로 처리자도 ADMIN으로
		RegisteredCustomer customer = customerService.register("CB46비활성", "01012344949");
		customerService.deactivate(customer.customerId(), admin.getId());
		entityManager.flush();
		// when 직원이 상세 조회
		// then 200, active=false (비활성이어도 이력은 볼 수 있어야 해서 404가 아님)
		mockMvc.perform(withSession(get("/api/customers/{id}", customer.customerId()), session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false))
				.andExpect(jsonPath("$.balance").value(0));
	}

	@Test
	@Tag("TC-48")
	@DisplayName("없는 고객의 상세를 조회하면 404 CUSTOMER_NOT_FOUND")
	void unknown_customer_detail_is_404() throws Exception {
		// given 로그인한 직원, 아무 고객도 아닌 UUID
		Cookie session = loginAsNewStaff("cb46-staff");
		UUID unknownId = UUID.randomUUID();
		// when 상세 조회
		// then 404 CUSTOMER_NOT_FOUND
		mockMvc.perform(withSession(get("/api/customers/{id}", unknownId), session))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
	}

	// 충전 API 호출 (201 확인까지)
	private void charge(Cookie session, UUID customerId, int amount, String key) throws Exception {
		mockMvc.perform(withSession(post("/api/customers/{id}/charges", customerId), session)
				.header("Idempotency-Key", key)
				.content("{\"amount\": " + amount + "}"))
				.andExpect(status().isCreated());
	}

	// 사용 API 호출 → 만들어진 사용 거래 id 돌려주기 (취소할 때 씀)
	private String use(Cookie session, UUID customerId, int amount, String key) throws Exception {
		String body = mockMvc.perform(withSession(post("/api/customers/{id}/uses", customerId), session)
				.header("Idempotency-Key", key)
				.content("{\"amount\": " + amount + "}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return idOf(body);
	}

	// 사용 취소 API 호출 (사유 필수)
	private void cancelUse(Cookie session, UUID customerId, String useEntryId, String reason, String key)
			throws Exception {
		mockMvc.perform(withSession(post("/api/customers/{customerId}/uses/{useEntryId}/cancel", customerId, useEntryId),
				session)
				.header("Idempotency-Key", key)
				.content(JSON.writeValueAsString(Map.of("reason", reason))))
				.andExpect(status().isCreated());
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

	// 응답 JSON에서 "id" 값만 꺼내기
	private static String idOf(String responseBody) {
		JsonNode body = JSON.readTree(responseBody);
		return body.get("id").asString();
	}

}
