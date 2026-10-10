package com.creditbook.prepaid.domain;

import static com.creditbook.prepaid.domain.PrepaidFixtures.EMPLOYEE_ID;
import static com.creditbook.prepaid.domain.PrepaidFixtures.NOW;
import static com.creditbook.prepaid.domain.PrepaidFixtures.accountWithBalance;
import static com.creditbook.prepaid.domain.PrepaidFixtures.won;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 잔액이 0원인지 판단 (REQ-4 고객 비활성화의 전제). 잔액 판단은 PrepaidAccount 만 한다.
 */
@Tag("REQ-4")
class PrepaidAccountZeroBalanceTest {

	@ParameterizedTest(name = "[{index}] 잔액 {0}원 → 0원인가 {1}")
	@CsvSource({ "0, true", "1, false", "300000, false" })
	@DisplayName("잔액이 정확히 0원일 때만 잔액이 0원인 계좌로 본다")
	void has_zero_balance_only_when_balance_is_exactly_zero(long balance, boolean expected) {
		// given
		PrepaidAccount account = accountWithBalance(balance);

		// when
		boolean result = account.hasZeroBalance();

		// then
		assertThat(result).isEqualTo(expected);
	}

	@Test
	@DisplayName("충전 후 전액을 사용해 잔액이 0원이 되면 잔액이 0원인 계좌로 본다")
	void has_zero_balance_after_using_everything() {
		// given
		PrepaidAccount account = accountWithBalance(5_000);

		// when
		account.use(won(5_000), EMPLOYEE_ID, NOW, null);

		// then
		assertThat(account.hasZeroBalance()).isTrue();
	}

}
