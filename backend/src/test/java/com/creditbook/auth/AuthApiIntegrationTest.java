package com.creditbook.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
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

/**
 * 인증 통합 테스트. 인증 자체를 검증하므로 @WithMockEmployee 로 주입하지 않고,
 * 실제로 발급한 토큰을 세션 쿠키에 실어 보안 필터 체인 전체(토큰 검증 → 직원 상태 조회)를 거친다.
 * 직원·고객 데이터는 클래스 @Transactional 로 테스트마다 롤백된다.
 */
@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class AuthApiIntegrationTest {

	private static final String PASSWORD = "test1234!";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	EmployeeRepository employeeRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JwtTokenService jwtTokenService;

	@Autowired
	JwtEncoder jwtEncoder;

	@Autowired
	CustomerService customerService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Clock clock;

	@Autowired
	EntityManager entityManager;

	@Test
	@DisplayName("비인증 요청은 모두 401 — 본문에 스택트레이스·내부 경로 없음")
	@Tag("TC-7")
	@Tag("REQ-22")
	@Tag("REQ-15")
	void unauthenticated_requests_are_rejected_with_401() throws Exception {
		// given — 토큰(쿠키) 없음
		UUID anyCustomerId = UUID.randomUUID();

		// when & then — 아직 없는 경로(/api/dashboard/summary)도 인증 검사가 먼저라 401
		mockMvc.perform(get("/api/customers"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(content().string(not(containsString("Exception"))))
				.andExpect(content().string(not(containsString("com.creditbook"))));

		mockMvc.perform(post("/api/customers/{id}/charges", anyCustomerId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 10000}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(content().string(not(containsString("Exception"))))
				.andExpect(content().string(not(containsString("com.creditbook"))));

		mockMvc.perform(get("/api/dashboard/summary"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(content().string(not(containsString("Exception"))))
				.andExpect(content().string(not(containsString("com.creditbook"))));
	}

	@Test
	@DisplayName("헬스체크는 인증 없이 200")
	@Tag("TC-7")
	@Tag("REQ-22")
	void health_check_is_open_without_authentication() throws Exception {
		// given — 토큰(쿠키) 없음

		// when & then  배포 서버(Render)가 서버 상태를 확인할 때 로그인하지 않음
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("유효한 토큰이면 보호 API 200 — 아래 거부 테스트의 대조군")
	@Tag("TC-9")
	@Tag("REQ-17")
	void valid_token_is_accepted() throws Exception {
		// given — 직원 저장 후 진짜 토큰 발급 → 쿠키에 담기
		Employee employee = saveEmployee("tc9-valid", Role.STAFF);
		Cookie session = sessionCookie(jwtTokenService.issue(employee.getId()).value());

		// when & then — 정상 토큰은 200 (이게 통과해야 아래 401이 "토큰 문제" 때문임을 믿을 수 있음)
		mockMvc.perform(get("/api/customers").cookie(session))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("만료된 토큰으로 보호 API 호출 시 401")
	@Tag("TC-9")
	@Tag("REQ-17")
	void expired_token_is_rejected_with_401() throws Exception {
		// given — 서명은 올바르지만 만료 시각이 1시간 전인 토큰
		Employee employee = saveEmployee("tc9-expired", Role.STAFF);
		Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS); // JWT 시각은 초 단위라 밀리초 버림
		// 발급 17시간 전, 만료 1시간 전 → 유효기간(16h)은 정상인데 이미 지난 토큰
		String expiredToken = encode(employee.getId(), now.minus(Duration.ofHours(17)), now.minus(Duration.ofHours(1)));

		// when & then — 만료 토큰은 401
		mockMvc.perform(get("/api/customers").cookie(sessionCookie(expiredToken)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("서명만 바꾼 토큰으로 보호 API 호출 시 500이 아니라 401")
	@Tag("TC-9")
	@Tag("REQ-17")
	void tampered_signature_is_rejected_with_401() throws Exception {
		// given — 유효한 토큰의 서명 첫 글자만 바꾼다 (마지막 글자는 패딩 비트만 바뀔 수 있어 쓰지 않는다)
		Employee employee = saveEmployee("tc9-tampered", Role.STAFF);
		String validToken = jwtTokenService.issue(employee.getId()).value();
		int signatureStart = validToken.lastIndexOf('.') + 1; // 토큰 = 헤더.내용.서명 → 마지막 '.' 다음이 서명 시작
		char first = validToken.charAt(signatureStart);
		String tamperedToken = validToken.substring(0, signatureStart) // 서명 앞부분 그대로
				+ (first == 'A' ? 'B' : 'A')                            // 서명 첫 글자만 교체
				+ validToken.substring(signatureStart + 1);             // 나머지 서명 그대로

		// when & then — 위조 토큰은 서버 오류(500) 없이 401
		mockMvc.perform(get("/api/customers").cookie(sessionCookie(tamperedToken)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("비밀번호가 평문으로 저장되지 않음 — BCrypt 해시, 같은 비밀번호도 계정마다 다른 해시")
	@Tag("TC-11")
	@Tag("REQ-22")
	void password_is_stored_as_salted_bcrypt_hash() {
		// given — 직원 생성 API는 아직 없어 인코더와 저장소로 만든다 (TC-11 판정 방법)
		saveEmployee("tc11-first", Role.STAFF);
		saveEmployee("tc11-second", Role.STAFF);

		// when — DB를 직접 조회 (JPA가 쌓아 둔 INSERT를 먼저 내보낸다)
		entityManager.flush();
		String firstHash = passwordHashOf("tc11-first");
		String secondHash = passwordHashOf("tc11-second");

		// then
		assertThat(firstHash).isNotEqualTo(PASSWORD).startsWith("$2a$");  // 평문 아님 + BCrypt 표시($2a$)로 시작
		assertThat(secondHash).isNotEqualTo(PASSWORD).startsWith("$2a$");
		assertThat(firstHash).isNotEqualTo(secondHash);                    // 같은 비번인데 해시 다름 = 솔트 적용
		assertThat(passwordEncoder.matches(PASSWORD, firstHash)).isTrue(); // 그래도 원래 비번으로 로그인은 됨
	}

	@Test
	@DisplayName("충전·사용 거래에 실행 직원과 서버 시각 기록 — 요청에 끼워 넣은 시각·직원 ID는 무시")
	@Tag("TC-31")
	@Tag("REQ-20")
	void ledger_entries_record_authenticated_employee_and_server_time() throws Exception {
		// given — 직원 A로 로그인, 다른 직원 B, 고객 1명
		Employee employeeA = saveEmployee("tc31-a", Role.STAFF);
		Employee employeeB = saveEmployee("tc31-b", Role.STAFF);
		Cookie sessionA = sessionCookie(jwtTokenService.issue(employeeA.getId()).value());
		RegisteredCustomer customer = customerService.register("감사로그TC31", "01031310031");

		// when — 충전 요청에 가짜 시각·다른 직원 ID를 끼워 넣고, 사용은 정상 호출
		// (멱등키 헤더는 충전·사용 API 필수라 넣음 — 없으면 400)
		mockMvc.perform(post("/api/customers/{id}/charges", customer.customerId())
				.cookie(sessionA)
				.header("Idempotency-Key", "tc31-charge")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"amount": 10000, "performedAt": "2020-01-01T00:00:00Z", "employeeId": "%s"}
						""".formatted(employeeB.getId())))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/customers/{id}/uses", customer.customerId())
				.cookie(sessionA)
				.header("Idempotency-Key", "tc31-use")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 3000}"))
				.andExpect(status().isCreated());

		// then — 거래 이력 API는 S4라 DB에서 직접 확인한다.
		// JPA가 아직 DB에 보내지 않은 INSERT를 먼저 내보내야 JdbcTemplate 조회에 보인다 (롤백은 그대로)
		entityManager.flush();
		// 이 고객 계좌의 거래 행들을 순서대로 읽어 LedgerRow(유형·처리 직원·처리 시각)로 담기
		List<LedgerRow> rows = jdbcTemplate.query(
				"SELECT type, performed_by, performed_at FROM ledger_entries WHERE account_id = ? ORDER BY seq",
				(rs, rowNum) -> new LedgerRow(                                   // 행 하나 → LedgerRow 하나
						rs.getString("type"),
						rs.getObject("performed_by", UUID.class),
						rs.getObject("performed_at", OffsetDateTime.class).toInstant()),
				customer.accountId());                                           // ? 자리에 들어갈 값

		assertThat(rows).extracting(LedgerRow::type).containsExactly("CHARGE", "USE"); // 충전·사용 2건이 이 순서로
		assertThat(rows).allSatisfy(row -> {                                             // 모든 행이
			assertThat(row.performedBy()).isEqualTo(employeeA.getId());                  // 로그인한 A로 기록 (끼워 넣은 B 무시)
			assertThat(row.performedAt()).isCloseTo(clock.instant(), within(5, ChronoUnit.SECONDS)); // 지금 시각 ±5초 (2020년 무시)
		});
	}

	//테스트용 직원
	private Employee saveEmployee(String loginId, Role role) {
		Employee employee = Employee.create(loginId, passwordEncoder.encode(PASSWORD), "테스트직원", role,
				clock.instant());
		employeeRepository.add(employee);
		return employee;
	}

	//DB password_hash칸 직접 읽기
	private String passwordHashOf(String loginId) {
		return jdbcTemplate.queryForObject("SELECT password_hash FROM employees WHERE login_id = ?", String.class,
				loginId);
	}

	// 만료된 토큰을 만들기
	private String encode(UUID employeeId, Instant issuedAt, Instant expiresAt) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(employeeId.toString())
				.id(UUID.randomUUID().toString())
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	//토큰을 로그인 쿠키(CB_SESSION)에 담기
	private static Cookie sessionCookie(String tokenValue) {
		return new Cookie(SessionCookies.NAME, tokenValue);
	}

	private record LedgerRow(String type, UUID performedBy, Instant performedAt) {
	}

}
