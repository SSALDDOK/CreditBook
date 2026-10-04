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
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.creditbook.global.security.CurrentEmployee;
import com.creditbook.prepaid.application.UsageResult;
import com.creditbook.prepaid.application.UsageService;
import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;

/**
 * 사용 API 의 요청·응답 형태. 서비스는 목으로 대체하고 웹 계층(검증·DTO 변환·오류 응답)만 본다.
 * 금액 오류·잔액 부족은 실제 도메인({@link PrepaidAccount#use})이 던진 예외로 응답을 확인한다.
 * 현재 직원은 테스트에서만 가짜로 넣는다 (운영 구현은 인증 연결 때 추가된다).
 */
@WebMvcTest(UsageController.class)
@Tag("REQ-8")
class UsageControllerTest {

	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID ACCOUNT_ID = UUID.fromString("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
	private static final UUID ENTRY_ID = UUID.fromString("3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	private static final Instant NOW = Instant.parse("2026-10-01T01:00:00Z");
	private static final String URL = "/api/customers/" + CUSTOMER_ID + "/uses";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	UsageService usageService;

	@MockitoBean
	CurrentEmployee currentEmployee;

	@BeforeEach
	void setUp() {
		given(currentEmployee.id()).willReturn(EMPLOYEE_ID);
	}

	private static UsageResult used(long amount, long balanceAfter, String memo) {
		return new UsageResult(ENTRY_ID, ACCOUNT_ID, CUSTOMER_ID, LedgerEntryType.USE, BigDecimal.valueOf(amount),
				BigDecimal.valueOf(balanceAfter), memo, EMPLOYEE_ID, NOW);
	}

	/** 서비스 대신 실제 도메인 규칙이 금액·잔액을 판단하게 한다. 잔액 {@code balance} 원인 계좌에서 사용한다. */
	private void useOnRealAccountWithBalance(long balance) {
		PrepaidAccount account = PrepaidAccount.open(CUSTOMER_ID, NOW);
		if (balance > 0) {
			account.charge(BigDecimal.valueOf(balance), ChargePolicy.ofMaxAmount(300_000), EMPLOYEE_ID, NOW, null);
		}
		given(usageService.use(eq(CUSTOMER_ID), any(), any(), eq(EMPLOYEE_ID))).willAnswer(invocation -> {
			account.use(invocation.getArgument(1), EMPLOYEE_ID, NOW, invocation.getArgument(2));
			throw new AssertionError("도메인이 사용을 거절해야 한다");
		});
	}

	@Test
	@DisplayName("잔액 50,000원 고객이 4,500원을 사용하면 잔액이 45,500원이 되고 USE 유형 거래가 생성된다")
	void use_returns_201_with_location_and_use_entry() throws Exception {
		// given
		given(usageService.use(CUSTOMER_ID, new BigDecimal("4500"), "음료", EMPLOYEE_ID))
				.willReturn(used(4_500, 45_500, "음료"));

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"amount": 4500, "memo": "음료"}
						"""))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", endsWith("/api/ledger-entries/" + ENTRY_ID)))
				.andExpect(jsonPath("$.id").value(ENTRY_ID.toString()))
				.andExpect(jsonPath("$.customerId").value(CUSTOMER_ID.toString()))
				.andExpect(jsonPath("$.type").value("USE"))
				.andExpect(jsonPath("$.amount").value(4500))
				.andExpect(jsonPath("$.balanceAfter").value(45500))
				.andExpect(jsonPath("$.memo").value("음료"))
				.andExpect(jsonPath("$.performedAt").value("2026-10-01T01:00:00Z"))
				.andExpect(jsonPath("$.accountId").doesNotExist())
				.andExpect(jsonPath("$.performedBy").doesNotExist());
	}

	@Test
	@DisplayName("메모 없이 사용할 수 있다")
	void use_without_memo_is_accepted() throws Exception {
		// given
		given(usageService.use(CUSTOMER_ID, new BigDecimal("1000"), null, EMPLOYEE_ID))
				.willReturn(used(1_000, 9_000, null));

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 1000}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.memo").doesNotExist());
	}

	@Test
	@Tag("REQ-9")
	@DisplayName("잔액과 같은 금액을 사용하면 성공하고 잔액이 0원이 된다")
	void use_whole_balance_is_accepted() throws Exception {
		// given
		given(usageService.use(CUSTOMER_ID, new BigDecimal("3000"), null, EMPLOYEE_ID))
				.willReturn(used(3_000, 0, null));

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 3000}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.balanceAfter").value(0));
	}

	@Test
	@Tag("REQ-20")
	@DisplayName("처리 직원은 요청 본문이 아니라 현재 직원에서 정해진다")
	void performer_comes_from_current_employee_not_request_body() throws Exception {
		// given
		UUID forged = UUID.randomUUID();
		given(usageService.use(any(), any(), any(), any())).willReturn(used(1_000, 9_000, null));

		// when
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 1000, \"performedBy\": \"" + forged + "\"}"))
				.andExpect(status().isCreated());

		// then
		verify(usageService).use(CUSTOMER_ID, new BigDecimal("1000"), null, EMPLOYEE_ID);
	}

	@Test
	@Tag("REQ-20")
	@DisplayName("요청 본문에 클라이언트 시각을 넣어도 무시되고 서버 시각이 기록된다")
	void performed_at_comes_from_server_clock_not_request_body() throws Exception {
		// given
		given(usageService.use(any(), any(), any(), any())).willReturn(used(1_000, 9_000, null));

		// when
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 1000, \"performedAt\": \"2020-01-01T00:00:00Z\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.performedAt").value("2026-10-01T01:00:00Z"));

		// then
		verify(usageService).use(CUSTOMER_ID, new BigDecimal("1000"), null, EMPLOYEE_ID);
	}

	@ParameterizedTest(name = "[{index}] 잔액 {0}원, 사용 {1}원 → 부족액 {2}원")
	@CsvSource({
			"3000, 5000, 2000",
			"3000, 3001, 1",
			"0,    1,    1" })
	@Tag("REQ-9")
	@DisplayName("잔액보다 큰 금액을 사용하면 400으로 거절하고 응답에 사유·현재 잔액·부족액이 표시된다")
	void insufficient_balance_is_rejected_with_balance_and_shortage(long balance, long amount, long shortage)
			throws Exception {
		// given
		useOnRealAccountWithBalance(balance);

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": " + amount + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
				.andExpect(jsonPath("$.message").value("잔액이 " + shortage + "원 부족합니다."))
				.andExpect(jsonPath("$.fieldErrors").isEmpty())
				.andExpect(jsonPath("$.details.balance").value(balance))
				.andExpect(jsonPath("$.details.shortage").value(shortage));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@CsvSource(delimiter = '|', value = {
			"0       | 금액은 1원 이상이어야 합니다.",
			"-1000   | 금액은 1원 이상이어야 합니다.",
			"1000.5  | 금액은 원 단위 정수여야 합니다." })
	@DisplayName("금액이 0 이하이거나 소수점이면 잔액 부족이 아니라 금액 오류로 400 거절하고 사유가 응답에 표시된다")
	void invalid_amount_is_rejected_with_reason(String amount, String expectedMessage) throws Exception {
		// given
		useOnRealAccountWithBalance(10_000);

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": " + amount + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_AMOUNT"))
				.andExpect(jsonPath("$.message").value(expectedMessage))
				.andExpect(jsonPath("$.fieldErrors").isEmpty())
				.andExpect(jsonPath("$.details").doesNotExist());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "{}", "{\"amount\": null}", "{\"memo\": \"음료\"}" })
	@DisplayName("금액을 입력하지 않으면 400으로 거절하고 사용하지 않는다")
	void use_rejects_missing_amount_with_400(String body) throws Exception {
		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(1))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("amount"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("금액을 입력해 주세요."));
		verify(usageService, never()).use(any(), any(), any(), any());
	}

	@Test
	@DisplayName("메모가 200자를 넘으면 400으로 거절하고 사용하지 않는다")
	void use_rejects_too_long_memo_with_400() throws Exception {
		// given
		String memo = "가".repeat(201);

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 1000, \"memo\": \"" + memo + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("memo"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("메모는 200자 이하여야 합니다."));
		verify(usageService, never()).use(any(), any(), any(), any());
	}

	@Test
	@DisplayName("고객이 없으면 404로 응답한다")
	void unknown_customer_is_rejected_with_404() throws Exception {
		// given
		given(usageService.use(any(), any(), any(), any()))
				.willThrow(new PrepaidAccountNotFoundException(CUSTOMER_ID));

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 1000}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("고객을 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("같은 계좌를 동시에 바꿔 충돌하면 409로 응답한다")
	void concurrent_modification_is_rejected_with_409() throws Exception {
		// given
		given(usageService.use(any(), any(), any(), any()))
				.willThrow(new ObjectOptimisticLockingFailureException(PrepaidAccount.class, ACCOUNT_ID));

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 1000}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
	}

	@Test
	@DisplayName("고객 ID 형식이 올바르지 않으면 400으로 거절한다")
	void malformed_customer_id_is_rejected_with_400() throws Exception {
		// when / then
		mockMvc.perform(post("/api/customers/not-a-uuid/uses")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 1000}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		verify(usageService, never()).use(any(), any(), any(), any());
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
		verify(usageService, never()).use(any(), any(), any(), any());
	}

	@Test
	@DisplayName("현재 직원을 알 수 없으면 401로 거절하고 사용하지 않는다")
	void unknown_current_employee_is_rejected_with_401() throws Exception {
		// given
		given(currentEmployee.id()).willReturn(null);

		// when / then
		mockMvc.perform(post(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": 1000}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
		verify(usageService, never()).use(any(), any(), any(), any());
	}

}
