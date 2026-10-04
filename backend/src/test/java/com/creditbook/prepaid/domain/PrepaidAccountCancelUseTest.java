package com.creditbook.prepaid.domain;

import static com.creditbook.prepaid.domain.PrepaidFixtures.EMPLOYEE_ID;
import static com.creditbook.prepaid.domain.PrepaidFixtures.NOW;
import static com.creditbook.prepaid.domain.PrepaidFixtures.POLICY;
import static com.creditbook.prepaid.domain.PrepaidFixtures.accountWithBalance;
import static com.creditbook.prepaid.domain.PrepaidFixtures.won;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("REQ-34")
class PrepaidAccountCancelUseTest {

	private static final String REASON = "주문 착오";

	@Test
	@Tag("TC-33")
	@DisplayName("사용 건을 취소하면 원 거래는 그대로 두고 USE_CANCEL 반제 거래가 추가되어 잔액이 사용 금액만큼 복구된다")
	void cancel_use_adds_use_cancel_entry_and_restores_balance() {
		// given
		PrepaidAccount account = accountWithBalance(50_000);
		LedgerEntry use = account.use(won(4_500), EMPLOYEE_ID, NOW, "음료");

		// when
		LedgerEntry cancel = account.cancelUse(use, Optional.empty(), "  " + REASON + " ", EMPLOYEE_ID, NOW);

		// then
		assertThat(account.getBalance()).isEqualByComparingTo("50000");
		assertThat(cancel.getType()).isEqualTo(LedgerEntryType.USE_CANCEL);
		assertThat(cancel.getAccountId()).isEqualTo(account.getId());
		assertThat(cancel.getReversesId()).isEqualTo(use.getId());
		assertThat(cancel.getAmount()).isEqualByComparingTo("4500");
		assertThat(cancel.getSignedAmount()).isEqualByComparingTo("4500");
		assertThat(cancel.getBalanceAfter()).isEqualByComparingTo("50000");
		assertThat(cancel.getMemo()).isEqualTo(REASON);
		// 원 거래는 바뀌지 않는다
		assertThat(use.getType()).isEqualTo(LedgerEntryType.USE);
		assertThat(use.getAmount()).isEqualByComparingTo("4500");
		assertThat(use.getBalanceAfter()).isEqualByComparingTo("45500");
		assertThat(use.getMemo()).isEqualTo("음료");
	}

	@Test
	@DisplayName("사용 취소는 기한이 없다 — 1년 지난 사용 건도 취소된다")
	void cancel_use_has_no_time_limit() {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		LedgerEntry use = account.use(won(3_000), EMPLOYEE_ID, NOW, null);
		Instant oneYearLater = NOW.plus(Duration.ofDays(366));

		// when
		LedgerEntry cancel = account.cancelUse(use, Optional.empty(), REASON, EMPLOYEE_ID, oneYearLater);

		// then
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
		assertThat(cancel.getPerformedAt()).isEqualTo(oneYearLater);
	}

