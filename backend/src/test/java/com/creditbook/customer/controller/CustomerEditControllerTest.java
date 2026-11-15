package com.creditbook.customer.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.creditbook.customer.application.CustomerEditCommand;
import com.creditbook.customer.application.CustomerService;
import com.creditbook.customer.domain.CustomerConcurrentModificationException;
import com.creditbook.customer.domain.CustomerDetail;
import com.creditbook.customer.domain.DuplicatePhoneException;
import com.creditbook.customer.domain.InvalidCustomerMemoException;
import com.creditbook.customer.domain.SamePhoneCustomer;
import com.creditbook.global.security.CurrentEmployee;
import com.creditbook.global.security.WebSecurityTestConfig;
import com.creditbook.support.WithMockEmployee;

/**
 * 고객 정보 수정 API 의 요청·응답 형태 (PATCH /api/customers/{id}, REQ-3). 서비스는 목으로 대체하고
 * 웹 계층(검증·DTO 변환·오류 응답)만 본다. 연락처는 모두 가상 번호다.
 */
@WebMvcTest(CustomerController.class)
@Import(WebSecurityTestConfig.class)
@WithMockEmployee
@Tag("REQ-3")
class CustomerEditControllerTest {

	private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000005001");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID OTHER_CUSTOMER_ID = UUID.fromString("a3f0c1e2-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final String PATH = "/api/customers/{id}";

	private static final CustomerDetail EDITED = new CustomerDetail(CUSTOMER_ID, "김새이름", "01000005678",
			new BigDecimal("5500"), true, Instant.parse("2026-10-01T01:00:00Z"), "라떼", 4L, List.of());

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	CustomerService customerService;

	@MockitoBean
	CurrentEmployee currentEmployee;

	@BeforeEach
	void stubCurrentEmployee() {
		given(currentEmployee.storeId()).willReturn(STORE_ID);
		given(currentEmployee.id()).willReturn(EMPLOYEE_ID);
	}

