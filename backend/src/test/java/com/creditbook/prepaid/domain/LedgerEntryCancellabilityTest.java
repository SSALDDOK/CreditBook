package com.creditbook.prepaid.domain;

import static com.creditbook.prepaid.domain.PrepaidFixtures.EMPLOYEE_ID;
import static com.creditbook.prepaid.domain.PrepaidFixtures.POLICY;
import static com.creditbook.prepaid.domain.PrepaidFixtures.accountWithBalance;
import static com.creditbook.prepaid.domain.PrepaidFixtures.won;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 취소 가능 판단 — 충전 취소의 '당일'(REQ-7)과 사용 취소의 기한 없음(REQ-34). 거래 이력의 cancellable 과
 * 충전 취소가 같은 판단({@link LedgerEntry#isSameBusinessDay})을 쓰는지 함께 본다. 영업 시간대는 Asia/Seoul, 서버 시각은 UTC 다.
 */
@Tag("REQ-7")
@Tag("REQ-34")
class LedgerEntryCancellabilityTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	/** KST 2026-10-16 14:22 */
	private static final Instant NOW = Instant.parse("2026-10-16T05:22:00Z");

	private static LedgerHistoryEntry historyEntry(LedgerEntryType type, Instant performedAt, boolean cancelled) {
		UUID reversesId = type.isReversal() ? UUID.randomUUID() : null;
		return new LedgerHistoryEntry(UUID.randomUUID(), type, won(4500),
				type.isDebit() ? won(-4500) : won(4500), won(45500), "음료", performedAt, "김직원", cancelled,
				reversesId);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@CsvSource(delimiter = '|', value = {
			// 경우                                         | 거래 시각(UTC)          | 판단 시각(UTC)          | 당일
			"KST 같은 날 자정 직후 거래, 자정 직전 판단       | 2026-10-14T15:00:00Z | 2026-10-15T14:59:59Z | true",
			"KST 자정 직전 거래, 자정 직후 판단 (UTC 같은 날) | 2026-10-14T14:59:59Z | 2026-10-14T15:00:00Z | false",
			"KST 같은 날 08:59·09:00 (UTC 로는 다른 날)      | 2026-10-14T23:59:00Z | 2026-10-15T00:00:00Z | true",
			"KST 23:59:59 거래, 다음 날 00:00:00 판단        | 2026-10-15T14:59:59Z | 2026-10-15T15:00:00Z | false",
			"같은 시각                                      | 2026-10-16T05:22:00Z | 2026-10-16T05:22:00Z | true",
			"하루 전 같은 시각                               | 2026-10-15T05:22:00Z | 2026-10-16T05:22:00Z | false",
	})
	@DisplayName("'당일'은 영업 시간대(Asia/Seoul)의 달력 날짜로 판단한다")
	void same_business_day_is_judged_in_business_zone(String description, Instant performedAt, Instant now,
			boolean expected) {
		// given: 거래 시각과 판단 시각

		// when
		boolean sameDay = LedgerEntry.isSameBusinessDay(performedAt, now, SEOUL);

		// then
		assertThat(sameDay).as(description).isEqualTo(expected);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@CsvSource(delimiter = '|', value = {
			// 경우                                       | 유형           | 거래 시각(UTC)          | 이미 취소 | 취소 가능
			"오늘 충전, 취소 전                            | CHARGE        | 2026-10-16T01:00:00Z | false | true",
			"KST 오늘 00:00 충전 (UTC 로는 어제)            | CHARGE        | 2026-10-15T15:00:00Z | false | true",
			"KST 어제 23:59:59 충전                        | CHARGE        | 2026-10-15T14:59:59Z | false | false",
			"오늘 충전이지만 이미 취소됨                     | CHARGE        | 2026-10-16T01:00:00Z | true  | false",
			"오늘 사용, 취소 전                            | USE           | 2026-10-16T01:00:00Z | false | true",
			"1년 전 사용, 취소 전 (기한 없음)               | USE           | 2025-10-16T01:00:00Z | false | true",
			"사용이지만 이미 취소됨                         | USE           | 2026-10-16T01:00:00Z | true  | false",
			"오늘의 충전 취소 행                            | CHARGE_CANCEL | 2026-10-16T01:00:00Z | false | false",
			"오늘의 사용 취소 행                            | USE_CANCEL    | 2026-10-16T01:00:00Z | false | false",
	})
	@DisplayName("사용 건은 기한 없이, 충전 건은 당일에만 취소할 수 있고, 이미 취소된 건과 취소 행은 취소할 수 없다")
	void cancellable_follows_type_cancel_state_and_same_day(String description, LedgerEntryType type,
			Instant performedAt, boolean cancelled, boolean expected) {
		// given
		LedgerHistoryEntry entry = historyEntry(type, performedAt, cancelled);

		// when
		boolean cancellable = entry.canBeCancelledAt(NOW, SEOUL);

		// then
		assertThat(cancellable).as(description).isEqualTo(expected);
		assertThat(LedgerEntry.canBeCancelledAt(type, performedAt, cancelled, NOW, SEOUL)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@EnumSource(LedgerEntryType.class)
	@DisplayName("영업 시간대나 판단 시각이 없으면 유형과 관계없이 판단하지 않고 거절한다")
	void business_zone_and_now_are_required(LedgerEntryType type) {
		// given: 시간대 또는 판단 시각 없이 판단을 요청한다

		// when / then
		assertThatThrownBy(() -> LedgerEntry.canBeCancelledAt(type, NOW, false, NOW, null))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> LedgerEntry.canBeCancelledAt(type, NOW, false, null, SEOUL))
				.isInstanceOf(NullPointerException.class);
	}

	@ParameterizedTest(name = "[{index}] 충전 {0} / 취소 {1}")
	@CsvSource({
			"2026-10-14T15:00:00Z, 2026-10-15T14:59:59Z",
			"2026-10-14T14:59:59Z, 2026-10-14T15:00:00Z",
			"2026-10-14T23:59:00Z, 2026-10-15T00:00:00Z",
			"2026-10-15T14:59:59Z, 2026-10-15T15:00:00Z",
	})
	@DisplayName("거래 이력이 취소 가능으로 표시한 충전 건은 실제로 취소되고, 불가로 표시한 충전 건은 당일이 아니라며 거절된다")
	void cancellable_flag_agrees_with_cancel_charge(Instant chargedAt, Instant cancelAt) {
		// given: 충전 직후 잔액 = 충전액이라 잔액 때문에 거절되지는 않는다
		PrepaidAccount account = accountWithBalance(0);
		LedgerEntry charge = account.charge(won(30_000), POLICY, EMPLOYEE_ID, chargedAt, "현금");
		boolean cancellable = LedgerEntry.canBeCancelledAt(charge.getType(), charge.getPerformedAt(), false,
				cancelAt, SEOUL);

		// when / then
		if (cancellable) {
			LedgerEntry cancel = account.cancelCharge(charge, Optional.empty(), "금액 잘못 입력", EMPLOYEE_ID, cancelAt,
					SEOUL);
			assertThat(cancel.getType()).isEqualTo(LedgerEntryType.CHARGE_CANCEL);
			assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
		}
		else {
			assertThatThrownBy(() -> account.cancelCharge(charge, Optional.empty(), "금액 잘못 입력", EMPLOYEE_ID,
					cancelAt, SEOUL))
					.isInstanceOf(ChargeCancelNotSameDayException.class);
			assertThat(account.getBalance()).isEqualByComparingTo("30000");
		}
	}

	@Test
	@DisplayName("거래 이력 한 건은 ID·유형·시각이 없으면 만들 수 없다")
	void history_entry_requires_id_type_and_time() {
		// given / when / then
		assertThatThrownBy(() -> new LedgerHistoryEntry(null, LedgerEntryType.USE, won(1), won(-1), won(0), null,
				NOW, null, false, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new LedgerHistoryEntry(UUID.randomUUID(), null, won(1), won(-1), won(0), null,
				NOW, null, false, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new LedgerHistoryEntry(UUID.randomUUID(), LedgerEntryType.USE, won(1), won(-1),
				won(0), null, null, null, false, null)).isInstanceOf(NullPointerException.class);
	}

}
