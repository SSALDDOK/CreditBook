package com.creditbook.prepaid.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;

import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.prepaid.domain.AlreadyCancelledException;
import com.creditbook.prepaid.domain.ChargeAlreadyUsedException;
import com.creditbook.prepaid.domain.ChargeCancelNotSameDayException;
import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.prepaid.domain.IdempotencyKeyReusedException;
import com.creditbook.prepaid.domain.InvalidCancelTargetException;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryNotFoundException;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;
import com.creditbook.prepaid.domain.ReversalConflictException;

/**
 * 충전 취소 유스케이스 (REQ-7). 스프링 없이 Mockito 로 저장소만 대체한다.
 * 취소 규칙(당일·잔액·대상)과 잔액 계산 자체는 PrepaidAccountCancelChargeTest 가 보고, 여기서는 대상·기존 반제 조회 연결,
 * 서버 시각·영업 시간대 전달, 요청 키 재응답, 거절 시 저장하지 않음, 로그를 본다.
 */
@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
@Tag("REQ-7")
class ChargeCancelServiceTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	/** KST 2026-10-15 10:00 */
	private static final Instant NOW = Instant.parse("2026-10-15T01:00:00Z");
	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	private static final IdempotencyKey KEY = IdempotencyKey.of("7d1e2f3a-charge-cancel-0001");
	private static final String REASON = "금액 잘못 입력";
	private static final ChargePolicy POLICY = ChargePolicy.ofMaxAmount(300_000);

	@Mock
	PrepaidAccountRepository prepaidAccountRepository;

	@Mock
	LedgerEntryRepository ledgerEntryRepository;

	@Mock
	CustomerRepository customerRepository;

	/** 트랜잭션 경계는 목으로 대신한다 — 실제 롤백·유니크 위반은 PostgreSQL 에서 확인한다. */
	@Mock
	PlatformTransactionManager transactionManager;

	CancelService service;

	@BeforeEach
	void setUp() {
		service = new CancelService(
				new IdempotentLedgerWriter(prepaidAccountRepository, ledgerEntryRepository, transactionManager),
				ledgerEntryRepository, customerRepository, Clock.fixed(NOW, ZoneOffset.UTC), SEOUL);
	}

	/** 잔액 0원 계좌. 저장소가 이 고객의 계좌로 돌려준다. */
	private PrepaidAccount account() {
		PrepaidAccount account = PrepaidAccount.open(CUSTOMER_ID, NOW.minusSeconds(86_400 * 30));
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID)).willReturn(Optional.of(account));
		return account;
	}

	/** 계좌에 {@code chargedAt} 에 충전하고, 저장소가 그 거래를 ID 로 찾아 주게 한다. */
	private LedgerEntry storedCharge(PrepaidAccount account, long amount, Instant chargedAt) {
		LedgerEntry charge = account.charge(BigDecimal.valueOf(amount), POLICY, EMPLOYEE_ID, chargedAt, null,
				IdempotencyKey.of("setup-charge-" + amount));
		// 잔액 준비용 충전은 조회되지 않으므로 lenient 로 둔다
		lenient().when(ledgerEntryRepository.findById(charge.getId())).thenReturn(Optional.of(charge));
		return charge;
	}

	@Test
	@DisplayName("오늘 등록된 충전 건을 사유를 입력해 취소하면 원 거래는 남고 CHARGE_CANCEL 반제 거래가 추가되어 잔액이 그만큼 줄어든다")
	void cancel_charge_reduces_balance_and_adds_one_charge_cancel_entry() {
		// given
		PrepaidAccount account = account();
		storedCharge(account, 5_000, NOW.minusSeconds(86_400 * 10));
		LedgerEntry charge = storedCharge(account, 30_000, NOW.minusSeconds(3600));

		// when
		ChargeCancelResult result = service.cancelCharge(CUSTOMER_ID, charge.getId(), " " + REASON + " ",
				EMPLOYEE_ID, KEY);

		// then
		ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).add(entryCaptor.capture());
		LedgerEntry cancel = entryCaptor.getValue();
		assertThat(cancel.getType()).isEqualTo(LedgerEntryType.CHARGE_CANCEL);
		assertThat(cancel.getReversesId()).isEqualTo(charge.getId());
		assertThat(cancel.getAmount()).isEqualByComparingTo("30000");
		assertThat(cancel.getBalanceAfter()).isEqualByComparingTo("5000");
		assertThat(cancel.getMemo()).isEqualTo(REASON);
		assertThat(cancel.getIdempotencyKey()).isEqualTo(KEY.value());
		assertThat(cancel.getPerformedBy()).isEqualTo(EMPLOYEE_ID);
		assertThat(cancel.getPerformedAt()).isEqualTo(NOW);
		assertThat(account.getBalance()).isEqualByComparingTo("5000");

		assertThat(result.entryId()).isEqualTo(cancel.getId());
		assertThat(result.accountId()).isEqualTo(account.getId());
		assertThat(result.customerId()).isEqualTo(CUSTOMER_ID);
		assertThat(result.type()).isEqualTo(LedgerEntryType.CHARGE_CANCEL);
		assertThat(result.amount()).isEqualByComparingTo("30000");
		assertThat(result.balanceAfter()).isEqualByComparingTo("5000");
		assertThat(result.reason()).isEqualTo(REASON);
		assertThat(result.reversesId()).isEqualTo(charge.getId());
		assertThat(result.performedBy()).isEqualTo(EMPLOYEE_ID);
		assertThat(result.performedAt()).isEqualTo(NOW);
		assertThat(result.replayed()).isFalse();
	}

	@Test
	@DisplayName("충전 취소는 잔액을 줄이므로 고객 상태를 조회하거나 바꾸지 않는다")
	void cancel_charge_does_not_touch_customer() {
		// given
		PrepaidAccount account = account();
		LedgerEntry charge = storedCharge(account, 10_000, NOW.minusSeconds(60));

		// when
		service.cancelCharge(CUSTOMER_ID, charge.getId(), REASON, EMPLOYEE_ID, KEY);

		// then
		verifyNoInteractions(customerRepository);
	}

	@Test
	@DisplayName("당일은 서버 시각을 영업 시간대(Asia/Seoul) 날짜로 바꿔 판단한다 — UTC 로는 전날인 KST 00:30 충전도 당일로 취소된다")
	void cancel_charge_uses_business_zone_for_same_day() {
		// given: KST 2026-10-15 00:30 = UTC 2026-10-14 15:30
		PrepaidAccount account = account();
		LedgerEntry charge = storedCharge(account, 10_000, Instant.parse("2026-10-14T15:30:00Z"));

		// when
		ChargeCancelResult result = service.cancelCharge(CUSTOMER_ID, charge.getId(), REASON, EMPLOYEE_ID, KEY);

		// then
		assertThat(result.balanceAfter()).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("당일 등록 건만 취소 가능 — 어제 충전 건은 거절되고 WARN 을 남기며 아무것도 저장하지 않는다")
	void cancel_charge_rejects_previous_day_charge(CapturedOutput output) {
		// given: KST 2026-10-14 23:59:59
		PrepaidAccount account = account();
		LedgerEntry charge = storedCharge(account, 10_000, Instant.parse("2026-10-14T14:59:59Z"));

		// when / then
		assertThatThrownBy(() -> service.cancelCharge(CUSTOMER_ID, charge.getId(), REASON, EMPLOYEE_ID, KEY))
				.isInstanceOf(ChargeCancelNotSameDayException.class);
		verify(ledgerEntryRepository, never()).add(any());
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
		assertThat(output.getOut().lines().filter(line -> line.contains("charge cancel rejected: not same day")))
				.singleElement()
				.asString()
				.contains("WARN", "accountId=" + account.getId(), "targetId=" + charge.getId(),
						"chargedOn=2026-10-14", "today=2026-10-15");
	}

	@Test
	@DisplayName("취소하면 잔액이 음수가 되는 충전 건은 거절되고 WARN 을 남기며 아무것도 저장하지 않는다")
	void cancel_charge_rejects_when_already_used(CapturedOutput output) {
		// given
		PrepaidAccount account = account();
		LedgerEntry charge = storedCharge(account, 10_000, NOW.minusSeconds(60));
		account.use(BigDecimal.valueOf(4_500), EMPLOYEE_ID, NOW, null, IdempotencyKey.of("setup-use"));

		// when / then
		assertThatThrownBy(() -> service.cancelCharge(CUSTOMER_ID, charge.getId(), REASON, EMPLOYEE_ID, KEY))
				.isInstanceOfSatisfying(ChargeAlreadyUsedException.class, e -> {
					assertThat(e.getBalance()).isEqualByComparingTo("5500");
					assertThat(e.getChargeAmount()).isEqualByComparingTo("10000");
				});
		verify(ledgerEntryRepository, never()).add(any());
		assertThat(account.getBalance()).isEqualByComparingTo("5500");
		assertThat(output.getOut().lines().filter(line -> line.contains("charge cancel rejected: already used")))
				.singleElement()
				.asString()
				.contains("WARN", "balance=5500", "chargeAmount=10000");
	}

	@Test
	@DisplayName("사용 거래는 충전 취소 대상이 아니다 — 거절되고 WARN 을 남기며 아무것도 저장하지 않는다")
	void cancel_charge_rejects_use_entry(CapturedOutput output) {
		// given
		PrepaidAccount account = account();
		storedCharge(account, 10_000, NOW.minusSeconds(60));
		LedgerEntry use = account.use(BigDecimal.valueOf(3_000), EMPLOYEE_ID, NOW, null,
				IdempotencyKey.of("setup-use"));
		given(ledgerEntryRepository.findById(use.getId())).willReturn(Optional.of(use));

		// when / then
		assertThatThrownBy(() -> service.cancelCharge(CUSTOMER_ID, use.getId(), REASON, EMPLOYEE_ID, KEY))
				.isInstanceOf(InvalidCancelTargetException.class);
		verify(ledgerEntryRepository, never()).add(any());
		assertThat(account.getBalance()).isEqualByComparingTo("7000");
		assertThat(output.getOut().lines().filter(line -> line.contains("charge cancel rejected: invalid target")))
				.singleElement()
				.asString()
				.contains("WARN", "targetType=USE");
	}

	@Test
	@DisplayName("이미 취소된 충전 건은 다시 취소할 수 없고 WARN 을 남기며 아무것도 저장하지 않는다")
	void cancel_charge_rejects_already_cancelled(CapturedOutput output) {
		// given
		PrepaidAccount account = account();
		storedCharge(account, 20_000, NOW.minusSeconds(120));
		LedgerEntry charge = storedCharge(account, 10_000, NOW.minusSeconds(60));
		LedgerEntry firstCancel = account.cancelCharge(charge, Optional.empty(), REASON, EMPLOYEE_ID, NOW, SEOUL,
				IdempotencyKey.of("first-cancel"));
		given(ledgerEntryRepository.findByReversesId(charge.getId())).willReturn(Optional.of(firstCancel));

		// when / then
		assertThatThrownBy(() -> service.cancelCharge(CUSTOMER_ID, charge.getId(), REASON, EMPLOYEE_ID, KEY))
				.isInstanceOfSatisfying(AlreadyCancelledException.class,
						e -> assertThat(e.getCancelEntryId()).isEqualTo(firstCancel.getId()));
		verify(ledgerEntryRepository, never()).add(any());
		assertThat(account.getBalance()).isEqualByComparingTo("20000");
		assertThat(output.getOut().lines().filter(line -> line.contains("charge cancel rejected: already cancelled")))
				.singleElement()
				.asString()
				.contains("WARN");
	}

	@Test
	@DisplayName("거래 ID 에 해당하는 거래가 없으면 거절하고 아무것도 저장하지 않는다")
	void cancel_charge_fails_when_entry_not_found() {
		// given
		account();
		UUID unknownEntry = UUID.randomUUID();
		given(ledgerEntryRepository.findById(unknownEntry)).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> service.cancelCharge(CUSTOMER_ID, unknownEntry, REASON, EMPLOYEE_ID, KEY))
				.isInstanceOf(LedgerEntryNotFoundException.class);
		verify(ledgerEntryRepository, never()).add(any());
	}

	@Test
	@DisplayName("충전 취소가 반영되면 잔액 변동을 INFO 로 남기고 취소 사유는 남기지 않는다")
	void cancel_charge_logs_balance_change_without_reason(CapturedOutput output) {
		// given
		PrepaidAccount account = account();
		LedgerEntry charge = storedCharge(account, 10_000, NOW.minusSeconds(60));
		String reason = "로그금지사유문구";

		// when
		service.cancelCharge(CUSTOMER_ID, charge.getId(), reason, EMPLOYEE_ID, KEY);

		// then
		assertThat(output.getOut().lines().filter(line -> line.contains("balance changed")))
				.singleElement()
				.asString()
				.contains("INFO", "type=CHARGE_CANCEL", "amount=10000", "balanceAfter=0",
						"accountId=" + account.getId(), "performedBy=" + EMPLOYEE_ID, "reversesId=" + charge.getId());
		assertThat(output.getOut()).doesNotContain(reason);
	}

	@Test
	@Tag("REQ-10")
	@DisplayName("같은 요청 키로 같은 충전 취소를 다시 요청하면 다시 줄이지 않고 처음 취소 거래를 재응답으로 돌려준다")
	void cancel_charge_with_same_key_replays_first_entry() {
		// given
		PrepaidAccount account = account();
		LedgerEntry charge = storedCharge(account, 10_000, NOW.minusSeconds(60));
		ChargeCancelResult first = service.cancelCharge(CUSTOMER_ID, charge.getId(), REASON, EMPLOYEE_ID, KEY);
		ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).add(entryCaptor.capture());
		given(ledgerEntryRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(entryCaptor.getValue()));

		// when
		ChargeCancelResult again = service.cancelCharge(CUSTOMER_ID, charge.getId(), REASON, EMPLOYEE_ID, KEY);

		// then
		assertThat(again.replayed()).isTrue();
		assertThat(again.entryId()).isEqualTo(first.entryId());
		assertThat(account.getBalance()).isEqualByComparingTo("0");
		verify(ledgerEntryRepository).add(any());
	}

	@Test
	@Tag("REQ-10")
	@DisplayName("같은 요청 키로 사용 취소에 쓴 키를 충전 취소에 다시 쓰면 다른 요청으로 거절한다")
	void cancel_charge_with_key_used_by_use_cancel_is_rejected() {
		// given
		PrepaidAccount account = account();
		LedgerEntry charge = storedCharge(account, 10_000, NOW.minusSeconds(60));
		LedgerEntry use = account.use(BigDecimal.valueOf(1_000), EMPLOYEE_ID, NOW, null,
				IdempotencyKey.of("setup-use"));
		LedgerEntry useCancel = account.cancelUse(use, Optional.empty(), REASON, EMPLOYEE_ID, NOW, KEY);
		given(ledgerEntryRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(useCancel));

		// when / then
		assertThatThrownBy(() -> service.cancelCharge(CUSTOMER_ID, charge.getId(), REASON, EMPLOYEE_ID, KEY))
				.isInstanceOf(IdempotencyKeyReusedException.class);
		verify(ledgerEntryRepository, never()).add(any());
	}

	@Test
	@DisplayName("같은 충전 건 동시 취소로 반제 UNIQUE 에 걸리면 다시 판정해 이미 취소됨으로 거절한다")
	void cancel_charge_reversal_conflict_is_revalidated_as_already_cancelled() {
		// given: 첫 저장은 반제 UNIQUE 위반, 다시 판정할 때는 먼저 커밋된(다른 요청의) 반제가 보인다
		PrepaidAccount account = account();
		storedCharge(account, 20_000, NOW.minusSeconds(120));
		LedgerEntry charge = storedCharge(account, 10_000, NOW.minusSeconds(60));
		LedgerEntry committedCancel = account.cancelCharge(charge, Optional.empty(), REASON, EMPLOYEE_ID, NOW, SEOUL,
				IdempotencyKey.of("winner"));
		willThrow(new ReversalConflictException(new RuntimeException("duplicate key")))
				.given(ledgerEntryRepository).add(any());
		given(ledgerEntryRepository.findByReversesId(charge.getId()))
				.willReturn(Optional.empty(), Optional.of(committedCancel));

		// when / then
		assertThatThrownBy(() -> service.cancelCharge(CUSTOMER_ID, charge.getId(), REASON, EMPLOYEE_ID, KEY))
				.isInstanceOf(AlreadyCancelledException.class);
		verify(ledgerEntryRepository, times(1)).add(any());
	}

}
