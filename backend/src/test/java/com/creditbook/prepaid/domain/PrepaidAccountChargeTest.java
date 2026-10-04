package com.creditbook.prepaid.domain;

import static com.creditbook.prepaid.domain.PrepaidFixtures.EMPLOYEE_ID;
import static com.creditbook.prepaid.domain.PrepaidFixtures.NOW;
import static com.creditbook.prepaid.domain.PrepaidFixtures.POLICY;
import static com.creditbook.prepaid.domain.PrepaidFixtures.accountWithBalance;
import static com.creditbook.prepaid.domain.PrepaidFixtures.won;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class PrepaidAccountChargeTest {

	@Test
	@Tag("REQ-5")
	@Tag("TC-1")
	@DisplayName("잔액 0원 고객에게 50,000원 충전을 등록하면 잔액이 50,000원이 되고 CHARGE 유형 거래가 1건 생성된다")
	void charge_increases_balance_and_creates_charge_entry() {
		// given
		PrepaidAccount account = accountWithBalance(0);

		// when
		LedgerEntry entry = account.charge(won(50_000), POLICY, EMPLOYEE_ID, NOW, "음료");

		// then
		assertThat(account.getBalance()).isEqualByComparingTo("50000");
		assertThat(entry.getType()).isEqualTo(LedgerEntryType.CHARGE);
		assertThat(entry.getAccountId()).isEqualTo(account.getId());
		assertThat(entry.getAmount()).isEqualByComparingTo("50000");
		assertThat(entry.getSignedAmount()).isEqualByComparingTo("50000");
		assertThat(entry.getBalanceAfter()).isEqualByComparingTo(account.getBalance());
		assertThat(entry.getReversesId()).isNull();
		assertThat(entry.getMemo()).isEqualTo("음료");
	}

	@Test
	@Tag("REQ-20")
	@DisplayName("거래에는 실행 직원과 서버 시각이 기록된다")
	void entry_records_performer_and_server_time() {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		UUID staffId = UUID.randomUUID();
		Instant serverTime = Instant.parse("2026-10-01T03:15:30Z");

		// when
		LedgerEntry charge = account.charge(won(1_000), POLICY, staffId, serverTime, null);
		LedgerEntry use = account.use(won(500), staffId, serverTime.plusSeconds(1), null);

		// then
		assertThat(charge.getPerformedBy()).isEqualTo(staffId);
		assertThat(charge.getPerformedAt()).isEqualTo(serverTime);
		assertThat(use.getPerformedBy()).isEqualTo(staffId);
		assertThat(use.getPerformedAt()).isEqualTo(serverTime.plusSeconds(1));
		assertThat(account.getUpdatedAt()).isEqualTo(serverTime.plusSeconds(1));
	}

	@Test
	@Tag("REQ-5")
	@DisplayName("빈 메모는 저장하지 않고 앞뒤 공백은 지운다")
	void blank_memo_is_stored_as_null_and_memo_is_stripped() {
		// given
		PrepaidAccount account = accountWithBalance(0);

		// when
		LedgerEntry blank = account.charge(won(1_000), POLICY, EMPLOYEE_ID, NOW, "   ");
		LedgerEntry padded = account.charge(won(1_000), POLICY, EMPLOYEE_ID, NOW, "  원두 ");

		// then
		assertThat(blank.getMemo()).isNull();
		assertThat(padded.getMemo()).isEqualTo("원두");
	}

}
