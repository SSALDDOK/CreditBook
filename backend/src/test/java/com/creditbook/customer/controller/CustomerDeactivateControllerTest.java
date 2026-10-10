package com.creditbook.customer.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.creditbook.customer.application.CustomerService;
import com.creditbook.customer.domain.CustomerBalanceNotZeroException;
import com.creditbook.customer.domain.CustomerNotFoundException;
import com.creditbook.global.security.CurrentEmployee;
import com.creditbook.global.security.WebSecurityTestConfig;
import com.creditbook.support.WithMockEmployee;

/**
 * 고객 비활성화 API 의 권한·응답 형태 (REQ-4, REQ-16). 서비스는 목으로 대체하고 웹 계층(보안 규칙·오류 응답)만 본다.
 * 보안 구성은 운영과 같은 SecurityConfig 를 올린다 (WebSecurityTestConfig).
 */
@WebMvcTest(CustomerController.class)
@Import(WebSecurityTestConfig.class)
@Tag("REQ-4")
class CustomerDeactivateControllerTest {

	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final String ADMIN_ID = "00000000-0000-0000-0000-00000000a001";
	private static final String PATH = "/api/customers/{id}/deactivate";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	CustomerService customerService;

	@MockitoBean
	CurrentEmployee currentEmployee;

	@BeforeEach
	void setUp() {
		given(currentEmployee.id()).willReturn(UUID.fromString(ADMIN_ID));
	}

	@Test
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("사장이 잔액 0원인 고객을 비활성화하면 204로 응답하고 본문이 없다")
	void admin_deactivates_customer_with_204() throws Exception {
		// when / then
		mockMvc.perform(patch(PATH, CUSTOMER_ID))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
		verify(customerService).deactivate(CUSTOMER_ID, UUID.fromString(ADMIN_ID));
	}

	@Test
	@Tag("REQ-16")
	@WithMockEmployee(role = "STAFF")
	@DisplayName("직원(STAFF)이 고객 비활성화를 요청하면 403으로 거절하고 비활성화하지 않는다")
	void staff_is_forbidden() throws Exception {
		// when / then
		mockMvc.perform(patch(PATH, CUSTOMER_ID))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"))
				.andExpect(jsonPath("$.message").value("권한이 없습니다."));
		verify(customerService, never()).deactivate(any(), any());
	}

	@Test
	@DisplayName("로그인하지 않고 요청하면 401로 거절하고 비활성화하지 않는다")
	void unauthenticated_is_rejected_with_401() throws Exception {
		// when / then
		mockMvc.perform(patch(PATH, CUSTOMER_ID))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		verify(customerService, never()).deactivate(any(), any());
	}

	@Test
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("잔액이 남은 고객이면 409와 현재 잔액(원 단위 정수)으로 응답한다")
	void balance_not_zero_is_mapped_to_409_with_balance() throws Exception {
		// given
		willThrow(new CustomerBalanceNotZeroException(CUSTOMER_ID, new BigDecimal("5000")))
				.given(customerService).deactivate(CUSTOMER_ID, UUID.fromString(ADMIN_ID));

		// when / then
		mockMvc.perform(patch(PATH, CUSTOMER_ID))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CUSTOMER_BALANCE_NOT_ZERO"))
				.andExpect(jsonPath("$.message").value("잔액이 0원인 고객만 비활성화할 수 있습니다."))
				.andExpect(jsonPath("$.fieldErrors").isEmpty())
				.andExpect(jsonPath("$.details.balance").value(5000))
				// 원 단위 정수로 나간다 (5000.0·"5000" 이 아님)
				.andExpect(content().string(containsString("\"balance\":5000}")));
	}

	@Test
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("없는 고객이면 404 CUSTOMER_NOT_FOUND 로 응답한다")
	void unknown_customer_is_mapped_to_404() throws Exception {
		// given
		willThrow(new CustomerNotFoundException(CUSTOMER_ID))
				.given(customerService).deactivate(CUSTOMER_ID, UUID.fromString(ADMIN_ID));

		// when / then
		mockMvc.perform(patch(PATH, CUSTOMER_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
				.andExpect(jsonPath("$.details").doesNotExist());
	}

	@Test
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("고객 ID 가 UUID 형식이 아니면 400 INVALID_INPUT 으로 거절한다")
	void malformed_id_is_rejected_with_400() throws Exception {
		// when / then
		mockMvc.perform(patch(PATH, "not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("id"))
				.andExpect(content().string(not(containsString("not-a-uuid"))));
		verify(customerService, never()).deactivate(any(), any());
	}

	@Test
	@WithMockEmployee(id = ADMIN_ID, role = "ADMIN")
	@DisplayName("동시에 계좌가 바뀌어 충돌하면 409 CONCURRENT_MODIFICATION 으로 응답한다")
	void optimistic_lock_failure_is_mapped_to_409() throws Exception {
		// given
		willThrow(new ObjectOptimisticLockingFailureException("PrepaidAccount", CUSTOMER_ID))
				.given(customerService).deactivate(CUSTOMER_ID, UUID.fromString(ADMIN_ID));

		// when / then
		mockMvc.perform(patch(PATH, CUSTOMER_ID))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
	}

}
