package com.creditbook.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.creditbook.TestcontainersConfiguration;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.global.security.JwtTestSupport.MutableClock;
import com.creditbook.global.security.SessionCookies;
import com.creditbook.support.RequiresDocker;
import com.creditbook.support.TestEmployees;
import com.creditbook.support.TestStores;

/**
 * 로그인 잠금(423)·IP 레이트 리밋(429) 통합 테스트 (REQ-15·TC-53).
 * <p>
 * 실패해도 실패 횟수가 DB에 남는지(롤백되지 않는지)를 봐야 해서 클래스에 @Transactional 을 붙이지 않는다 — 데이터는 실제로 커밋되고
 * 테스트가 끝날 때 지운다. 시간 경계(5분 잠금, 1분 창)는 시계를 고정해 두고 옮겨서 본다.
 * 레이트 리밋 한도는 운영 기준(1분 10회)으로 되돌려 쓰고, 절차 1~8은 테스트마다 다른 IP로 보내 그 한도에 걸리지 않게 한다.
 */
@AutoConfigureMockMvc
@RequiresDocker
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"creditbook.auth.login-lock.max-failures=5",
		"creditbook.auth.login-lock.duration=5m",
		"creditbook.auth.login-rate-limit.max-requests=10", // 테스트 설정(10000)을 운영 기준으로 되돌림
		"creditbook.auth.login-rate-limit.window=1m" })
class LoginLockApiIntegrationTest {

	private static final String PASSWORD = "test1234!";
	private static final String WRONG_PASSWORD = "wrong-pass";
	private static final String LOCKED_MESSAGE = "로그인을 5회 실패해 계정이 잠겼습니다. 잠시 후 다시 시도해 주세요.";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	EmployeeRepository employeeRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	MutableClock clock;

	// 매장 (REQ-36) — 직원·고객은 매장이 있어야 저장된다. 테스트마다 새 매장
	UUID storeId;

	@BeforeEach
	void createStore() {
		storeId = TestStores.create(jdbcTemplate);
	}

	// 서버 시계를 테스트가 옮길 수 있는 시계로 바꿈
	@TestConfiguration
	static class FixedClockConfig {

		@Bean
		@Primary
		MutableClock testClock() {
			return new MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
		}

	}

	@BeforeEach
	void resetClock() {
		clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
	}

	// 이 클래스가 만든 직원만 지움 (커밋된 데이터라 롤백되지 않음)
	@AfterEach
	void deleteEmployees() {
		jdbcTemplate.update("DELETE FROM employees WHERE login_id LIKE 'tc53-%'");
	}

	@Test
	@DisplayName("틀린 비밀번호 4회는 매번 401, 5번째에 올바른 비밀번호면 200이고 연속 실패 횟수가 0으로 초기화된다")
	@Tag("TC-53")
	@Tag("REQ-15")
	void login_succeeds_after_four_failures_and_resets_count() throws Exception {
		// given
		saveEmployee("tc53-s1");
		String ip = "10.53.0.1";

		// when & then — 1~4회 틀림 → 401
		for (int i = 1; i <= 4; i++) {
			login(ip, "tc53-s1", WRONG_PASSWORD)
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		}
		assertThat(failedCountOf("tc53-s1")).isEqualTo(4); // 실패해도 횟수는 DB에 남음 (롤백 안 됨)

		// 5번째는 맞게 → 200, 쿠키 발급
		login(ip, "tc53-s1", PASSWORD)
				.andExpect(status().isOk())
				.andExpect(cookie().exists(SessionCookies.NAME));

		assertThat(failedCountOf("tc53-s1")).isZero();
		assertThat(lockedUntilOf("tc53-s1")).isNull();
	}

