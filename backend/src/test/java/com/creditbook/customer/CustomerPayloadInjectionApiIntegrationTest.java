package com.creditbook.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.global.security.JwtTokenService;
import com.creditbook.global.security.SessionCookies;
import com.creditbook.support.RequiresDocker;
import com.creditbook.support.TestEmployees;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;

// 보안 점검(REQ-27) 입력값 검증 — 이름·메모에 SQL·스크립트 페이로드를 넣어도 그냥 글자로 저장되고 그대로 돌아온다
@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional // 테스트마다 롤백 → 같은 번호를 다시 써도 됨
@Tag("REQ-27")
@Tag("REQ-1")
@Tag("TC-22")
class CustomerPayloadInjectionApiIntegrationTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();

	// 아무도 안 쓰는 번호
	private static final String PHONE = "01022220022";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	EmployeeRepository employeeRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JwtTokenService jwtTokenService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	EntityManager entityManager;

	@Autowired
	Clock clock;

	// TC-22 절차의 페이로드 4종 그대로 (메모는 200자까지라 다 들어간다)
	@ParameterizedTest(name = "메모 {0}")
	@ValueSource(strings = { "'; DROP TABLE transaction; --", "' OR '1'='1", "<script>alert(1)</script>",
			"{{7*7}}" })
	@DisplayName("메모에 SQL·스크립트 페이로드를 넣어도 그냥 글자로 저장되고 응답에 그대로 돌아오며, 테이블은 멀쩡하다")
	void memo_payload_is_stored_as_plain_text(String payload) throws Exception {
		// given 로그인한 직원
		Cookie session = loginAsNewStaff("tc22-memo");

		// when 메모에 페이로드를 넣어 등록
		register(session, "TC22메모", payload)
				// then 201, 응답 메모가 넣은 글자 그대로 ({{7*7}}이 49가 되지 않음)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.memo").value(payload));
		entityManager.flush();

		// then DB에도 글자 그대로 저장되고, 테이블은 그대로 있다
		assertThat(memoInDb(PHONE)).isEqualTo(payload);
		assertThat(tablesStillExist()).isTrue();
	}

	// 이름은 20자까지라 TC-22 원문 중 20자가 넘는 두 개(DROP·script)는 같은 종류의 짧은 값으로 바꿨다 — 2026-10-29 수동 점검과 같은 값
	@ParameterizedTest(name = "이름 {0}")
	@ValueSource(strings = { "';DROP TABLE x;--", "' OR '1'='1", "<svg/onload=alert``>", "{{7*7}}" })
	@DisplayName("이름에 SQL·스크립트 페이로드를 넣어도 그냥 글자로 저장되고, 상세 조회와 그 이름 검색에 그대로 돌아온다")
	void name_payload_is_stored_and_returned_as_plain_text(String payload) throws Exception {
		// given 로그인한 직원
		Cookie session = loginAsNewStaff("tc22-name");

		// when 이름에 페이로드를 넣어 등록
		String body = register(session, payload, null)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value(payload))
				.andReturn().getResponse().getContentAsString();
		UUID customerId = UUID.fromString(JSON.readTree(body).get("id").asString());
		entityManager.flush();

		// then 상세 조회에 이름이 그대로 나온다
		mockMvc.perform(get("/api/customers/{id}", customerId).cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value(payload));
		// then 그 이름으로 검색하면 그 고객 1명만 나온다 (' OR '1'='1 로 전체가 나오지 않음)
		mockMvc.perform(get("/api/customers").param("q", payload).cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].id").value(customerId.toString()));
		assertThat(tablesStillExist()).isTrue();
	}

	@Test
	@DisplayName("이름 1,000,000자 같은 과대 입력은 400 INVALID_INPUT으로 거부하고 아무것도 저장하지 않는다")
	void oversized_name_is_rejected() throws Exception {
		// given 로그인한 직원, 100만 자 이름
		Cookie session = loginAsNewStaff("tc22-big");
		String hugeName = "가".repeat(1_000_000);

		// when 등록
		register(session, hugeName, null)
				// then 400, name 칸 오류
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
		entityManager.flush();

		// then 고객이 만들어지지 않았다
		assertThat(customerCountWithPhone(PHONE)).isZero();
	}

	// 등록 API 호출 — memo에 null을 주면 본문에 memo 칸을 아예 넣지 않는다 (화면에서 메모 없이 등록할 때와 같은 요청)
	private ResultActions register(Cookie session, String name, String memo) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("phone", PHONE);
		if (memo != null) {
			body.put("memo", memo);
		}
		return mockMvc.perform(post("/api/customers")
				.cookie(session)
				.contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(body)));
	}

	// 그 번호 고객의 메모 (DB에 실제로 저장된 값)
	private String memoInDb(String phoneDigits) {
		return jdbcTemplate.queryForObject("SELECT memo FROM customers WHERE phone = ?", String.class, phoneDigits);
	}

	private int customerCountWithPhone(String phoneDigits) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM customers WHERE phone = ?", Integer.class,
				phoneDigits);
	}

	// DROP 페이로드가 실행되지 않았는지 — 고객·장부 테이블이 그대로 있는가
	private boolean tablesStillExist() {
		return jdbcTemplate.queryForObject(
				"SELECT to_regclass('customers') IS NOT NULL AND to_regclass('ledger_entries') IS NOT NULL",
				Boolean.class);
	}

	// 직원 저장 → 진짜 토큰 발급 → 로그인 쿠키(CB_SESSION)에 담기
	private Cookie loginAsNewStaff(String loginId) {
		Employee employee = TestEmployees.readyStaff(loginId, passwordEncoder.encode("test1234!"), "테스트직원",
				clock.instant());
		employeeRepository.add(employee);
		return new Cookie(SessionCookies.NAME, jwtTokenService.issue(employee.getId()).value());
	}

}
