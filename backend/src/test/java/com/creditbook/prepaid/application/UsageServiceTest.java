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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.InsufficientBalanceException;
import com.creditbook.prepaid.domain.InvalidAmountException;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 사용 유스케이스. 스프링 없이 Mockito 로 저장소만 대체한다.
 * 잔액 계산 규칙 자체는 PrepaidAccount 도메인 테스트가 보고, 여기서는 조회·저장·시각·처리 직원 연결과
 * 거절 시 아무것도 저장하지 않는지를 본다.
 */
@ExtendWith(MockitoExtension.class)
class UsageServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-01T01:00:00Z");
	private static final Instant OPENED_AT = NOW.minusSeconds(3600);
	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	/** 준비용 충전 한도 — 시험에 필요한 잔액을 한 번에 만든다. */
	private static final ChargePolicy SETUP_POLICY = ChargePolicy.ofMaxAmount(300_000);

	@Mock
	PrepaidAccountRepository prepaidAccountRepository;

	@Mock
	LedgerEntryRepository ledgerEntryRepository;

	UsageService service;

	@BeforeEach
	void setUp() {
		service = new UsageService(prepaidAccountRepository, ledgerEntryRepository, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	/** 잔액이 {@code balance} 원인 계좌. 공개 API(open → charge)로만 만든다. */
	private PrepaidAccount accountWithBalance(long balance) {
		PrepaidAccount account = PrepaidAccount.open(CUSTOMER_ID, OPENED_AT);
		if (balance > 0) {
			account.charge(BigDecimal.valueOf(balance), SETUP_POLICY, EMPLOYEE_ID, OPENED_AT, null);
		}
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID)).willReturn(Optional.of(account));
		return account;
	}

	@Test
	@Tag("REQ-8")
	@DisplayName("잔액 50,000원 고객이 4,500원을 사용하면 잔액이 45,500원이 되고 USE 유형 거래가 생성된다")
	void use_decreases_balance_and_adds_one_use_entry() {
		// given
		PrepaidAccount account = accountWithBalance(50_000);

		// when
		UsageResult result = service.use(CUSTOMER_ID, new BigDecimal("4500"), "음료", EMPLOYEE_ID);

		// then
		ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).add(entryCaptor.capture());
		LedgerEntry entry = entryCaptor.getValue();
		assertThat(entry.getType()).isEqualTo(LedgerEntryType.USE);
		assertThat(entry.getAccountId()).isEqualTo(account.getId());
		assertThat(entry.getAmount()).isEqualByComparingTo("4500");
		assertThat(entry.getBalanceAfter()).isEqualByComparingTo("45500");
		assertThat(entry.getIdempotencyKey()).isNull();
		assertThat(account.getBalance()).isEqualByComparingTo("45500");

		assertThat(result.entryId()).isEqualTo(entry.getId());
		assertThat(result.accountId()).isEqualTo(account.getId());
		assertThat(result.customerId()).isEqualTo(CUSTOMER_ID);
		assertThat(result.type()).isEqualTo(LedgerEntryType.USE);
		assertThat(result.amount()).isEqualByComparingTo("4500");
		assertThat(result.balanceAfter()).isEqualByComparingTo("45500");
		assertThat(result.memo()).isEqualTo("음료");
	}

	@Test
	@Tag("REQ-20")
	@DisplayName("사용 거래에는 처리 직원과 서버 시각이 기록된다")
	void use_records_performer_and_server_time() {
		// given
		PrepaidAccount account = accountWithBalance(10_000);

		// when
		UsageResult result = service.use(CUSTOMER_ID, new BigDecimal("1000"), null, EMPLOYEE_ID);

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
	@Tag("REQ-9")
	@DisplayName("잔액 3,000원 고객이 5,000원 사용을 시도하면 거래가 생성되지 않고 부족 금액이 안내된다")
	void use_fails_when_amount_exceeds_balance_and_saves_nothing() {
		// given
		PrepaidAccount account = accountWithBalance(3_000);

		// when / then
		assertThatThrownBy(() -> service.use(CUSTOMER_ID, new BigDecimal("5000"), null, EMPLOYEE_ID))
				.isInstanceOfSatisfying(InsufficientBalanceException.class, e -> {
					assertThat(e.getBalance()).isEqualByComparingTo("3000");
					assertThat(e.getShortage()).isEqualByComparingTo("2000");
				});
		verify(ledgerEntryRepository, never()).add(any());
		assertThat(account.getBalance()).isEqualByComparingTo("3000");
		assertThat(account.getUpdatedAt()).isEqualTo(OPENED_AT);
	}

	/** 잔액 부족 경계값은 여기에 모은다. */
	@ParameterizedTest(name = "[{index}] 잔액 {0}원에서 {1}원 사용 → 성공 {2}, 부족액 {3}")
	@CsvSource({
			"3000, 3000, true,  0",
			"3000, 3001, false, 1",
			"0,    1,    false, 1" })
	@Tag("REQ-9")
	@DisplayName("잔액과 같은 금액까지는 사용할 수 있고 1원이라도 넘으면 부족액과 함께 거절하고 아무것도 저장하지 않는다")
	void use_balance_boundaries(long balance, long amount, boolean accepted, long shortage) {
		// given
		PrepaidAccount account = accountWithBalance(balance);

		// when / then
		if (accepted) {
			UsageResult result = service.use(CUSTOMER_ID, BigDecimal.valueOf(amount), null, EMPLOYEE_ID);
			assertThat(result.balanceAfter()).isEqualByComparingTo(BigDecimal.valueOf(balance - amount));
			assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(balance - amount));
			verify(ledgerEntryRepository).add(any());
		}
		else {
			assertThatThrownBy(() -> service.use(CUSTOMER_ID, BigDecimal.valueOf(amount), null, EMPLOYEE_ID))
					.isInstanceOfSatisfying(InsufficientBalanceException.class,
							e -> assertThat(e.getShortage()).isEqualByComparingTo(BigDecimal.valueOf(shortage)));
			verify(ledgerEntryRepository, never()).add(any());
			assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(balance));
		}
	}

	@ParameterizedTest(name = "[{index}] 금액 {0}")
	@ValueSource(strings = { "0", "-1000", "1000.5" })
	@Tag("REQ-8")
	@DisplayName("금액이 0 이하이거나 소수점이면 잔액 부족이 아니라 금액 오류로 거절하고 아무것도 저장하지 않는다")
	void use_rejects_invalid_amount_and_saves_nothing(String amount) {
		// given
		PrepaidAccount account = accountWithBalance(10_000);

		// when / then
		assertThatThrownBy(() -> service.use(CUSTOMER_ID, new BigDecimal(amount), null, EMPLOYEE_ID))
				.isInstanceOf(InvalidAmountException.class)
				.isNotInstanceOf(InsufficientBalanceException.class);
		verify(ledgerEntryRepository, never()).add(any());
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
	}

	@Test
	@Tag("REQ-8")
	@DisplayName("계좌가 없는 고객이 사용하면 거절하고 아무것도 저장하지 않는다")
	void use_fails_when_account_not_found() {
		// given
		UUID unknownCustomer = UUID.randomUUID();
		given(prepaidAccountRepository.findByCustomerId(unknownCustomer)).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> service.use(unknownCustomer, new BigDecimal("1000"), null, EMPLOYEE_ID))
				.isInstanceOf(PrepaidAccountNotFoundException.class);
		verify(ledgerEntryRepository, never()).add(any());
	}

}
