package com.creditbook.prepaid.domain;

import static com.creditbook.prepaid.domain.PrepaidFixtures.EMPLOYEE_ID;
import static com.creditbook.prepaid.domain.PrepaidFixtures.NOW;
import static com.creditbook.prepaid.domain.PrepaidFixtures.POLICY;
import static com.creditbook.prepaid.domain.PrepaidFixtures.STORE_ID;
import static com.creditbook.prepaid.domain.PrepaidFixtures.accountWithBalance;
import static com.creditbook.prepaid.domain.PrepaidFixtures.won;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 계좌와 거래의 매장 (REQ-36). 계좌는 고객의 매장에 속하고, 계좌가 만드는 거래는 원 거래·반제 거래 모두 계좌의 매장을 그대로 갖는다 —
 * 거래의 매장을 따로 받는 길이 없으므로 계좌와 어긋날 수 없다.
 */
@Tag("REQ-36")
class PrepaidAccountStoreTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final IdempotencyKey KEY = IdempotencyKey.of("store-test-key");

	@Test
	@DisplayName("계좌는 매장 없이 열 수 없다")
	void account_requires_store() {
		// given
		UUID customerId = UUID.randomUUID();

		// when / then
		assertThatThrownBy(() -> PrepaidAccount.open(null, customerId, NOW))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("storeId");
	}

	@Test
	@DisplayName("계좌를 열면 고객과 같은 매장에 속하고 잔액은 0원이다")
	void opened_account_belongs_to_store() {
		// given
		UUID storeId = UUID.randomUUID();
		UUID customerId = UUID.randomUUID();

		// when
		PrepaidAccount account = PrepaidAccount.open(storeId, customerId, NOW);

		// then
		assertThat(account.getStoreId()).isEqualTo(storeId);
		assertThat(account.getCustomerId()).isEqualTo(customerId);
		assertThat(account.getBalance()).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("충전·사용 거래는 계좌의 매장을 그대로 갖는다")
	void original_entries_follow_account_store() {
		// given
		PrepaidAccount account = PrepaidAccount.open(STORE_ID, UUID.randomUUID(), NOW);

		// when
		LedgerEntry charge = account.charge(won(10_000), POLICY, EMPLOYEE_ID, NOW, null, KEY);
		LedgerEntry use = account.use(won(3_000), EMPLOYEE_ID, NOW, null, IdempotencyKey.of("store-test-use"));

		// then
		assertThat(charge.getStoreId()).isEqualTo(account.getStoreId());
		assertThat(use.getStoreId()).isEqualTo(account.getStoreId());
		assertThat(account.getBalance()).isEqualByComparingTo("7000");
	}

	@Test
	@DisplayName("사용 취소 반제 거래는 계좌의 매장을 그대로 갖는다")
	void use_cancel_entry_follows_account_store() {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		LedgerEntry use = account.use(won(4_000), EMPLOYEE_ID, NOW, null);

		// when
		LedgerEntry cancel = account.cancelUse(use, Optional.empty(), "주문 착오", EMPLOYEE_ID, NOW, KEY);

		// then
		assertThat(cancel.getStoreId()).isEqualTo(account.getStoreId()).isEqualTo(use.getStoreId());
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@DisplayName("충전 취소 반제 거래는 계좌의 매장을 그대로 갖는다")
	void charge_cancel_entry_follows_account_store() {
		// given
		PrepaidAccount account = accountWithBalance(0);
		LedgerEntry charge = account.charge(won(5_000), POLICY, EMPLOYEE_ID, NOW, null);

		// when
		LedgerEntry cancel = account.cancelCharge(charge, Optional.empty(), "금액 잘못 입력", EMPLOYEE_ID, NOW, SEOUL,
				KEY);

		// then
		assertThat(cancel.getStoreId()).isEqualTo(account.getStoreId()).isEqualTo(charge.getStoreId());
		assertThat(account.getBalance()).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("같은 매장이라도 다른 계좌의 거래는 반제할 수 없다")
	void reversal_of_other_account_entry_in_same_store_is_rejected() {
		// given — 같은 매장의 두 계좌
		PrepaidAccount mine = accountWithBalance(10_000);
		PrepaidAccount other = accountWithBalance(10_000);
		LedgerEntry otherUse = other.use(won(1_000), EMPLOYEE_ID, NOW, null);
		assertThat(mine.getStoreId()).isEqualTo(other.getStoreId());

		// when / then
		assertThatThrownBy(() -> mine.cancelUse(otherUse, Optional.empty(), "주문 착오", EMPLOYEE_ID, NOW, KEY))
				.isInstanceOf(InvalidCancelTargetException.class);
		assertThat(mine.getBalance()).isEqualByComparingTo("10000");
	}

}
