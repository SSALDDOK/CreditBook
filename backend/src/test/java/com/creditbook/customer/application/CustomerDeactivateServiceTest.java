package com.creditbook.customer.application;

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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerBalanceNotZeroException;
import com.creditbook.customer.domain.CustomerNotFoundException;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 고객 비활성화 유스케이스 (REQ-4). 스프링 없이 Mockito 로 저장소만 대체한다.
 * 동시 충전과의 충돌(계좌 version) 자체는 DB 가 있어야 확인되므로 통합 테스트 범위다 — 여기서는 version 을 올리는 조회를 쓰는지만 본다.
 */
@ExtendWith(MockitoExtension.class)
@Tag("REQ-4")
class CustomerDeactivateServiceTest {

	private static final Instant REGISTERED_AT = Instant.parse("2026-10-01T01:00:00Z");
	private static final Instant NOW = Instant.parse("2026-10-11T03:00:00Z");
	private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-00000000a001");

	@Mock
	CustomerRepository customerRepository;

	@Mock
	PrepaidAccountRepository prepaidAccountRepository;

	CustomerService service;

	@BeforeEach
	void setUp() {
		service = new CustomerService(customerRepository, prepaidAccountRepository, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static PrepaidAccount accountOf(Customer customer, long balance) {
		PrepaidAccount account = PrepaidAccount.open(customer.getId(), REGISTERED_AT);
		if (balance > 0) {
			account.charge(BigDecimal.valueOf(balance), ChargePolicy.ofMaxAmount(300_000), ADMIN_ID, REGISTERED_AT,
					null, IdempotencyKey.of("setup-charge"));
		}
		return account;
	}

	@Test
	@DisplayName("잔액이 0원인 고객을 비활성화하면 서버 시각으로 비활성 상태가 된다")
	void deactivate_marks_customer_inactive_with_server_clock() {
		// given
		Customer customer = Customer.register("김단골", "01012345678", REGISTERED_AT);
		PrepaidAccount account = accountOf(customer, 0);
		given(customerRepository.findById(customer.getId())).willReturn(Optional.of(customer));
		given(prepaidAccountRepository.findByCustomerIdAndIncrementVersion(customer.getId()))
				.willReturn(Optional.of(account));

		// when
		service.deactivate(customer.getId(), ADMIN_ID);

		// then
		assertThat(customer.isActive()).isFalse();
		assertThat(customer.getUpdatedAt()).isEqualTo(NOW);
		// 동시 충전과 겹치면 한쪽이 충돌하도록 version 을 올리는 조회를 쓴다 (일반 조회는 쓰지 않는다)
		verify(prepaidAccountRepository, never()).findByCustomerId(any());
	}

	@Test
	@DisplayName("잔액이 남은 고객은 비활성화하지 않고 잔액을 담은 예외로 거절한다")
	void deactivate_rejects_customer_with_balance() {
		// given
		Customer customer = Customer.register("김단골", "01012345678", REGISTERED_AT);
		PrepaidAccount account = accountOf(customer, 5_000);
		given(customerRepository.findById(customer.getId())).willReturn(Optional.of(customer));
		given(prepaidAccountRepository.findByCustomerIdAndIncrementVersion(customer.getId()))
				.willReturn(Optional.of(account));

		// when / then
		assertThatThrownBy(() -> service.deactivate(customer.getId(), ADMIN_ID))
				.isInstanceOfSatisfying(CustomerBalanceNotZeroException.class,
						ex -> assertThat(ex.getBalance()).isEqualByComparingTo("5000"));
		assertThat(customer.isActive()).isTrue();
	}

	@Test
	@DisplayName("이미 비활성인 고객은 오류 없이 그대로 둔다")
	void deactivate_is_idempotent() {
		// given
		Customer customer = Customer.register("김단골", "01012345678", REGISTERED_AT);
		PrepaidAccount account = accountOf(customer, 0);
		customer.deactivate(account, REGISTERED_AT);
		given(customerRepository.findById(customer.getId())).willReturn(Optional.of(customer));
		given(prepaidAccountRepository.findByCustomerIdAndIncrementVersion(customer.getId()))
				.willReturn(Optional.of(account));

		// when
		service.deactivate(customer.getId(), ADMIN_ID);

		// then
		assertThat(customer.isActive()).isFalse();
		assertThat(customer.getUpdatedAt()).isEqualTo(REGISTERED_AT);
	}

	@Test
	@DisplayName("없는 고객이면 고객 없음으로 거절하고 계좌를 조회하지 않는다")
	void deactivate_rejects_unknown_customer() {
		// given
		UUID unknownId = UUID.randomUUID();
		given(customerRepository.findById(unknownId)).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> service.deactivate(unknownId, ADMIN_ID))
				.isInstanceOfSatisfying(CustomerNotFoundException.class,
						ex -> assertThat(ex.getCustomerId()).isEqualTo(unknownId));
		verify(prepaidAccountRepository, never()).findByCustomerIdAndIncrementVersion(any());
	}

	@Test
	@DisplayName("고객의 계좌가 없으면 고객 없음으로 거절하고 상태를 바꾸지 않는다")
	void deactivate_rejects_customer_without_account() {
		// given
		Customer customer = Customer.register("김단골", "01012345678", REGISTERED_AT);
		given(customerRepository.findById(customer.getId())).willReturn(Optional.of(customer));
		given(prepaidAccountRepository.findByCustomerIdAndIncrementVersion(customer.getId()))
				.willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> service.deactivate(customer.getId(), ADMIN_ID))
				.isInstanceOf(PrepaidAccountNotFoundException.class);
		assertThat(customer.isActive()).isTrue();
	}

}
