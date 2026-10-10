package com.creditbook.prepaid.domain;

import static com.creditbook.prepaid.domain.PrepaidFixtures.EMPLOYEE_ID;
import static com.creditbook.prepaid.domain.PrepaidFixtures.NOW;
import static com.creditbook.prepaid.domain.PrepaidFixtures.POLICY;
import static com.creditbook.prepaid.domain.PrepaidFixtures.accountWithBalance;
import static com.creditbook.prepaid.domain.PrepaidFixtures.newAccount;
import static com.creditbook.prepaid.domain.PrepaidFixtures.won;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 요청 키가 거래에 저장되는지, 그리고 같은 키로 다시 온 요청이 "같은 요청" 인지 판단하는 규칙 (REQ-10).
 */
@Tag("REQ-10")
class LedgerEntryIdempotencyTest {

	private static final IdempotencyKey KEY = IdempotencyKey.of("3f2b8c1d-key-0001");

	@Test
	@DisplayName("충전·사용 거래에는 요청 키가 그대로 저장된다")
	void charge_and_use_store_idempotency_key() {
		// given
		PrepaidAccount account = newAccount();
		IdempotencyKey useKey = IdempotencyKey.of("use-key-0001");

		// when
		LedgerEntry charge = account.charge(won(10_000), POLICY, EMPLOYEE_ID, NOW, "음료", KEY);
		LedgerEntry use = account.use(won(4_500), EMPLOYEE_ID, NOW, null, useKey);

		// then
		assertThat(charge.getIdempotencyKey()).isEqualTo(KEY.value());
		assertThat(use.getIdempotencyKey()).isEqualTo(useKey.value());
		assertThat(account.getBalance()).isEqualByComparingTo("5500");
	}

	@Test
	@DisplayName("API 경로의 충전·사용은 요청 키 없이 호출할 수 없고 잔액도 바뀌지 않는다")
	void public_charge_and_use_require_key() {
		// given
		PrepaidAccount account = accountWithBalance(10_000);

		// when / then
		assertThatThrownBy(() -> account.charge(won(1_000), POLICY, EMPLOYEE_ID, NOW, null, null))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> account.use(won(1_000), EMPLOYEE_ID, NOW, null, null))
				.isInstanceOf(NullPointerException.class);
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@DisplayName("같은 고객·같은 유형·같은 금액·같은 메모면 같은 요청이다")
	void same_customer_type_amount_memo_is_same_request() {
		// given
		PrepaidAccount account = newAccount();
		LedgerEntry stored = account.charge(won(10_000), POLICY, EMPLOYEE_ID, NOW, "음료", KEY);

		// when
		boolean same = stored.isSameRequest(account.getId(),
				IdempotentRequest.original(LedgerEntryType.CHARGE, won(10_000), "음료"));

		// then
		assertThat(same).isTrue();
	}

	@ParameterizedTest(name = "[{index}] 저장 메모 [{0}] / 요청 금액 {1}, 메모 [{2}]")
	@CsvSource(delimiter = '|', nullValues = "NULL", value = {
			"음료  | 10000.0 | 음료",
			"음료  | 10000   | '  음료  '",
			"NULL  | 10000   | NULL",
			"NULL  | 10000   | '   '",
			"'  '  | 10000   | NULL" })
	@DisplayName("금액은 값으로, 메모는 저장할 때와 같은 규칙(앞뒤 공백 제거, 빈 값은 없음)으로 맞춰 비교한다")
	void amount_and_memo_are_compared_after_normalization(String storedMemo, String requestAmount,
			String requestMemo) {
		// given
		PrepaidAccount account = newAccount();
		LedgerEntry stored = account.charge(won(10_000), POLICY, EMPLOYEE_ID, NOW, storedMemo, KEY);

		// when
		boolean same = stored.isSameRequest(account.getId(),
				IdempotentRequest.original(LedgerEntryType.CHARGE, new BigDecimal(requestAmount), requestMemo));

		// then
		assertThat(same).isTrue();
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@CsvSource(delimiter = '|', nullValues = "NULL", value = {
			"다른 금액 | CHARGE | 10001 | 음료",
			"다른 유형 | USE    | 10000 | 음료",
			"다른 메모 | CHARGE | 10000 | 베이커리",
			"메모 없음 | CHARGE | 10000 | NULL",
			"금액 없음 | CHARGE | NULL  | 음료" })
	@DisplayName("유형·금액·메모 중 하나라도 다르면 다른 요청이다")
	void different_type_amount_or_memo_is_different_request(String label, LedgerEntryType type, String amount,
			String memo) {
		// given
		PrepaidAccount account = newAccount();
		LedgerEntry stored = account.charge(won(10_000), POLICY, EMPLOYEE_ID, NOW, "음료", KEY);
		BigDecimal requestAmount = amount == null ? null : new BigDecimal(amount);

		// when
		boolean same = stored.isSameRequest(account.getId(), IdempotentRequest.original(type, requestAmount, memo));

		// then
		assertThat(same).isFalse();
	}

	@Test
	@DisplayName("다른 고객(다른 계좌)이거나 계좌가 없는 고객이면 다른 요청이다")
	void different_or_missing_account_is_different_request() {
		// given
		PrepaidAccount account = newAccount();
		PrepaidAccount other = newAccount();
		LedgerEntry stored = account.charge(won(10_000), POLICY, EMPLOYEE_ID, NOW, "음료", KEY);
		IdempotentRequest request = IdempotentRequest.original(LedgerEntryType.CHARGE, won(10_000), "음료");

		// when / then
		assertThat(stored.isSameRequest(other.getId(), request)).isFalse();
		assertThat(stored.isSameRequest(null, request)).isFalse();
	}

	@Test
	@DisplayName("반제 요청은 금액 대신 대상 거래와 사유를 비교한다")
	void reversal_request_compares_target_and_reason() {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		LedgerEntry use = account.use(won(3_000), EMPLOYEE_ID, NOW, null);
		LedgerEntry cancel = account.cancelUse(use, Optional.empty(), "주문 착오", EMPLOYEE_ID, NOW);

		// when / then
		assertThat(cancel.isSameRequest(account.getId(),
				IdempotentRequest.reversal(LedgerEntryType.USE_CANCEL, use.getId(), " 주문 착오 "))).isTrue();
		assertThat(cancel.isSameRequest(account.getId(),
				IdempotentRequest.reversal(LedgerEntryType.USE_CANCEL, use.getId(), "고객 요청"))).isFalse();
		assertThat(cancel.isSameRequest(account.getId(),
				IdempotentRequest.reversal(LedgerEntryType.USE_CANCEL, UUID.randomUUID(), "주문 착오"))).isFalse();
		assertThat(cancel.isSameRequest(account.getId(),
				IdempotentRequest.reversal(LedgerEntryType.CHARGE_CANCEL, use.getId(), "주문 착오"))).isFalse();
	}

	@Test
	@DisplayName("원 거래 요청에 반제 유형을, 반제 요청에 원 거래 유형을 넣으면 거절한다")
	void request_type_must_match_factory() {
		// when / then
		assertThatThrownBy(() -> IdempotentRequest.original(LedgerEntryType.USE_CANCEL, won(1_000), null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> IdempotentRequest.reversal(LedgerEntryType.USE, UUID.randomUUID(), "사유"))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
