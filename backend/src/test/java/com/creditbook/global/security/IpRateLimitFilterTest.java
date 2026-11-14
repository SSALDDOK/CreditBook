package com.creditbook.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.creditbook.global.error.ErrorCode;
import com.creditbook.global.security.JwtTestSupport.MutableClock;

import tools.jackson.databind.json.JsonMapper;

/**
 * IP 레이트 리밋 필터. 로그인 (REQ-15): 같은 IP 의 POST /api/auth/login 을 1분 고정 창마다 10번까지 받고, 넘으면 429 와 Retry-After.
 * 가입 (REQ-36): POST /api/setup 을 10분 고정 창마다 5번까지. 필터만 순수 객체로 검증한다 (스프링 컨텍스트 없음).
 * 기준값은 운영 기본 설정과 같고, 필터는 SecurityConfig 와 같은 이름·경로·오류 코드로 만든다.
 */
@Tag("REQ-15")
@ExtendWith(OutputCaptureExtension.class)
class IpRateLimitFilterTest {

	private static final Instant NOW = Instant.parse("2026-11-03T01:00:00Z");
	private static final int MAX = 10;
	private static final Duration WINDOW = Duration.ofMinutes(1);
	private static final int SIGNUP_MAX = 5;
	private static final Duration SIGNUP_WINDOW = Duration.ofMinutes(10);

	private final MutableClock clock = new MutableClock(NOW);
	private final SecurityErrorResponseWriter writer = new SecurityErrorResponseWriter(JsonMapper.builder().build());
	private final IpRateLimitFilter filter = new IpRateLimitFilter("login", SecurityConfig.LOGIN, MAX, WINDOW,
			ErrorCode.LOGIN_RATE_LIMITED, clock, writer);
	private final IpRateLimitFilter signupFilter = new IpRateLimitFilter("signup", SecurityConfig.SIGNUP, SIGNUP_MAX,
			SIGNUP_WINDOW, ErrorCode.SIGNUP_RATE_LIMITED, clock, writer);

	/** 요청 하나를 보내고 응답을 돌려준다. 체인까지 갔으면 응답 상태는 200(기본값)이다. */
	private Result send(String method, String path, String ip) throws Exception {
		return send(filter, method, path, ip);
	}