	@Test
	@DisplayName("틀린 비밀번호 5회 연속 — 1~4회는 401, 5번째 실패 응답부터 423 ACCOUNT_LOCKED, retryAfterSeconds 300")
	@Tag("TC-53")
	@Tag("REQ-15")
	void fifth_failure_locks_account_with_423() throws Exception {
		// given
		saveEmployee("tc53-s2");
		String ip = "10.53.0.2";

		// when & then — 1~4회 → 401
		for (int i = 1; i <= 4; i++) {
			login(ip, "tc53-s2", WRONG_PASSWORD).andExpect(status().isUnauthorized());
		}

		// 5번째 실패 → 그 응답부터 423 (잠금 전용 응답 형식)
		login(ip, "tc53-s2", WRONG_PASSWORD)
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
				.andExpect(jsonPath("$.message").value(LOCKED_MESSAGE))
				.andExpect(jsonPath("$.details.retryAfterSeconds").value(300))
				.andExpect(header().string("Retry-After", "300"))
				.andExpect(cookie().doesNotExist(SessionCookies.NAME));

		assertThat(lockedUntilOf("tc53-s2")).isEqualTo(clock.instant().plus(Duration.ofMinutes(5))); // 지금 + 5분
	}

	@Test
	@DisplayName("잠긴 동안에는 올바른 비밀번호도 423이고 쿠키가 없으며, 남은 시간이 시간에 따라 줄어든다")
	@Tag("TC-53")
	@Tag("REQ-15")
	void locked_account_rejects_correct_password() throws Exception {
		// given — 5회 틀려서 잠김
		saveEmployee("tc53-s3");
		String ip = "10.53.0.3";
		lockByFailures(ip, "tc53-s3");

		// when & then — 바로 맞게 넣어도 423
		login(ip, "tc53-s3", PASSWORD)
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.details.retryAfterSeconds").value(300))
				.andExpect(cookie().doesNotExist(SessionCookies.NAME));

		// 100초 뒤 → 남은 시간 200초
		advance(Duration.ofSeconds(100));
		login(ip, "tc53-s3", PASSWORD)
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.details.retryAfterSeconds").value(200))
				.andExpect(header().string("Retry-After", "200"))
				.andExpect(cookie().doesNotExist(SessionCookies.NAME));
	}

	@Test
	@DisplayName("잠금 후 4분 59초에는 423(남은 1초), 5분 0초에는 올바른 비밀번호로 200이고 실패 횟수·잠금 끝 시각이 초기화된다")
	@Tag("TC-53")
	@Tag("REQ-15")
	void lock_expires_after_five_minutes() throws Exception {
		// given
		saveEmployee("tc53-s4");
		String ip = "10.53.0.4";
		lockByFailures(ip, "tc53-s4");

		// when & then — 4분 59초: 아직 잠김
		advance(Duration.ofMinutes(4).plusSeconds(59));
		login(ip, "tc53-s4", PASSWORD)
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.details.retryAfterSeconds").value(1));

		// 5분 0초: 풀림
		advance(Duration.ofSeconds(1));
		login(ip, "tc53-s4", PASSWORD)
				.andExpect(status().isOk())
				.andExpect(cookie().exists(SessionCookies.NAME));

		assertThat(failedCountOf("tc53-s4")).isZero();
		assertThat(lockedUntilOf("tc53-s4")).isNull();
	}

	@Test
	@DisplayName("잠금 만료 후 다시 1회 틀리면 401 — 횟수는 1부터 다시 센다(점점 늘어나는 잠금 없음)")
	@Tag("TC-53")
	@Tag("REQ-15")
	void count_restarts_after_lock_expires() throws Exception {
		// given — 잠근 뒤 5분 지남 (로그인 성공 없이)
		saveEmployee("tc53-s5");
		String ip = "10.53.0.5";
		lockByFailures(ip, "tc53-s5");
		advance(Duration.ofMinutes(5));

		// when & then — 1회 틀림 → 423이 아니라 401
		login(ip, "tc53-s5", WRONG_PASSWORD)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

		assertThat(failedCountOf("tc53-s5")).isEqualTo(1); // 6이 아니라 1
		assertThat(lockedUntilOf("tc53-s5")).isNull();
	}

