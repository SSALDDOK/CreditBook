package com.creditbook.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

/**
 * RequestIdFilter 단위 테스트 — 스프링 컨텍스트 없이 Mock 요청·응답·체인으로 헤더 검증, MDC 설정·정리를 본다.
 */
class RequestIdFilterTest {

	private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

	private final RequestIdFilter filter = new RequestIdFilter();

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	/** 체인 안에서 보인 MDC requestId 를 담아 둔다. */
	private static FilterChain capturingChain(AtomicReference<String> seen) {
		return (request, response) -> seen.set(MDC.get(RequestIdFilter.MDC_KEY));
	}

	@Test
	@DisplayName("헤더 없이 요청하면 새 id 를 만들어 체인 안의 로그 문맥과 응답 헤더에 같은 값으로 싣는다")
	void generates_new_id_when_header_is_missing() throws Exception {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/customers");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> seenInChain = new AtomicReference<>();

		// when
		filter.doFilter(request, response, capturingChain(seenInChain));

		// then
		String header = response.getHeader(RequestIdFilter.HEADER_NAME);
		assertThat(header).matches(UUID_PATTERN);
		assertThat(seenInChain.get()).isEqualTo(header);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("acceptableIds")
	@DisplayName("형식에 맞는 헤더는 그 값을 그대로 쓴다")
	void keeps_incoming_id_when_format_is_valid(String description, String incoming) throws Exception {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/customers");
		request.addHeader(RequestIdFilter.HEADER_NAME, incoming);
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> seenInChain = new AtomicReference<>();

		// when
		filter.doFilter(request, response, capturingChain(seenInChain));

		// then
		assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo(incoming);
		assertThat(seenInChain.get()).isEqualTo(incoming);
	}

	static Stream<Arguments> acceptableIds() {
		return Stream.of(
				Arguments.of("UUID", "3f2b8c1e-9d4a-4e7b-8c6d-1a2b3c4d5e6f"),
				Arguments.of("1자 (하한)", "a"),
				Arguments.of("64자 (상한)", "A".repeat(64)),
				Arguments.of("영문 대소문자·숫자·하이픈", "Req-2026-abcXYZ-01"));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("unacceptableIds")
	@DisplayName("형식에 맞지 않거나 너무 긴 헤더는 버리고 새 id 를 만든다")
	void replaces_incoming_id_when_format_is_invalid(String description, String incoming) throws Exception {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/customers");
		request.addHeader(RequestIdFilter.HEADER_NAME, incoming);
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> seenInChain = new AtomicReference<>();

		// when
		filter.doFilter(request, response, capturingChain(seenInChain));

		// then
		String header = response.getHeader(RequestIdFilter.HEADER_NAME);
		assertThat(header).isNotEqualTo(incoming).matches(UUID_PATTERN);
		assertThat(seenInChain.get()).isEqualTo(header);
	}

	static Stream<Arguments> unacceptableIds() {
		return Stream.of(
				Arguments.of("빈 값", ""),
				Arguments.of("공백만", "   "),
				Arguments.of("65자 (상한 초과)", "A".repeat(65)),
				Arguments.of("중간 공백", "abc def"),
				Arguments.of("끝에 개행", "abc\n"),
				Arguments.of("CRLF 로 가짜 로그 줄 주입", "abc\r\n2026-10-07 INFO fake"),
				Arguments.of("퍼센트 인코딩", "abc%0Adef"),
				Arguments.of("중괄호 (패턴 문자)", "${jndi:x}"),
				Arguments.of("밑줄", "abc_def"),
				Arguments.of("한글", "요청아이디"));
	}

	@Test
	@DisplayName("요청이 끝나면 다음 요청에 id 가 남지 않는다")
	void clears_mdc_after_request() throws Exception {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/customers");
		request.addHeader(RequestIdFilter.HEADER_NAME, "first-request");

		// when
		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		// then
		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
	}

	@Test
	@DisplayName("체인에서 예외가 나도 요청이 끝나면 id 가 지워지고 응답 헤더에는 id 가 실려 있다")
	void clears_mdc_even_when_chain_throws() {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/customers");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain failing = (req, res) -> {
			throw new ServletException("boom");
		};

		// when / then
		assertThatThrownBy(() -> filter.doFilter(request, response, failing)).isInstanceOf(ServletException.class);
		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
		assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).matches(UUID_PATTERN);
	}

	@Test
	@DisplayName("헤더 없는 요청이 연달아 오면 요청마다 다른 id 를 만든다")
	void generates_distinct_ids_per_request() throws Exception {
		// given
		MockHttpServletResponse first = new MockHttpServletResponse();
		MockHttpServletResponse second = new MockHttpServletResponse();

		// when
		filter.doFilter(new MockHttpServletRequest("GET", "/a"), first, new MockFilterChain());
		filter.doFilter(new MockHttpServletRequest("GET", "/b"), second, new MockFilterChain());

		// then
		assertThat(first.getHeader(RequestIdFilter.HEADER_NAME))
				.isNotEqualTo(second.getHeader(RequestIdFilter.HEADER_NAME));
	}

	@Test
	@DisplayName("같은 요청이 오류 디스패치로 다시 들어오면 처음 정한 id 를 그대로 쓴다")
	void reuses_id_on_error_dispatch() throws Exception {
		// given
		String original = UUID.randomUUID().toString();
		MockHttpServletRequest errorDispatch = new MockHttpServletRequest("GET", "/error");
		errorDispatch.setDispatcherType(DispatcherType.ERROR);
		errorDispatch.setAttribute(RequestIdFilter.REQUEST_ATTRIBUTE, original);
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> seenInChain = new AtomicReference<>();

		// when
		filter.doFilter(errorDispatch, response, capturingChain(seenInChain));

		// then
		assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo(original);
		assertThat(seenInChain.get()).isEqualTo(original);
		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
	}

	@Test
	@DisplayName("같은 스레드에서 겹쳐 들어온 요청이 끝나면 바깥 요청의 id 를 되돌린다")
	void restores_outer_id_after_nested_request() throws Exception {
		// given
		MDC.put(RequestIdFilter.MDC_KEY, "outer-id");
		MockHttpServletRequest inner = new MockHttpServletRequest("GET", "/inner");
		inner.addHeader(RequestIdFilter.HEADER_NAME, "inner-id");
		AtomicReference<String> seenInChain = new AtomicReference<>();

		// when
		filter.doFilter(inner, new MockHttpServletResponse(), capturingChain(seenInChain));

		// then
		assertThat(seenInChain.get()).isEqualTo("inner-id");
		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("outer-id");
	}

}
