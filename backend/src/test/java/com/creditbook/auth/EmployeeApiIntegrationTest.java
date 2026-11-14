package com.creditbook.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

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
import com.jayway.jsonpath.JsonPath;

/**
 * 직원 계정 관리 API 통합 테스트 (REQ-30·REQ-16). 화면에서 메뉴를 숨기는 것은 보안이 아니므로 API를 직접 호출해
 * 막히는지 본다.
 * 역할은 요청마다 DB에서 읽으므로 @WithMockEmployee 로 주입하지 않고, 실제로 발급한 토큰으로 보안 필터 체인 전체를 지나게
 * 한다.
 * 직원 데이터는 클래스 @Transactional 로 테스트마다 롤백된다.
 */
@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class EmployeeApiIntegrationTest {

	private static final String PASSWORD = "test1234!";
	private static final String NEW_STAFF_BODY = """
			{"name": "새직원", "loginId": "tc26.new"}
			""";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	CustomerService customerService;

	@Autowired
	EmployeeRepository employeeRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JwtTokenService jwtTokenService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Clock clock;

	// 매장 (REQ-36) — 직원·고객은 매장이 있어야 저장된다. 테스트마다 새 매장
	UUID storeId;

	@BeforeEach
	void createStore() {
		storeId = TestStores.create(jdbcTemplate);
	}

	@Test
	@DisplayName("STAFF 토큰으로 직원 목록 조회 — 403")
	@Tag("TC-26")
	@Tag("REQ-30")
	@Tag("REQ-16")
	void staff_cannot_list_employees() throws Exception {
		// given — 비밀번호 변경까지 마친 일반 직원 (변경 필요 계정이면 403 사유가 달라짐)
		Cookie staff = sessionOf(saveStaff("tc26.staff1"));

		// when & then — 메뉴를 숨겨도 API를 직접 부를 수 있으니 서버가 막아야 함
		mockMvc.perform(withSession(get("/api/employees"), staff))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"))
				.andExpect(content().string(not(containsString("passwordHash"))));
	}

	@Test
	@DisplayName("STAFF 토큰으로 직원 추가 — 403, 직원이 만들어지지 않는다")
	@Tag("TC-26")
	@Tag("REQ-30")
	@Tag("REQ-16")
	void staff_cannot_add_employee() throws Exception {
		// given
		Cookie staff = sessionOf(saveStaff("tc26.staff2"));

		// when & then — 아래 ADMIN 테스트와 같은 본문, 토큰만 STAFF
		mockMvc.perform(withSession(post("/api/employees"), staff)
				.content(NEW_STAFF_BODY))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"))
				.andExpect(content().string(not(containsString("passwordHash"))));

		assertThat(countByLoginId("tc26.new")).isZero(); // 거절됐으니 행도 없음
	}

	@Test
	@DisplayName("ADMIN이 직원 추가 본문에 role: ADMIN을 끼워 보냄 — 400 INVALID_INPUT, 직원이 만들어지지 않고 ADMIN이 늘지 않는다")
	@Tag("TC-26")
	@Tag("REQ-30")
	@Tag("REQ-16")
	void add_employee_rejects_role_in_request_body() throws Exception {
		// given — 사장 1명. 다른 테스트가 남긴 직원이 있어도 되게 절대값 대신 전후 비교
		Cookie owner = sessionOf(saveOwner("tc26.owner1"));
		int adminsBefore = countAdmins();

		// when & then — role은 받지 않는 항목이라 무시가 아니라 거절 (무시하면 조용히 넘어가서 시도를 알 수 없음)
		mockMvc.perform(withSession(post("/api/employees"), owner)
				.content("""
						{"name": "권한상승", "loginId": "tc26.admin", "role": "ADMIN"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("role"))
				.andExpect(content().string(not(containsString("passwordHash"))));

		assertThat(countByLoginId("tc26.admin")).isZero(); // 직원 안 생김
		assertThat(countAdmins()).isEqualTo(adminsBefore); // ADMIN도 그대로
	}

	@Test
	@DisplayName("ADMIN이 직원 추가 — 생성 응답에만 initialPassword가 있고 재조회 응답에는 없다, 어느 응답에도 passwordHash는 없다")
	@Tag("TC-26")
	@Tag("REQ-30")
	@Tag("REQ-16")
	void initial_password_is_returned_only_when_employee_is_added() throws Exception {
		// given
		Cookie owner = sessionOf(saveOwner("tc26.owner2"));

		// when — 추가. 위 403이 권한 때문임을 보여 주는 대조군이기도 함 (같은 본문, 토큰만 ADMIN)
		String created = mockMvc.perform(withSession(post("/api/employees"), owner)
				.content(NEW_STAFF_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.role").value("STAFF")) // 권한은 STAFF로 고정
				.andExpect(jsonPath("$.initialPassword").isNotEmpty())
				.andExpect(content().string(not(containsString("passwordHash"))))
				.andReturn().getResponse().getContentAsString();

		String initialPassword = JsonPath.read(created, "$.initialPassword"); // 응답 JSON에서 값만 꺼내기
		String storedHash = passwordHashOf("tc26.new");

		// then — DB에는 원문이 아니라 해시만 (해시로 그 비밀번호가 맞는지는 확인됨)
		assertThat(storedHash).isNotEqualTo(initialPassword);
		assertThat(passwordEncoder.matches(initialPassword, storedHash)).isTrue();

		// when & then — 재조회: 직원은 보이지만 초기 비밀번호는 키도 값도 없음, 해시 값도 없음
		mockMvc.perform(withSession(get("/api/employees"), owner))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].loginId", hasItem("tc26.new")))
				.andExpect(content().string(not(containsString("initialPassword"))))
				.andExpect(content().string(not(containsString(initialPassword))))
				.andExpect(content().string(not(containsString("passwordHash"))))
				.andExpect(content().string(not(containsString(storedHash))));
	}

	@Test
	@DisplayName("유일한 ADMIN이 본인 계정을 비활성화 — 409 ADMIN_ACCOUNT_PROTECTED, 계정은 활성 그대로")
	@Tag("TC-27")
	@Tag("REQ-30")
	void only_admin_cannot_deactivate_own_account() throws Exception {
		// given ADMIN 1명(매장 개설 계정)
		Employee ownerAccount = saveOwner("tc27.owner2");
		Cookie owner = sessionOf(ownerAccount);

		// when — ADMIN 본인 계정을 비활성화
		mockMvc.perform(withSession(patch("/api/employees/{id}/deactivate", ownerAccount.getId()), owner))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ADMIN_ACCOUNT_PROTECTED"))
				.andExpect(jsonPath("$.message").value("사장님 계정은 비활성화하거나 비밀번호를 초기화할 수 없습니다."));

		// then — 재조회: 계정 활성 그대로.
		mockMvc.perform(withSession(get("/api/employees"), owner))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].loginId", hasItem("tc27.owner2")))
				.andExpect(jsonPath("$.items[?(@.loginId =='tc27.owner2')].active", hasItem(true)));
	}

	@Test
	@DisplayName("STAFF 비활성화 — 204, 그 직원의 기존 토큰은 다음 요청부터 401")
	@Tag("TC-27")
	@Tag("REQ-30")
	void deactivated_staff_token_is_rejected_with_401() throws Exception {
		// given ADMIN 1명(매장 개설 계정),STAFF 1명
		Employee ownerAccount = saveOwner("tc27.owner2");
		Employee staffAccount = saveStaff("tc27.staff2");
		Cookie owner = sessionOf(ownerAccount);
		Cookie staff = sessionOf(staffAccount);

		// staff 활성화 상태확인 - 고객목록으로 확인하는 이유는 staff는 직원관리 페이지 접근 불가.
		mockMvc.perform(withSession(get("/api/customers"), staff))
				.andExpect(status().isOk());

		// when — 사장 쿠키로 STAFF를 비활성화해 204를 확인
		mockMvc.perform(withSession(patch("/api/employees/{id}/deactivate", staffAccount.getId()), owner))
				.andExpect(status().isNoContent());

		// then — 비활성화 Staff로 고객목록 재조회 401확인
		mockMvc.perform(withSession(get("/api/customers"), staff))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("직원을 비활성화해도 그 직원이 처리한 과거 거래의 처리 직원은 그대로 조회된다")
	@Tag("TC-27")
	@Tag("REQ-30")
	void deactivated_staff_stays_as_performer_of_past_transactions() throws Exception {
		// given ADMIN 1명(매장 개설 계정),STAFF 1명 , 고객 1명
		Employee ownerAccount = saveOwner("tc27.owner2");
		Employee staffAccount = saveStaff("tc27.staff2");
		Cookie owner = sessionOf(ownerAccount);
		Cookie staff = sessionOf(staffAccount);
		RegisteredCustomer customer = customerService.register(storeId, "이름", "01027270001");

		// staff 활성화 상태확인 - 고객목록으로 확인하는 이유는 staff는 직원관리 페이지 접근 불가.
		mockMvc.perform(withSession(get("/api/customers"), staff))
				.andExpect(status().isOk());

		// when — staff가 충전 201
		mockMvc.perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), staff)
				.header("Idempotency-Key", "tc27-charge")
				.content("{\"amount\": 10000}"))
				.andExpect(status().isCreated());

		// 사장 -> 충전한 staff 비활성화 204
		mockMvc.perform(withSession(patch("/api/employees/{id}/deactivate", staffAccount.getId()), owner))
				.andExpect(status().isNoContent());

		// then — 사장 쿠키로 거래 내역에서 충전 처리한 직원 이름 확인.
		mockMvc.perform(withSession(get("/api/customers/{id}/transactions",customer.customerId()), owner))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$..content[0].performedByName").value("테스트직원"));
	}

	// 사장 저장 (매장 개설 계정과 같음)
	private Employee saveOwner(String loginId) {
		Employee owner = TestEmployees.owner(storeId, loginId, passwordEncoder.encode(PASSWORD), "테스트사장", clock.instant());
		employeeRepository.add(owner);
		return owner;
	}

	// 직원 저장 (첫 비밀번호 변경까지 마친 상태)
	private Employee saveStaff(String loginId) {
		Employee staff = TestEmployees.readyStaff(storeId, loginId, passwordEncoder.encode(PASSWORD), "테스트직원", clock.instant());
		employeeRepository.add(staff);
		return staff;
	}

	// 진짜 토큰 발급 → 로그인 쿠키(CB_SESSION)에 담기
	private Cookie sessionOf(Employee employee) {
		return new Cookie(SessionCookies.NAME, jwtTokenService.issue(employee.getId()).value());
	}

	private static MockHttpServletRequestBuilder withSession(MockHttpServletRequestBuilder request, Cookie session) {
		return request.cookie(session).contentType(MediaType.APPLICATION_JSON);
	}

	private int countByLoginId(String loginId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM employees WHERE login_id = ?", Integer.class, loginId);
	}

	private int countAdmins() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM employees WHERE role = 'ADMIN'", Integer.class);
	}

	// DB password_hash칸 직접 읽기
	private String passwordHashOf(String loginId) {
		return jdbcTemplate.queryForObject("SELECT password_hash FROM employees WHERE login_id = ?", String.class,
				loginId);
	}

}
