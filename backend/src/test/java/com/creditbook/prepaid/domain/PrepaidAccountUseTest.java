package com.creditbook.prepaid.domain;

import static com.creditbook.prepaid.domain.PrepaidFixtures.EMPLOYEE_ID;
import static com.creditbook.prepaid.domain.PrepaidFixtures.NOW;
import static com.creditbook.prepaid.domain.PrepaidFixtures.accountWithBalance;
import static com.creditbook.prepaid.domain.PrepaidFixtures.won;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PrepaidAccountUseTest {

	@Test
	@Tag("REQ-8")
	@DisplayName("잔액 50,000원 고객이 4,500원을 사용하면 잔액이 45,500원이 되고 USE 유형 거래가 생성된다")
	void use_decreases_balance_and_creates_use_entry() {
		// given
		PrepaidAccount account = accountWithBalance(50_000);

		// when
		LedgerEntry entry = account.use(won(4_500), EMPLOYEE_ID, NOW, "음료");

		// then
		assertThat(account.getBalance()).isEqualByComparingTo("45500");
		assertThat(entry.getType()).isEqualTo(LedgerEntryType.USE);
		assertThat(entry.getAccountId()).isEqualTo(account.getId());
		assertThat(entry.getAmount()).isEqualByComparingTo("4500");
		assertThat(entry.getSignedAmount()).isEqualByComparingTo("-4500");
		assertThat(entry.getBalanceAfter()).isEqualByComparingTo("45500");
		assertThat(entry.getReversesId()).isNull();
	}

	@Test
	@Tag("REQ-9")
	@DisplayName("잔액 3,000원 고객이 5,000원 사용을 시도하면 거래가 생성되지 않고 부족 금액이 안내된다")
	void use_fails_when_amount_exceeds_balance() {
		// given
		PrepaidAccount account = accountWithBalance(3_000);

		// when / then
		assertThatThrownBy(() -> account.use(won(5_000), EMPLOYEE_ID, NOW, null))
				.isInstanceOfSatisfying(InsufficientBalanceException.class, e -> {
					assertThat(e.getShortage()).isEqualByComparingTo("2000");
					assertThat(e.getBalance()).isEqualByComparingTo("3000");
					assertThat(e.getRequestedAmount()).isEqualByComparingTo("5000");
					assertThat(e.getMessage()).contains("2000원 부족");
				});
		assertThat(account.getBalance()).isEqualByComparingTo("3000");
	}

	/** 잔액 부족 경계값은 모두 여기에 모은다. */
	@ParameterizedTest(name = "잔액 {0}원에서 {1}원 사용 → 성공 {2}, 부족 금액 {3}")
	@CsvSource({
			"3000, 2999, true,  0",
			"3000, 3000, true,  0",
			"3000, 3001, false, 1",
			"0,    1,    false, 1",
	})
	@Tag("REQ-9")
	@Tag("TC-2")
	@Tag("TC-3")
	@DisplayName("잔액과 같은 금액까지는 사용할 수 있고 1원이라도 넘으면 부족 금액과 함께 거절된다")
	void use_balance_boundaries(long balance, long amount, boolean accepted, long shortage) {
		// given
		PrepaidAccount account = accountWithBalance(balance);

		// when / then
		assertThat(account.canUse(won(amount))).isEqualTo(accepted);
		if (accepted) {
			LedgerEntry entry = account.use(won(amount), EMPLOYEE_ID, NOW, null);
			assertThat(account.getBalance()).isEqualByComparingTo(won(balance - amount));
			assertThat(entry.getBalanceAfter()).isEqualByComparingTo(won(balance - amount));
		} else {
			assertThatThrownBy(() -> account.use(won(amount), EMPLOYEE_ID, NOW, null))
					.isInstanceOfSatisfying(InsufficientBalanceException.class,
							e -> assertThat(e.getShortage()).isEqualByComparingTo(won(shortage)));
			assertThat(account.getBalance()).isEqualByComparingTo(won(balance));
		}
	}

	@ParameterizedTest(name = "{0}원 사용은 금액 오류로 거절된다")
	@ValueSource(longs = { 0, -1 })
	@Tag("REQ-8")
	@DisplayName("0원·음수 사용은 잔액 부족이 아니라 금액 오류로 거절된다")
	void use_rejects_non_positive_amount(long amount) {
		// given
		PrepaidAccount account = accountWithBalance(3_000);

		// when / then
		assertThatThrownBy(() -> account.use(won(amount), EMPLOYEE_ID, NOW, null))
				.isInstanceOf(InvalidAmountException.class);
		assertThat(account.getBalance()).isEqualByComparingTo("3000");
	}

}
