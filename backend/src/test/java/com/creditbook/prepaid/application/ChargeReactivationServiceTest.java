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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.prepaid.domain.ChargeLimitExceededException;
import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 비활성 고객에게 충전하면 자동 재활성화 (REQ-33). 스프링 없이 Mockito 로 저장소만 대체한다.
 * 실제 커밋·롤백(재활성화와 충전이 함께 반영·취소되는지)은 PostgreSQL 통합 테스트에서 본다.
 */
@ExtendWith(MockitoExtension.class)
@Tag("REQ-33")
class ChargeReactivationServiceTest {

	private static final Instant REGISTERED_AT = Instant.parse("2026-10-01T01:00:00Z");
	private static final Instant DEACTIVATED_AT = Instant.parse("2026-10-11T03:00:00Z");
	private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	private static final IdempotencyKey KEY = IdempotencyKey.of("3f2b8c1d-key-0033");
	private static final ChargePolicy POLICY = ChargePolicy.ofMaxAmount(300_000);

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

	ChargeService service;

	@BeforeEach
	void setUp() {
		customer = Customer.register("김단골", "01012345678", REGISTERED_AT);
		account = PrepaidAccount.open(customer.getId(), REGISTERED_AT);
		service = new ChargeService(
				new IdempotentLedgerWriter(prepaidAccountRepository, ledgerEntryRepository, transactionManager),
				customerRepository, POLICY, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private void deactivateCustomer() {
		customer.deactivate(account, DEACTIVATED_AT);
	}

	private void givenStoredCustomerAndAccount() {
		given(prepaidAccountRepository.findByCustomerId(customer.getId())).willReturn(Optional.of(account));
		given(customerRepository.findById(customer.getId())).willReturn(Optional.of(customer));
	}

	@Test
	@DisplayName("비활성 고객에게 충전하면 충전이 기록되고 고객이 다시 활성화된다")
	void charge_reactivates_inactive_customer() {
		// given
		deactivateCustomer();
		givenStoredCustomerAndAccount();

		// when
		ChargeResult result = service.charge(customer.getId(), new BigDecimal("10000"), null, EMPLOYEE_ID, KEY);

		// then
		ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).add(entryCaptor.capture());
		assertThat(entryCaptor.getValue().getType()).isEqualTo(LedgerEntryType.CHARGE);
		assertThat(entryCaptor.getValue().getBalanceAfter()).isEqualByComparingTo("10000");
		assertThat(result.replayed()).isFalse();
		assertThat(result.balanceAfter()).isEqualByComparingTo("10000");
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
		assertThat(customer.isActive()).isTrue();
		assertThat(customer.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("활성 고객에게 충전하면 고객 상태와 변경 시각은 바뀌지 않는다")
	void charge_keeps_active_customer_unchanged() {
		// given
		givenStoredCustomerAndAccount();

		// when
		service.charge(customer.getId(), new BigDecimal("10000"), null, EMPLOYEE_ID, KEY);

		// then
		assertThat(customer.isActive()).isTrue();
		assertThat(customer.getUpdatedAt()).isEqualTo(REGISTERED_AT);
		verify(ledgerEntryRepository).add(any());
	}

	@Test
	@DisplayName("비활성 고객의 충전이 1회 한도에 걸리면 거래를 저장하지 않고 고객도 비활성 그대로다")
	void rejected_charge_does_not_reactivate() {
		// given
		deactivateCustomer();
		given(prepaidAccountRepository.findByCustomerId(customer.getId())).willReturn(Optional.of(account));

		// when / then
		assertThatThrownBy(() -> service.charge(customer.getId(), new BigDecimal("300001"), null, EMPLOYEE_ID, KEY))
				.isInstanceOf(ChargeLimitExceededException.class);
		verify(ledgerEntryRepository, never()).add(any());
		verify(customerRepository, never()).findById(any());
		assertThat(customer.isActive()).isFalse();
		assertThat(account.getBalance()).isEqualByComparingTo("0");
	}

	@Test
	@Tag("REQ-10")
	@DisplayName("같은 요청 키로 다시 온 충전은 저장된 거래로 재응답하고 고객을 다시 활성화하지 않는다")
	void replayed_charge_does_not_reactivate_again() {
		// given: 처음 충전(재활성화 포함)이 커밋된 뒤 잔액을 모두 쓰고 다시 비활성화된 고객
		deactivateCustomer();
		givenStoredCustomerAndAccount();
		ChargeResult first = service.charge(customer.getId(), new BigDecimal("10000"), null, EMPLOYEE_ID, KEY);
		ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).add(entryCaptor.capture());
		account.use(new BigDecimal("10000"), EMPLOYEE_ID, NOW, null, IdempotencyKey.of("setup-use"));
		customer.deactivate(account, NOW);
		given(ledgerEntryRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(entryCaptor.getValue()));

		// when
		ChargeResult again = service.charge(customer.getId(), new BigDecimal("10000"), null, EMPLOYEE_ID, KEY);

		// then
		assertThat(again.replayed()).isTrue();
		assertThat(again.entryId()).isEqualTo(first.entryId());
		assertThat(customer.isActive()).isFalse();
		assertThat(account.getBalance()).isEqualByComparingTo("0");
		verify(ledgerEntryRepository).add(any());
		verify(customerRepository).findById(customer.getId());
	}

}