	private org.springframework.test.web.servlet.ResultActions patchJson(String body) throws Exception {
		return mockMvc.perform(patch(PATH, CUSTOMER_ID).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test
	@DisplayName("직원이 이름·연락처·메모를 고치면 하이픈을 지운 연락처로 서비스에 넘기고 상세와 같은 모양으로 200 응답한다")
	void staff_edits_customer_and_gets_detail_shape() throws Exception {
		// given
		CustomerEditCommand expected = new CustomerEditCommand(3L, "김새이름", "01000005678", "라떼", false);
		given(customerService.edit(STORE_ID, CUSTOMER_ID, expected, EMPLOYEE_ID)).willReturn(EDITED);

		// when / then
		patchJson("""
				{"version": 3, "name": "김새이름", "phone": "010-0000-5678", "memo": "라떼"}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(CUSTOMER_ID.toString()))
				.andExpect(jsonPath("$.name").value("김새이름"))
				.andExpect(jsonPath("$.maskedPhone").value("010-****-5678"))
				.andExpect(jsonPath("$.balance").value(5500))
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.memo").value("라떼"))
				.andExpect(jsonPath("$.version").value(4))
				.andExpect(jsonPath("$.recentEntries").isArray())
				.andExpect(jsonPath("$.phone").doesNotExist())
				.andExpect(content().string(not(containsString("01000005678"))));
		verify(customerService).edit(STORE_ID, CUSTOMER_ID, expected, EMPLOYEE_ID);
	}

	@Test
	@WithMockEmployee(role = "ADMIN")
	@DisplayName("사장도 고객 정보를 고칠 수 있고, 보내지 않은 칸은 null 로 넘겨 그대로 둔다")
	void admin_edits_only_memo() throws Exception {
		// given
		CustomerEditCommand expected = new CustomerEditCommand(0L, null, null, "", false);
		given(customerService.edit(STORE_ID, CUSTOMER_ID, expected, EMPLOYEE_ID)).willReturn(EDITED);

		// when / then
		patchJson("""
				{"version": 0, "memo": ""}
				""")
				.andExpect(status().isOk());
		verify(customerService).edit(STORE_ID, CUSTOMER_ID, expected, EMPLOYEE_ID);
	}

	@Test
	@DisplayName("확인 표시를 보내면 서비스에 확인 표시를 넘긴다")
	void edit_passes_confirm_duplicate_phone() throws Exception {
		// given
		CustomerEditCommand expected = new CustomerEditCommand(1L, null, "01098765432", null, true);
		given(customerService.edit(STORE_ID, CUSTOMER_ID, expected, EMPLOYEE_ID)).willReturn(EDITED);

		// when / then
		patchJson("""
				{"version": 1, "phone": "01098765432", "confirmDuplicatePhone": true}
				""")
				.andExpect(status().isOk());
		verify(customerService).edit(STORE_ID, CUSTOMER_ID, expected, EMPLOYEE_ID);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "{\"name\": \"김새이름\"}", "{\"version\": null, \"name\": \"김새이름\"}" })
	@DisplayName("버전이 없으면 400으로 거절하고 다시 불러오라고 안내한다")
	void edit_rejects_missing_version(String body) throws Exception {
		// when / then
		patchJson(body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(1))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("version"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("버전 정보가 없습니다. 고객 정보를 다시 불러와 주세요."));
		verify(customerService, never()).edit(any(), any(), any(), any());
	}

	@Test
	@DisplayName("버전이 음수면 400으로 거절한다")
	void edit_rejects_negative_version() throws Exception {
		// when / then
		patchJson("""
				{"version": -1, "name": "김새이름"}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("version"));
		verify(customerService, never()).edit(any(), any(), any(), any());
	}

	@ParameterizedTest(name = "[{index}] 이름 \"{0}\"")
	@ValueSource(strings = { "", "   " })
	@DisplayName("이름을 비우거나 공백만 보내면 400으로 거절한다")
	void edit_rejects_blank_name(String name) throws Exception {
		// when / then
		patchJson("{\"version\": 0, \"name\": \"" + name + "\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(1))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("이름을 입력해 주세요."));
		verify(customerService, never()).edit(any(), any(), any(), any());
	}

	@Test
	@DisplayName("이름이 20자를 넘으면 400으로 거절한다")
	void edit_rejects_long_name() throws Exception {
		// when / then
		patchJson("{\"version\": 0, \"name\": \"" + "가".repeat(21) + "\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
		verify(customerService, never()).edit(any(), any(), any(), any());
	}

	@ParameterizedTest(name = "[{index}] 연락처 \"{0}\"")
	@ValueSource(strings = { "", " ", "01012345", "010--1234-5678", "010.1234.5678" })
	@DisplayName("연락처를 비우거나 형식이 틀리면 400으로 거절한다 — 연락처는 지울 수 없다")
	void edit_rejects_empty_or_invalid_phone(String phone) throws Exception {
		// when / then
		patchJson("{\"version\": 0, \"phone\": \"" + phone + "\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(1))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("phone"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value("연락처는 숫자 9–11자리여야 합니다. 하이픈(-)은 숫자 사이에만 쓸 수 있습니다."))
				.andExpect(content().string(not(containsString("rejectedValue"))));
		verify(customerService, never()).edit(any(), any(), any(), any());
	}

	@ParameterizedTest(name = "[{index}] 항목 {0}")
	@ValueSource(strings = { "balance", "active", "storeId" })
	@Tag("REQ-27")
	@DisplayName("받지 않는 항목을 보내면 400으로 거절한다")
	void edit_rejects_unknown_fields(String field) throws Exception {
		// when / then
		patchJson("{\"version\": 0, \"" + field + "\": 1}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value(field));
		verify(customerService, never()).edit(any(), any(), any(), any());
	}

	@Test
	@DisplayName("메모가 200자를 넘어 도메인이 거절하면 400 INVALID_INPUT, fieldErrors 에 memo 로 응답한다")
	void edit_maps_long_memo_to_invalid_input() throws Exception {
		// given
		given(customerService.edit(eq(STORE_ID), eq(CUSTOMER_ID), any(), eq(EMPLOYEE_ID)))
				.willThrow(new InvalidCustomerMemoException("메모는 200자 이하여야 합니다."));

		// when / then
		patchJson("{\"version\": 0, \"memo\": \"" + "메".repeat(201) + "\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("memo"));
	}

	@Test
	@DisplayName("버전이 낡았으면 409 CONCURRENT_MODIFICATION 으로 응답한다")
	void edit_maps_stale_version_to_409() throws Exception {
		// given
		given(customerService.edit(eq(STORE_ID), eq(CUSTOMER_ID), any(), eq(EMPLOYEE_ID)))
				.willThrow(new CustomerConcurrentModificationException(CUSTOMER_ID, 2L, 3L));

		// when / then
		patchJson("""
				{"version": 2, "name": "김새이름"}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
	}

	@Test
	@DisplayName("바뀔 연락처의 고객이 있으면 등록과 같은 모양의 409 DUPLICATE_PHONE 으로 응답한다")
	void edit_maps_duplicate_phone_to_409() throws Exception {
		// given
		given(customerService.edit(eq(STORE_ID), eq(CUSTOMER_ID), any(), eq(EMPLOYEE_ID)))
				.willThrow(new DuplicatePhoneException(List.of(new SamePhoneCustomer(OTHER_CUSTOMER_ID, "박서연", false))));

		// when / then
		patchJson("""
				{"version": 0, "phone": "01098765432"}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_PHONE"))
				.andExpect(jsonPath("$.details.existingCustomers[0].id").value(OTHER_CUSTOMER_ID.toString()))
				.andExpect(jsonPath("$.details.existingCustomers[0].name").value("박서연"))
				.andExpect(jsonPath("$.details.existingCustomers[0].active").value(false));
	}

}
