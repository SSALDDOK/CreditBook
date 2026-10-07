package com.creditbook.prepaid.controller;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.creditbook.global.security.CurrentEmployee;
import com.creditbook.global.security.WebSecurityTestConfig;
import com.creditbook.prepaid.application.ChargeResult;
import com.creditbook.prepaid.application.ChargeService;
import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;
import com.creditbook.support.WithMockEmployee;

/**
 * 충전 API 의 요청·응답 형태. 서비스는 목으로 대체하고 웹 계층(검증·DTO 변환·오류 응답)만 본다.
 * 현재 직원은 테스트에서만 가짜로 넣는다 (운영 구현은 인증 연결 때 추가된다).
 */
@WebMvcTest(ChargeController.class)
@Import(WebSecurityTestConfig.class)
@WithMockEmployee
@Tag("REQ-5")
class ChargeControllerTest {

	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID ACCOUNT_ID = UUID.fromString("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
	private static final UUID ENTRY_ID = UUID.fromString("3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	private static final Instant NOW = Instant.parse("2026-10-01T01:00:00Z");
	private static final String URL = "/api/customers/" + CUSTOMER_ID + "/charges";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ChargeService chargeService;

	@MockitoBean
	CurrentEmployee currentEmployee;

	@BeforeEach
	void setUp() {
		given(currentEmployee.id()).willReturn(EMPLOYEE_ID);
	}

	private static ChargeResult charged(long amount, long balanceAfter, String memo) {
		return new ChargeResult(ENTRY_ID, ACCOUNT_ID, CUSTOMER_ID, LedgerEntryType.CHARGE, BigDecimal.valueOf(amount),
				BigDecimal.valueOf(balanceAfter), memo, EMPLOYEE_ID, NOW);
	}

	@Test
	@DisplayName("잔액 0원 고객에게 50,000원 충전을 등록하면 잔액이 50,000원이 되고 CHARGE 유형 거래가 1건 생성된다")
	void charge_returns_201_with_location_and_charge_entry() throws Exception {
		// given
		given(chargeService.charge(CUSTOMER_ID, new BigDecimal("50000"), "음료", EMPLOYEE_ID))
				.willReturn(charged(50_000, 50_000, "음료"));

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"amount": 50000, "memo": "음료"}
						"""))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", endsWith("/api/ledger-entries/" + ENTRY_ID)))
				.andExpect(jsonPath("$.id").value(ENTRY_ID.toString()))
				.andExpect(jsonPath("$.customerId").value(CUSTOMER_ID.toString()))
				.andExpect(jsonPath("$.type").value("CHARGE"))
				.andExpect(jsonPath("$.amount").value(50000))
				.andExpect(jsonPath("$.balanceAfter").value(50000))
				.andExpect(jsonPath("$.memo").value("음료"))
				.andExpect(jsonPath("$.performedAt").value("2026-10-01T01:00:00Z"))
				.andExpect(jsonPath("$.accountId").doesNotExist())
				.andExpect(jsonPath("$.performedBy").doesNotExist());
	}

	@Test
	@DisplayName("메모 없이 충전할 수 있다")
	void charge_without_memo_is_accepted() throws Exception {
		// given
		given(chargeService.charge(CUSTOMER_ID, new BigDecimal("10000"), null, EMPLOYEE_ID))
				.willReturn(charged(10_000, 10_000, null));

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 10000}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.memo").doesNotExist());
	}

	@Test
	@Tag("REQ-20")
	@DisplayName("처리 직원은 요청 본문이 아니라 현재 직원에서 정해진다")
	void performer_comes_from_current_employee_not_request_body() throws Exception {
		// given
		UUID forged = UUID.randomUUID();
		given(chargeService.charge(any(), any(), any(), any())).willReturn(charged(10_000, 10_000, null));

		// when
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 10000, \"performedBy\": \"" + forged + "\"}"))
				.andExpect(status().isCreated());

		// then
		verify(chargeService).charge(CUSTOMER_ID, new BigDecimal("10000"), null, EMPLOYEE_ID);
	}

	@Test
	@Tag("REQ-20")
	@DisplayName("요청 본문에 클라이언트 시각을 넣어도 무시되고 서버 시각이 기록된다")
	void performed_at_comes_from_server_clock_not_request_body() throws Exception {
		// given
		given(chargeService.charge(any(), any(), any(), any())).willReturn(charged(10_000, 10_000, null));

		// when
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 10000, \"performedAt\": \"2020-01-01T00:00:00Z\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.performedAt").value("2026-10-01T01:00:00Z"));

		// then
		verify(chargeService).charge(CUSTOMER_ID, new BigDecimal("10000"), null, EMPLOYEE_ID);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "{}", "{\"amount\": null}", "{\"memo\": \"음료\"}" })
	@DisplayName("금액을 입력하지 않으면 400으로 거절하고 충전하지 않는다")
	void charge_rejects_missing_amount_with_400(String body) throws Exception {
		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(1))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("amount"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("금액을 입력해 주세요."));
		verify(chargeService, never()).charge(any(), any(), any(), any());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@CsvSource(delimiter = '|', value = {
			"0       | INVALID_AMOUNT        | 금액은 1원 이상이어야 합니다.",
			"-1000   | INVALID_AMOUNT        | 금액은 1원 이상이어야 합니다.",
			"1000.5  | INVALID_AMOUNT        | 금액은 원 단위 정수여야 합니다.",
			"300001  | CHARGE_LIMIT_EXCEEDED | 1회 충전 한도(300000원)를 넘었습니다." })
	@Tag("REQ-6")
	@DisplayName("금액이 규칙에 맞지 않으면 400으로 거절하고 사유가 응답에 표시된다")
	void invalid_amount_is_rejected_with_reason(String amount, String expectedCode, String expectedMessage)
			throws Exception {
		// given: 서비스 대신 실제 도메인 규칙이 금액을 판단하게 한다 (한도는 application.yml 과 같은 300,000원)
		PrepaidAccount account = PrepaidAccount.open(CUSTOMER_ID, NOW);
		ChargePolicy policy = ChargePolicy.ofMaxAmount(300_000);
		given(chargeService.charge(eq(CUSTOMER_ID), any(), any(), eq(EMPLOYEE_ID))).willAnswer(invocation -> {
			account.charge(invocation.getArgument(1), policy, EMPLOYEE_ID, NOW, invocation.getArgument(2));
			throw new AssertionError("도메인이 금액을 거절해야 한다");
		});

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": " + amount + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(expectedCode))
				.andExpect(jsonPath("$.message").value(expectedMessage))
				.andExpect(jsonPath("$.fieldErrors").isEmpty());
	}

	@Test
	@Tag("REQ-6")
	@DisplayName("1회 충전 한도와 같은 금액은 충전할 수 있다")
	void charge_at_limit_is_accepted() throws Exception {
		// given
		given(chargeService.charge(CUSTOMER_ID, new BigDecimal("300000"), null, EMPLOYEE_ID))
				.willReturn(charged(300_000, 300_000, null));

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 300000}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.balanceAfter").value(300000));
	}

	@Test
	@DisplayName("메모가 200자를 넘으면 400으로 거절하고 충전하지 않는다")
	void charge_rejects_too_long_memo_with_400() throws Exception {
		// given
		String memo = "가".repeat(201);

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 10000, \"memo\": \"" + memo + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("memo"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("메모는 200자 이하여야 합니다."));
		verify(chargeService, never()).charge(any(), any(), any(), any());
	}

	@Test
	@DisplayName("메모는 200자까지 받는다")
	void charge_accepts_memo_of_max_length() throws Exception {
		// given
		String memo = "가".repeat(200);
		given(chargeService.charge(CUSTOMER_ID, new BigDecimal("10000"), memo, EMPLOYEE_ID))
				.willReturn(charged(10_000, 10_000, memo));

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 10000, \"memo\": \"" + memo + "\"}"))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("고객이 없으면 404로 응답한다")
	void unknown_customer_is_rejected_with_404() throws Exception {
		// given
		given(chargeService.charge(any(), any(), any(), any()))
				.willThrow(new PrepaidAccountNotFoundException(CUSTOMER_ID));

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 10000}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("고객을 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("고객 ID 형식이 올바르지 않으면 400으로 거절한다")
	void malformed_customer_id_is_rejected_with_400() throws Exception {
		// when / then
		mockMvc.perform(post("/api/customers/not-a-uuid/charges")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 10000}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		verify(chargeService, never()).charge(any(), any(), any(), any());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "{\"amount\": \"abc\"}", "{\"amount\": " })
	@DisplayName("금액이 숫자가 아니거나 본문이 JSON 이 아니면 400으로 거절한다")
	void malformed_body_is_rejected_with_400(String body) throws Exception {
		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
		verify(chargeService, never()).charge(any(), any(), any(), any());
	}

	@Test
	@DisplayName("현재 직원을 알 수 없으면 401로 거절하고 충전하지 않는다")
	void unknown_current_employee_is_rejected_with_401() throws Exception {
		// given
		given(currentEmployee.id()).willReturn(null);

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 10000}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
		verify(chargeService, never()).charge(any(), any(), any(), any());
	}

}
