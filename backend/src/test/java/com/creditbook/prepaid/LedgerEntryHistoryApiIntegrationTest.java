package com.creditbook.prepaid;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
@Tag("REQ-12")
@Tag("TC-49")
class LedgerEntryHistoryApiIntegrationTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();

	// 시계 고정 — 충전 취소 가능 여부(당일)가 실제 시계에 따라 바뀌면 자정 근처에 깨지므로 앱 전체 clock 빈을 바꿔 끼운다
	// 기본은 오늘 KST 10-15 10:00 (UTC 01:00)
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
	EntityManager entityManager;

	// 시계는 static 이라 테스트끼리 공유된다 — 앞 테스트가 옮겨 둔 시각에 기대지 않게 매번 오늘로 되돌린다
	@BeforeEach
	void resetClock() {
		CLOCK.set(TODAY);
	}

	@Test
	@DisplayName("충전 2건·사용 3건이 있는 고객의 이력을 조회하면 최신순 5건과 각 시점의 잔액이 표시된다")
	void history_lists_all_entries_latest_first_with_recorded_balance() throws Exception {
		// given 30,000 충전 → 20,000 충전 → 4,000 · 3,000 · 2,000 사용 (잔액 41,000)
		Cookie session = loginAsNewStaff("cb47-staff");
		RegisteredCustomer customer = customerService.register("CB47이력", "01012344747");
		charge(session, customer.customerId(), 30000, "cb47-charge-1");
		charge(session, customer.customerId(), 20000, "cb47-charge-2");
		use(session, customer.customerId(), 4000, "cb47-use-1");
		use(session, customer.customerId(), 3000, "cb47-use-2");
		use(session, customer.customerId(), 2000, "cb47-use-3");
		entityManager.flush();

		// when 이력 조회 (한 페이지에 다 들어가는 크기)
		// then 5건 모두, 최신이 맨 위, 거래 후 잔액은 그때 기록된 값 그대로
		mockMvc.perform(withSession(get("/api/customers/{id}/transactions", customer.customerId())
						.param("page", "0").param("size", "20"), session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(5))
				.andExpect(jsonPath("$.hasNext").value(false))
				.andExpect(jsonPath("$.content.length()").value(5))
				.andExpect(jsonPath("$.content[0].type").value("USE"))
				.andExpect(jsonPath("$.content[0].signedAmount").value(-2000))
				.andExpect(jsonPath("$.content[0].balanceAfter").value(41000))
				.andExpect(jsonPath("$.content[0].performedByName").value("테스트직원"))
				.andExpect(jsonPath("$.content[1].balanceAfter").value(43000))
				.andExpect(jsonPath("$.content[2].balanceAfter").value(46000))
				.andExpect(jsonPath("$.content[3].type").value("CHARGE"))
				.andExpect(jsonPath("$.content[3].signedAmount").value(20000))
				.andExpect(jsonPath("$.content[3].balanceAfter").value(50000))
				// 맨 아래가 첫 충전 — 30,000원 충전 직후 잔액
				.andExpect(jsonPath("$.content[4].type").value("CHARGE"))
				.andExpect(jsonPath("$.content[4].balanceAfter").value(30000));
	}

	@Test
	@DisplayName("페이지를 나누면 최신순으로 이어서 나오고 마지막 페이지에서 hasNext가 false다")
	void history_pages_continue_in_latest_first_order() throws Exception {
		// given 거래 5건 (잔액 순서: 10,000 → 9,000 → 8,000 → 7,000 → 6,000)
		Cookie session = loginAsNewStaff("cb47-page-staff");
		RegisteredCustomer customer = customerService.register("CB47페이지", "01012344848");
		charge(session, customer.customerId(), 10000, "cb47-page-charge");
		for (int i = 1; i <= 4; i++) {
			use(session, customer.customerId(), 1000, "cb47-page-use-" + i);
		}
		entityManager.flush();

		// when 2건씩 나눠서 두 번째 페이지
		// then 최신에서 3·4번째 거래, 전체 5건 / 3페이지, 다음 페이지 있음
		mockMvc.perform(withSession(get("/api/customers/{id}/transactions", customer.customerId())
						.param("page", "1").param("size", "2"), session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(2))
				.andExpect(jsonPath("$.totalElements").value(5))
				.andExpect(jsonPath("$.totalPages").value(3))
				.andExpect(jsonPath("$.hasNext").value(true))
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].balanceAfter").value(8000))
				.andExpect(jsonPath("$.content[1].balanceAfter").value(9000));

		// 마지막 페이지 — 첫 충전 1건만, 다음 페이지 없음
		mockMvc.perform(withSession(get("/api/customers/{id}/transactions", customer.customerId())
						.param("page", "2").param("size", "2"), session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hasNext").value(false))
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].type").value("CHARGE"))
				.andExpect(jsonPath("$.content[0].balanceAfter").value(10000));
	}

	@Test
	@Tag("REQ-7")
	@Tag("REQ-34")
	@DisplayName("취소 가능 여부는 서버가 정한다 — 사용은 취소 전이면 가능, 충전은 KST 오늘만, 취소된 원거래·취소 행은 불가")
	void cancellable_follows_cancel_rules() throws Exception {
		// given KST 어제 23:30(UTC 10-14 14:30)에 10,000 충전
		CLOCK.set(Instant.parse("2026-10-14T14:30:00Z"));
		Cookie session = loginAsNewStaff("cb47-cancel-staff");
		RegisteredCustomer customer = customerService.register("CB47취소", "01012345050");
		String yesterdayChargeId = charge(session, customer.customerId(), 10000, "cb47-cancel-charge-1");
		// 1시간 뒤 KST 오늘 00:30(UTC로는 아직 10-14) — UTC 날짜로 판단하면 둘 다 '같은 날'이 되는 경계
		CLOCK.set(Instant.parse("2026-10-14T15:30:00Z"));
		String todayChargeId = charge(session, customer.customerId(), 20000, "cb47-cancel-charge-2");
		String openUseId = use(session, customer.customerId(), 5000, "cb47-cancel-use-1");
		String cancelledUseId = use(session, customer.customerId(), 1000, "cb47-cancel-use-2");
		cancelUse(session, customer.customerId(), cancelledUseId, "주문 취소", "cb47-cancel-use-cancel");
		entityManager.flush();
		// 조회는 오늘 KST 10:00
		CLOCK.set(TODAY);

		// when 이력 조회
		// then 최신순: 사용 취소 → 취소된 사용 → 사용 → 오늘 충전 → 어제 충전
		mockMvc.perform(withSession(get("/api/customers/{id}/transactions", customer.customerId()), session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(5))
				// 취소 행 — 다시 취소 불가, 어떤 원거래를 되돌렸는지 reversesId, 메모는 취소 사유
				.andExpect(jsonPath("$.content[0].type").value("USE_CANCEL"))
				.andExpect(jsonPath("$.content[0].reversesId").value(cancelledUseId))
				.andExpect(jsonPath("$.content[0].memo").value("주문 취소"))
				.andExpect(jsonPath("$.content[0].cancelled").value(false))
				.andExpect(jsonPath("$.content[0].cancellable").value(false))
				// 이미 취소된 사용 — cancelled=true라 화면은 비활성 [취소]
				.andExpect(jsonPath("$.content[1].id").value(cancelledUseId))
				.andExpect(jsonPath("$.content[1].cancelled").value(true))
				.andExpect(jsonPath("$.content[1].cancellable").value(false))
				// 취소 안 된 사용 — 기한 없이 가능
				.andExpect(jsonPath("$.content[2].id").value(openUseId))
				.andExpect(jsonPath("$.content[2].reversesId").doesNotExist())
				.andExpect(jsonPath("$.content[2].cancellable").value(true))
				// KST 오늘 충전 — 가능
				.andExpect(jsonPath("$.content[3].id").value(todayChargeId))
				.andExpect(jsonPath("$.content[3].cancellable").value(true))
				// KST 어제 충전 — UTC로는 같은 날이어도 불가
				.andExpect(jsonPath("$.content[4].id").value(yesterdayChargeId))
				.andExpect(jsonPath("$.content[4].cancelled").value(false))
				.andExpect(jsonPath("$.content[4].cancellable").value(false));
	}

	@Test
	@DisplayName("거래가 한 건도 없는 고객은 200과 빈 목록으로 조회된다")
	void history_of_customer_without_entries_is_empty() throws Exception {
		// given 등록만 한 고객 (충전 전이라 계좌에 거래 없음)
		Cookie session = loginAsNewStaff("cb47-empty-staff");
		RegisteredCustomer customer = customerService.register("CB47빈이력", "01012345151");
		entityManager.flush();

		// when 이력 조회
		// then 200, 빈 content, 0건 (404가 아님 — 화면은 '아직 거래가 없습니다')
		mockMvc.perform(withSession(get("/api/customers/{id}/transactions", customer.customerId()), session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(0))
				.andExpect(jsonPath("$.totalElements").value(0))
				.andExpect(jsonPath("$.hasNext").value(false));
	}

	@Test
	@Tag("REQ-4")
	@DisplayName("비활성 고객도 거래 이력이 조회된다")
	void history_of_inactive_customer_is_readable() throws Exception {
		// given 10,000 충전 → 10,000 사용(잔액 0) → 사장님이 비활성화
		Cookie session = loginAsNewStaff("cb47-inactive-staff");
		Employee admin = saveAdmin("cb47-inactive-admin"); // 비활성화는 ADMIN만 하므로 처리자도 ADMIN으로
		RegisteredCustomer customer = customerService.register("CB47비활성", "01012345252");
		charge(session, customer.customerId(), 10000, "cb47-inactive-charge");
		use(session, customer.customerId(), 10000, "cb47-inactive-use");
		entityManager.flush();
		customerService.deactivate(customer.customerId(), admin.getId());
		entityManager.flush();

		// when 직원이 이력 조회
		// then 200, 비활성화 전 거래 2건이 그대로
		mockMvc.perform(withSession(get("/api/customers/{id}/transactions", customer.customerId()), session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.content[0].type").value("USE"))
				.andExpect(jsonPath("$.content[0].balanceAfter").value(0));
	}

	@Test
	@DisplayName("없는 고객의 이력을 조회하면 404 CUSTOMER_NOT_FOUND")
	void history_of_unknown_customer_is_404() throws Exception {
		// given 로그인한 직원, 아무 고객도 아닌 UUID
		Cookie session = loginAsNewStaff("cb47-404-staff");
		UUID unknownId = UUID.randomUUID();

		// when 이력 조회
		// then 404 CUSTOMER_NOT_FOUND
		mockMvc.perform(withSession(get("/api/customers/{id}/transactions", unknownId), session))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "101" })
	@DisplayName("한 페이지 크기가 1~100을 벗어나면 400 INVALID_INPUT")
	void history_rejects_size_out_of_range(String size) throws Exception {
		// given 로그인한 직원, 있는 고객
		Cookie session = loginAsNewStaff("cb47-size-staff");
		RegisteredCustomer customer = customerService.register("CB47크기", "01012345353");

		// when size 0 또는 101로 조회
		// then 400 INVALID_INPUT (고객 목록 API와 같은 규칙)
		mockMvc.perform(withSession(get("/api/customers/{id}/transactions", customer.customerId())
						.param("size", size), session))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("로그인하지 않으면 거래 이력을 조회할 수 없다")
	void history_requires_login() throws Exception {
		// given 세션 쿠키 없음, 있는 고객
		RegisteredCustomer customer = customerService.register("CB47비로그인", "01012345454");

		// when 이력 조회
		// then 401 UNAUTHENTICATED
		mockMvc.perform(get("/api/customers/{id}/transactions", customer.customerId()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	// 충전 API 호출 → 만들어진 충전 거래 id 돌려주기
	private String charge(Cookie session, UUID customerId, int amount, String key) throws Exception {
		String body = mockMvc.perform(withSession(post("/api/customers/{id}/charges", customerId), session)
				.header("Idempotency-Key", key)
				.content("{\"amount\": " + amount + "}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return idOf(body);
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
