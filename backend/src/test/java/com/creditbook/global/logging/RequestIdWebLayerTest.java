package com.creditbook.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.creditbook.global.security.CurrentEmployee;
import com.creditbook.prepaid.application.UsageResult;
import com.creditbook.prepaid.application.UsageService;
import com.creditbook.prepaid.controller.UsageController;
import com.creditbook.prepaid.domain.LedgerEntryType;

/**
 * 웹 계층 슬라이스에서 RequestIdFilter 가 MockMvc 체인에 들어가는지, 성공·4xx·5xx 응답 헤더와 로그 라인에 같은 id 가 실리는지 본다.
 * 사용 API 를 대표 경로로 쓰고 서비스는 목으로 대체한다. 로그 형식은 application.yml 의 {@code logging.pattern.correlation} 이다.
 */
@WebMvcTest(UsageController.class)
@ExtendWith(OutputCaptureExtension.class)
class RequestIdWebLayerTest {

	private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";
	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	private static final String URL = "/api/customers/" + CUSTOMER_ID + "/uses";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	UsageService usageService;

	@MockitoBean
	CurrentEmployee currentEmployee;

	private static MockHttpServletRequestBuilder useRequest(long amount) {
		return post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"amount\": " + amount + "}");
	}

	private void givenSuccessfulUse() {
		given(currentEmployee.id()).willReturn(EMPLOYEE_ID);
		given(usageService.use(any(), any(), any(), any())).willReturn(new UsageResult(UUID.randomUUID(),
				UUID.randomUUID(), CUSTOMER_ID, LedgerEntryType.USE, new BigDecimal("1000"), new BigDecimal("9000"),
				null, EMPLOYEE_ID, Instant.parse("2026-10-07T01:00:00Z")));
	}

	@Test
	@DisplayName("헤더 없이 요청하면 응답 헤더에 새 id 가 실린다")
	void response_carries_generated_id_when_header_is_missing() throws Exception {
		// given
		givenSuccessfulUse();

		// when / then
		mockMvc.perform(useRequest(1000))
				.andExpect(status().isCreated())
				.andExpect(header().string(RequestIdFilter.HEADER_NAME, matchesPattern(UUID_PATTERN)));
	}

	@Test
	@DisplayName("형식에 맞는 헤더로 요청하면 응답 헤더에 그 값이 그대로 실린다")
	void response_echoes_valid_incoming_id() throws Exception {
		// given
		givenSuccessfulUse();

		// when / then
		mockMvc.perform(useRequest(1000).header(RequestIdFilter.HEADER_NAME, "client-req-0001"))
				.andExpect(status().isCreated())
				.andExpect(header().string(RequestIdFilter.HEADER_NAME, "client-req-0001"));
	}

	@Test
	@DisplayName("형식에 맞지 않는 헤더로 요청하면 그 값을 버리고 응답 헤더에 새 id 가 실린다")
	void response_replaces_invalid_incoming_id() throws Exception {
		// given
		givenSuccessfulUse();
		String forged = "abc\r\n2026-10-07 INFO forged line";

		// when
		MvcResult result = mockMvc.perform(useRequest(1000).header(RequestIdFilter.HEADER_NAME, forged))
				.andExpect(status().isCreated())
				.andReturn();

		// then
		assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER_NAME))
				.isNotEqualTo(forged)
				.matches(UUID_PATTERN);
	}

	@Test
	@DisplayName("입력 검증 오류(400) 응답에도 id 가 응답 헤더에 실리고 본문은 바뀌지 않는다")
	void validation_error_response_carries_id() throws Exception {
		// given
		given(currentEmployee.id()).willReturn(EMPLOYEE_ID);

		// when / then
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}")
				.header(RequestIdFilter.HEADER_NAME, "bad-input-1"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string(RequestIdFilter.HEADER_NAME, "bad-input-1"))
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.requestId").doesNotExist());
	}

	@Test
	@DisplayName("없는 경로(404) 응답에도 id 가 응답 헤더에 실린다")
	void not_found_response_carries_id() throws Exception {
		// when / then
		mockMvc.perform(get("/api/no-such-path").header(RequestIdFilter.HEADER_NAME, "missing-path-1"))
				.andExpect(status().isNotFound())
				.andExpect(header().string(RequestIdFilter.HEADER_NAME, "missing-path-1"));
	}

	@Test
	@DisplayName("인증 거절(401) 응답과 WARN 로그에 같은 id 가 실린다")
	void unauthenticated_response_and_warn_log_carry_same_id(CapturedOutput output) throws Exception {
		// given
		given(currentEmployee.id()).willReturn(null);

		// when
		MvcResult result = mockMvc.perform(useRequest(1000))
				.andExpect(status().isUnauthorized())
				.andReturn();

		// then
		String requestId = result.getResponse().getHeader(RequestIdFilter.HEADER_NAME);
		assertThat(requestId).matches(UUID_PATTERN);
		assertThat(output.getOut().lines().filter(line -> line.contains("request rejected: unauthenticated")))
				.singleElement()
				.asString()
				.contains("WARN")
				.contains("[rid=" + requestId + "]");
	}

	@Test
	@DisplayName("예상하지 못한 오류(500)는 ERROR 로그와 응답 헤더에 같은 id 를 싣고, 응답 본문에는 스택트레이스 등 내부 정보를 넣지 않는다")
	void unexpected_error_logs_id_and_hides_internals(CapturedOutput output) throws Exception {
		// given
		given(currentEmployee.id()).willReturn(EMPLOYEE_ID);
		given(usageService.use(any(), any(), any(), any()))
				.willThrow(new IllegalStateException("internal-detail-should-not-leak"));

		// when
		MvcResult result = mockMvc.perform(useRequest(1000).header(RequestIdFilter.HEADER_NAME, "boom-0001"))
				.andExpect(status().isInternalServerError())
				.andExpect(header().string(RequestIdFilter.HEADER_NAME, "boom-0001"))
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.requestId").doesNotExist())
				.andReturn();

		// then
		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("internal-detail-should-not-leak", "IllegalStateException", "at com.creditbook");
		assertThat(output.getOut().lines().filter(line -> line.contains("unexpected error")))
				.singleElement()
				.asString()
				.contains("ERROR")
				.contains("[rid=boom-0001]");
		assertThat(output.getOut()).contains("internal-detail-should-not-leak");
	}

	@Test
	@DisplayName("요청이 끝나면 다음 요청에 id 가 남지 않는다")
	void id_does_not_leak_to_next_request() throws Exception {
		// given
		givenSuccessfulUse();

		// when
		MvcResult first = mockMvc.perform(useRequest(1000)).andReturn();
		String afterFirst = MDC.get(RequestIdFilter.MDC_KEY);
		MvcResult second = mockMvc.perform(useRequest(1000)).andReturn();

		// then
		assertThat(afterFirst).isNull();
		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
		assertThat(first.getResponse().getHeader(RequestIdFilter.HEADER_NAME))
				.isNotEqualTo(second.getResponse().getHeader(RequestIdFilter.HEADER_NAME));
	}

}
