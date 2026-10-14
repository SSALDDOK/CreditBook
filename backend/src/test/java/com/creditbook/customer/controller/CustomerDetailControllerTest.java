package com.creditbook.customer.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.creditbook.customer.application.CustomerService;
import com.creditbook.customer.domain.CustomerDetail;
import com.creditbook.customer.domain.CustomerNotFoundException;
import com.creditbook.global.security.WebSecurityTestConfig;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.support.WithMockEmployee;

/**
 * 고객 상세 API 의 요청·응답 형태 (GET /api/customers/{id}). 서비스는 목으로 대체한다.
 * 실제 SQL(한 문장 조회·최근 5건·반제 여부)은 DB 가 있어야 하므로 통합 테스트에서 본다. 연락처는 모두 가상 번호다.
 */
@WebMvcTest(CustomerController.class)
@Import(WebSecurityTestConfig.class)
@Tag("REQ-11")
class CustomerDetailControllerTest {

	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID USE_ID = UUID.fromString("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
	private static final UUID CHARGE_ID = UUID.fromString("5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b");
	private static final String PATH = "/api/customers/{id}";

	private static final CustomerDetail KIM = new CustomerDetail(CUSTOMER_ID, "김단골", "01000005678",
			new BigDecimal("5500"), true, Instant.parse("2026-10-01T01:00:00Z"),
			List.of(
					new CustomerDetail.RecentEntry(USE_ID, LedgerEntryType.USE, new BigDecimal("4500"),
							new BigDecimal("-4500"), new BigDecimal("5500"), "음료",
							Instant.parse("2026-10-15T02:30:00Z"), "박직원", false),
					new CustomerDetail.RecentEntry(CHARGE_ID, LedgerEntryType.CHARGE, new BigDecimal("10000"),
							new BigDecimal("10000"), new BigDecimal("10000"), null,
							Instant.parse("2026-10-15T01:00:00Z"), "김사장", false)));

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	CustomerService customerService;

	@Test
	@WithMockEmployee(role = "STAFF")
	@DisplayName("직원이 고객 상세를 조회하면 잔액·활성 여부와 최근 거래를 최신순으로 200 응답한다")
	void staff_gets_detail_with_recent_entries() throws Exception {
		// given
		given(customerService.getDetail(CUSTOMER_ID)).willReturn(KIM);

		// when / then
		mockMvc.perform(get(PATH, CUSTOMER_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(CUSTOMER_ID.toString()))
				.andExpect(jsonPath("$.name").value("김단골"))
				.andExpect(jsonPath("$.maskedPhone").value("010-****-5678"))
				.andExpect(jsonPath("$.balance").value(5500))
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.createdAt").value("2026-10-01T01:00:00Z"))
				.andExpect(jsonPath("$.recentEntries.length()").value(2))
				.andExpect(jsonPath("$.recentEntries[0].id").value(USE_ID.toString()))
				.andExpect(jsonPath("$.recentEntries[0].type").value("USE"))
				.andExpect(jsonPath("$.recentEntries[0].amount").value(4500))
				.andExpect(jsonPath("$.recentEntries[0].signedAmount").value(-4500))
				.andExpect(jsonPath("$.recentEntries[0].balanceAfter").value(5500))
				.andExpect(jsonPath("$.recentEntries[0].memo").value("음료"))
				.andExpect(jsonPath("$.recentEntries[0].performedAt").value("2026-10-15T02:30:00Z"))
				.andExpect(jsonPath("$.recentEntries[0].performedByName").value("박직원"))
				.andExpect(jsonPath("$.recentEntries[0].cancelled").value(false))
				.andExpect(jsonPath("$.recentEntries[1].type").value("CHARGE"))
				// 금액은 원 단위 정수로 나간다 (5500.0·"5500" 이 아님)
				.andExpect(content().string(containsString("\"balance\":5500,")));
	}

	@Test
	@WithMockEmployee(role = "ADMIN")
	@DisplayName("사장도 고객 상세를 조회할 수 있다")
	void admin_gets_detail() throws Exception {
		// given
		given(customerService.getDetail(CUSTOMER_ID)).willReturn(KIM);

		// when / then
		mockMvc.perform(get(PATH, CUSTOMER_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(CUSTOMER_ID.toString()));
	}

	@Test
	@Tag("REQ-4")
	@WithMockEmployee
	@DisplayName("비활성 고객도 조회되고 active 가 false 로, 거래가 없으면 최근 거래가 빈 배열로 나간다")
	void inactive_customer_without_entries_is_returned() throws Exception {
		// given
		CustomerDetail inactive = new CustomerDetail(CUSTOMER_ID, "박휴면", "0200001234", BigDecimal.ZERO, false,
				Instant.parse("2026-10-01T01:00:00Z"), List.of());
		given(customerService.getDetail(CUSTOMER_ID)).willReturn(inactive);

		// when / then
		mockMvc.perform(get(PATH, CUSTOMER_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false))
				.andExpect(jsonPath("$.balance").value(0))
				.andExpect(jsonPath("$.recentEntries").isArray())
				.andExpect(jsonPath("$.recentEntries").isEmpty());
	}

	@Test
	@Tag("REQ-26")
	@WithMockEmployee
	@DisplayName("상세 응답에는 연락처 원문이 어디에도 없고 수정·비활성화용 필드도 없다")
	void response_has_no_raw_phone_nor_mutation_fields() throws Exception {
		// given
		given(customerService.getDetail(CUSTOMER_ID)).willReturn(KIM);

		// when / then
		mockMvc.perform(get(PATH, CUSTOMER_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.phone").doesNotExist())
				.andExpect(jsonPath("$.version").doesNotExist())
				.andExpect(jsonPath("$.accountId").doesNotExist())
				.andExpect(jsonPath("$.recentEntries[0].reversesId").doesNotExist())
				.andExpect(jsonPath("$.recentEntries[0].idempotencyKey").doesNotExist())
				.andExpect(content().string(not(containsString("01000005678"))));
	}

	@Test
	@WithMockEmployee
	@DisplayName("없는 고객이면 404 CUSTOMER_NOT_FOUND 로 응답하고 details 를 싣지 않는다")
	void unknown_customer_is_mapped_to_404() throws Exception {
		// given
		given(customerService.getDetail(CUSTOMER_ID)).willThrow(new CustomerNotFoundException(CUSTOMER_ID));

		// when / then
		mockMvc.perform(get(PATH, CUSTOMER_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
				.andExpect(jsonPath("$.details").doesNotExist());
	}

	@Test
	@WithMockEmployee
	@DisplayName("고객 ID 가 UUID 형식이 아니면 400 INVALID_INPUT 으로 거절하고 조회하지 않는다")
	void malformed_id_is_rejected_with_400() throws Exception {
		// when / then
		mockMvc.perform(get(PATH, "not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("id"))
				.andExpect(jsonPath("$.details").doesNotExist())
				.andExpect(content().string(not(containsString("not-a-uuid"))));
		verify(customerService, never()).getDetail(any());
	}

	@Test
	@DisplayName("로그인하지 않고 요청하면 401로 거절하고 조회하지 않는다")
	void unauthenticated_is_rejected_with_401() throws Exception {
		// when / then
		mockMvc.perform(get(PATH, CUSTOMER_ID))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		verify(customerService, never()).getDetail(any());
	}

}
