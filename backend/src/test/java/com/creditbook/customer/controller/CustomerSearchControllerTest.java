package com.creditbook.customer.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.creditbook.customer.application.CustomerService;
import com.creditbook.customer.domain.CustomerSummary;
import com.creditbook.global.security.WebSecurityTestConfig;
import com.creditbook.support.WithMockEmployee;

/**
 * 고객 목록·검색 API 의 요청·응답 형태 (GET /api/customers). 서비스는 목으로 대체한다.
 * 검색 조건이 실제로 고객을 걸러내는지(조인·비활성 제외·정렬)는 DB 가 있어야 하므로 S3 통합 테스트에서 본다.
 * 연락처는 모두 가상 번호다.
 */
@WebMvcTest(CustomerController.class)
@Import(WebSecurityTestConfig.class)
@WithMockEmployee
@Tag("REQ-2")
class CustomerSearchControllerTest {

	private static final Instant NOW = Instant.parse("2026-09-30T01:00:00Z");

	private static final CustomerSummary KIM = new CustomerSummary(
			UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c"), "김단골", "01000005678", new BigDecimal("15000"),
			NOW);
	private static final CustomerSummary LEE = new CustomerSummary(
			UUID.fromString("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"), "이단골", "0200001234", BigDecimal.ZERO,
			NOW.minusSeconds(60));

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	CustomerService customerService;

	private static Page<CustomerSummary> pageOf(List<CustomerSummary> content, int page, int size, long total) {
		return new PageImpl<>(content, PageRequest.of(page, size), total);
	}

	@Test
	@DisplayName("Given 고객이 30명 등록된 상태에서 When 이름 일부, 전화번호 뒷자리를 검색하면 Then 해당 고객만 잔액과 함께 목록에 표시된다")
	void search_returns_matching_customers_with_balance() throws Exception {
		// given: 30명 중 검색어에 맞는 1명만 서비스가 돌려준다
		given(customerService.search("5678", 0, 20)).willReturn(pageOf(List.of(KIM), 0, 20, 1));

		// when / then
		mockMvc.perform(get("/api/customers").param("q", "5678"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(KIM.id().toString()))
				.andExpect(jsonPath("$.content[0].name").value("김단골"))
				.andExpect(jsonPath("$.content[0].maskedPhone").value("010-****-5678"))
				.andExpect(jsonPath("$.content[0].balance").value(15000))
				.andExpect(jsonPath("$.content[0].createdAt").value("2026-09-30T01:00:00Z"))
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	@DisplayName("검색어 없이 조회하면 첫 페이지를 20명 단위로 돌려주고 페이지 정보를 함께 담는다")
	void list_without_query_uses_default_page_and_returns_page_info() throws Exception {
		// given
		given(customerService.search(null, 0, 20)).willReturn(pageOf(List.of(KIM, LEE), 0, 20, 30));

		// when / then
		mockMvc.perform(get("/api/customers"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[1].maskedPhone").value("02-****-1234"))
				.andExpect(jsonPath("$.content[1].balance").value(0))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(30))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.hasNext").value(true))
				// Spring Data Page 를 그대로 직렬화하지 않는다
				.andExpect(jsonPath("$.pageable").doesNotExist())
				.andExpect(jsonPath("$.sort").doesNotExist());
		verify(customerService).search(null, 0, 20);
	}

	@Test
	@DisplayName("요청한 페이지 번호와 크기로 조회한다")
	void page_and_size_are_passed_to_service() throws Exception {
		// given
		given(customerService.search("김", 1, 10)).willReturn(pageOf(List.of(LEE), 1, 10, 11));

		// when / then
		mockMvc.perform(get("/api/customers").param("q", "김").param("page", "1").param("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(10))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.hasNext").value(false));
		verify(customerService).search("김", 1, 10);
	}

	@Test
	@Tag("REQ-26")
	@DisplayName("목록 응답에는 연락처 원문이 어디에도 없고 가운데 자리를 가린 연락처만 있다")
	void list_response_never_contains_raw_phone() throws Exception {
		// given
		given(customerService.search(null, 0, 20)).willReturn(pageOf(List.of(KIM, LEE), 0, 20, 2));

		// when / then
		mockMvc.perform(get("/api/customers"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].phone").doesNotExist())
				.andExpect(jsonPath("$.content[1].phone").doesNotExist())
				.andExpect(content().string(not(containsString("01000005678"))))
				.andExpect(content().string(not(containsString("0200001234"))))
				.andExpect(content().string(not(containsString("0000"))));
	}

	@ParameterizedTest(name = "[{index}] {0}={1}")
	@CsvSource({
			"page, -1",
			"size, 0",
			"size, 101",
			"q,    가나다라마바사아자차카타파하가나다라마바사" })
	@DisplayName("페이지 번호가 음수이거나 페이지 크기가 1–100을 벗어나거나 검색어가 20자를 넘으면 400으로 거절한다")
	void out_of_range_parameters_are_rejected_with_400(String name, String value) throws Exception {
		// when / then
		mockMvc.perform(get("/api/customers").param(name, value))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value(name))
				.andExpect(jsonPath("$.fieldErrors[0].message").isNotEmpty())
				// 거절된 값(검색어는 개인정보일 수 있음)을 응답에 되돌려 보내지 않는다
				.andExpect(content().string(not(containsString(value))));
		verify(customerService, never()).search(any(), anyInt(), anyInt());
	}

	@ParameterizedTest(name = "[{index}] {0}={1}")
	@CsvSource({ "page, abc", "size, 1.5" })
	@DisplayName("페이지 번호나 크기가 숫자가 아니면 400으로 거절한다")
	void non_numeric_parameters_are_rejected_with_400(String name, String value) throws Exception {
		// when / then
		mockMvc.perform(get("/api/customers").param(name, value))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value(name));
		verify(customerService, never()).search(any(), anyInt(), anyInt());
	}

}
