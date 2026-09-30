package com.creditbook.prepaid.domain;

import static com.creditbook.prepaid.domain.PrepaidFixtures.EMPLOYEE_ID;
import static com.creditbook.prepaid.domain.PrepaidFixtures.NOW;
import static com.creditbook.prepaid.domain.PrepaidFixtures.accountWithBalance;
import static com.creditbook.prepaid.domain.PrepaidFixtures.won;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("REQ-19")
class PrepaidAccountAmountIntegrityTest {

	@Test
	@DisplayName("1원 단위 거래를 1,000건 반복해도 잔액 조회 시 거래 합계와 잔액이 정확히 일치한다")
	void balance_equals_sum_of_entries_after_1000_one_won_entries() {
		// given
		PrepaidAccount account = accountWithBalance(0);
		List<LedgerEntry> entries = new ArrayList<>();

		// when: 1원 충전 두 번에 1원 사용 한 번 꼴로 1,000건
		for (int i = 0; i < 1_000; i++) {
			if (i % 3 == 2) {
				entries.add(account.use(won(1), EMPLOYEE_ID, NOW, null));
			} else {
				entries.add(account.charge(won(1), EMPLOYEE_ID, NOW, null));
			}
		}

		// then: 잔액 = signed_amount 합계, 각 거래의 balance_after = 그 시점까지의 누적합
		BigDecimal runningSum = BigDecimal.ZERO;
		for (LedgerEntry entry : entries) {
			runningSum = runningSum.add(entry.getSignedAmount());
			assertThat(entry.getBalanceAfter()).isEqualByComparingTo(runningSum);
		}
		assertThat(entries).hasSize(1_000);
		assertThat(account.getBalance()).isEqualByComparingTo(runningSum);
		assertThat(account.getBalance()).isEqualByComparingTo("334");   // 충전 667건 - 사용 333건
	}

	@ParameterizedTest(name = "소수점 금액 {0} 원 충전은 거절된다")
	@ValueSource(strings = { "100.5", "0.1", "4500.01" })
	@DisplayName("소수점 금액 충전은 거절되고 잔액과 거래는 그대로다")
	void charge_rejects_fractional_amount(String amount) {
		// given
		PrepaidAccount account = accountWithBalance(10_000);

		// when / then
		assertThatThrownBy(() -> account.charge(new BigDecimal(amount), EMPLOYEE_ID, NOW, null))
				.isInstanceOf(InvalidAmountException.class);
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
	}

	@ParameterizedTest(name = "소수점 금액 {0} 원 사용은 거절된다")
	@ValueSource(strings = { "100.5", "0.1", "4500.01" })
	@DisplayName("소수점 금액 사용은 거절되고 잔액과 거래는 그대로다")
	void use_rejects_fractional_amount(String amount) {
		// given
		PrepaidAccount account = accountWithBalance(10_000);

		// when / then
		assertThatThrownBy(() -> account.use(new BigDecimal(amount), EMPLOYEE_ID, NOW, null))
				.isInstanceOf(InvalidAmountException.class);
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@DisplayName("4500.00 처럼 소수부가 0인 금액은 받아들이고 scale 0 으로 저장한다")
	void integral_amount_with_trailing_zeros_is_normalized_to_scale_zero() {
		// given
		PrepaidAccount account = accountWithBalance(0);

		// when
		LedgerEntry entry = account.charge(new BigDecimal("4500.00"), EMPLOYEE_ID, NOW, null);

		// then
		assertThat(entry.getAmount().scale()).isZero();
		assertThat(entry.getBalanceAfter().scale()).isZero();
		assertThat(account.getBalance().scale()).isZero();
		assertThat(account.getBalance()).isEqualByComparingTo("4500");
	}

	@Test
	@DisplayName("금액이 없으면 거절된다")
	void null_amount_is_rejected() {
		// given
		PrepaidAccount account = accountWithBalance(10_000);

		// when / then
		assertThatThrownBy(() -> account.use(null, EMPLOYEE_ID, NOW, null))
				.isInstanceOf(InvalidAmountException.class);
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@DisplayName("NUMERIC(12,0) 범위를 넘는 금액은 DB 에 가기 전에 거절된다")
	void amount_over_numeric_12_is_rejected() {
		// given
		PrepaidAccount account = accountWithBalance(10_000);

		// when / then
		assertThatThrownBy(() -> account.use(new BigDecimal("1000000000000"), EMPLOYEE_ID, NOW, null))
				.isInstanceOf(InvalidAmountException.class);
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
	}

}
