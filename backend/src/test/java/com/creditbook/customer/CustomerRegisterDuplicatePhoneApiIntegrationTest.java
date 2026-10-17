package com.creditbook.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.ResultActions;
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
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional // 테스트마다 롤백 → 같은 번호를 테스트끼리 다시 써도 됨
@Tag("REQ-1")
@Tag("TC-45")
class CustomerRegisterDuplicatePhoneApiIntegrationTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();

	// TC-45 사전조건의 번호 — 고객 A(활성)·고객 B(비활성)
	private static final String PHONE_A = "01000001111";
	private static final String PHONE_B = "01000002222";

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
	@DisplayName("같은 연락처의 활성 고객이 있으면 확인 표시 없이 등록할 때 409 DUPLICATE_PHONE과 기존 고객 이름을 주고 아무것도 만들지 않는다")
	void duplicate_phone_without_confirm_returns_409_with_existing_customer() throws Exception {
		// given 고객 A(활성) 010-0000-1111
		Cookie session = loginAsNewStaff("tc45-staff");
		RegisteredCustomer customerA = customerService.register("TC45고객A", PHONE_A);
		entityManager.flush();
		// when 같은 번호로 다른 이름 등록 (확인 표시 없음)
		String body = register(session, "TC45가족", "010-0000-1111", null, false)
				// then 409, 기존 고객 A의 이름·활성 여부가 담긴다
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_PHONE"))
				.andExpect(jsonPath("$.details.existingCustomers.length()").value(1))
				.andExpect(jsonPath("$.details.existingCustomers[0].id").value(customerA.customerId().toString()))
				.andExpect(jsonPath("$.details.existingCustomers[0].name").value("TC45고객A"))
				.andExpect(jsonPath("$.details.existingCustomers[0].active").value(true))
				.andReturn().getResponse().getContentAsString();
		// 연락처는 원문이든 가린 값이든 응답에 없다 (화면이 입력값으로 문구를 만든다)
		// ("1111"만으로 찾으면 고객 ID(UUID)에 우연히 들어 있을 때 실패하므로 번호 모양으로 찾는다)
		assertThat(body).doesNotContain(PHONE_A).doesNotContain("0000-1111").doesNotContain("****-1111");
		// 고객·계좌 행 수 변화 없음 — 그 번호 고객은 A 한 명, 계좌도 1개
		assertThat(customerCountWithPhone(PHONE_A)).isEqualTo(1);
		assertThat(accountCountWithPhone(PHONE_A)).isEqualTo(1);
	}

	@Test
	@DisplayName("확인 표시를 붙여 다시 보내면 201로 등록되고, 같은 번호 고객 2명은 검색에서 이름으로 구분된다")
	void duplicate_phone_with_confirm_registers_and_both_are_searchable() throws Exception {
		// given 고객 A(활성) 010-0000-1111
		Cookie session = loginAsNewStaff("tc45-staff");
		customerService.register("TC45고객A", PHONE_A);
		entityManager.flush();
		// when 같은 요청을 확인 표시(confirmDuplicatePhone=true)를 붙여 보냄
		register(session, "TC45가족", "010-0000-1111", null, true)
				// then 201, 새 고객 잔액 0원
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("TC45가족"))
				.andExpect(jsonPath("$.balance").value(0));
		entityManager.flush();
		// 같은 번호의 고객 2명·계좌 2개
		assertThat(customerCountWithPhone(PHONE_A)).isEqualTo(2);
		assertThat(accountCountWithPhone(PHONE_A)).isEqualTo(2);
		// 그 번호로 검색하면 두 고객이 모두 나오고 이름이 다르다
		mockMvc.perform(get("/api/customers").param("q", PHONE_A).cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.content[?(@.name == 'TC45고객A')]").exists())
				.andExpect(jsonPath("$.content[?(@.name == 'TC45가족')]").exists());
	}

	@Test
	@DisplayName("하이픈만 다른 같은 번호도 중복으로 보고 409 DUPLICATE_PHONE을 준다")
	void phone_without_hyphens_is_same_number() throws Exception {
		// given 고객 A(활성) — 하이픈 넣어 등록해도 숫자만 저장된다
		Cookie session = loginAsNewStaff("tc45-staff");
		register(session, "TC45고객A", "010-0000-1111", null, false).andExpect(status().isCreated());
		entityManager.flush();
		// when 하이픈 없이 01000001111로 등록 (확인 표시 없음)
		register(session, "TC45다른사람", "01000001111", null, false)
				// then 항목 1과 같은 중복 응답
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_PHONE"))
				.andExpect(jsonPath("$.details.existingCustomers[0].name").value("TC45고객A"));
		assertThat(customerCountWithPhone(PHONE_A)).isEqualTo(1);
	}

	@Test
	@Tag("REQ-33")
	@DisplayName("비활성 고객의 번호도 중복으로 보고 active=false로 알리며, 비활성 고객은 그대로 둔다")
	void inactive_customers_phone_is_duplicate_and_stays_inactive() throws Exception {
		// given 고객 B 등록(0원) → 사장님이 비활성화
		Cookie session = loginAsNewStaff("tc45-staff");
		Employee admin = saveAdmin("tc45-admin"); // 비활성화는 ADMIN만
		RegisteredCustomer customerB = customerService.register("TC45고객B", PHONE_B);
		customerService.deactivate(customerB.customerId(), admin.getId());
		entityManager.flush();
		// when B 번호로 등록 (확인 표시 없음)
		register(session, "TC45새손님", "010-0000-2222", null, false)
				// then 409, B가 비활성이라는 표시
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_PHONE"))
				.andExpect(jsonPath("$.details.existingCustomers[0].name").value("TC45고객B"))
				.andExpect(jsonPath("$.details.existingCustomers[0].active").value(false));
		// 고객·계좌 행 수 변화 없음, B는 비활성 그대로 (중복 확인이 재활성화하면 안 됨)
		assertThat(customerCountWithPhone(PHONE_B)).isEqualTo(1);
		assertThat(accountCountWithPhone(PHONE_B)).isEqualTo(1);
		mockMvc.perform(get("/api/customers/{id}", customerB.customerId()).cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));
	}

	@Test
	@DisplayName("같은 번호 고객이 여럿이면 모두 알려 주고, 활성 고객이 먼저 그 안에서는 최근 등록순으로 온다")
	void several_same_phone_customers_are_listed_active_first() throws Exception {
		// given 같은 번호로 3명 — 비활성 1명(가장 먼저 등록), 활성 2명 (확인 표시로 등록, 등록 순서: 옛주인 → 엄마 → 딸)
		Cookie session = loginAsNewStaff("tc45-staff");
		Employee admin = saveAdmin("tc45-admin");
		RegisteredCustomer oldOwner = customerService.register("TC45옛주인", PHONE_A);
		customerService.deactivate(oldOwner.customerId(), admin.getId());
		customerService.register("TC45엄마", PHONE_A);
		customerService.register("TC45딸", PHONE_A);
		entityManager.flush();
		// when 같은 번호로 또 등록 (확인 표시 없음)
		register(session, "TC45아들", "010-0000-1111", null, false)
				// then 3명 모두, 활성(딸 → 엄마, 최근 등록순) 다음에 비활성(옛주인)
				// 화면은 활성이 한 명이라도 있으면 활성 창을 띄운다 (SCR-4, 10-18 결정)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.details.existingCustomers.length()").value(3))
				.andExpect(jsonPath("$.details.existingCustomers[0].name").value("TC45딸"))
				.andExpect(jsonPath("$.details.existingCustomers[1].name").value("TC45엄마"))
				.andExpect(jsonPath("$.details.existingCustomers[2].name").value("TC45옛주인"))
				.andExpect(jsonPath("$.details.existingCustomers[2].active").value(false));
	}

	@Test
	@DisplayName("새 번호는 메모 없이 등록하면 201이고 메모는 비어 있다")
	void register_without_memo_succeeds() throws Exception {
		// given 로그인한 직원, 아무도 안 쓰는 번호
		Cookie session = loginAsNewStaff("tc45-staff");
		// when 메모 없이 등록
		register(session, "TC45메모없음", "010-0000-3333", null, false)
				// then 201, memo는 null
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.memo").doesNotExist());
	}

	@ParameterizedTest(name = "메모 {0}자 → {1}")
	@CsvSource({ "200, 201", "201, 400" })
	@DisplayName("메모는 200자까지 등록되고 201자면 400 INVALID_INPUT, 고객·계좌가 생기지 않는다")
	void memo_length_boundary(int memoLength, int expectedStatus) throws Exception {
		// given 로그인한 직원, 아무도 안 쓰는 번호, 길이만큼의 메모
		Cookie session = loginAsNewStaff("tc45-staff");
		String memo = "메".repeat(memoLength);
		// when 메모를 붙여 등록
		ResultActions result = register(session, "TC45메모", "010-0000-4444", memo, false)
				.andExpect(status().is(expectedStatus));
		entityManager.flush();
		// then 200자면 그대로 저장, 201자면 memo 필드 오류이고 아무것도 안 만들어진다
		if (expectedStatus == 201) {
			result.andExpect(jsonPath("$.memo").value(memo));
			assertThat(customerCountWithPhone("01000004444")).isEqualTo(1);
		} else {
			result.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
					.andExpect(jsonPath("$.fieldErrors[0].field").value("memo"));
			assertThat(customerCountWithPhone("01000004444")).isZero();
			assertThat(accountCountWithPhone("01000004444")).isZero();
		}
	}

	// 등록 API 호출 — memo가 null이면 본문에서 뺀다
	private ResultActions register(Cookie session, String name, String phone, String memo, boolean confirm)
			throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("phone", phone);
		if (memo != null) {
			body.put("memo", memo);
		}
		body.put("confirmDuplicatePhone", confirm);
		return mockMvc.perform(post("/api/customers")
				.cookie(session)
				.contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(body)));
	}

	// 그 번호(숫자 정규형)로 저장된 고객 수
	private int customerCountWithPhone(String phoneDigits) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM customers WHERE phone = ?", Integer.class,
				phoneDigits);
	}

	// 그 번호 고객들의 선결제 계좌 수
	private int accountCountWithPhone(String phoneDigits) {
		return jdbcTemplate.queryForObject("""
				SELECT count(*) FROM prepaid_accounts a
				JOIN customers c ON c.id = a.customer_id
				WHERE c.phone = ?
				""", Integer.class, phoneDigits);
	}

	// ADMIN 직원만 저장 (서비스로 비활성화할 때 처리자 ID용)
	private Employee saveAdmin(String loginId) {
		Employee admin = Employee.create(loginId, passwordEncoder.encode("test5678!"), "테스트사장", Role.ADMIN,
				clock.instant());
		employeeRepository.add(admin);
		return admin;
	}

	// 직원 저장 → 진짜 토큰 발급 → 로그인 쿠키(CB_SESSION)에 담기
	private Cookie loginAsNewStaff(String loginId) {
		Employee employee = Employee.create(loginId, passwordEncoder.encode("test1234!"), "테스트직원", Role.STAFF,
				clock.instant());
		employeeRepository.add(employee);
		return new Cookie(SessionCookies.NAME, jwtTokenService.issue(employee.getId()).value());
	}

}
