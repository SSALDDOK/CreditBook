package com.creditbook.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.customer.domain.InvalidPhoneNumberException;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 고객 등록 유스케이스. 스프링 없이 Mockito 로 저장소만 대체한다.
 */
@ExtendWith(MockitoExtension.class)
@Tag("REQ-1")
class CustomerServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-30T01:00:00Z");

	@Mock
	CustomerRepository customerRepository;

	@Mock
	PrepaidAccountRepository prepaidAccountRepository;

	CustomerService service;

	@BeforeEach
	void setUp() {
		service = new CustomerService(customerRepository, prepaidAccountRepository, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	@DisplayName("Given 사장 또는 직원이 로그인한 상태에서 When 이름과 연락처를 입력해 등록하면 Then 잔액 0원인 선결제 계좌(PrepaidAccount)가 함께 생성된다")
	void register_saves_customer_with_zero_balance_account() {
		// given
		String name = "김단골";
		String phone = "01012345678";

		// when
		RegisteredCustomer result = service.register(name, phone);

		// then
		ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
		ArgumentCaptor<PrepaidAccount> accountCaptor = ArgumentCaptor.forClass(PrepaidAccount.class);
		InOrder order = inOrder(customerRepository, prepaidAccountRepository);
		order.verify(customerRepository).add(customerCaptor.capture());
		order.verify(prepaidAccountRepository).add(accountCaptor.capture());

		Customer customer = customerCaptor.getValue();
		PrepaidAccount account = accountCaptor.getValue();
		assertThat(customer.getName()).isEqualTo("김단골");
		assertThat(customer.getPhone()).isEqualTo("01012345678");
		assertThat(account.getCustomerId()).isEqualTo(customer.getId());
		assertThat(account.getBalance()).isEqualByComparingTo("0");

		assertThat(result.customerId()).isEqualTo(customer.getId());
		assertThat(result.accountId()).isEqualTo(account.getId());
		assertThat(result.balance()).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("고객과 계좌에는 서버 시각이 등록 시각으로 기록된다")
	void register_uses_server_clock() {
		// given
		String name = "김단골";

		// when
		RegisteredCustomer result = service.register(name, "01012345678");

		// then
		ArgumentCaptor<PrepaidAccount> accountCaptor = ArgumentCaptor.forClass(PrepaidAccount.class);
		verify(prepaidAccountRepository).add(accountCaptor.capture());
		assertThat(result.createdAt()).isEqualTo(NOW);
		assertThat(accountCaptor.getValue().getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("고객 정보가 규칙에 맞지 않으면 고객도 계좌도 저장하지 않는다")
	void register_saves_nothing_when_customer_is_invalid() {
		// given
		String invalidPhone = "0101234";

		// when / then
		assertThatThrownBy(() -> service.register("김단골", invalidPhone))
				.isInstanceOf(InvalidPhoneNumberException.class);
		verify(customerRepository, never()).add(any());
		verify(prepaidAccountRepository, never()).add(any());
	}

}