	private static Result send(IpRateLimitFilter target, String method, String path, String ip) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setRemoteAddr(ip);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		target.doFilter(request, response, chain);
		return new Result(response, chain.getRequest() != null);
	}

	private Result login(String ip) throws Exception {
		return send("POST", "/api/auth/login", ip);
	}

	private Result signup(String ip) throws Exception {
		return send(signupFilter, "POST", "/api/setup", ip);
	}

	private record Result(MockHttpServletResponse response, boolean passed) {
	}

	@ParameterizedTest(name = "[{index}] {0}번째 요청 → 통과 {1}")
	@CsvSource({ "1, true", "10, true", "11, false", "12, false" })
	@DisplayName("같은 IP 의 로그인 요청은 1분에 10번까지 받고 11번째부터 429 로 거절한다")
	void eleventh_request_in_window_is_rejected(int nth, boolean expectedPassed) throws Exception {
		// given
		for (int i = 1; i < nth; i++) {
			login("203.0.113.7");
		}

		// when
		Result result = login("203.0.113.7");

		// then
		assertThat(result.passed()).isEqualTo(expectedPassed);
		assertThat(result.response().getStatus()).isEqualTo(expectedPassed ? 200 : 429);
	}

	@Test
	@DisplayName("429 응답은 LOGIN_RATE_LIMITED 오류 본문(details 없음)과 창이 끝날 때까지 남은 초를 Retry-After 로 준다")
	void rejected_response_has_error_body_and_retry_after() throws Exception {
		// given — 창은 NOW 에 시작해 NOW+60초에 끝난다
		for (int i = 0; i < MAX; i++) {
			login("203.0.113.7");
		}
		clock.set(NOW.plusSeconds(20).plusMillis(500));

		// when
		Result result = login("203.0.113.7");

		// then
		MockHttpServletResponse response = result.response();
		assertThat(response.getStatus()).isEqualTo(429);
		assertThat(response.getHeader("Retry-After")).isEqualTo("40");
		assertThat(response.getContentType()).startsWith("application/json");
		assertThat(response.getContentAsString())
				.contains("\"code\":\"LOGIN_RATE_LIMITED\"")
				.contains("\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.\"")
				.contains("\"fieldErrors\":[]")
				.doesNotContain("details");
	}

	@Test
	@DisplayName("창이 끝나면(1분 0초) 같은 IP 도 다시 10번 받는다")
	void new_window_starts_after_one_minute() throws Exception {
		// given
		for (int i = 0; i < MAX + 1; i++) {
			login("203.0.113.7");
		}
		clock.set(NOW.plus(WINDOW).minusMillis(1));
		assertThat(login("203.0.113.7").passed()).isFalse();

		// when
		clock.set(NOW.plus(WINDOW));

		// then
		for (int i = 0; i < MAX; i++) {
			assertThat(login("203.0.113.7").passed()).isTrue();
		}
		assertThat(login("203.0.113.7").passed()).isFalse();
	}

	@Test
	@DisplayName("IP 마다 따로 센다 — 한 IP 가 막혀도 다른 IP 의 로그인은 받는다")
	void counts_per_ip() throws Exception {
		// given
		for (int i = 0; i < MAX + 1; i++) {
			login("203.0.113.7");
		}

		// when
		Result other = login("198.51.100.20");

		// then
		assertThat(other.passed()).isTrue();
	}

	@Test
	@DisplayName("X-Forwarded-For 헤더를 바꿔도 다른 IP 로 세지 않는다 — 접속 주소(remoteAddr)만 쓴다")
	void forwarded_header_is_not_trusted() throws Exception {
		// given
		for (int i = 0; i < MAX; i++) {
			login("203.0.113.7");
		}
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
		request.setRemoteAddr("203.0.113.7");
		request.addHeader("X-Forwarded-For", "192.0.2.99");
		MockHttpServletResponse response = new MockHttpServletResponse();

		// when
		filter.doFilter(request, response, new MockFilterChain());

		// then
		assertThat(response.getStatus()).isEqualTo(429);
	}

	@ParameterizedTest(name = "[{index}] {0} {1}")
	@CsvSource({ "GET, /api/auth/login", "POST, /api/auth/logout", "POST, /api/customers", "GET, /api/auth/session",
			"POST, /api/setup" })
	@DisplayName("로그인(POST /api/auth/login) 말고는 세지도 막지도 않는다")
	void other_requests_are_not_limited(String method, String path) throws Exception {
		// given
		for (int i = 0; i < MAX + 1; i++) {
			login("203.0.113.7");
		}

		// when
		Result result = send(method, path, "203.0.113.7");

		// then
		assertThat(result.passed()).isTrue();
	}

	@Test
	@DisplayName("창이 끝난 IP 항목은 다음 창 길이가 지난 뒤 첫 요청 때 지워진다 — 맵이 계속 커지지 않는다")
	void expired_windows_are_swept() throws Exception {
		// given
		login("203.0.113.1");
		login("203.0.113.2");
		login("203.0.113.3");
		assertThat(filter.trackedClients()).isEqualTo(3);

		// when
		clock.set(NOW.plus(WINDOW).plusSeconds(1));
		login("203.0.113.9");

		// then
		assertThat(filter.trackedClients()).isEqualTo(1);
	}

	@ParameterizedTest(name = "[{index}] 남은 {0}ms → {1}초")
	@CsvSource({ "60000, 60", "59001, 60", "1000, 1", "1, 1", "0, 1" })
	@DisplayName("Retry-After 는 남은 시간을 올림한 정수 초이고 1초보다 작지 않다")
	void retry_after_is_rounded_up(long remainingMillis, long expected) {
		assertThat(IpRateLimitFilter.retryAfterSeconds(NOW, NOW.plusMillis(remainingMillis))).isEqualTo(expected);
	}

	@ParameterizedTest(name = "[{index}] maxRequests={0}")
	@ValueSource(ints = { 0, -1 })
	@DisplayName("요청 수 기준이 1 미만이면 기동을 멈춘다")
	void max_requests_must_be_positive(int maxRequests) {
		assertThatThrownBy(() -> new LoginRateLimitProperties(maxRequests, WINDOW))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("max-requests");
	}

	@ParameterizedTest(name = "[{index}] {0}초")
	@ValueSource(longs = { 0, -1 })
	@DisplayName("창 길이가 0 이하이면 기동을 멈춘다")
	void window_must_be_positive(long seconds) {
		assertThatThrownBy(() -> new LoginRateLimitProperties(MAX, Duration.ofSeconds(seconds)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("window");
	}

	@Test
	@DisplayName("요청 수 기준·창 길이가 없으면 기동을 멈춘다")
	void missing_values_fail() {
		assertThatThrownBy(() -> new LoginRateLimitProperties(null, WINDOW)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new LoginRateLimitProperties(MAX, null)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("로그인 거절 로그는 창마다 처음 한 번, 'login rate limited: ip=… maxRequests=… window=…' WARN 으로 남는다")
	void login_rejection_log_is_unchanged(CapturedOutput output) throws Exception {
		// given
		for (int i = 0; i < MAX; i++) {
			login("203.0.113.7");
		}

		// when — 넘친 요청 2번
		login("203.0.113.7");
		login("203.0.113.7");

		// then
		assertThat(output.getOut().lines().filter(line -> line.contains("rate limited")))
				.singleElement().asString()
				.contains("WARN")
				.contains("login rate limited: ip=203.0.113.7 maxRequests=10 window=PT1M");
	}

	@Test
	@DisplayName("필터를 만들 때 요청 수 기준이 1 미만이거나 창 길이가 0 이하이면 거절한다")
	void filter_rejects_invalid_limits() {
		assertThatThrownBy(() -> new IpRateLimitFilter("login", SecurityConfig.LOGIN, 0, WINDOW,
				ErrorCode.LOGIN_RATE_LIMITED, clock, writer)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new IpRateLimitFilter("login", SecurityConfig.LOGIN, MAX, Duration.ZERO,
				ErrorCode.LOGIN_RATE_LIMITED, clock, writer)).isInstanceOf(IllegalArgumentException.class);
	}

	// ---- 초대 코드 가입 (REQ-36)

	@ParameterizedTest(name = "[{index}] {0}번째 요청 → 통과 {1}")
	@CsvSource({ "1, true", "5, true", "6, false", "7, false" })
	@Tag("REQ-36")
	@DisplayName("같은 IP 의 가입 요청은 10분에 5번까지 받고 6번째부터 429 로 거절한다")
	void sixth_signup_in_window_is_rejected(int nth, boolean expectedPassed) throws Exception {
		// given
		for (int i = 1; i < nth; i++) {
			signup("203.0.113.7");
		}

		// when
		Result result = signup("203.0.113.7");

		// then
		assertThat(result.passed()).isEqualTo(expectedPassed);
		assertThat(result.response().getStatus()).isEqualTo(expectedPassed ? 200 : 429);
	}

	@Test
	@Tag("REQ-36")
	@DisplayName("가입 429 응답은 SIGNUP_RATE_LIMITED 오류 본문(details 없음)과 창이 끝날 때까지 남은 초를 Retry-After 로 준다")
	void signup_rejected_response_has_error_body_and_retry_after(CapturedOutput output) throws Exception {
		// given — 창은 NOW 에 시작해 NOW+600초에 끝난다
		for (int i = 0; i < SIGNUP_MAX; i++) {
			signup("203.0.113.7");
		}
		clock.set(NOW.plusSeconds(100).plusMillis(500));

		// when
		Result result = signup("203.0.113.7");

		// then
		MockHttpServletResponse response = result.response();
		assertThat(response.getStatus()).isEqualTo(429);
		assertThat(response.getHeader("Retry-After")).isEqualTo("500");
		assertThat(response.getContentAsString())
				.contains("\"code\":\"SIGNUP_RATE_LIMITED\"")
				.contains("\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.\"")
				.contains("\"fieldErrors\":[]")
				.doesNotContain("details");
		assertThat(output.getOut()).contains("signup rate limited: ip=203.0.113.7 maxRequests=5 window=PT10M");
	}

	@Test
	@Tag("REQ-36")
	@DisplayName("가입 창이 끝나면(10분 0초) 같은 IP 도 다시 5번 받는다")
	void new_signup_window_starts_after_ten_minutes() throws Exception {
		// given
		for (int i = 0; i < SIGNUP_MAX + 1; i++) {
			signup("203.0.113.7");
		}

		// when
		clock.set(NOW.plus(SIGNUP_WINDOW));

		// then
		for (int i = 0; i < SIGNUP_MAX; i++) {
			assertThat(signup("203.0.113.7").passed()).isTrue();
		}
		assertThat(signup("203.0.113.7").passed()).isFalse();
	}

	@Test
	@Tag("REQ-36")
	@DisplayName("가입과 로그인은 따로 센다 — 가입이 막혀도 같은 IP 의 로그인은 받고, 그 반대도 같다")
	void signup_and_login_are_counted_separately() throws Exception {
		// given — 가입 한도 초과
		for (int i = 0; i < SIGNUP_MAX + 1; i++) {
			signup("203.0.113.7");
		}

		// when / then — 같은 IP 의 로그인은 받는다
		assertThat(login("203.0.113.7").passed()).isTrue();

		// given — 다른 IP 의 로그인 한도 초과
		for (int i = 0; i < MAX + 1; i++) {
			login("198.51.100.20");
		}

		// when / then — 그 IP 의 가입은 받는다
		assertThat(signup("198.51.100.20").passed()).isTrue();
	}

	@ParameterizedTest(name = "[{index}] {0} {1}")
	@CsvSource({ "GET, /api/setup", "GET, /api/setup/status", "POST, /api/auth/login", "POST, /api/customers" })
	@Tag("REQ-36")
	@DisplayName("가입 필터는 POST /api/setup 말고는 세지도 막지도 않는다")
	void signup_filter_ignores_other_requests(String method, String path) throws Exception {
		// given
		for (int i = 0; i < SIGNUP_MAX + 1; i++) {
			signup("203.0.113.7");
		}

		// when
		Result result = send(signupFilter, method, path, "203.0.113.7");

		// then
		assertThat(result.passed()).isTrue();
	}

	@ParameterizedTest(name = "[{index}] maxRequests={0}")
	@ValueSource(ints = { 0, -1 })
	@Tag("REQ-36")
	@DisplayName("가입 요청 수 기준이 1 미만이면 기동을 멈춘다")
	void signup_max_requests_must_be_positive(int maxRequests) {
		assertThatThrownBy(() -> new SignupRateLimitProperties(maxRequests, SIGNUP_WINDOW))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("signup-rate-limit.max-requests");
	}

	@Test
	@Tag("REQ-36")
	@DisplayName("가입 창 길이가 0 이하이거나 값이 없으면 기동을 멈춘다")
	void signup_window_must_be_positive() {
		assertThatThrownBy(() -> new SignupRateLimitProperties(SIGNUP_MAX, Duration.ZERO))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("signup-rate-limit.window");
		assertThatThrownBy(() -> new SignupRateLimitProperties(null, SIGNUP_WINDOW))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new SignupRateLimitProperties(SIGNUP_MAX, null))
				.isInstanceOf(IllegalStateException.class);
	}

}
