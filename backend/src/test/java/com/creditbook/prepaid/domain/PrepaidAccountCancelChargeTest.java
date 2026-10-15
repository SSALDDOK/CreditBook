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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 충전 취소 (REQ-7). '당일' 은 영업 시간대(Asia/Seoul) 달력 날짜로 판단한다 — 서버·DB 시각은 UTC 다.
 */
@Tag("REQ-7")
class PrepaidAccountCancelChargeTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	/** KST 2026-10-15 10:00 */
	private static final Instant CHARGED_AT = Instant.parse("2026-10-15T01:00:00Z");
	/** 같은 날 KST 2026-10-15 11:00 */
	private static final Instant SAME_DAY = CHARGED_AT.plusSeconds(3600);
	private static final String REASON = "금액 잘못 입력";

	/** 잔액 {@code priorBalance} 원인 계좌에 {@code chargeAmount} 원을 {@code chargedAt} 에 충전한다. */
	private static ChargedAccount charged(long priorBalance, long chargeAmount, Instant chargedAt) {
		PrepaidAccount account = accountWithBalance(priorBalance);
		LedgerEntry charge = account.charge(won(chargeAmount), POLICY, EMPLOYEE_ID, chargedAt, "현금 선결제");
		return new ChargedAccount(account, charge);
	}

	private record ChargedAccount(PrepaidAccount account, LedgerEntry charge) {
	}

	@Test
	@DisplayName("오늘 등록된 충전 건을 사유를 입력해 취소하면 원 거래는 남고 CHARGE_CANCEL 반제 거래가 추가되어 잔액이 그만큼 줄어든다")
	void cancel_charge_adds_charge_cancel_entry_and_reduces_balance() {
		// given
		ChargedAccount given = charged(5_000, 30_000, CHARGED_AT);
		PrepaidAccount account = given.account();
		LedgerEntry charge = given.charge();

		// when
		LedgerEntry cancel = account.cancelCharge(charge, Optional.empty(), "  " + REASON + " ", EMPLOYEE_ID,
				SAME_DAY, SEOUL);

		// then
		assertThat(account.getBalance()).isEqualByComparingTo("5000");
		assertThat(account.getUpdatedAt()).isEqualTo(SAME_DAY);
		assertThat(cancel.getType()).isEqualTo(LedgerEntryType.CHARGE_CANCEL);
		assertThat(cancel.getAccountId()).isEqualTo(account.getId());
		assertThat(cancel.getReversesId()).isEqualTo(charge.getId());
		assertThat(cancel.getAmount()).isEqualByComparingTo("30000");
		assertThat(cancel.getSignedAmount()).isEqualByComparingTo("-30000");
		assertThat(cancel.getBalanceAfter()).isEqualByComparingTo("5000");
		assertThat(cancel.getMemo()).isEqualTo(REASON);
		assertThat(cancel.getPerformedBy()).isEqualTo(EMPLOYEE_ID);
		assertThat(cancel.getPerformedAt()).isEqualTo(SAME_DAY);
		// 원 거래는 바뀌지 않는다
		assertThat(charge.getType()).isEqualTo(LedgerEntryType.CHARGE);
		assertThat(charge.getAmount()).isEqualByComparingTo("30000");
		assertThat(charge.getBalanceAfter()).isEqualByComparingTo("35000");
		assertThat(charge.getMemo()).isEqualTo("현금 선결제");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@CsvSource(delimiter = '|', value = {
			// 경우                          | 충전 전 잔액 | 충전 후 사용액 | 허용 | 취소 후 잔액(거절이면 그대로)
			"잔액 = 충전액                    | 0 | 0 | true  | 0",
			"잔액 = 충전액 − 1                | 0 | 1 | false | 9999",
			"잔액 = 충전액 + 1                | 1 | 0 | true  | 1" })
	@DisplayName("잔액이 충전액 이상일 때만 충전 취소가 허용된다 — 잔액이 충전액보다 적으면 CHARGE_ALREADY_USED 로 거절된다")
	void cancel_charge_balance_boundary(String label, long priorBalance, long usedAfterCharge, boolean allowed,
			String expectedBalance) {
		// given: 10,000원 충전 후 일부 사용
		ChargedAccount given = charged(priorBalance, 10_000, CHARGED_AT);
		PrepaidAccount account = given.account();
		if (usedAfterCharge > 0) {
			account.use(won(usedAfterCharge), EMPLOYEE_ID, CHARGED_AT, null);
		}

		// when / then
		if (allowed) {
			LedgerEntry cancel = account.cancelCharge(given.charge(), Optional.empty(), REASON, EMPLOYEE_ID,
					SAME_DAY, SEOUL);
			assertThat(cancel.getBalanceAfter()).isEqualByComparingTo(expectedBalance);
		}
		else {
			assertThatThrownBy(() -> account.cancelCharge(given.charge(), Optional.empty(), REASON, EMPLOYEE_ID,
					SAME_DAY, SEOUL))
					.isInstanceOf(ChargeAlreadyUsedException.class);
		}
		assertThat(account.getBalance()).isEqualByComparingTo(expectedBalance);
	}

	@Test
	@DisplayName("취소하면 잔액이 음수가 되는 충전 건은 취소할 수 없다 — 현재 잔액과 충전액을 담아 거절한다")
	void cancel_charge_rejected_when_balance_below_charge_amount_carries_amounts() {
		// given
		ChargedAccount given = charged(0, 10_000, CHARGED_AT);
		PrepaidAccount account = given.account();
		account.use(won(4_500), EMPLOYEE_ID, CHARGED_AT, null);

		// when / then
		assertThatThrownBy(() -> account.cancelCharge(given.charge(), Optional.empty(), REASON, EMPLOYEE_ID,
				SAME_DAY, SEOUL))
				.isInstanceOfSatisfying(ChargeAlreadyUsedException.class, e -> {
					assertThat(e.getBalance()).isEqualByComparingTo("5500");
					assertThat(e.getChargeAmount()).isEqualByComparingTo("10000");
					assertThat(e.getTargetId()).isEqualTo(given.charge().getId());
				})
				.hasMessage("잔액이 부족해 취소할 수 없어요. 먼저 사용 건을 취소해 주세요.");
		assertThat(account.getBalance()).isEqualByComparingTo("5500");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@CsvSource(delimiter = '|', value = {
			// 경우                                         | 충전 시각(UTC)         | 취소 시각(UTC)         | 허용
			"KST 같은 날 00:00:00 충전 → 23:59:59 취소        | 2026-10-14T15:00:00Z | 2026-10-15T14:59:59Z | true",
			"KST 다음날 00:00:00 취소                         | 2026-10-15T01:00:00Z | 2026-10-15T15:00:00Z | false",
			"UTC 로는 전날 충전이지만 KST 로는 같은 날          | 2026-10-14T15:30:00Z | 2026-10-15T01:00:00Z | true",
			"UTC 로는 같은 날이지만 KST 로는 다음날             | 2026-10-15T14:00:00Z | 2026-10-15T16:00:00Z | false",
			"하루 전 충전                                     | 2026-10-14T01:00:00Z | 2026-10-15T01:00:00Z | false" })
	@DisplayName("당일(Asia/Seoul 달력 날짜) 등록 건만 취소할 수 있다 — 다른 날짜의 충전 건은 CHARGE_CANCEL_NOT_SAME_DAY 로 거절된다")
	void cancel_charge_same_day_boundary(String label, Instant chargedAt, Instant cancelledAt, boolean allowed) {
		// given
		ChargedAccount given = charged(0, 10_000, chargedAt);
		PrepaidAccount account = given.account();

		// when / then
		if (allowed) {
			LedgerEntry cancel = account.cancelCharge(given.charge(), Optional.empty(), REASON, EMPLOYEE_ID,
					cancelledAt, SEOUL);
			assertThat(cancel.getType()).isEqualTo(LedgerEntryType.CHARGE_CANCEL);
			assertThat(account.getBalance()).isEqualByComparingTo("0");
		}
		else {
			assertThatThrownBy(() -> account.cancelCharge(given.charge(), Optional.empty(), REASON, EMPLOYEE_ID,
					cancelledAt, SEOUL))
					.isInstanceOfSatisfying(ChargeCancelNotSameDayException.class,
							e -> assertThat(e.getTargetId()).isEqualTo(given.charge().getId()))
					.hasMessage("당일 충전 건만 취소할 수 있습니다.");
			assertThat(account.getBalance()).isEqualByComparingTo("10000");
		}
	}

	@Test
	@DisplayName("당일이 아니면서 잔액도 부족한 충전 건은 당일이 아니라는 이유로 거절된다")
	void cancel_charge_checks_same_day_before_balance() {
		// given
		ChargedAccount given = charged(0, 10_000, CHARGED_AT);
		PrepaidAccount account = given.account();
		account.use(won(1_000), EMPLOYEE_ID, CHARGED_AT, null);
		Instant nextDay = CHARGED_AT.plusSeconds(24 * 3600);

		// when / then
		assertThatThrownBy(() -> account.cancelCharge(given.charge(), Optional.empty(), REASON, EMPLOYEE_ID,
				nextDay, SEOUL))
				.isInstanceOf(ChargeCancelNotSameDayException.class);
		assertThat(account.getBalance()).isEqualByComparingTo("9000");
	}

	@ParameterizedTest(name = "사유 [{0}] 로는 취소할 수 없다")
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "\t" })
	@DisplayName("충전 취소는 사유가 필수다 — 사유가 없으면 거절되고 잔액은 그대로다")
	void cancel_charge_requires_reason(String reason) {
		// given
		ChargedAccount given = charged(0, 10_000, CHARGED_AT);
		PrepaidAccount account = given.account();

		// when / then
		assertThatThrownBy(() -> account.cancelCharge(given.charge(), Optional.empty(), reason, EMPLOYEE_ID,
				SAME_DAY, SEOUL))
				.isInstanceOf(CancelReasonRequiredException.class);
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@DisplayName("취소 사유는 200자까지 저장된다")
	void cancel_charge_accepts_reason_of_max_length() {
		// given
		ChargedAccount given = charged(0, 10_000, CHARGED_AT);
		String reason = "가".repeat(LedgerEntry.MEMO_MAX_LENGTH);

		// when
		LedgerEntry cancel = given.account().cancelCharge(given.charge(), Optional.empty(), reason, EMPLOYEE_ID,
				SAME_DAY, SEOUL);

		// then
		assertThat(cancel.getMemo()).isEqualTo(reason);
	}

	@Test
	@DisplayName("반제 대상은 CHARGE 거래만이다 — 사용 거래는 충전 취소 대상이 아니다")
	void cancel_charge_rejects_use_entry() {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		LedgerEntry use = account.use(won(3_000), EMPLOYEE_ID, CHARGED_AT, null);

		// when / then
		assertThatThrownBy(() -> account.cancelCharge(use, Optional.empty(), REASON, EMPLOYEE_ID, SAME_DAY, SEOUL))
				.isInstanceOf(InvalidCancelTargetException.class)
				.hasMessage("CHARGE_CANCEL 는 CHARGE 거래만 취소할 수 있습니다.");
		assertThat(account.getBalance()).isEqualByComparingTo("7000");
	}

	@Test
	@DisplayName("반제 행은 다시 반제할 수 없다 — 충전 취소 거래를 다시 취소하면 거절된다")
	void cancel_charge_rejects_reversal_entry() {
		// given
		ChargedAccount given = charged(10_000, 10_000, CHARGED_AT);
		PrepaidAccount account = given.account();
		LedgerEntry cancel = account.cancelCharge(given.charge(), Optional.empty(), REASON, EMPLOYEE_ID, SAME_DAY,
				SEOUL);

		// when / then
		assertThatThrownBy(() -> account.cancelCharge(cancel, Optional.empty(), REASON, EMPLOYEE_ID, SAME_DAY, SEOUL))
				.isInstanceOf(InvalidCancelTargetException.class)
				.hasMessage("취소 거래는 다시 취소할 수 없습니다.");
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@DisplayName("반제 대상은 같은 계좌의 거래만이다 — 다른 계좌의 충전 건은 취소할 수 없다")
	void cancel_charge_rejects_entry_of_other_account() {
		// given
		ChargedAccount other = charged(0, 10_000, CHARGED_AT);
		PrepaidAccount account = accountWithBalance(50_000);

		// when / then
		assertThatThrownBy(() -> account.cancelCharge(other.charge(), Optional.empty(), REASON, EMPLOYEE_ID,
				SAME_DAY, SEOUL))
				.isInstanceOf(InvalidCancelTargetException.class)
				.hasMessage("다른 계좌의 거래는 취소할 수 없습니다.");
		assertThat(account.getBalance()).isEqualByComparingTo("50000");
		assertThat(other.account().getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@DisplayName("원 1건당 반제 1건 — 이미 취소된 충전 건은 다시 취소할 수 없다")
	void cancel_charge_rejects_already_cancelled_charge() {
		// given
		ChargedAccount given = charged(20_000, 10_000, CHARGED_AT);
		PrepaidAccount account = given.account();
		LedgerEntry firstCancel = account.cancelCharge(given.charge(), Optional.empty(), REASON, EMPLOYEE_ID,
				SAME_DAY, SEOUL);

		// when / then: 서비스가 조회한 기존 반제 거래를 넘긴다
		assertThatThrownBy(() -> account.cancelCharge(given.charge(), Optional.of(firstCancel), REASON, EMPLOYEE_ID,
				SAME_DAY, SEOUL))
				.isInstanceOfSatisfying(AlreadyCancelledException.class, e -> {
					assertThat(e.getTargetId()).isEqualTo(given.charge().getId());
					assertThat(e.getCancelEntryId()).isEqualTo(firstCancel.getId());
					assertThat(e.getMessage()).isEqualTo("이미 취소된 충전 건입니다.");
				});
		assertThat(account.getBalance()).isEqualByComparingTo("20000");
	}

	@Test
	@DisplayName("이미 취소된 충전 건은 날짜가 지났어도 이미 취소됐다는 이유로 거절된다")
	void cancel_charge_reports_already_cancelled_before_same_day() {
		// given
		ChargedAccount given = charged(20_000, 10_000, CHARGED_AT);
		PrepaidAccount account = given.account();
		LedgerEntry firstCancel = account.cancelCharge(given.charge(), Optional.empty(), REASON, EMPLOYEE_ID,
				SAME_DAY, SEOUL);
		Instant nextDay = CHARGED_AT.plusSeconds(24 * 3600);

		// when / then
		assertThatThrownBy(() -> account.cancelCharge(given.charge(), Optional.of(firstCancel), REASON, EMPLOYEE_ID,
				nextDay, SEOUL))
				.isInstanceOf(AlreadyCancelledException.class);
		assertThat(account.getBalance()).isEqualByComparingTo("20000");
	}

	@Test
	@Tag("REQ-10")
	@DisplayName("충전 취소 거래에는 요청 키가 그대로 저장된다")
	void cancel_charge_stores_idempotency_key() {
		// given
		ChargedAccount given = charged(0, 10_000, CHARGED_AT);
		IdempotencyKey key = IdempotencyKey.of("charge-cancel-0001");

		// when
		LedgerEntry cancel = given.account().cancelCharge(given.charge(), Optional.empty(), REASON, EMPLOYEE_ID,
				SAME_DAY, SEOUL, key);

		// then
		assertThat(cancel.getIdempotencyKey()).isEqualTo(key.value());
		assertThat(given.account().getBalance()).isEqualByComparingTo("0");
	}

	@Test
	@Tag("REQ-10")
	@DisplayName("API 경로의 충전 취소는 요청 키 없이 호출할 수 없고 잔액도 바뀌지 않는다")
	void public_cancel_charge_requires_key() {
		// given
		ChargedAccount given = charged(0, 10_000, CHARGED_AT);

		// when / then
		assertThatThrownBy(() -> given.account().cancelCharge(given.charge(), Optional.empty(), REASON, EMPLOYEE_ID,
				SAME_DAY, SEOUL, null))
				.isInstanceOf(NullPointerException.class);
		assertThat(given.account().getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@DisplayName("영업 시간대 없이는 당일 여부를 판단할 수 없어 호출 오류로 거절한다")
	void cancel_charge_requires_business_zone() {
		// given
		ChargedAccount given = charged(0, 10_000, CHARGED_AT);

		// when / then
		assertThatThrownBy(() -> given.account().cancelCharge(given.charge(), Optional.empty(), REASON, EMPLOYEE_ID,
				SAME_DAY, null))
				.isInstanceOf(NullPointerException.class);
		assertThat(given.account().getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@Tag("REQ-19")
	@DisplayName("충전·사용·충전 취소를 섞어도 잔액은 거래 합계와 일치한다")
	void balance_equals_sum_of_entries_with_charge_cancel() {
		// given
		PrepaidAccount account = accountWithBalance(0);
		LedgerEntry charge1 = account.charge(won(20_000), POLICY, EMPLOYEE_ID, CHARGED_AT, null);
		LedgerEntry use = account.use(won(4_500), EMPLOYEE_ID, CHARGED_AT, null);
		LedgerEntry charge2 = account.charge(won(10_000), POLICY, EMPLOYEE_ID, CHARGED_AT, null);

		// when
		LedgerEntry cancel = account.cancelCharge(charge2, Optional.empty(), REASON, EMPLOYEE_ID, SAME_DAY, SEOUL);

		// then
		BigDecimal sum = charge1.getSignedAmount().add(use.getSignedAmount()).add(charge2.getSignedAmount())
				.add(cancel.getSignedAmount());
		assertThat(account.getBalance()).isEqualByComparingTo(sum);
		assertThat(account.getBalance()).isEqualByComparingTo("15500");
		assertThat(cancel.getBalanceAfter()).isEqualByComparingTo("15500");
	}

}
