package com.creditbook.prepaid.controller;

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

import com.creditbook.customer.domain.CustomerNotFoundException;
import com.creditbook.global.security.WebSecurityTestConfig;
import com.creditbook.prepaid.application.LedgerHistoryItem;
import com.creditbook.prepaid.application.LedgerHistoryService;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.LedgerHistoryEntry;
import com.creditbook.support.WithMockEmployee;

/**
 * 거래 이력 API 의 요청 형식 검증과 응답 형태 (GET /api/customers/{customerId}/transactions). 서비스는 목으로 대체한다.
 * 실제 정렬·취소 여부·건수·비로그인 401 은 DB·전체 컨텍스트가 있어야 하므로 통합 테스트에서 본다.
 */
@WebMvcTest(LedgerEntryController.class)
@Import(WebSecurityTestConfig.class)
@WithMockEmployee
class LedgerEntryControllerTest {

	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID USE_ID = UUID.fromString("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
	private static final String PATH = "/api/customers/{customerId}/transactions";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	LedgerHistoryService ledgerHistoryService;

	private static Page<LedgerHistoryItem> pageOf(List<LedgerHistoryItem> content, int page, int size, long total) {
		return new PageImpl<>(content, PageRequest.of(page, size), total);
	}

	@Test
	@DisplayName("기본 페이지(0, 20)로 조회해 거래를 최신순 그대로, 페이지 정보와 함께 돌려준다")
	void default_page_returns_entries_with_page_info() throws Exception {
		// given
		LedgerHistoryEntry use = new LedgerHistoryEntry(USE_ID, LedgerEntryType.USE, new BigDecimal("4500"),
				new BigDecimal("-4500"), new BigDecimal("45500"), "음료, 베이커리",
				Instant.parse("2026-10-16T05:22:00Z"), "김직원", false, null);
		given(ledgerHistoryService.getHistory(CUSTOMER_ID, 0, 20))
				.willReturn(pageOf(List.of(new LedgerHistoryItem(use, true)), 0, 20, 1));

		// when / then
		mockMvc.perform(get(PATH, CUSTOMER_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(USE_ID.toString()))
				.andExpect(jsonPath("$.content[0].type").value("USE"))
				.andExpect(jsonPath("$.content[0].amount").value(4500))
				.andExpect(jsonPath("$.content[0].signedAmount").value(-4500))
				.andExpect(jsonPath("$.content[0].balanceAfter").value(45500))
				.andExpect(jsonPath("$.content[0].memo").value("음료, 베이커리"))
				.andExpect(jsonPath("$.content[0].performedAt").value("2026-10-16T05:22:00Z"))
				.andExpect(jsonPath("$.content[0].performedByName").value("김직원"))
				.andExpect(jsonPath("$.content[0].cancelled").value(false))
				.andExpect(jsonPath("$.content[0].reversesId").isEmpty())
				.andExpect(jsonPath("$.content[0].cancellable").value(true))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.totalPages").value(1))
				.andExpect(jsonPath("$.hasNext").value(false))
				// Spring Data Page 를 그대로 직렬화하지 않는다
				.andExpect(jsonPath("$.pageable").doesNotExist())
				.andExpect(jsonPath("$.sort").doesNotExist())
				// 금액은 소수점·지수 표기 없이 정수로 나간다
				.andExpect(content().string(containsString("\"amount\":4500,")))
				.andExpect(content().string(containsString("\"balanceAfter\":45500,")));
		verify(ledgerHistoryService).getHistory(CUSTOMER_ID, 0, 20);
	}

	@Test
	@DisplayName("요청한 페이지 번호와 크기로 조회한다 (크기 경계 1·100 허용)")
	void page_and_size_are_passed_to_service() throws Exception {
		// given
		given(ledgerHistoryService.getHistory(any(), anyInt(), anyInt())).willReturn(Page.empty());

		// when / then
		mockMvc.perform(get(PATH, CUSTOMER_ID).param("page", "3").param("size", "1")).andExpect(status().isOk());
		mockMvc.perform(get(PATH, CUSTOMER_ID).param("size", "100")).andExpect(status().isOk());
		verify(ledgerHistoryService).getHistory(CUSTOMER_ID, 3, 1);
		verify(ledgerHistoryService).getHistory(CUSTOMER_ID, 0, 100);
	}

	@Test
	@WithMockEmployee(role = "ADMIN")
	@DisplayName("ADMIN 도 거래 이력을 조회할 수 있고, 거래가 없으면 빈 content 로 200 이다")
	void admin_can_read_empty_history() throws Exception {
		// given
		given(ledgerHistoryService.getHistory(CUSTOMER_ID, 0, 20)).willReturn(pageOf(List.of(), 0, 20, 0));

		// when / then
		mockMvc.perform(get(PATH, CUSTOMER_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isEmpty())
				.andExpect(jsonPath("$.totalElements").value(0))
				.andExpect(jsonPath("$.hasNext").value(false));
	}

	@ParameterizedTest(name = "[{index}] page={0}, size={1}")
	@CsvSource({
			"-1, 20",
			"0, 0",
			"0, 101",
			"abc, 20",
			"0, xyz",
	})
	@DisplayName("페이지 번호가 음수이거나 크기가 1–100 밖이거나 숫자가 아니면 400 INVALID_INPUT 이고 서비스를 부르지 않는다")
	void invalid_page_or_size_is_rejected(String page, String size) throws Exception {
		// when / then
		mockMvc.perform(get(PATH, CUSTOMER_ID).param("page", page).param("size", size))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(content().string(not(containsString("Exception"))));
		verify(ledgerHistoryService, never()).getHistory(any(), anyInt(), anyInt());
	}

	@Test
	@DisplayName("고객 ID 가 UUID 형식이 아니면 400 INVALID_INPUT 이고 서비스를 부르지 않는다")
	void malformed_customer_id_is_rejected() throws Exception {
		// when / then
		mockMvc.perform(get(PATH, "not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		verify(ledgerHistoryService, never()).getHistory(any(), anyInt(), anyInt());
	}

	@Test
	@DisplayName("없는 고객이면 404 CUSTOMER_NOT_FOUND")
	void unknown_customer_is_404() throws Exception {
		// given
		given(ledgerHistoryService.getHistory(CUSTOMER_ID, 0, 20))
				.willThrow(new CustomerNotFoundException(CUSTOMER_ID));

		// when / then
		mockMvc.perform(get(PATH, CUSTOMER_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
	}

}
