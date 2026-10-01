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

import com.creditbook.prepaid.domain.ChargeLimitExceededException;
import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.InvalidAmountException;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 충전 유스케이스. 스프링 없이 Mockito 로 저장소만 대체한다.
 * 잔액 계산 규칙 자체는 PrepaidAccount 도메인 테스트가 보고, 여기서는 조회·저장·시각·처리 직원 연결을 본다.
 */
@ExtendWith(MockitoExtension.class)
class ChargeServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-01T01:00:00Z");
	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	/** application.yml 의 creditbook.charge.max-amount 와 같은 1회 충전 한도. */
	private static final ChargePolicy POLICY = ChargePolicy.ofMaxAmount(300_000);

	@Mock
	PrepaidAccountRepository prepaidAccountRepository;

	@Mock
	LedgerEntryRepository ledgerEntryRepository;

	PrepaidAccount account;

	ChargeService service;

	@BeforeEach
	void setUp() {
		account = PrepaidAccount.open(CUSTOMER_ID, NOW.minusSeconds(3600));
		service = newService(POLICY);
	}

	private ChargeService newService(ChargePolicy policy) {
		return new ChargeService(prepaidAccountRepository, ledgerEntryRepository, policy,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	@Tag("REQ-5")
	@DisplayName("잔액 0원 고객에게 50,000원 충전을 등록하면 잔액이 50,000원이 되고 CHARGE 유형 거래가 1건 생성된다")
	void charge_increases_balance_and_adds_one_charge_entry() {
		// given
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID)).willReturn(Optional.of(account));

		// when
		ChargeResult result = service.charge(CUSTOMER_ID, new BigDecimal("50000"), "음료", EMPLOYEE_ID);

		// then
		ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).add(entryCaptor.capture());
		LedgerEntry entry = entryCaptor.getValue();
		assertThat(entry.getType()).isEqualTo(LedgerEntryType.CHARGE);
		assertThat(entry.getAccountId()).isEqualTo(account.getId());
		assertThat(entry.getAmount()).isEqualByComparingTo("50000");
		assertThat(entry.getBalanceAfter()).isEqualByComparingTo("50000");
		assertThat(account.getBalance()).isEqualByComparingTo("50000");

		assertThat(result.entryId()).isEqualTo(entry.getId());
		assertThat(result.accountId()).isEqualTo(account.getId());
		assertThat(result.customerId()).isEqualTo(CUSTOMER_ID);
		assertThat(result.amount()).isEqualByComparingTo("50000");
		assertThat(result.balanceAfter()).isEqualByComparingTo("50000");
		assertThat(result.memo()).isEqualTo("음료");
	}

	@Test
	@Tag("REQ-20")
	@DisplayName("충전 거래에는 처리 직원과 서버 시각이 기록된다")
	void charge_records_performer_and_server_time() {
		// given
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID)).willReturn(Optional.of(account));

		// when
		ChargeResult result = service.charge(CUSTOMER_ID, new BigDecimal("10000"), null, EMPLOYEE_ID);

		// then
		ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).add(entryCaptor.capture());
		assertThat(entryCaptor.getValue().getPerformedBy()).isEqualTo(EMPLOYEE_ID);
		assertThat(entryCaptor.getValue().getPerformedAt()).isEqualTo(NOW);
		assertThat(result.performedBy()).isEqualTo(EMPLOYEE_ID);
		assertThat(result.performedAt()).isEqualTo(NOW);
		assertThat(account.getUpdatedAt()).isEqualTo(NOW);
	}

	@ParameterizedTest(name = "[{index}] 금액 {0}")
	@ValueSource(strings = { "0", "-1000", "1000.5", "300001" })
	@Tag("REQ-5")
	@DisplayName("금액이 0 이하이거나 소수점이거나 1회 한도를 넘으면 거절하고 거래를 저장하지 않으며 잔액도 바뀌지 않는다")
	void charge_rejects_invalid_amount_and_saves_nothing(String amount) {
		// given
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID)).willReturn(Optional.of(account));

		// when / then
		assertThatThrownBy(() -> service.charge(CUSTOMER_ID, new BigDecimal(amount), null, EMPLOYEE_ID))
				.isInstanceOf(InvalidAmountException.class);
		verify(ledgerEntryRepository, never()).add(any());
		assertThat(account.getBalance()).isEqualByComparingTo("0");
	}

	@ParameterizedTest(name = "[{index}] 한도 {0}원, 금액 {1}원 → 허용 {2}")
	@CsvSource({
			"1000, 1000, true",
			"1000, 1001, false",
			"500000, 500000, true",
			"500000, 500001, false" })
	@Tag("REQ-6")
	@DisplayName("1회 충전 한도는 설정값을 따르며 한도와 같은 금액까지 충전할 수 있다")
	void charge_limit_follows_configured_policy(long maxAmount, long amount, boolean allowed) {
		// given
		ChargeService configured = newService(ChargePolicy.ofMaxAmount(maxAmount));
		given(prepaidAccountRepository.findByCustomerId(CUSTOMER_ID)).willReturn(Optional.of(account));

		// when / then
		if (allowed) {
			ChargeResult result = configured.charge(CUSTOMER_ID, BigDecimal.valueOf(amount), null, EMPLOYEE_ID);
			assertThat(result.balanceAfter()).isEqualByComparingTo(BigDecimal.valueOf(amount));
			verify(ledgerEntryRepository).add(any());
		}
		else {
			assertThatThrownBy(() -> configured.charge(CUSTOMER_ID, BigDecimal.valueOf(amount), null, EMPLOYEE_ID))
					.isInstanceOf(ChargeLimitExceededException.class);
			verify(ledgerEntryRepository, never()).add(any());
		}
	}

	@Test
	@Tag("REQ-5")
	@DisplayName("계좌가 없는 고객에게 충전하면 거절하고 아무것도 저장하지 않는다")
	void charge_fails_when_account_not_found() {
		// given
		UUID unknownCustomer = UUID.randomUUID();
		given(prepaidAccountRepository.findByCustomerId(unknownCustomer)).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> service.charge(unknownCustomer, new BigDecimal("10000"), null, EMPLOYEE_ID))
				.isInstanceOf(PrepaidAccountNotFoundException.class);
		verify(ledgerEntryRepository, never()).add(any());
	}

}
