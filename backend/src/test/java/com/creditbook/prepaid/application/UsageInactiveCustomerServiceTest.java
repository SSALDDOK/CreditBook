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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerInactiveException;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 비활성 고객 사용 거부 (REQ-33). 스프링 없이 Mockito 로 저장소만 대체한다.
 * 거부되면 거래를 저장하지 않고 잔액도 바뀌지 않는지, 같은 요청 키 재응답이 거부보다 먼저인지를 본다.
 */
@ExtendWith(MockitoExtension.class)
@Tag("REQ-33")
class UsageInactiveCustomerServiceTest {

	private static final Instant REGISTERED_AT = Instant.parse("2026-10-01T01:00:00Z");
	private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	private static final IdempotencyKey KEY = IdempotencyKey.of("3f2b8c1d-key-0033");
	private static final ChargePolicy SETUP_POLICY = ChargePolicy.ofMaxAmount(300_000);

	@Mock
	PrepaidAccountRepository prepaidAccountRepository;

	@Mock
	LedgerEntryRepository ledgerEntryRepository;

	@Mock
	CustomerRepository customerRepository;

	/** 트랜잭션 경계는 목으로 대신한다. */
	@Mock
	PlatformTransactionManager transactionManager;

	Customer customer;

	PrepaidAccount account;

	UsageService service;

	@BeforeEach
	void setUp() {
		customer = Customer.register("김단골", "01012345678", REGISTERED_AT);
		account = PrepaidAccount.open(customer.getId(), REGISTERED_AT);
		customer.deactivate(account, REGISTERED_AT.plusSeconds(60));
		service = new UsageService(
				new IdempotentLedgerWriter(prepaidAccountRepository, ledgerEntryRepository, transactionManager),
				customerRepository, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private void givenStoredCustomerAndAccount() {
		given(prepaidAccountRepository.findByCustomerId(customer.getId())).willReturn(Optional.of(account));
		given(customerRepository.findById(customer.getId())).willReturn(Optional.of(customer));
	}

	@ParameterizedTest(name = "[{index}] 금액 {0}원")
	@ValueSource(longs = { 1, 1_000, 300_000 })
	@DisplayName("비활성 고객에게 사용을 요청하면 비활성 고객이라 거절하고 거래를 저장하지 않으며 잔액도 바뀌지 않는다")
	void use_is_rejected_for_inactive_customer(long amount) {
		// given
		givenStoredCustomerAndAccount();

		// when / then
		assertThatThrownBy(() -> service.use(customer.getId(), BigDecimal.valueOf(amount), null, EMPLOYEE_ID, KEY))
				.isInstanceOfSatisfying(CustomerInactiveException.class,
						ex -> assertThat(ex.getCustomerId()).isEqualTo(customer.getId()))
				.hasMessage("비활성 고객입니다. 충전하면 다시 활성화됩니다.");
		verify(ledgerEntryRepository, never()).add(any());
		assertThat(account.getBalance()).isEqualByComparingTo("0");
		assertThat(account.getUpdatedAt()).isEqualTo(REGISTERED_AT);
		assertThat(customer.isActive()).isFalse();
	}

	@Test
	@DisplayName("충전으로 재활성화된 고객은 다시 사용할 수 있다")
	void reactivated_customer_can_use() {
		// given
		givenStoredCustomerAndAccount();
		account.charge(new BigDecimal("10000"), SETUP_POLICY, EMPLOYEE_ID, REGISTERED_AT, null,
				IdempotencyKey.of("setup-charge"));
		customer.reactivate(REGISTERED_AT);

		// when
		UsageResult result = service.use(customer.getId(), new BigDecimal("4000"), null, EMPLOYEE_ID, KEY);

		// then
		assertThat(result.balanceAfter()).isEqualByComparingTo("6000");
		verify(ledgerEntryRepository).add(any());
	}

	@Test
	@Tag("REQ-10")
	@DisplayName("같은 요청 키로 다시 온 사용은 고객이 그 사이 비활성이 됐어도 저장된 거래로 재응답한다")
	void replay_takes_precedence_over_inactive_rejection() {
		// given: 활성일 때 처리된 사용 거래가 저장돼 있고, 그 뒤 잔액을 모두 쓰고 비활성화된 고객
		givenStoredCustomerAndAccount();
		customer.reactivate(REGISTERED_AT);
		account.charge(new BigDecimal("10000"), SETUP_POLICY, EMPLOYEE_ID, REGISTERED_AT, null,
				IdempotencyKey.of("setup-charge"));
		UsageResult first = service.use(customer.getId(), new BigDecimal("10000"), null, EMPLOYEE_ID, KEY);
		ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).add(entryCaptor.capture());
		customer.deactivate(account, NOW);
		given(ledgerEntryRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(entryCaptor.getValue()));

		// when
		UsageResult again = service.use(customer.getId(), new BigDecimal("10000"), null, EMPLOYEE_ID, KEY);

		// then
		assertThat(again.replayed()).isTrue();
		assertThat(again.entryId()).isEqualTo(first.entryId());
		assertThat(account.getBalance()).isEqualByComparingTo("0");
		verify(ledgerEntryRepository).add(any());
	}

}
