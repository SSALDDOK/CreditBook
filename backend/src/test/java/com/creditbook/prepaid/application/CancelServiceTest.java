package com.creditbook.prepaid.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;

import com.creditbook.prepaid.domain.AlreadyCancelledException;
import com.creditbook.prepaid.domain.CancelReasonRequiredException;
import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.prepaid.domain.IdempotencyKeyReusedException;
import com.creditbook.prepaid.domain.InvalidCancelTargetException;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryNotFoundException;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 사용 취소 유스케이스. 스프링 없이 Mockito 로 저장소만 대체한다.
 * 취소 대상 규칙과 잔액 계산 자체는 PrepaidAccount 도메인 테스트가 보고, 여기서는 대상·기존 반제 조회 연결,
 * 시각·처리 직원·요청 키 기록, 거절 시 아무것도 저장하지 않는지를 본다.
 */
@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
@Tag("REQ-34")
class CancelServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-12T01:00:00Z");
	private static final Instant USED_AT = NOW.minusSeconds(3600);
	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	private static final IdempotencyKey KEY = IdempotencyKey.of("7d1e2f3a-cancel-0001");
	private static final String REASON = "주문 착오";
	/** 준비용 충전 한도 — 시험에 필요한 잔액을 한 번에 만든다. */
	private static final ChargePolicy SETUP_POLICY = ChargePolicy.ofMaxAmount(300_000);

	@Mock
	PrepaidAccountRepository prepaidAccountRepository;

	@Mock
	LedgerEntryRepository ledgerEntryRepository;

	/** 트랜잭션 경계는 목으로 대신한다 — 실제 롤백·유니크 위반은 PostgreSQL 에서 확인한다. */
	@Mock
	PlatformTransactionManager transactionManager;

	CancelService service;

	@BeforeEach
	void setUp() {
		service = new CancelService(
				new IdempotentLedgerWriter(prepaidAccountRepository, ledgerEntryRepository, transactionManager),
				ledgerEntryRepository, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	/** 잔액이 {@code balance} 원인 계좌. 공개 API(open → charge)로만 만들고 저장소가 이 고객의 계좌로 돌려준다. */
	private PrepaidAccount accountWithBalance(long balance) {
		PrepaidAccount account = PrepaidAccount.open(CUSTOMER_ID, USED_AT);
		account.charge(BigDecimal.valueOf(balance), SETUP_POLICY, EMPLOYEE_ID, USED_AT, null,
				IdempotencyKey.of("setup-charge"));
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID)).willReturn(Optional.of(account));
		return account;
	}

	/** 계좌에서 사용하고, 저장소가 그 거래를 ID 로 찾아 주게 한다. */
	private LedgerEntry storedUse(PrepaidAccount account, long amount) {
		LedgerEntry use = account.use(BigDecimal.valueOf(amount), EMPLOYEE_ID, USED_AT, "음료",
				IdempotencyKey.of("setup-use"));
		given(ledgerEntryRepository.findById(use.getId())).willReturn(Optional.of(use));
		return use;
	}

	@Test
	@DisplayName("사용 건을 취소하면 원 거래는 그대로 두고 USE_CANCEL 반제 거래가 추가되어 잔액이 사용 금액만큼 복구된다")
	void cancel_use_restores_balance_and_adds_one_use_cancel_entry() {
		// given
		PrepaidAccount account = accountWithBalance(50_000);
		LedgerEntry use = storedUse(account, 4_500);

		// when
		UseCancelResult result = service.cancelUse(CUSTOMER_ID, use.getId(), "  " + REASON + " ", EMPLOYEE_ID, KEY);

		// then
		ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).add(entryCaptor.capture());
		LedgerEntry cancel = entryCaptor.getValue();
		assertThat(cancel.getType()).isEqualTo(LedgerEntryType.USE_CANCEL);
		assertThat(cancel.getAccountId()).isEqualTo(account.getId());
		assertThat(cancel.getReversesId()).isEqualTo(use.getId());
		assertThat(cancel.getAmount()).isEqualByComparingTo("4500");
		assertThat(cancel.getBalanceAfter()).isEqualByComparingTo("50000");
		assertThat(cancel.getMemo()).isEqualTo(REASON);
		assertThat(cancel.getIdempotencyKey()).isEqualTo(KEY.value());
		assertThat(account.getBalance()).isEqualByComparingTo("50000");
		// 원 거래는 바뀌지 않는다
		assertThat(use.getType()).isEqualTo(LedgerEntryType.USE);
		assertThat(use.getBalanceAfter()).isEqualByComparingTo("45500");

		assertThat(result.entryId()).isEqualTo(cancel.getId());
		assertThat(result.accountId()).isEqualTo(account.getId());
		assertThat(result.customerId()).isEqualTo(CUSTOMER_ID);
		assertThat(result.type()).isEqualTo(LedgerEntryType.USE_CANCEL);
		assertThat(result.amount()).isEqualByComparingTo("4500");
		assertThat(result.balanceAfter()).isEqualByComparingTo("50000");
		assertThat(result.reason()).isEqualTo(REASON);
		assertThat(result.reversesId()).isEqualTo(use.getId());
		assertThat(result.replayed()).isFalse();
	}

	@Test
	@Tag("REQ-20")
	@DisplayName("사용 취소 거래에는 처리 직원과 서버 시각이 기록된다")
	void cancel_use_records_performer_and_server_time() {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		LedgerEntry use = storedUse(account, 3_000);

		// when
		UseCancelResult result = service.cancelUse(CUSTOMER_ID, use.getId(), REASON, EMPLOYEE_ID, KEY);

		// then
		ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).add(entryCaptor.capture());
		assertThat(entryCaptor.getValue().getPerformedBy()).isEqualTo(EMPLOYEE_ID);
		assertThat(entryCaptor.getValue().getPerformedAt()).isEqualTo(NOW);
		assertThat(result.performedBy()).isEqualTo(EMPLOYEE_ID);
		assertThat(result.performedAt()).isEqualTo(NOW);
		assertThat(account.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("취소가 반영되면 잔액 변동을 INFO 로 남기고 취소 사유는 남기지 않는다")
	void cancel_use_logs_balance_change_without_reason(CapturedOutput output) {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		LedgerEntry use = storedUse(account, 3_000);
		String reason = "로그금지사유문구";

		// when
		service.cancelUse(CUSTOMER_ID, use.getId(), reason, EMPLOYEE_ID, KEY);

		// then
		assertThat(output.getOut().lines().filter(line -> line.contains("balance changed")))
				.singleElement()
				.asString()
				.contains("INFO", "type=USE_CANCEL", "amount=3000", "balanceAfter=10000",
						"accountId=" + account.getId(), "performedBy=" + EMPLOYEE_ID)
				.doesNotContain(reason);
		assertThat(output.getOut()).doesNotContain(reason);
	}

	@Test
	@DisplayName("계좌가 없는 고객이면 거래를 조회하지 않고 거절하며 아무것도 저장하지 않는다")
	void cancel_use_fails_when_account_not_found() {
		// given
		UUID unknownCustomer = UUID.randomUUID();
		given(prepaidAccountRepository.findByCustomerId(unknownCustomer)).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> service.cancelUse(unknownCustomer, UUID.randomUUID(), REASON, EMPLOYEE_ID, KEY))
				.isInstanceOf(PrepaidAccountNotFoundException.class);
		verify(ledgerEntryRepository, never()).findById(any());
		verify(ledgerEntryRepository, never()).add(any());
	}

	@Test
	@DisplayName("거래 ID 에 해당하는 거래가 없으면 거절하고 아무것도 저장하지 않는다")
	void cancel_use_fails_when_entry_not_found() {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		UUID unknownEntry = UUID.randomUUID();
		given(ledgerEntryRepository.findById(unknownEntry)).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> service.cancelUse(CUSTOMER_ID, unknownEntry, REASON, EMPLOYEE_ID, KEY))
				.isInstanceOfSatisfying(LedgerEntryNotFoundException.class,
						e -> assertThat(e.getEntryId()).isEqualTo(unknownEntry));
		verify(ledgerEntryRepository, never()).add(any());
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@DisplayName("이미 취소된 사용 건은 다시 취소할 수 없고 먼저 만든 취소 거래를 알려 주며 아무것도 저장하지 않는다")
	void cancel_use_rejects_already_cancelled_use(CapturedOutput output) {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		LedgerEntry use = storedUse(account, 3_000);
		LedgerEntry firstCancel = account.cancelUse(use, Optional.empty(), REASON, EMPLOYEE_ID, USED_AT,
				IdempotencyKey.of("first-cancel"));
		given(ledgerEntryRepository.findByReversesId(use.getId())).willReturn(Optional.of(firstCancel));

		// when / then
		assertThatThrownBy(() -> service.cancelUse(CUSTOMER_ID, use.getId(), REASON, EMPLOYEE_ID, KEY))
				.isInstanceOfSatisfying(AlreadyCancelledException.class,
						e -> assertThat(e.getCancelEntryId()).isEqualTo(firstCancel.getId()))
				.hasMessage("이미 취소된 사용 건입니다.");
		verify(ledgerEntryRepository, never()).add(any());
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
		assertThat(output.getOut().lines().filter(line -> line.contains("use cancel rejected: already cancelled")))
				.singleElement()
				.asString()
				.contains("WARN");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "다른 고객의 사용 건", "충전 거래", "취소 거래(반제 행)" })
	@DisplayName("다른 고객의 거래·USE 가 아닌 거래·취소 거래는 사용 취소 대상이 아니며 아무것도 저장하지 않는다")
	void cancel_use_rejects_invalid_target(String targetKind, CapturedOutput output) {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		LedgerEntry target = switch (targetKind) {
			case "다른 고객의 사용 건" -> {
				PrepaidAccount other = PrepaidAccount.open(UUID.randomUUID(), USED_AT);
				other.charge(BigDecimal.valueOf(10_000), SETUP_POLICY, EMPLOYEE_ID, USED_AT, null,
						IdempotencyKey.of("other-charge"));
				yield other.use(BigDecimal.valueOf(3_000), EMPLOYEE_ID, USED_AT, null, IdempotencyKey.of("other-use"));
			}
			case "충전 거래" -> account.charge(BigDecimal.valueOf(1_000), SETUP_POLICY, EMPLOYEE_ID, USED_AT, null,
					IdempotencyKey.of("target-charge"));
			default -> {
				LedgerEntry use = account.use(BigDecimal.valueOf(1_000), EMPLOYEE_ID, USED_AT, null,
						IdempotencyKey.of("target-use"));
				yield account.cancelUse(use, Optional.empty(), REASON, EMPLOYEE_ID, USED_AT,
						IdempotencyKey.of("target-cancel"));
			}
		};
		given(ledgerEntryRepository.findById(target.getId())).willReturn(Optional.of(target));
		BigDecimal balanceBefore = account.getBalance();

		// when / then
		assertThatThrownBy(() -> service.cancelUse(CUSTOMER_ID, target.getId(), REASON, EMPLOYEE_ID, KEY))
				.isInstanceOf(InvalidCancelTargetException.class);
		verify(ledgerEntryRepository, never()).add(any());
		assertThat(account.getBalance()).isEqualByComparingTo(balanceBefore);
		assertThat(output.getOut().lines().filter(line -> line.contains("use cancel rejected: invalid target")))
				.singleElement()
				.asString()
				.contains("WARN");
	}

	@ParameterizedTest(name = "사유 [{0}] 로는 취소할 수 없다")
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "\t" })
	@DisplayName("사용 취소는 사유가 필수이며 사유가 없으면 아무것도 저장하지 않는다")
	void cancel_use_requires_reason(String reason) {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		LedgerEntry use = storedUse(account, 3_000);

		// when / then
		assertThatThrownBy(() -> service.cancelUse(CUSTOMER_ID, use.getId(), reason, EMPLOYEE_ID, KEY))
				.isInstanceOf(CancelReasonRequiredException.class);
		verify(ledgerEntryRepository, never()).add(any());
		assertThat(account.getBalance()).isEqualByComparingTo("7000");
	}

	@Test
	@Tag("REQ-10")
	@DisplayName("같은 요청 키로 같은 사용 취소를 다시 요청하면 다시 복구하지 않고 처음 취소 거래를 재응답으로 돌려준다")
	void cancel_use_with_same_key_replays_first_entry() {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		LedgerEntry use = storedUse(account, 4_000);
		UseCancelResult first = service.cancelUse(CUSTOMER_ID, use.getId(), REASON, EMPLOYEE_ID, KEY);
		ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).add(entryCaptor.capture());
		given(ledgerEntryRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(entryCaptor.getValue()));

		// when
		UseCancelResult again = service.cancelUse(CUSTOMER_ID, use.getId(), " " + REASON + " ", EMPLOYEE_ID, KEY);

		// then
		assertThat(first.replayed()).isFalse();
		assertThat(again.replayed()).isTrue();
		assertThat(again.entryId()).isEqualTo(first.entryId());
		assertThat(again.reversesId()).isEqualTo(use.getId());
		assertThat(again.balanceAfter()).isEqualByComparingTo("10000");
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
		verify(ledgerEntryRepository).add(any());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = { "다른 사유", "다른 대상 거래" })
	@Tag("REQ-10")
	@DisplayName("같은 요청 키로 사유나 대상 거래가 다른 사용 취소를 요청하면 거절하고 아무것도 저장하지 않는다")
	void cancel_use_with_same_key_for_different_request_is_rejected(String difference) {
		// given
		PrepaidAccount account = accountWithBalance(10_000);
		LedgerEntry use = account.use(BigDecimal.valueOf(4_000), EMPLOYEE_ID, USED_AT, null,
				IdempotencyKey.of("setup-use"));
		LedgerEntry stored = account.cancelUse(use, Optional.empty(), REASON, EMPLOYEE_ID, USED_AT, KEY);
		given(ledgerEntryRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(stored));
		UUID targetId = difference.equals("다른 대상 거래") ? UUID.randomUUID() : use.getId();
		String reason = difference.equals("다른 사유") ? "고객 요청" : REASON;

		// when / then
		assertThatThrownBy(() -> service.cancelUse(CUSTOMER_ID, targetId, reason, EMPLOYEE_ID, KEY))
				.isInstanceOfSatisfying(IdempotencyKeyReusedException.class,
						e -> assertThat(e.getStoredEntryId()).isEqualTo(stored.getId()));
		verify(ledgerEntryRepository, never()).add(any());
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
	}

}