	@Test
	@DisplayName("3회 실패 → 성공 → 4회 실패면 잠기지 않는다 (연속 실패만 센다)")
	@Tag("TC-53")
	@Tag("REQ-15")
	void success_in_between_resets_consecutive_count() throws Exception {
		// given
		saveEmployee("tc53-s6");
		String ip = "10.53.0.6";

		// when — 3회 틀림 → 성공 → 4회 틀림 (요청 8번, IP 한도 10 안)
		for (int i = 1; i <= 3; i++) {
			login(ip, "tc53-s6", WRONG_PASSWORD).andExpect(status().isUnauthorized());
		}
		login(ip, "tc53-s6", PASSWORD).andExpect(status().isOk());
		for (int i = 1; i <= 4; i++) {
			login(ip, "tc53-s6", WRONG_PASSWORD).andExpect(status().isUnauthorized()); // 4번째도 423 아님
		}

		// then
		assertThat(failedCountOf("tc53-s6")).isEqualTo(4);
		assertThat(lockedUntilOf("tc53-s6")).isNull();
	}

	@Test
	@DisplayName("없는 아이디로 6회 시도하면 매번 같은 401·같은 메시지이고, 423이 나오지 않으며 DB에 실패 기록이 저장되지 않는다")
	@Tag("TC-53")
	@Tag("REQ-15")
	void unknown_login_id_is_never_locked() throws Exception {
		// given
		String ip = "10.53.0.7";
		Integer employeesBefore = countEmployees();
		String firstMessage = null;

		// when & then — 6회 모두 401 (5번째·6번째도 423 아님)
		for (int i = 1; i <= 6; i++) {
			String body = login(ip, "tc53-nobody", WRONG_PASSWORD)
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
					.andReturn().getResponse().getContentAsString();
			if (firstMessage == null) {
				firstMessage = body;
			}
			assertThat(body).isEqualTo(firstMessage); // 매번 같은 응답
		}

		// 없는 아이디는 아무것도 저장하지 않음
		assertThat(countEmployees()).isEqualTo(employeesBefore);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM employees WHERE login_id = 'tc53-nobody'",
				Integer.class)).isZero();
	}

	@Test
	@DisplayName("A 계정이 잠긴 동안에도 B 계정은 정상 로그인된다 (계정 단위 잠금)")
	@Tag("TC-53")
	@Tag("REQ-15")
	void lock_is_per_account() throws Exception {
		// given — A만 잠김
		saveEmployee("tc53-s8a");
		saveEmployee("tc53-s8b");
		String ip = "10.53.0.8";
		lockByFailures(ip, "tc53-s8a");

		// when & then — 같은 IP에서 B는 200 (요청 6번째, IP 한도 안)
		login(ip, "tc53-s8b", PASSWORD)
				.andExpect(status().isOk())
				.andExpect(cookie().exists(SessionCookies.NAME));
		assertThat(failedCountOf("tc53-s8b")).isZero();
	}

	@Test
	@DisplayName("같은 IP에서 1분 안에 로그인 요청 10회 뒤 11번째는 올바른 비밀번호라도 429, 없는 아이디도 같은 429이고 계정 실패 횟수는 늘지 않는다")
	@Tag("TC-53")
	@Tag("REQ-15")
	void eleventh_login_request_from_same_ip_is_rate_limited() throws Exception {
		// given — 같은 IP에서 없는 아이디로 10회 (한도까지 채움)
		saveEmployee("tc53-s9");
		String ip = "10.53.0.9";
		for (int i = 1; i <= 10; i++) {
			login(ip, "tc53-nobody", WRONG_PASSWORD).andExpect(status().isUnauthorized());
		}

		// when & then — 11번째: 있는 계정 + 맞는 비밀번호라도 429
		login(ip, "tc53-s9", PASSWORD)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"))
				.andExpect(jsonPath("$.message").value("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."))
				.andExpect(jsonPath("$.fieldErrors").isEmpty())
				.andExpect(jsonPath("$.details").doesNotExist()) // 남은 시간은 본문에 없음
				.andExpect(header().exists("Retry-After"))
				.andExpect(cookie().doesNotExist(SessionCookies.NAME));

		// 12번째: 없는 아이디도 똑같이 429 → 아이디 존재 여부가 안 드러남
		login(ip, "tc53-nobody", WRONG_PASSWORD)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));

		// 13번째: 있는 계정 + 틀린 비밀번호 → 429에서 멈추므로 실패로 세지 않음
		login(ip, "tc53-s9", WRONG_PASSWORD).andExpect(status().isTooManyRequests());
		assertThat(failedCountOf("tc53-s9")).isZero();
	}

	@Test
	@DisplayName("1분이 지나면 같은 IP에서 다시 정상 응답이 오고, 제한 중에도 다른 IP는 정상 로그인된다")
	@Tag("TC-53")
	@Tag("REQ-15")
	void rate_limit_is_per_ip_and_resets_after_window() throws Exception {
		// given — IP X가 한도를 넘김
		saveEmployee("tc53-s10");
		String limitedIp = "10.53.0.10";
		for (int i = 1; i <= 10; i++) {
			login(limitedIp, "tc53-nobody", WRONG_PASSWORD).andExpect(status().isUnauthorized());
		}
		login(limitedIp, "tc53-s10", PASSWORD).andExpect(status().isTooManyRequests());

		// when & then — 제한 중에도 다른 IP는 200
		login("10.53.0.11", "tc53-s10", PASSWORD).andExpect(status().isOk());

		// 1분 뒤 → X도 다시 정상 (틀리면 401, 맞으면 200)
		advance(Duration.ofMinutes(1));
		login(limitedIp, "tc53-s10", WRONG_PASSWORD).andExpect(status().isUnauthorized());
		login(limitedIp, "tc53-s10", PASSWORD)
				.andExpect(status().isOk())
				.andExpect(cookie().exists(SessionCookies.NAME));
	}

	// 로그인 요청 (MockMvc는 기본 IP가 모두 127.0.0.1이라 IP를 직접 지정)
	private ResultActions login(String ip, String loginId, String password) throws Exception {
		return mockMvc.perform(post("/api/auth/login")
				.with(request -> {
					request.setRemoteAddr(ip);
					return request;
				})
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"loginId": "%s", "password": "%s"}
						""".formatted(loginId, password)));
	}

	// 5회 틀려서 잠그기 (5번째가 423인지까지 확인)
	private void lockByFailures(String ip, String loginId) throws Exception {
		for (int i = 1; i <= 4; i++) {
			login(ip, loginId, WRONG_PASSWORD).andExpect(status().isUnauthorized());
		}
		login(ip, loginId, WRONG_PASSWORD).andExpect(status().isLocked());
	}

	private void advance(Duration duration) {
		clock.set(clock.instant().plus(duration));
	}

	//테스트용 직원 (트랜잭션을 열어 저장하고 바로 커밋)
	private void saveEmployee(String loginId) {
		transactionTemplate.executeWithoutResult(status -> employeeRepository.add(
				TestEmployees.readyStaff(storeId, loginId, passwordEncoder.encode(PASSWORD), "잠금테스트", clock.instant())));
	}

	//DB 칸 직접 읽기
	private Integer failedCountOf(String loginId) {
		return jdbcTemplate.queryForObject("SELECT failed_login_count FROM employees WHERE login_id = ?",
				Integer.class, loginId);
	}

	private Instant lockedUntilOf(String loginId) {
		java.sql.Timestamp lockedUntil = jdbcTemplate.queryForObject(
				"SELECT locked_until FROM employees WHERE login_id = ?", java.sql.Timestamp.class, loginId);
		return lockedUntil == null ? null : lockedUntil.toInstant();
	}

	private Integer countEmployees() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM employees", Integer.class);
	}

}
