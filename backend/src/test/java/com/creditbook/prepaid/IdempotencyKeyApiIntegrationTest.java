package com.creditbook.prepaid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 같은 요청이 시간차를 두고 두 번 오는 경우(응답이 늦어 다시 보냄, 재전송, 더블클릭) 거래가 한 번만 생기는지 본다 (REQ-10 B).
 * 첫 요청이 저장을 끝낸 뒤 오므로 낙관적 락으로는 충돌이 나지 않고, 요청 키(Idempotency-Key)로만 막을 수 있다.
 * 거래 행의 처리 직원(performed_by)이 실제 직원이어야 하므로 직원을 만들고 그 직원의 토큰으로 호출한다.
 */
@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Tag("REQ-10")
class IdempotencyKeyApiIntegrationTest {

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
	@DisplayName("동일 멱등키로 두 번 차감 요청 — 1건만 반영되고 두 번째 응답은 같은 거래 ID")
	@Tag("TC-12")
	void same_key_use_request_twice_is_applied_once() throws Exception {
		// given — 잔액 10,000원
		Cookie session = loginAsNewStaff("tc12-staff");
		RegisteredCustomer customer = customerService.register("멱등TC12", "01012120012");
		mockMvc.perform(withSession(post("/api/customers/{id}/charges", customer.customerId()), session)
				.header("Idempotency-Key", "tc12-charge")
				.content("{\"amount\": 10000}"))
				.andExpect(status().isCreated());

		// when — 같은 키(abc123)로 3,000원 사용을 두 번 (더블클릭·재전송 상황)
		String first = mockMvc.perform(withSession(post("/api/customers/{id}/uses", customer.customerId()), session)
				.header("Idempotency-Key", "abc123")
				.content("{\"amount\": 3000}"))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed")) // 첫 요청은 진짜 처리 → 재전송 표시 없음
				.andReturn().getResponse().getContentAsString();          // 응답 본문(JSON) 글자 그대로 받아 둠

		String second = mockMvc.perform(withSession(post("/api/customers/{id}/uses", customer.customerId()), session)
				.header("Idempotency-Key", "abc123")
				.content("{\"amount\": 3000}"))
				.andExpect(status().isCreated())                           // 두 번째도 201 (화면은 성공으로 보임)
				.andExpect(header().string("Idempotent-Replayed", "true")) // 하지만 "저장된 응답을 다시 보낸 것" 표시
				.andReturn().getResponse().getContentAsString();

		// then — 같은 거래 ID, 잔액 7,000원(3,000원만 한 번 빠짐), 사용 거래 1건
		entityManager.flush(); //JPA메모리 -> DB로 보내기
		assertThat(idOf(second)).isEqualTo(idOf(first));
		assertThat(balanceOf(customer.accountId())).isEqualByComparingTo("7000"); // BigDecimal은 isEqualByComparingTo
		assertThat(countEntries(customer.accountId(), "USE")).isEqualTo(1);
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
