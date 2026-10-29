package com.creditbook.prepaid.controller;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.creditbook.global.security.CurrentEmployee;
import com.creditbook.global.security.WebSecurityTestConfig;
import com.creditbook.prepaid.application.CancelService;
import com.creditbook.prepaid.application.UseCancelResult;
import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.prepaid.domain.IdempotencyKeyReusedException;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryNotFoundException;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;
import com.creditbook.prepaid.domain.ReversalConflictException;
import com.creditbook.support.WithMockEmployee;

/**
 * 사용 취소 API 의 요청·응답 형태. 서비스는 목으로 대체하고 웹 계층(검증·DTO 변환·오류 응답)만 본다.
 * 취소 대상 거절·이미 취소·사유 없음은 실제 도메인({@link PrepaidAccount#cancelUse})이 던진 예외로 응답을 확인한다.
 */
@WebMvcTest(CancelController.class)
@Import(WebSecurityTestConfig.class)
@WithMockEmployee
@Tag("REQ-34")
class CancelControllerTest {

	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID ACCOUNT_ID = UUID.fromString("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
	private static final UUID USE_ENTRY_ID = UUID.fromString("5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b");
	private static final UUID CANCEL_ENTRY_ID = UUID.fromString("3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-00000000a001");
	private static final Instant NOW = Instant.parse("2026-10-12T01:00:00Z");
	private static final String URL = "/api/customers/" + CUSTOMER_ID + "/uses/" + USE_ENTRY_ID + "/cancel";
	private static final String KEY_VALUE = "7d1e2f3a-cancel-0001";
	private static final IdempotencyKey KEY = IdempotencyKey.of(KEY_VALUE);
	private static final String REASON = "주문 착오";
	private static final String BODY = "{\"reason\": \"주문 착오\"}";

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

	private static UseCancelResult cancelled(String reason, boolean replayed) {
		return new UseCancelResult(CANCEL_ENTRY_ID, ACCOUNT_ID, CUSTOMER_ID, LedgerEntryType.USE_CANCEL,
				new BigDecimal("4500"), new BigDecimal("50000"), reason, USE_ENTRY_ID, EMPLOYEE_ID, NOW, replayed);
	}

	/** 잔액 10,000원인 실제 계좌. 공개 API(open → charge)로만 만든다. */
	private static PrepaidAccount realAccount() {
		PrepaidAccount account = PrepaidAccount.open(CUSTOMER_ID, NOW);
		account.charge(new BigDecimal("10000"), ChargePolicy.ofMaxAmount(300_000), EMPLOYEE_ID, NOW, null,
				IdempotencyKey.of("setup-charge"));
		return account;
	}

	/** 서비스 대신 실제 도메인 규칙이 취소 대상·사유를 판단하게 한다. */
	private void cancelOnRealAccount(PrepaidAccount account, LedgerEntry target, Optional<LedgerEntry> existingCancel,
			String reason) {
		given(cancelService.cancelUse(CUSTOMER_ID, USE_ENTRY_ID, REASON, EMPLOYEE_ID, KEY)).willAnswer(invocation -> {
			account.cancelUse(target, existingCancel, reason, EMPLOYEE_ID, NOW, KEY);
			throw new AssertionError("도메인이 취소를 거절해야 한다");
		});
	}

	@Test
	@DisplayName("사용 건을 취소하면 201과 함께 USE_CANCEL 거래·복구된 잔액·사유·원 거래 ID 가 응답된다")
	void cancel_use_returns_201_with_location_and_use_cancel_entry() throws Exception {
		// given
		given(cancelService.cancelUse(CUSTOMER_ID, USE_ENTRY_ID, REASON, EMPLOYEE_ID, KEY))
				.willReturn(cancelled(REASON, false));

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", endsWith("/api/ledger-entries/" + CANCEL_ENTRY_ID)))
				.andExpect(header().doesNotExist(IdempotencyHeaders.IDEMPOTENT_REPLAYED))
				.andExpect(jsonPath("$.id").value(CANCEL_ENTRY_ID.toString()))
				.andExpect(jsonPath("$.customerId").value(CUSTOMER_ID.toString()))
				.andExpect(jsonPath("$.type").value("USE_CANCEL"))
				.andExpect(jsonPath("$.amount").value(4500))
				.andExpect(jsonPath("$.balanceAfter").value(50000))
				.andExpect(jsonPath("$.reason").value(REASON))
				.andExpect(jsonPath("$.reversesId").value(USE_ENTRY_ID.toString()))
				.andExpect(jsonPath("$.performedAt").value("2026-10-12T01:00:00Z"))
				.andExpect(jsonPath("$.memo").doesNotExist())
				.andExpect(jsonPath("$.accountId").doesNotExist())
				.andExpect(jsonPath("$.performedBy").doesNotExist());
	}

	@Test
	@WithMockEmployee(id = "00000000-0000-0000-0000-00000000a001", role = "ADMIN")
	@Tag("REQ-16")
	@DisplayName("사장(ADMIN)도 사용 건을 취소할 수 있다")
	void admin_can_cancel_use() throws Exception {
		// given
		given(currentEmployee.id()).willReturn(ADMIN_ID);
		given(cancelService.cancelUse(CUSTOMER_ID, USE_ENTRY_ID, REASON, ADMIN_ID, KEY))
				.willReturn(cancelled(REASON, false));

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.type").value("USE_CANCEL"));
	}

	@Test
	@Tag("REQ-10")
	@DisplayName("같은 요청 키로 같은 취소를 다시 보내면 201로 처음 취소 거래를 돌려주고 Idempotent-Replayed: true 를 싣는다")
	void replayed_cancel_returns_201_with_replayed_header() throws Exception {
		// given
		given(cancelService.cancelUse(CUSTOMER_ID, USE_ENTRY_ID, REASON, EMPLOYEE_ID, KEY))
				.willReturn(cancelled(REASON, true));

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isCreated())
				.andExpect(header().string(IdempotencyHeaders.IDEMPOTENT_REPLAYED, "true"))
				.andExpect(header().string("Location", endsWith("/api/ledger-entries/" + CANCEL_ENTRY_ID)))
				.andExpect(jsonPath("$.id").value(CANCEL_ENTRY_ID.toString()));
	}

