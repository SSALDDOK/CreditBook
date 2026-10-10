package com.creditbook.prepaid.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.prepaid.domain.IdempotencyKeyConflictException;
import com.creditbook.prepaid.domain.IdempotencyKeyReusedException;
import com.creditbook.prepaid.domain.IdempotentRequest;
import com.creditbook.prepaid.domain.InsufficientBalanceException;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 요청 키 처리 순서 (REQ-10). 저장소와 트랜잭션 관리자는 목으로 대신한다.
 * "같은 요청인가" 판단 규칙 자체는 LedgerEntryIdempotencyTest 가, 실제 유니크 위반·롤백은 PostgreSQL 에서 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@Tag("REQ-10")
class IdempotentLedgerWriterTest {

	private static final Instant NOW = Instant.parse("2026-10-11T01:00:00Z");
	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID OTHER_CUSTOMER_ID = UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	private static final IdempotencyKey KEY = IdempotencyKey.of("3f2b8c1d-key-0001");
	private static final ChargePolicy POLICY = ChargePolicy.ofMaxAmount(300_000);

	@Mock
	PrepaidAccountRepository prepaidAccountRepository;

	@Mock
	LedgerEntryRepository ledgerEntryRepository;

	@Mock
	PlatformTransactionManager transactionManager;

	IdempotentLedgerWriter writer;

	PrepaidAccount account;

	@BeforeEach
	void setUp() {
		writer = new IdempotentLedgerWriter(prepaidAccountRepository, ledgerEntryRepository, transactionManager);
		account = PrepaidAccount.open(CUSTOMER_ID, NOW.minusSeconds(3600));
	}

	private static IdempotentRequest chargeRequest(long amount, String memo) {
		return IdempotentRequest.original(LedgerEntryType.CHARGE, BigDecimal.valueOf(amount), memo);
	}

	private Function<PrepaidAccount, LedgerEntry> chargeOperation(long amount, String memo) {
		return target -> target.charge(BigDecimal.valueOf(amount), POLICY, EMPLOYEE_ID, NOW, memo, KEY);
	}

	/** 이 키로 이미 저장된 10,000원 충전 거래 (계좌 잔액 10,000원). */
	private LedgerEntry storedCharge() {
		return account.charge(BigDecimal.valueOf(10_000), POLICY, EMPLOYEE_ID, NOW, "음료", KEY);
	}

