package com.creditbook.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.creditbook.global.security.JwtTestSupport.MutableClock;

import tools.jackson.databind.json.JsonMapper;

/**
 * 로그인 IP 레이트 리밋 (REQ-15): 같은 IP 의 POST /api/auth/login 을 1분 고정 창마다 10번까지 받고, 넘으면 429 와 Retry-After.
 * 필터만 순수 객체로 검증한다 (스프링 컨텍스트 없음). 기준값은 운영 기본 설정과 같은 10회·1분.
 */
@Tag("REQ-15")
class LoginRateLimitFilterTest {

	private static final Instant NOW = Instant.parse("2026-11-03T01:00:00Z");
	private static final int MAX = 10;
	private static final Duration WINDOW = Duration.ofMinutes(1);

	private final MutableClock clock = new MutableClock(NOW);
	private final LoginRateLimitFilter filter = new LoginRateLimitFilter(new LoginRateLimitProperties(MAX, WINDOW), clock,
			new SecurityErrorResponseWriter(JsonMapper.builder().build()));

	/** 요청 하나를 보내고 응답을 돌려준다. 체인까지 갔으면 응답 상태는 200(기본값)이다. */
	private Result send(String method, String path, String ip) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setRemoteAddr(ip);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request, response, chain);
		return new Result(response, chain.getRequest() != null);
	}

	private Result login(String ip) throws Exception {
		return send("POST", "/api/auth/login", ip);
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
	@CsvSource({ "GET, /api/auth/login", "POST, /api/auth/logout", "POST, /api/customers", "GET, /api/auth/session" })
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
		assertThat(LoginRateLimitFilter.retryAfterSeconds(NOW, NOW.plusMillis(remainingMillis))).isEqualTo(expected);
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

}