	@Test
	@DisplayName("취소 사유는 200자까지 받는다")
	void reason_of_200_chars_is_accepted() throws Exception {
		// given
		String reason = "가".repeat(LedgerEntry.MEMO_MAX_LENGTH);
		given(cancelService.cancelUse(CUSTOMER_ID, USE_ENTRY_ID, reason, EMPLOYEE_ID, KEY))
				.willReturn(cancelled(reason, false));

		// when / then
		mockMvc.perform(cancelRequest("{\"reason\": \"" + reason + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.reason").value(reason));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "{}", "{\"reason\": null}", "{\"reason\": \"\"}", "{\"reason\": \"   \"}" })
	@DisplayName("취소 사유가 없거나 공백이면 400으로 거절하고 취소하지 않는다")
	void missing_reason_is_rejected_with_400(String body) throws Exception {
		// when / then
		mockMvc.perform(cancelRequest(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(1))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("reason"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("취소 사유를 입력해 주세요."));
		verify(cancelService, never()).cancelUse(any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("요청 검증을 지나 도메인이 사유 없음으로 거절해도 400 INVALID_INPUT 으로 응답한다")
	void domain_reason_required_is_mapped_to_400() throws Exception {
		// given
		PrepaidAccount account = realAccount();
		LedgerEntry use = account.use(new BigDecimal("3000"), EMPLOYEE_ID, NOW, null, IdempotencyKey.of("setup-use"));
		cancelOnRealAccount(account, use, Optional.empty(), "   ");

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("취소 사유를 입력해 주세요."));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@CsvSource(delimiter = '|', value = {
			"다른 고객의 사용 건 | 다른 계좌의 거래는 취소할 수 없습니다.",
			"충전 거래          | USE_CANCEL 는 USE 거래만 취소할 수 있습니다.",
			"취소 거래(반제 행) | 취소 거래는 다시 취소할 수 없습니다." })
	@DisplayName("다른 고객의 거래·USE 가 아닌 거래·취소 거래를 취소하면 400 INVALID_CANCEL_TARGET 으로 거절하고 사유가 응답에 표시된다")
	void invalid_cancel_target_is_rejected_with_400(String targetKind, String expectedMessage) throws Exception {
		// given
		PrepaidAccount account = realAccount();
		LedgerEntry target = switch (targetKind) {
			case "다른 고객의 사용 건" -> {
				PrepaidAccount other = PrepaidAccount.open(UUID.randomUUID(), NOW);
				other.charge(new BigDecimal("10000"), ChargePolicy.ofMaxAmount(300_000), EMPLOYEE_ID, NOW, null,
						IdempotencyKey.of("other-charge"));
				yield other.use(new BigDecimal("3000"), EMPLOYEE_ID, NOW, null, IdempotencyKey.of("other-use"));
			}
			case "충전 거래" -> account.charge(new BigDecimal("1000"), ChargePolicy.ofMaxAmount(300_000), EMPLOYEE_ID,
					NOW, null, IdempotencyKey.of("target-charge"));
			default -> {
				LedgerEntry use = account.use(new BigDecimal("1000"), EMPLOYEE_ID, NOW, null,
						IdempotencyKey.of("target-use"));
				yield account.cancelUse(use, Optional.empty(), REASON, EMPLOYEE_ID, NOW,
						IdempotencyKey.of("target-cancel"));
			}
		};
		cancelOnRealAccount(account, target, Optional.empty(), REASON);

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CANCEL_TARGET"))
				.andExpect(jsonPath("$.message").value(expectedMessage))
				.andExpect(jsonPath("$.fieldErrors").isEmpty())
				.andExpect(jsonPath("$.details").doesNotExist());
	}

	@Test
	@DisplayName("이미 취소된 사용 건을 다시 취소하면 409 ALREADY_CANCELLED 로 거절하고 먼저 만든 취소 거래 ID 가 응답에 표시된다")
	void already_cancelled_is_rejected_with_409() throws Exception {
		// given
		PrepaidAccount account = realAccount();
		LedgerEntry use = account.use(new BigDecimal("3000"), EMPLOYEE_ID, NOW, null, IdempotencyKey.of("setup-use"));
		LedgerEntry firstCancel = account.cancelUse(use, Optional.empty(), REASON, EMPLOYEE_ID, NOW,
				IdempotencyKey.of("first-cancel"));
		cancelOnRealAccount(account, use, Optional.of(firstCancel), REASON);

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"))
				.andExpect(jsonPath("$.message").value("이미 취소된 사용 건입니다."))
				.andExpect(jsonPath("$.fieldErrors").isEmpty())
				.andExpect(jsonPath("$.details.cancelEntryId").value(firstCancel.getId().toString()));
	}

	@Test
	@DisplayName("고객이 없으면 404 CUSTOMER_NOT_FOUND 로 응답한다")
	void unknown_customer_is_rejected_with_404() throws Exception {
		// given
		given(cancelService.cancelUse(any(), any(), any(), any(), any()))
				.willThrow(new PrepaidAccountNotFoundException(CUSTOMER_ID));

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("고객을 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("거래가 없으면 404 LEDGER_ENTRY_NOT_FOUND 로 응답한다")
	void unknown_entry_is_rejected_with_404() throws Exception {
		// given
		given(cancelService.cancelUse(any(), any(), any(), any(), any()))
				.willThrow(new LedgerEntryNotFoundException(USE_ENTRY_ID));

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("LEDGER_ENTRY_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("거래를 찾을 수 없습니다."))
				.andExpect(jsonPath("$.details").doesNotExist());
	}

	@Test
	@DisplayName("같은 계좌를 동시에 바꿔 충돌하면 409 CONCURRENT_MODIFICATION 으로 응답한다")
	void concurrent_modification_is_rejected_with_409() throws Exception {
		// given
		given(cancelService.cancelUse(any(), any(), any(), any(), any()))
				.willThrow(new ObjectOptimisticLockingFailureException(PrepaidAccount.class, ACCOUNT_ID));

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
	}

	@Test
	@DisplayName("같은 사용 건 동시 취소가 다시 판정해도 겹치면 500이 아니라 409 CONCURRENT_MODIFICATION 으로 응답한다")
	void repeated_reversal_conflict_is_rejected_with_409() throws Exception {
		// given
		given(cancelService.cancelUse(any(), any(), any(), any(), any()))
				.willThrow(new ReversalConflictException(new RuntimeException("duplicate key")));

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "/api/customers/not-a-uuid/uses/" + "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b" + "/cancel",
			"/api/customers/0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c/uses/not-a-uuid/cancel" })
	@DisplayName("고객 ID 나 거래 ID 형식이 올바르지 않으면 400으로 거절한다")
	void malformed_path_id_is_rejected_with_400(String path) throws Exception {
		// when / then
		mockMvc.perform(post(path).header(IdempotencyHeaders.IDEMPOTENCY_KEY, KEY_VALUE)
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		verify(cancelService, never()).cancelUse(any(), any(), any(), any(), any());
	}

	@ParameterizedTest(name = "[{index}] 키 [{0}]")
	@ValueSource(strings = { "", "한글키", "key with space", "a/b" })
	@Tag("REQ-10")
	@DisplayName("요청 키 형식이 틀리면 400 INVALID_IDEMPOTENCY_KEY 로 거절하고 취소하지 않는다")
	void malformed_key_is_rejected_with_400(String key) throws Exception {
		// when / then
		mockMvc.perform(post(URL).header(IdempotencyHeaders.IDEMPOTENCY_KEY, key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
		verify(cancelService, never()).cancelUse(any(), any(), any(), any(), any());
	}

	@Test
	@Tag("REQ-10")
	@DisplayName("요청 키 헤더가 없으면 400 INVALID_IDEMPOTENCY_KEY 로 거절하고 취소하지 않는다")
	void missing_key_is_rejected_with_400() throws Exception {
		// when / then
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"))
				.andExpect(jsonPath("$.message").value("요청 키(Idempotency-Key)를 확인해 주세요."));
		verify(cancelService, never()).cancelUse(any(), any(), any(), any(), any());
	}

	@Test
	@Tag("REQ-10")
	@DisplayName("같은 요청 키로 다른 취소를 요청하면 422 IDEMPOTENCY_KEY_REUSED 로 거절한다")
	void reused_key_is_rejected_with_422() throws Exception {
		// given
		given(cancelService.cancelUse(any(), any(), any(), any(), any()))
				.willThrow(new IdempotencyKeyReusedException(CANCEL_ENTRY_ID));

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isUnprocessableContent())
				.andExpect(header().doesNotExist(IdempotencyHeaders.IDEMPOTENT_REPLAYED))
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	@WithAnonymousUser
	@DisplayName("로그인하지 않고 요청하면 401로 거절하고 취소하지 않는다")
	void unauthenticated_is_rejected_with_401() throws Exception {
		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		verify(cancelService, never()).cancelUse(any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("현재 직원을 알 수 없으면 401로 거절하고 취소하지 않는다")
	void unknown_current_employee_is_rejected_with_401() throws Exception {
		// given
		given(currentEmployee.id()).willReturn(null);

		// when / then
		mockMvc.perform(cancelRequest(BODY))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
		verify(cancelService, never()).cancelUse(any(), any(), any(), any(), any());
	}

	@Test
	@Tag("REQ-20")
	@Tag("REQ-27")
	@DisplayName("요청 본문에 처리 직원을 끼워 넣으면 400 INVALID_INPUT 로 거절하고 사용 취소하지 않는다")
	void performer_in_request_body_is_rejected() throws Exception {
		// when / then 처리 직원은 현재 직원에서만 정해진다 — 본문에 넣으면 받지 않는 항목으로 거절
		mockMvc.perform(cancelRequest("{\"reason\": \"주문 착오\", \"performedBy\": \"" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("performedBy"));
		verify(cancelService, never()).cancelUse(any(), any(), any(), any(), any());
	}

}
