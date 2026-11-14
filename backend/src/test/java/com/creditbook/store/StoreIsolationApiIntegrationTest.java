package com.creditbook.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.customer.application.CustomerService;
import com.creditbook.customer.application.RegisteredCustomer;
import com.creditbook.global.security.JwtTokenService;
import com.creditbook.global.security.SessionCookies;
import com.creditbook.support.RequiresDocker;
import com.creditbook.support.TestEmployees;
import com.creditbook.support.TestStores;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;

/**
 * 매장 간 격리 통합 테스트 (REQ-36, TC-60).
 * 매장 A 계정으로 매장 B 의 고객·거래·직원을 조회·변경하면 404 이고 B 는 그대로인지 실제 PostgreSQL 로 본다.
 * 403 이 아니라 404 — 다른 매장의 고객·거래·직원이 있다는 사실도 드러내지 않는다.
 */
@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // 매장 A·B 를 한 번만 만들고, 7번 경로 목록이 B 의 id 를 쓸 수 있게
@Tag("TC-60")
@Tag("REQ-36")
// @Transactional 붙이지 않는다 — 매장·직원·거래가 실제로 커밋돼야 다른 매장 계정의 요청이 그 데이터를 볼 기회가 생김
class StoreIsolationApiIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String PASSWORD = "test1234!";

	// 경로 안의 {변수} 이름
	private static final Pattern PATH_VARIABLE = Pattern.compile("\\{(\\w+)}");

	// 7번에서 본문이 필요한 경로 — 본문이 틀리면 404 보다 먼저 400 이 나서 격리를 증명하지 못한다
	private static final Map<String, String> REQUEST_BODIES = Map.of(
			"/api/customers/{customerId}/charges", "{\"amount\": 1000}",
			"/api/customers/{customerId}/uses", "{\"amount\": 1000}",
			"/api/customers/{customerId}/charges/{chargeEntryId}/cancel", "{\"reason\": \"TC-60 격리 확인\"}",
			"/api/customers/{customerId}/uses/{useEntryId}/cancel", "{\"reason\": \"TC-60 격리 확인\"}");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	EmployeeRepository employeeRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JwtTokenService jwtTokenService;

	@Autowired
	CustomerService customerService;

	@Autowired
	Clock clock;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	// 요청을 보내는 쪽
	StoreFixture storeA;

	// 남의 매장 — 이 매장의 고객·거래·직원 id 로 A 계정이 요청한다
	StoreFixture storeB;

	@BeforeAll
	void openStores() throws Exception {
		storeA = openStore("tc60a");
		storeB = openStore("tc60b");
	}

	@Test
	@DisplayName("다른 매장 고객의 상세·거래 이력을 조회하면 404다")
	void staff_cannot_view_other_store_customer_detail_or_history() throws Exception {
		// given A매장 직원, B매장 고객 (@BeforeAll 에서 준비)
		// when then 상세·거래 이력을 조회, 404 CUSTOMER_NOT_FOUND
		mockMvc.perform(withSession(get("/api/customers/{id}", storeB.customerId()), storeA.staffSession()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
		mockMvc.perform(
				withSession(get("/api/customers/{id}/transactions", storeB.customerId()), storeA.staffSession()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
	}

	@Test
	@DisplayName("다른 매장 고객에게 충전·사용하면 404이고 그 매장 잔액·장부는 그대로다")
	void charge_and_use_on_other_store_customer_return_404_and_keep_its_ledger() throws Exception {
		// given B매장 고객 장부 (잔액 7000,거래 2건)
		LedgerSnapshot before = ledgerOf(storeB.accountId()); // (원본)요청 전 B 장부 (잔액 7000,거래 2건)

		// when 충전 API 호출 — 키는 UUID / A매장 직원 -> B매장 고객
		mockMvc.perform(withSession(post("/api/customers/{id}/charges", storeB.customerId()), storeA.staffSession())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.content("{\"amount\": 5000}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
		// when 사용 API 호출 — 키는 UUID / A매장 직원 -> B매장 고객
		mockMvc.perform(withSession(post("/api/customers/{id}/uses", storeB.customerId()), storeA.staffSession())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.content("{\"amount\": 5000}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));

		// then 요청 후 B 장부 = 요청 전 B 장부
		assertThat(ledgerOf(storeB.accountId())).isEqualTo(before);
	}

	@Test
	@DisplayName("다른 매장 거래를 충전 취소·사용 취소하면 404이고 그 매장 장부는 그대로다")
	void cancel_on_other_store_entries_returns_404_and_keeps_its_ledger() throws Exception {
		// given 요청 전 A,B 장부
		LedgerSnapshot beforeA = ledgerOf(storeA.accountId());
		LedgerSnapshot beforeB = ledgerOf(storeB.accountId());
		// when A 직원 세션으로 B 고객의 B 거래를 충전 취소 시도 (IDOR 시도)
		mockMvc.perform(withSession(post("/api/customers/{customerId}/charges/{chargeEntryId}/cancel",
				storeB.customerId(), storeB.chargeEntryId()), storeA.staffSession())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.content("{\"reason\": \"충전취소\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
		// when A 직원 세션으로 A 고객의 B 거래를 충전 취소 시도 (IDOR 시도)
		mockMvc.perform(withSession(post("/api/customers/{customerId}/charges/{chargeEntryId}/cancel",
				storeA.customerId(), storeB.chargeEntryId()), storeA.staffSession())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.content("{\"reason\": \"충전취소\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("LEDGER_ENTRY_NOT_FOUND"));

		// when A 직원 세션으로 B 고객의 B 거래를 사용 취소 시도 (IDOR 시도)
		mockMvc.perform(withSession(post("/api/customers/{customerId}/uses/{useEntryId}/cancel",
				storeB.customerId(), storeB.useEntryId()), storeA.staffSession())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.content("{\"reason\": \"사용취소\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
		// when A 직원 세션으로 A 고객의 B 거래를 사용 취소 시도 (IDOR 시도)
		mockMvc.perform(withSession(post("/api/customers/{customerId}/uses/{useEntryId}/cancel",
				storeA.customerId(), storeB.useEntryId()), storeA.staffSession())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.content("{\"reason\": \"사용취소\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("LEDGER_ENTRY_NOT_FOUND"));

		// then 요청 후 A,B 장부 = 요청 전 A,B 장부
		assertThat(ledgerOf(storeA.accountId())).isEqualTo(beforeA);
		assertThat(ledgerOf(storeB.accountId())).isEqualTo(beforeB);
	}

	@Test
	@DisplayName("다른 매장 직원의 비밀번호 초기화·비활성화는 404이고 그 직원은 그대로다")
	void owner_cannot_reset_password_or_deactivate_other_store_employee() throws Exception {
		// given (원본) 요청 전 B매장직원 정보
		EmployeeSnapshot before = employeeOf(storeB.staffId());
		// when A매장 사장 쿠키로 B매장 STAFF를 비밀번호 초기화 시도
		mockMvc.perform(
				withSession(post("/api/employees/{id}/reset-password", storeB.staffId()), storeA.ownerSession()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("EMPLOYEE_NOT_FOUND"));
		// when A매장 사장 쿠키로 B매장 STAFF를 비활성화 시도
		mockMvc.perform(withSession(patch("/api/employees/{id}/deactivate", storeB.staffId()), storeA.ownerSession()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("EMPLOYEE_NOT_FOUND"));
		// then 요청 후 B매장직원 정보 = 요청 전 B매장직원 정보
		assertThat(employeeOf(storeB.staffId())).isEqualTo(before);
	}

	@Test
	@DisplayName("고객 목록·검색(비활성 포함)에 다른 매장 고객이 나오지 않는다")
	void customer_list_and_search_exclude_other_store_customers() throws Exception {
		// when then 검색어 없이 목록 → A 고객 O, B 고객 X
		mockMvc.perform(
				withSession(get("/api/customers").param("includeInactive", "true"), storeA.staffSession()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].id", hasItem(storeA.customerId().toString())))
				.andExpect(jsonPath("$.content[*].id", not(hasItem(storeB.customerId().toString()))));
		// when then A매장 직원이 B매장 고객의 이름 또는 연락처 뒷자리로 검색 -> 검색 X
		mockMvc.perform(
				withSession(get("/api/customers").param("includeInactive", "true").param("q", "격리tc60b"),
						storeA.staffSession()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].id", not(hasItem(storeB.customerId().toString()))));

		mockMvc.perform(
				withSession(get("/api/customers").param("includeInactive", "true").param("q", storeB.customerPhone()),
						storeA.staffSession()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].id", not(hasItem(storeB.customerId().toString()))));

		// when then A매장 직원이 A매장 고객 이름으로 검색 -> O
		mockMvc.perform(
				withSession(get("/api/customers").param("includeInactive", "true").param("q", "격리tc60a"),
						storeA.staffSession()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].id", hasItem(storeA.customerId().toString())));
	}

	@Test
	@DisplayName("직원 목록에 다른 매장 직원이 나오지 않는다")
	void employee_list_excludes_other_store_employees() throws Exception {
		// when then A매장 사장이 B매장직원 조회 시도 -> 조회X
		mockMvc.perform(withSession(get("/api/employees"), storeA.ownerSession()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].loginId", hasItem(startsWith("tc60a"))))
				.andExpect(jsonPath("$.items[*].loginId", not(hasItem(startsWith("tc60b")))));
	}

	@Test
	@DisplayName("다른 매장 고객과 같은 연락처로 등록하면 중복 경고 없이 등록되고 그 매장 고객 정보는 응답에 나오지 않는다")
	void register_with_other_store_phone_has_no_duplicate_warning() throws Exception {
		// when then B 고객과 같은 번호로 A에 등록 -> 경고 없이 201
		String body = mockMvc.perform(
				withSession(post("/api/customers"), storeA.staffSession())
						.content("{\"phone\": \"" + storeB.customerPhone() + "\", \"name\": \"TC60같은번호\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("TC60같은번호"))
				.andExpect(jsonPath("$.id").value(not(storeB.customerId().toString())))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		// then 응답에 B 고객 정보 없음
		assertThat(body).doesNotContain(storeB.customerId().toString()).doesNotContain("격리tc60b");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("otherStoreResourceCalls")
	@DisplayName("앱의 모든 API 경로를 다른 매장의 고객·거래·직원 id로 호출하면 404다")
	void every_path_with_other_store_id_returns_404(EndpointCall call) throws Exception {
		// when then 격리 되어있는지 확인. 404
		int status = send(call, storeA.ownerSession()).getResponse().getStatus();
		assertThat(status).isEqualTo(404);
	}

	/**
	 * 매장 하나의 준비물. 고객은 충전 10,000원 → 사용 3,000원을 마쳐 잔액 7,000원, 거래 2건.
	 * 세션은 쿠키 그대로 요청에 붙인다({@link #withSession}).
	 */
	record StoreFixture(UUID staffId, Cookie ownerSession, Cookie staffSession, UUID customerId, UUID accountId,
			String customerPhone, UUID chargeEntryId, UUID useEntryId) {
	}

	// 매장·사장·직원(비밀번호 변경 필요 없음)·고객·거래 2건을 만든다. 모두 커밋된다
	private StoreFixture openStore(String label) throws Exception {
		UUID storeId = TestStores.create(jdbcTemplate);
		String hash = passwordEncoder.encode(PASSWORD);
		// 직원은 트랜잭션 안에서 만들어야 커밋된다
		Employee owner = transactionTemplate.execute(status -> {
			Employee e = TestEmployees.owner(storeId, newLoginId(label + "o"), hash, "사장", clock.instant());
			employeeRepository.add(e);
			return e;
		});
		Employee staff = transactionTemplate.execute(status -> {
			Employee e = TestEmployees.readyStaff(storeId, newLoginId(label + "s"), hash, "직원", clock.instant());
			employeeRepository.add(e);
			return e;
		});
		Cookie ownerSession = sessionOf(owner);
		Cookie staffSession = sessionOf(staff);

		String phone = newPhone();
		RegisteredCustomer customer = customerService.register(storeId, "격리" + label, phone);
		UUID chargeEntryId = UUID.fromString(idOf(postLedger(staffSession,
				"/api/customers/{id}/charges", customer.customerId(), "{\"amount\": 10000}")));
		UUID useEntryId = UUID.fromString(idOf(postLedger(staffSession,
				"/api/customers/{id}/uses", customer.customerId(), "{\"amount\": 3000}")));

		return new StoreFixture(staff.getId(), ownerSession, staffSession, customer.customerId(),
				customer.accountId(), phone, chargeEntryId, useEntryId);
	}

	// 준비용 충전·사용 — 성공(201)이 아니면 준비 단계에서 바로 멈춘다
	private String postLedger(Cookie session, String path, UUID customerId, String body) throws Exception {
		MvcResult result = mockMvc.perform(withSession(
				request(HttpMethod.POST, path, customerId), session)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.content(body))
				.andReturn();
		if (result.getResponse().getStatus() != 201) {
			throw new IllegalStateException("준비 거래 실패: " + path + " → " + result.getResponse().getStatus());
		}
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private Cookie sessionOf(Employee employee) {
		return new Cookie(SessionCookies.NAME, jwtTokenService.issue(employee.getId()).value());
	}

	private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder request, Cookie session) {
		return request.cookie(session).contentType(MediaType.APPLICATION_JSON);
	}

	// 장부가 "그대로"인지 비교할 때 쓴다 — 잔액과 거래 건수
	record LedgerSnapshot(BigDecimal balance, int entryCount) {
	}

	private LedgerSnapshot ledgerOf(UUID accountId) {
		BigDecimal balance = jdbcTemplate.queryForObject("SELECT balance FROM prepaid_accounts WHERE id = ?",
				BigDecimal.class, accountId);
		Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries WHERE account_id = ?",
				Integer.class, accountId);
		return new LedgerSnapshot(balance, count);
	}

	// 직원이 "그대로"인지 비교할 때 쓴다 — 활성 여부·비밀번호 변경 필요·비밀번호 해시(초기화되면 바뀜)
	record EmployeeSnapshot(boolean active, boolean mustChangePassword, String passwordHash) {
	}

	private EmployeeSnapshot employeeOf(UUID employeeId) {
		return jdbcTemplate.queryForObject(
				"SELECT active, must_change_password, password_hash FROM employees WHERE id = ?",
				(rs, row) -> new EmployeeSnapshot(rs.getBoolean(1), rs.getBoolean(2), rs.getString(3)), employeeId);
	}

	// 응답 JSON 에서 "id" 값만 꺼내기
	private static String idOf(String responseBody) {
		return JSON.readTree(responseBody).get("id").asString();
	}

	// 실행마다 다른 연락처 010 + 8자리 (커밋되므로 고정 번호면 다른 테스트와 겹칠 수 있다)
	private static String newPhone() {
		return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
	}

	// 아이디 규칙 영문·숫자·점 4–20자 — 접두어 + 무작위 8자
	private static String newLoginId(String prefix) {
		return prefix + UUID.randomUUID().toString().substring(0, 8);
	}

	// ── 7번: 앱의 경로 목록 ──────────────────────────────────────

	/**
	 * 7번 한 줄. 테스트 이름에는 'PATCH /api/customers/{id}/deactivate' 처럼 메서드와 경로 틀만 보인다.
	 */
	record EndpointCall(HttpMethod method, String pathTemplate, String path, String body) {
		@Override
		public String toString() {
			return method + " " + pathTemplate;
		}
	}

	/**
	 * 앱에 등록된 경로 중 {변수}가 있는 /api 경로를 모두 읽어, 변수를 매장 B 의 고객·거래·직원 id 로 채운다.
	 * 새 경로를 만들면 자동으로 포함된다. 모르는 변수 이름이 나오면 여기서 실패한다 — 그 변수에 B 의 어떤 id(고객·거래·직원)를 넣을지
	 * {@link #otherStoreIdFor} 에 더한다. 본문이 필요한 새 경로는 {@link #REQUEST_BODIES} 에 더한다.
	 */
	Stream<EndpointCall> otherStoreResourceCalls() {
		List<EndpointCall> calls = new ArrayList<>();
		for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
			Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
			for (String template : info.getPatternValues()) {
				if (!template.startsWith("/api/") || !template.contains("{")) {
					continue;
				}
				String path = fillPathVariables(template);
				for (RequestMethod method : methods) {
					calls.add(new EndpointCall(HttpMethod.valueOf(method.name()), template, path,
							REQUEST_BODIES.get(template)));
				}
			}
		}
		if (calls.isEmpty()) {
			throw new IllegalStateException("경로 목록을 읽지 못했다 — 핸들러 매핑을 확인");
		}
		return calls.stream();
	}

	private String fillPathVariables(String template) {
		Matcher matcher = PATH_VARIABLE.matcher(template);
		StringBuilder path = new StringBuilder();
		while (matcher.find()) {
			matcher.appendReplacement(path, otherStoreIdFor(template, matcher.group(1)).toString());
		}
		matcher.appendTail(path);
		return path.toString();
	}

	// 경로 변수 이름 → 매장 B 의 어떤 id(고객·거래·직원)를 넣을지
	private UUID otherStoreIdFor(String template, String variable) {
		return switch (variable) {
			case "customerId" -> storeB.customerId();
			case "chargeEntryId" -> storeB.chargeEntryId();
			case "useEntryId" -> storeB.useEntryId();
			case "id" -> template.startsWith("/api/employees/") ? storeB.staffId()
					: template.startsWith("/api/customers/") ? storeB.customerId()
							: unknownVariable(template, variable);
			default -> unknownVariable(template, variable);
		};
	}

	private static UUID unknownVariable(String template, String variable) {
		throw new IllegalStateException("모르는 경로 변수 {" + variable + "}: " + template
				+ " — otherStoreIdFor 에 B 의 어떤 id 를 넣을지 더한다");
	}

	/** 7번 요청을 보낸다. 요청 키는 늘 붙인다(필요 없는 경로는 무시). 상태·본문은 통째로 돌려준다. */
	private MvcResult send(EndpointCall call, Cookie session) throws Exception {
		MockHttpServletRequestBuilder builder = withSession(request(call.method(), call.path()), session)
				.header("Idempotency-Key", UUID.randomUUID().toString());
		if (call.body() != null) {
			builder.content(call.body());
		}
		return mockMvc.perform(builder).andReturn();
	}

}