	@ParameterizedTest(name = "사유 [{0}] 로는 취소할 수 없다")
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "\t" })
	@Tag("TC-34")
	@DisplayName("사용 취소는 사유가 필수다")
	void cancel_use_requires_reason(String reason) {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		LedgerEntry use = account.use(won(3_000), EMPLOYEE_ID, NOW, null);

		// when / then
		assertThatThrownBy(() -> account.cancelUse(use, Optional.empty(), reason, EMPLOYEE_ID, NOW))
				.isInstanceOf(CancelReasonRequiredException.class);
		assertThat(account.getBalance()).isEqualByComparingTo("7000");
	}

	@Test
	@DisplayName("충전 거래는 사용 취소 대상이 아니다")
	void cancel_use_rejects_charge_entry() {
		// given
		PrepaidAccount account = accountWithBalance(0);
		LedgerEntry charge = account.charge(won(10_000), POLICY, EMPLOYEE_ID, NOW, null);

		// when / then
		assertThatThrownBy(() -> account.cancelUse(charge, Optional.empty(), REASON, EMPLOYEE_ID, NOW))
				.isInstanceOf(InvalidCancelTargetException.class);
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@DisplayName("취소 거래(반제 행)는 다시 취소할 수 없다")
	void cancel_use_rejects_reversal_entry() {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		LedgerEntry use = account.use(won(3_000), EMPLOYEE_ID, NOW, null);
		LedgerEntry cancel = account.cancelUse(use, Optional.empty(), REASON, EMPLOYEE_ID, NOW);

		// when / then
		assertThatThrownBy(() -> account.cancelUse(cancel, Optional.empty(), REASON, EMPLOYEE_ID, NOW))
				.isInstanceOf(InvalidCancelTargetException.class)
				.hasMessageContaining("다시 취소할 수 없습니다");
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@DisplayName("다른 계좌의 사용 건은 취소할 수 없다")
	void cancel_use_rejects_entry_of_other_account() {
		// given
		PrepaidAccount other = accountWithBalance(10_000);
		LedgerEntry othersUse = other.use(won(3_000), EMPLOYEE_ID, NOW, null);
		PrepaidAccount account = accountWithBalance(10_000);

		// when / then
		assertThatThrownBy(() -> account.cancelUse(othersUse, Optional.empty(), REASON, EMPLOYEE_ID, NOW))
				.isInstanceOf(InvalidCancelTargetException.class)
				.hasMessageContaining("다른 계좌");
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
		assertThat(other.getBalance()).isEqualByComparingTo("7000");
	}

	@Test
	@Tag("TC-34")
	@DisplayName("이미 취소된 사용 건은 다시 취소할 수 없다")
	void cancel_use_rejects_already_cancelled_use() {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		LedgerEntry use = account.use(won(3_000), EMPLOYEE_ID, NOW, null);
		LedgerEntry firstCancel = account.cancelUse(use, Optional.empty(), REASON, EMPLOYEE_ID, NOW);

		// when / then: 서비스가 조회한 기존 반제 거래를 넘긴다
		assertThatThrownBy(() -> account.cancelUse(use, Optional.of(firstCancel), REASON, EMPLOYEE_ID, NOW))
				.isInstanceOfSatisfying(AlreadyCancelledException.class, e -> {
					assertThat(e.getTargetId()).isEqualTo(use.getId());
					assertThat(e.getCancelEntryId()).isEqualTo(firstCancel.getId());
				});
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@DisplayName("기존 취소 거래로 넘긴 행이 대상 거래를 반제한 것이 아니면 호출 오류로 거절한다")
	void cancel_use_rejects_unrelated_existing_cancel() {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		LedgerEntry firstUse = account.use(won(1_000), EMPLOYEE_ID, NOW, null);
		LedgerEntry secondUse = account.use(won(2_000), EMPLOYEE_ID, NOW, null);
		LedgerEntry cancelOfFirst = account.cancelUse(firstUse, Optional.empty(), REASON, EMPLOYEE_ID, NOW);

		// when / then
		assertThatThrownBy(() -> account.cancelUse(secondUse, Optional.of(cancelOfFirst), REASON, EMPLOYEE_ID, NOW))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(account.getBalance()).isEqualByComparingTo("8000");
	}

	@Test
	@Tag("REQ-19")
	@DisplayName("사용과 사용 취소를 섞어도 잔액은 거래 합계와 일치한다")
	void balance_equals_sum_of_entries_with_use_cancels() {
		// given
		PrepaidAccount account = accountWithBalance(0);
		LedgerEntry charge = account.charge(won(20_000), POLICY, EMPLOYEE_ID, NOW, null);
		LedgerEntry use1 = account.use(won(4_500), EMPLOYEE_ID, NOW, null);
		LedgerEntry use2 = account.use(won(6_000), EMPLOYEE_ID, NOW, null);

		// when
		LedgerEntry cancel = account.cancelUse(use1, Optional.empty(), REASON, EMPLOYEE_ID, NOW);

		// then
		BigDecimal sum = charge.getSignedAmount().add(use1.getSignedAmount()).add(use2.getSignedAmount())
				.add(cancel.getSignedAmount());
		assertThat(account.getBalance()).isEqualByComparingTo(sum);
		assertThat(account.getBalance()).isEqualByComparingTo("14000");
		assertThat(cancel.getBalanceAfter()).isEqualByComparingTo("14000");
	}

}
