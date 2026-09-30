package com.creditbook.customer.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.creditbook.customer.application.CustomerService;
import com.creditbook.customer.application.RegisteredCustomer;
import com.creditbook.customer.domain.InvalidCustomerNameException;

/**
 * 고객 등록 API 의 요청·응답 형태. 서비스는 목으로 대체하고 웹 계층(검증·DTO 변환·오류 응답)만 본다.
 */
@WebMvcTest(CustomerController.class)
@Tag("REQ-1")
class CustomerControllerTest {

	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID ACCOUNT_ID = UUID.fromString("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
	private static final Instant NOW = Instant.parse("2026-09-30T01:00:00Z");

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	CustomerService customerService;

	private static RegisteredCustomer registered(String name, String phone) {
		return new RegisteredCustomer(CUSTOMER_ID, name, phone, ACCOUNT_ID, BigDecimal.ZERO, NOW);
	}

	@Test
	@DisplayName("Given 사장 또는 직원이 로그인한 상태에서 When 이름과 연락처를 입력해 등록하면 Then 잔액 0원인 선결제 계좌(PrepaidAccount)가 함께 생성된다")
	void register_returns_201_with_location_and_zero_balance() throws Exception {
		// given
		given(customerService.register("김단골", "01012345678")).willReturn(registered("김단골", "01012345678"));

		// when / then
		mockMvc.perform(post("/api/customers")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name": "김단골", "phone": "01012345678"}
						"""))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", endsWith("/api/customers/" + CUSTOMER_ID)))
				.andExpect(jsonPath("$.id").value(CUSTOMER_ID.toString()))
				.andExpect(jsonPath("$.name").value("김단골"))
				.andExpect(jsonPath("$.maskedPhone").value("010-****-5678"))
				.andExpect(jsonPath("$.balance").value(0))
				.andExpect(jsonPath("$.createdAt").value("2026-09-30T01:00:00Z"))
				.andExpect(jsonPath("$.accountId").doesNotExist())
				.andExpect(jsonPath("$.active").doesNotExist());
	}

	@Test
	@DisplayName("하이픈을 넣어 입력한 연락처는 숫자만 남겨 등록한다")
	void register_strips_hyphens_from_phone() throws Exception {
		// given
		given(customerService.register("김단골", "01012345678")).willReturn(registered("김단골", "01012345678"));

		// when / then
		mockMvc.perform(post("/api/customers")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name": "김단골", "phone": "010-1234-5678"}
						"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.maskedPhone").value("010-****-5678"));
		verify(customerService).register("김단골", "01012345678");
	}

	@Test
	@Tag("REQ-26")
	@DisplayName("등록 응답에는 연락처 원문이 어디에도 없고 가운데 자리를 가린 연락처만 있다")
	void register_response_never_contains_raw_phone() throws Exception {
		// given
		given(customerService.register("김단골", "01012345678")).willReturn(registered("김단골", "01012345678"));

		// when / then
		mockMvc.perform(post("/api/customers")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name": "김단골", "phone": "010-1234-5678"}
						"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.maskedPhone").value("010-****-5678"))
				.andExpect(jsonPath("$.phone").doesNotExist())
				.andExpect(content().string(not(containsString("01012345678"))))
				.andExpect(content().string(not(containsString("010-1234-5678"))))
				.andExpect(content().string(not(containsString("1234"))));
	}

	@ParameterizedTest(name = "[{index}] 연락처 {0}")
	@ValueSource(strings = { "\"phone\": null", "\"phone\": \"\"", "\"memo\": \"연락처 필드 없음\"" })
	@DisplayName("연락처를 입력하지 않으면 400으로 거절하고 등록하지 않는다")
	void register_rejects_missing_phone_with_400(String phoneField) throws Exception {
		// given: null, 빈 문자열, 필드 누락 (연락처 필수 — 2026-09-30 사용자 결정)

		// when / then
		mockMvc.perform(post("/api/customers")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"김단골\", " + phoneField + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(1))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("phone"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("연락처를 입력해 주세요."));
		verify(customerService, never()).register(any(), any());
	}

	@ParameterizedTest(name = "[{index}] 이름 {0}")
	@ValueSource(strings = { "\"name\": null", "\"name\": \"\"", "\"name\": \"   \"",
			"\"name\": \"가나다라마바사아자차카타파하가나다라마바사\"" })
	@DisplayName("이름이 비어 있거나 20자를 넘으면 400으로 거절하고 등록하지 않는다")
	void register_rejects_invalid_name_with_400(String nameField) throws Exception {
		// when / then
		mockMvc.perform(post("/api/customers")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{" + nameField + ", \"phone\": \"01012345678\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
				.andExpect(jsonPath("$.fieldErrors[0].message").isNotEmpty());
		verify(customerService, never()).register(any(), any());
	}

	@ParameterizedTest(name = "[{index}] 연락처 \"{0}\"")
	@ValueSource(strings = { "01012345", "010123456789", "010-1234", "0-1-2-3-4-5-6-7", "010--1234-5678", "-01012345678",
			"01012345678-", "010 1234 5678", "010.1234.5678", "0101234567a", " " })
	@DisplayName("연락처가 숫자 9–11자리 형식이 아니면 400으로 거절하고 등록하지 않는다")
	void register_rejects_invalid_phone_with_400(String phone) throws Exception {
		// when / then
		mockMvc.perform(post("/api/customers")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"김단골\", \"phone\": \"" + phone + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("phone"))
				// 입력값(개인정보)은 응답에 되돌려 보내지 않는다
				.andExpect(content().string(not(containsString("rejectedValue"))));
		verify(customerService, never()).register(any(), any());
	}

	@Test
	@DisplayName("도메인이 이름을 거절하면 400과 도메인 오류 코드로 응답한다")
	void domain_rejection_is_mapped_to_400() throws Exception {
		// given: 요청 형식 검증은 통과했지만 도메인 규칙이 거절하는 경우 (형식 검증과 도메인 규칙이 어긋날 때의 최후 방어선)
		given(customerService.register(anyString(), any()))
				.willThrow(new InvalidCustomerNameException("이름은 20자 이하여야 합니다."));

		// when / then
		mockMvc.perform(post("/api/customers")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"김단골\", \"phone\": \"01012345678\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CUSTOMER_NAME"))
				.andExpect(jsonPath("$.message").value("이름은 20자 이하여야 합니다."))
				.andExpect(jsonPath("$.fieldErrors").isEmpty());
	}

	@Test
	@DisplayName("요청 본문이 JSON 이 아니면 400으로 거절한다")
	void malformed_json_is_rejected_with_400() throws Exception {
		// when / then
		mockMvc.perform(post("/api/customers")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
		verify(customerService, never()).register(any(), any());
	}

	@Test
	@DisplayName("동시 수정 충돌은 409로 응답한다")
	void optimistic_lock_failure_is_mapped_to_409() throws Exception {
		// given
		given(customerService.register(anyString(), any()))
				.willThrow(new ObjectOptimisticLockingFailureException("PrepaidAccount", ACCOUNT_ID));

		// when / then
		mockMvc.perform(post("/api/customers")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"김단골\", \"phone\": \"01012345678\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
	}

	@Test
	@DisplayName("예상하지 못한 오류는 500으로 응답하고 스택트레이스·내부 메시지를 응답에 넣지 않는다")
	void unexpected_error_hides_details() throws Exception {
		// given
		given(customerService.register(anyString(), any()))
				.willThrow(new IllegalStateException("db password=secret at com.creditbook.Internal"));

		// when / then
		mockMvc.perform(post("/api/customers")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"김단골\", \"phone\": \"01012345678\"}"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(content().string(not(containsString("secret"))))
				.andExpect(content().string(not(containsString("com.creditbook"))))
				.andExpect(content().string(not(containsString("IllegalStateException"))));
	}

	@Test
	@DisplayName("JSON 이 아닌 형식으로 요청하면 415로 거절한다")
	void unsupported_media_type_is_rejected_with_415() throws Exception {
		// when / then
		mockMvc.perform(post("/api/customers")
				.contentType(MediaType.TEXT_PLAIN)
				.content("김단골"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

}