	@Test
	@DisplayName("처음 온 요청 키면 거래를 만들어 요청 키와 함께 저장한다")
	void first_request_writes_entry_with_key() {
		// given
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID)).willReturn(Optional.of(account));

		// when
		LedgerWriteOutcome outcome = writer.write(CUSTOMER_ID, KEY, chargeRequest(10_000, "음료"),
				chargeOperation(10_000, "음료"));

		// then
		assertThat(outcome.replayed()).isFalse();
		verify(ledgerEntryRepository).add(outcome.entry());
		assertThat(outcome.entry().getIdempotencyKey()).isEqualTo(KEY.value());
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@DisplayName("같은 요청 키로 같은 요청이 다시 오면 새 거래 없이 처음 거래를 돌려주고 잔액은 바뀌지 않는다")
	void same_key_same_request_replays_stored_entry() {
		// given
		LedgerEntry stored = storedCharge();
		given(ledgerEntryRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(stored));
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID)).willReturn(Optional.of(account));

		// when
		LedgerWriteOutcome outcome = writer.write(CUSTOMER_ID, KEY, chargeRequest(10_000, "음료"),
				chargeOperation(10_000, "음료"));

		// then
		assertThat(outcome.replayed()).isTrue();
		assertThat(outcome.entry()).isSameAs(stored);
		assertThat(outcome.entry().getBalanceAfter()).isEqualByComparingTo("10000");
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
		verify(ledgerEntryRepository, never()).add(any());
	}

	@ParameterizedTest(name = "[{index}] 금액 {0}, 메모 {1}")
	@CsvSource(nullValues = "NULL", value = { "10001, 음료", "10000, 베이커리", "10000, NULL" })
	@DisplayName("같은 요청 키로 금액·메모가 다른 요청이 오면 거절하고 거래를 만들지 않는다")
	void same_key_different_request_is_rejected(long amount, String memo) {
		// given
		LedgerEntry stored = storedCharge();
		given(ledgerEntryRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(stored));
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID)).willReturn(Optional.of(account));

		// when / then
		assertThatThrownBy(() -> writer.write(CUSTOMER_ID, KEY, chargeRequest(amount, memo),
				chargeOperation(amount, memo)))
				.isInstanceOf(IdempotencyKeyReusedException.class)
				.hasMessage("같은 요청 키로 다른 거래를 요청했습니다. 새로 시도해 주세요.");
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
		verify(ledgerEntryRepository, never()).add(any());
	}

	@Test
	@DisplayName("같은 요청 키로 다른 유형(충전 키로 사용)을 요청하면 거절한다")
	void same_key_different_type_is_rejected() {
		// given
		LedgerEntry stored = storedCharge();
		given(ledgerEntryRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(stored));
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID)).willReturn(Optional.of(account));
		IdempotentRequest useRequest = IdempotentRequest.original(LedgerEntryType.USE, BigDecimal.valueOf(10_000), "음료");

		// when / then
		assertThatThrownBy(() -> writer.write(CUSTOMER_ID, KEY, useRequest,
				target -> target.use(BigDecimal.valueOf(10_000), EMPLOYEE_ID, NOW, "음료", KEY)))
				.isInstanceOf(IdempotencyKeyReusedException.class);
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
		verify(ledgerEntryRepository, never()).add(any());
	}

	@Test
	@DisplayName("같은 요청 키로 다른 고객이나 계좌가 없는 고객을 요청하면 거절한다")
	void same_key_different_customer_is_rejected() {
		// given
		LedgerEntry stored = storedCharge();
		PrepaidAccount otherAccount = PrepaidAccount.open(OTHER_CUSTOMER_ID, NOW);
		UUID customerWithoutAccount = UUID.randomUUID();
		given(ledgerEntryRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(stored));
		given(prepaidAccountRepository.findByCustomerId(OTHER_CUSTOMER_ID)).willReturn(Optional.of(otherAccount));
		given(prepaidAccountRepository.findByCustomerId(customerWithoutAccount)).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> writer.write(OTHER_CUSTOMER_ID, KEY, chargeRequest(10_000, "음료"),
				chargeOperation(10_000, "음료")))
				.isInstanceOf(IdempotencyKeyReusedException.class);
		assertThatThrownBy(() -> writer.write(customerWithoutAccount, KEY, chargeRequest(10_000, "음료"),
				chargeOperation(10_000, "음료")))
				.isInstanceOf(IdempotencyKeyReusedException.class);
		assertThat(otherAccount.getBalance()).isEqualByComparingTo("0");
		verify(ledgerEntryRepository, never()).add(any());
	}

	@Test
	@DisplayName("처음 요청이 잔액 부족으로 실패하면 키가 저장되지 않아 같은 키로 다시 요청하면 다시 판정한다")
	void failed_first_request_does_not_consume_key() {
		// given
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID)).willReturn(Optional.of(account));
		IdempotentRequest useRequest = IdempotentRequest.original(LedgerEntryType.USE, BigDecimal.valueOf(5_000), null);
		Function<PrepaidAccount, LedgerEntry> use = target -> target.use(BigDecimal.valueOf(5_000), EMPLOYEE_ID, NOW,
				null, KEY);
		assertThatThrownBy(() -> writer.write(CUSTOMER_ID, KEY, useRequest, use))
				.isInstanceOf(InsufficientBalanceException.class);
		account.charge(BigDecimal.valueOf(10_000), POLICY, EMPLOYEE_ID, NOW, null, IdempotencyKey.of("top-up"));

		// when
		LedgerWriteOutcome outcome = writer.write(CUSTOMER_ID, KEY, useRequest, use);

		// then
		assertThat(outcome.replayed()).isFalse();
		assertThat(account.getBalance()).isEqualByComparingTo("5000");
		verify(ledgerEntryRepository, times(1)).add(any());
	}

	@Test
	@DisplayName("요청 키가 처음이고 고객의 계좌가 없으면 거절한다")
	void first_request_for_unknown_customer_is_rejected() {
		// given
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID)).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> writer.write(CUSTOMER_ID, KEY, chargeRequest(10_000, null),
				chargeOperation(10_000, null)))
				.isInstanceOf(PrepaidAccountNotFoundException.class);
		verify(ledgerEntryRepository, never()).add(any());
	}

	@Test
	@DisplayName("같은 키 동시 요청으로 저장이 유니크 위반이 되면 새 읽기 전용 트랜잭션에서 먼저 저장된 거래를 찾아 재응답한다")
	void unique_violation_replays_from_new_read_only_transaction() {
		// given: 이 요청이 조회할 때는 없었지만 저장 직전에 다른 요청이 같은 키로 먼저 저장했다
		PrepaidAccount winnerView = PrepaidAccount.open(CUSTOMER_ID, NOW);
		LedgerEntry storedByOther = winnerView.charge(BigDecimal.valueOf(10_000), POLICY, EMPLOYEE_ID, NOW, "음료",
				KEY);
		given(ledgerEntryRepository.findByIdempotencyKey(KEY))
				.willReturn(Optional.empty(), Optional.of(storedByOther));
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID))
				.willReturn(Optional.of(account), Optional.of(winnerView));
		willThrow(new IdempotencyKeyConflictException(new RuntimeException("duplicate key")))
				.given(ledgerEntryRepository).add(any());

		// when
		LedgerWriteOutcome outcome = writer.write(CUSTOMER_ID, KEY, chargeRequest(10_000, "음료"),
				chargeOperation(10_000, "음료"));

		// then
		assertThat(outcome.replayed()).isTrue();
		assertThat(outcome.entry()).isSameAs(storedByOther);
		ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
		verify(transactionManager, times(2)).getTransaction(definitions.capture());
		List<TransactionDefinition> used = definitions.getAllValues();
		assertThat(used.get(0).isReadOnly()).isFalse();
		assertThat(used.get(1).getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		assertThat(used.get(1).isReadOnly()).isTrue();
		verify(transactionManager).rollback(any());
	}

	@Test
	@DisplayName("같은 키 동시 요청에서 먼저 저장된 거래가 다른 요청이면 거절한다")
	void unique_violation_with_different_stored_request_is_rejected() {
		// given
		PrepaidAccount winnerView = PrepaidAccount.open(CUSTOMER_ID, NOW);
		LedgerEntry storedByOther = winnerView.charge(BigDecimal.valueOf(20_000), POLICY, EMPLOYEE_ID, NOW, null, KEY);
		given(ledgerEntryRepository.findByIdempotencyKey(KEY))
				.willReturn(Optional.empty(), Optional.of(storedByOther));
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID))
				.willReturn(Optional.of(account), Optional.of(winnerView));
		willThrow(new IdempotencyKeyConflictException(new RuntimeException("duplicate key")))
				.given(ledgerEntryRepository).add(any());

		// when / then
		assertThatThrownBy(() -> writer.write(CUSTOMER_ID, KEY, chargeRequest(10_000, "음료"),
				chargeOperation(10_000, "음료")))
				.isInstanceOf(IdempotencyKeyReusedException.class);
	}

	@Test
	@DisplayName("같은 키 동시 요청에서 먼저 저장된 거래를 찾지 못하면 충돌로 거절한다")
	void unique_violation_without_stored_entry_is_conflict() {
		// given
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID)).willReturn(Optional.of(account));
		IdempotencyKeyConflictException conflict =
				new IdempotencyKeyConflictException(new RuntimeException("duplicate key"));
		willThrow(conflict).given(ledgerEntryRepository).add(any());

		// when / then
		assertThatThrownBy(() -> writer.write(CUSTOMER_ID, KEY, chargeRequest(10_000, "음료"),
				chargeOperation(10_000, "음료")))
				.isSameAs(conflict);
		verify(ledgerEntryRepository, times(2)).findByIdempotencyKey(KEY);
	}

}
