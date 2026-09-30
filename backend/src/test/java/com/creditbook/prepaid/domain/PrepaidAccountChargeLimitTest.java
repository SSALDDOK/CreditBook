package com.creditbook.prepaid.domain;

import static com.creditbook.prepaid.domain.PrepaidFixtures.EMPLOYEE_ID;
import static com.creditbook.prepaid.domain.PrepaidFixtures.NOW;
import static com.creditbook.prepaid.domain.PrepaidFixtures.POLICY;
import static com.creditbook.prepaid.domain.PrepaidFixtures.accountWithBalance;
import static com.creditbook.prepaid.domain.PrepaidFixtures.won;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("REQ-6")
class PrepaidAccountChargeLimitTest {

	/** 충전 금액 경계값은 모두 여기에 모은다. 한도를 바꿔도 같은 규칙이 성립해야 한다. */
	@ParameterizedTest(name = "1회 한도 {0}원일 때 {1}원 충전 → {2}")
	@CsvSource({
			"300000, 1,      ACCEPTED",
			"300000, 300000, ACCEPTED",
			"300000, 0,      INVALID_AMOUNT",
			"300000, -1,     INVALID_AMOUNT",
			"300000, 300001, LIMIT_EXCEEDED",
			"10000,  10000,  ACCEPTED",
			"10000,  10001,  LIMIT_EXCEEDED",
	})
	@DisplayName("0원·음수·1회 충전 한도 초과 금액은 충전되지 않는다")
	void charge_amount_boundaries(long maxAmount, long amount, String expected) {
		// given
		PrepaidAccount account = accountWithBalance(5_000);
		ChargePolicy policy = ChargePolicy.ofMaxAmount(maxAmount);

		// when / then
		switch (expected) {
			case "ACCEPTED" -> {
				LedgerEntry entry = account.charge(won(amount), policy, EMPLOYEE_ID, NOW, null);
				assertThat(entry.getType()).isEqualTo(LedgerEntryType.CHARGE);
				assertThat(account.getBalance()).isEqualByComparingTo(won(5_000 + amount));
			}
			case "INVALID_AMOUNT" -> {
				assertThatThrownBy(() -> account.charge(won(amount), policy, EMPLOYEE_ID, NOW, null))
						.isInstanceOf(InvalidAmountException.class)
						.isNotInstanceOf(ChargeLimitExceededException.class);
				assertThat(account.getBalance()).isEqualByComparingTo("5000");
			}
			case "LIMIT_EXCEEDED" -> {
				assertThatThrownBy(() -> account.charge(won(amount), policy, EMPLOYEE_ID, NOW, null))
						.isInstanceOfSatisfying(ChargeLimitExceededException.class,
								e -> assertThat(e.getMaxAmount()).isEqualByComparingTo(won(maxAmount)));
				assertThat(account.getBalance()).isEqualByComparingTo("5000");
			}
			default -> throw new IllegalArgumentException(expected);
		}
	}

	@Test
	@DisplayName("충전 한도는 1회 한도이며 잔액 상한이 아니다 — 여러 번 충전해 30만원을 넘을 수 있다")
	void limit_applies_per_charge_not_to_balance() {
		// given
		PrepaidAccount account = accountWithBalance(0);
		account.charge(won(300_000), POLICY, EMPLOYEE_ID, NOW, null);

		// when
		LedgerEntry second = account.charge(won(300_000), POLICY, EMPLOYEE_ID, NOW, null);

		// then
		assertThat(account.getBalance()).isEqualByComparingTo("600000");
		assertThat(second.getBalanceAfter()).isEqualByComparingTo("600000");
	}

	@ParameterizedTest(name = "1회 충전 한도 {0} 은 정책으로 만들 수 없다")
	@ValueSource(strings = { "0", "-1", "1.5" })
	@DisplayName("1회 충전 한도는 1원 이상의 정수여야 한다")
	void charge_policy_rejects_invalid_max_amount(String maxAmount) {
		// given
		BigDecimal value = new BigDecimal(maxAmount);

		// when / then
		assertThatThrownBy(() -> new ChargePolicy(value)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("여러 번 충전해 잔액이 NUMERIC(12,0) 범위를 넘으면 거절된다")
	void charge_rejected_when_balance_would_overflow_numeric_12() {
		// given
		ChargePolicy unlimited = new ChargePolicy(PrepaidAccount.NUMERIC_12_MAX);
		PrepaidAccount account = accountWithBalance(0);
		account.charge(PrepaidAccount.NUMERIC_12_MAX, unlimited, EMPLOYEE_ID, NOW, null);

		// when / then
		assertThatThrownBy(() -> account.charge(won(1), unlimited, EMPLOYEE_ID, NOW, null))
				.isInstanceOf(InvalidAmountException.class);
		assertThat(account.getBalance()).isEqualByComparingTo(PrepaidAccount.NUMERIC_12_MAX);
	}

}
