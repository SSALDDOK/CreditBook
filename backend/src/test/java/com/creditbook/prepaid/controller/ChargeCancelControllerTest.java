package com.creditbook.prepaid.controller;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
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
import java.time.ZoneId;
import java.util.Optional;
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
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.creditbook.global.security.CurrentEmployee;
import com.creditbook.global.security.WebSecurityTestConfig;
import com.creditbook.prepaid.application.CancelService;
import com.creditbook.prepaid.application.ChargeCancelResult;
import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryNotFoundException;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;
import com.creditbook.support.WithMockEmployee;

/**
 * 충전 취소 API 의 요청·응답 형태 (REQ-7). 서비스는 목으로 대체하고 웹 계층(검증·DTO 변환·오류 응답)만 본다.
 * 당일 아님·잔액 부족·대상 오류·이미 취소는 실제 도메인({@link PrepaidAccount#cancelCharge})이 던진 예외로 응답을 확인한다.
 */
@WebMvcTest(CancelController.class)
@Import(WebSecurityTestConfig.class)
@WithMockEmployee
@Tag("REQ-7")
class ChargeCancelControllerTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID ACCOUNT_ID = UUID.fromString("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
	private static final UUID CHARGE_ENTRY_ID = UUID.fromString("5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b");
	private static final UUID CANCEL_ENTRY_ID = UUID.fromString("3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-00000000a001");
	/** KST 2026-10-15 10:00 */
	private static final Instant NOW = Instant.parse("2026-10-15T01:00:00Z");
	private static final String URL = "/api/customers/" + CUSTOMER_ID + "/charges/" + CHARGE_ENTRY_ID + "/cancel";
	private static final String KEY_VALUE = "7d1e2f3a-charge-cancel-0001";
	private static final IdempotencyKey KEY = IdempotencyKey.of(KEY_VALUE);
	private static final String REASON = "금액 잘못 입력";
	private static final String BODY = "{\"reason\": \"금액 잘못 입력\"}";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	CancelService cancelService;

	@MockitoBean
	CurrentEmployee currentEmployee;

	@BeforeEach
	void setUp() {
		given(currentEmployee.id()).willReturn(EMPLOYEE_ID);
	}

	private static MockHttpServletRequestBuilder cancelRequest(String body) {
		return post(URL).header(IdempotencyHeaders.IDEMPOTENCY_KEY, KEY_VALUE)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private static ChargeCancelResult cancelled(String reason, boolean replayed) {
		return new ChargeCancelResult(CANCEL_ENTRY_ID, ACCOUNT_ID, CUSTOMER_ID, LedgerEntryType.CHARGE_CANCEL,
				new BigDecimal("30000"), new BigDecimal("5000"), reason, CHARGE_ENTRY_ID, EMPLOYEE_ID, NOW, replayed);
	}

	/** 빈 계좌. 공개 API(open)로만 만든다. */
	private static PrepaidAccount realAccount() {
		return PrepaidAccount.open(CUSTOMER_ID, NOW);
	}

	private static LedgerEntry charge(PrepaidAccount account, long amount, Instant chargedAt) {
		return account.charge(BigDecimal.valueOf(amount), ChargePolicy.ofMaxAmount(300_000), EMPLOYEE_ID, chargedAt,
				null, IdempotencyKey.of("setup-charge-" + amount));
	}

	/** 서비스 대신 실제 도메인 규칙이 취소 가능 여부를 판단하게 한다. */
	private void cancelOnRealAccount(PrepaidAccount account, LedgerEntry target, Optional<LedgerEntry> existingCancel) {
		given(cancelService.cancelCharge(CUSTOMER_ID, CHARGE_ENTRY_ID, REASON, EMPLOYEE_ID, KEY))
				.willAnswer(invocation -> {
					account.cancelCharge(target, existingCancel, REASON, EMPLOYEE_ID, NOW, SEOUL, KEY);
					throw new AssertionError("도메인이 취소를 거절해야 한다");
				});
	}

	@Test
	@DisplayName("충전 건을 취소하면 201과 함께 CHARGE_CANCEL 거래·줄어든 잔액·사유·원 거래 ID 가 응답된다")
	void cancel_charge_returns_201_with_location_and_charge_cancel_entry() throws Exception {
		// given
		given(cancelService.cancelCharge(CUSTOMER_ID, CHARGE_ENTRY_ID, REASON, EMPLOYEE_ID, KEY))
				.willReturn(cancelled(REASON, false));

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", endsWith("/api/ledger-entries/" + CANCEL_ENTRY_ID)))
				.andExpect(header().doesNotExist(IdempotencyHeaders.IDEMPOTENT_REPLAYED))
				.andExpect(content().json("""
						{
						  "id": "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f",
						  "customerId": "0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c",
						  "type": "CHARGE_CANCEL",
						  "amount": 30000,
						  "balanceAfter": 5000,
						  "reason": "금액 잘못 입력",
						  "reversesId": "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b",
						  "performedAt": "2026-10-15T01:00:00Z"
						}
						""", true));
	}

	@Test
	@WithMockEmployee(id = "00000000-0000-0000-0000-00000000a001", role = "ADMIN")
	@DisplayName("사장(ADMIN)도 충전 건을 취소할 수 있다")
	void admin_can_cancel_charge() throws Exception {
		// given
		given(currentEmployee.id()).willReturn(ADMIN_ID);
		given(cancelService.cancelCharge(CUSTOMER_ID, CHARGE_ENTRY_ID, REASON, ADMIN_ID, KEY))
				.willReturn(cancelled(REASON, false));

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.type").value("CHARGE_CANCEL"));
	}

	@Test
	@Tag("REQ-10")
	@DisplayName("같은 요청 키로 같은 충전 취소를 다시 보내면 201로 처음 취소 거래를 돌려주고 Idempotent-Replayed: true 를 싣는다")
	void replayed_cancel_returns_201_with_replayed_header() throws Exception {
		// given
		given(cancelService.cancelCharge(CUSTOMER_ID, CHARGE_ENTRY_ID, REASON, EMPLOYEE_ID, KEY))
				.willReturn(cancelled(REASON, true));

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isCreated())
				.andExpect(header().string(IdempotencyHeaders.IDEMPOTENT_REPLAYED, "true"))
				.andExpect(jsonPath("$.id").value(CANCEL_ENTRY_ID.toString()));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "{}", "{\"reason\": null}", "{\"reason\": \"\"}", "{\"reason\": \"   \"}" })
	@DisplayName("충전 취소는 사유가 필수다 — 사유가 없거나 공백이면 400 INVALID_INPUT 으로 거절하고 취소하지 않는다")
	void missing_reason_is_rejected_with_400(String body) throws Exception {
		// when / then
		mockMvc.perform(cancelRequest(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(1))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("reason"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("취소 사유를 입력해 주세요."));
		verify(cancelService, never()).cancelCharge(any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("취소 사유가 200자를 넘으면 400 INVALID_INPUT 으로 거절하고 취소하지 않는다")
	void reason_over_200_chars_is_rejected_with_400() throws Exception {
		// given
		String reason = "가".repeat(LedgerEntry.MEMO_MAX_LENGTH + 1);

		// when / then
		mockMvc.perform(cancelRequest("{\"reason\": \"" + reason + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("reason"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("취소 사유는 200자 이하여야 합니다."));
		verify(cancelService, never()).cancelCharge(any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("취소하면 잔액이 음수가 되는 충전 건은 409 CHARGE_ALREADY_USED 로 거절하고 현재 잔액과 충전액을 알려 준다")
	void charge_already_used_is_rejected_with_409() throws Exception {
		// given: 10,000원 충전 후 4,500원 사용 → 잔액 5,500
		PrepaidAccount account = realAccount();
		LedgerEntry charge = charge(account, 10_000, NOW);
		account.use(new BigDecimal("4500"), EMPLOYEE_ID, NOW, null, IdempotencyKey.of("setup-use"));
		cancelOnRealAccount(account, charge, Optional.empty());

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isConflict())
				.andExpect(content().json("""
						{
						  "code": "CHARGE_ALREADY_USED",
						  "message": "잔액이 부족해 취소할 수 없어요. 먼저 사용 건을 취소해 주세요.",
						  "fieldErrors": [],
						  "details": { "balance": 5500, "chargeAmount": 10000 }
						}
						""", true));
	}

	@Test
	@DisplayName("당일 등록 건만 취소 가능 — 어제 충전 건은 409 CHARGE_CANCEL_NOT_SAME_DAY 로 거절한다")
	void not_same_day_is_rejected_with_409() throws Exception {
		// given: KST 2026-10-14 23:59:59 충전
		PrepaidAccount account = realAccount();
		LedgerEntry charge = charge(account, 10_000, Instant.parse("2026-10-14T14:59:59Z"));
		cancelOnRealAccount(account, charge, Optional.empty());

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isConflict())
				.andExpect(content().json("""
						{
						  "code": "CHARGE_CANCEL_NOT_SAME_DAY",
						  "message": "당일 충전 건만 취소할 수 있습니다.",
						  "fieldErrors": []
						}
						""", true));
	}

	@Test
	@DisplayName("사용 거래를 충전 취소하면 400 INVALID_CANCEL_TARGET 으로 거절한다")
	void use_entry_is_rejected_with_400() throws Exception {
		// given
		PrepaidAccount account = realAccount();
		charge(account, 10_000, NOW);
		LedgerEntry use = account.use(new BigDecimal("3000"), EMPLOYEE_ID, NOW, null, IdempotencyKey.of("setup-use"));
		cancelOnRealAccount(account, use, Optional.empty());

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CANCEL_TARGET"))
				.andExpect(jsonPath("$.message").value("CHARGE_CANCEL 는 CHARGE 거래만 취소할 수 있습니다."))
				.andExpect(jsonPath("$.details").doesNotExist());
	}

	@Test
	@DisplayName("이미 취소된 충전 건을 다시 취소하면 409 ALREADY_CANCELLED 로 거절하고 먼저 만든 취소 거래 ID 를 알려 준다")
	void already_cancelled_is_rejected_with_409() throws Exception {
		// given
		PrepaidAccount account = realAccount();
		charge(account, 20_000, NOW);
		LedgerEntry charge = charge(account, 10_000, NOW);
		LedgerEntry firstCancel = account.cancelCharge(charge, Optional.empty(), REASON, EMPLOYEE_ID, NOW, SEOUL,
				IdempotencyKey.of("first-cancel"));
		cancelOnRealAccount(account, charge, Optional.of(firstCancel));

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"))
				.andExpect(jsonPath("$.message").value("이미 취소된 충전 건입니다."))
				.andExpect(jsonPath("$.details.cancelEntryId").value(firstCancel.getId().toString()));
	}

	@Test
	@DisplayName("고객이 없으면 404 CUSTOMER_NOT_FOUND 로 응답한다")
	void unknown_customer_is_rejected_with_404() throws Exception {
		// given
		given(cancelService.cancelCharge(any(), any(), any(), any(), any()))
				.willThrow(new PrepaidAccountNotFoundException(CUSTOMER_ID));

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
	}

	@Test
	@DisplayName("거래가 없으면 404 LEDGER_ENTRY_NOT_FOUND 로 응답한다")
	void unknown_entry_is_rejected_with_404() throws Exception {
		// given
		given(cancelService.cancelCharge(any(), any(), any(), any(), any()))
				.willThrow(new LedgerEntryNotFoundException(CHARGE_ENTRY_ID));

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("LEDGER_ENTRY_NOT_FOUND"));
	}

	@Test
	@Tag("REQ-10")
	@DisplayName("요청 키 헤더가 없으면 400 INVALID_IDEMPOTENCY_KEY 로 거절하고 취소하지 않는다")
	void missing_key_is_rejected_with_400() throws Exception {
		// when / then
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
		verify(cancelService, never()).cancelCharge(any(), any(), any(), any(), any());
	}

	@Test
	@WithAnonymousUser
	@DisplayName("로그인하지 않고 요청하면 401로 거절하고 취소하지 않는다")
	void unauthenticated_is_rejected_with_401() throws Exception {
		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		verify(cancelService, never()).cancelCharge(any(), any(), any(), any(), any());
	}

	@Test
	@Tag("REQ-20")
	@Tag("REQ-27")
	@DisplayName("요청 본문에 처리 직원을 끼워 넣으면 400 INVALID_INPUT 로 거절하고 충전 취소하지 않는다")
	void performer_in_request_body_is_rejected() throws Exception {
		// when / then 처리 직원은 현재 직원에서만 정해진다 — 본문에 넣으면 받지 않는 항목으로 거절
		mockMvc.perform(cancelRequest("{\"reason\": \"금액 잘못 입력\", \"performedBy\": \"" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("performedBy"));
		verify(cancelService, never()).cancelCharge(any(), any(), any(), any(), any());
	}

}
