package com.creditbook.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * 프록시 경유 확인 (CB-59): 비밀 헤더가 맞는 요청만 통과시키고, 없거나 다르면 403. 헬스 체크는 확인하지 않는다.
 * 필터만 순수 객체로 검증한다 (스프링 컨텍스트 없음).
 */
@Tag("REQ-15")
class OriginGuardFilterTest {

	private static final String SECRET = "0123456789abcdef0123456789abcdef";

	private final OriginGuardFilter filter = new OriginGuardFilter(new OriginGuardProperties(SECRET),
			new SecurityErrorResponseWriter(JsonMapper.builder().build()));

	/** 요청 하나를 보내고 응답을 돌려준다. 체인까지 갔으면 응답 상태는 200(기본값)이다. */
	private Result send(String method, String path, String headerValue) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		if (headerValue != null) {
			request.addHeader(OriginGuardFilter.HEADER, headerValue);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request, response, chain);
		return new Result(response, chain.getRequest() != null);
	}

	private record Result(MockHttpServletResponse response, boolean passed) {
	}

	@Test
	@DisplayName("프록시가 붙인 비밀 헤더가 맞으면 통과한다")
	void request_with_matching_secret_passes() throws Exception {
		// given
		String header = SECRET;

		// when
		Result result = send("POST", "/api/auth/login", header);

		// then
		assertThat(result.passed()).isTrue();
		assertThat(result.response().getStatus()).isEqualTo(200);
	}

	@ParameterizedTest(name = "[{index}] 헤더 값 \"{0}\"")
	@NullSource
	@ValueSource(strings = { "", "wrong", "0123456789abcdef0123456789abcdeX", "0123456789abcdef0123456789abcdef0" })
	@DisplayName("비밀 헤더가 없거나 다르면 403 으로 거절하고 다음 필터로 넘기지 않는다")
	void request_without_matching_secret_is_rejected(String header) throws Exception {
		// given - 프록시를 거치지 않고 서버 주소로 직접 보낸 요청

		// when
		Result result = send("POST", "/api/auth/login", header);

		// then
		assertThat(result.passed()).isFalse();
		assertThat(result.response().getStatus()).isEqualTo(403);
		assertThat(result.response().getContentAsString()).contains("\"code\":\"FORBIDDEN\"").doesNotContain(SECRET);
	}

	@Test
	@DisplayName("헬스 체크는 비밀 헤더 없이도 통과한다")
	void health_check_passes_without_secret() throws Exception {
		// given - 배포처가 직접 부르는 헬스 체크

		// when
		Result result = send("GET", "/actuator/health", null);

		// then
		assertThat(result.passed()).isTrue();
	}

	@Test
	@DisplayName("헬스 체크 말고 다른 actuator 경로는 확인한다")
	void other_actuator_paths_are_checked() throws Exception {
		// when
		Result result = send("GET", "/actuator/env", null);

		// then
		assertThat(result.passed()).isFalse();
		assertThat(result.response().getStatus()).isEqualTo(403);
	}

	@Test
	@DisplayName("설정값이 비어 있으면 확인을 끈다")
	void blank_secret_disables_guard() {
		assertThat(new OriginGuardProperties(null).isEnabled()).isFalse();
		assertThat(new OriginGuardProperties("  ").isEnabled()).isFalse();
		assertThat(new OriginGuardProperties(SECRET).isEnabled()).isTrue();
	}

	@Test
	@DisplayName("설정값이 32자보다 짧으면 기동을 멈춘다")
	void short_secret_fails_startup() {
		assertThatThrownBy(() -> new OriginGuardProperties("short-secret")).isInstanceOf(IllegalStateException.class);
	}

}
