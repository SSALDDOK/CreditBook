package com.creditbook.prepaid.domain;

import static com.creditbook.prepaid.domain.PrepaidFixtures.EMPLOYEE_ID;
import static com.creditbook.prepaid.domain.PrepaidFixtures.NOW;
import static com.creditbook.prepaid.domain.PrepaidFixtures.accountWithBalance;
import static com.creditbook.prepaid.domain.PrepaidFixtures.won;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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

}
