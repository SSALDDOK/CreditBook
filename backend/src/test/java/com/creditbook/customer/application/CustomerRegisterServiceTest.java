package com.creditbook.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.customer.domain.DuplicatePhoneException;
import com.creditbook.customer.domain.InvalidCustomerMemoException;
import com.creditbook.customer.domain.SamePhoneCustomer;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 고객 등록의 메모·같은 번호 확인 (REQ-1). 스프링 없이 Mockito 로 저장소만 대체한다.
 */
@ExtendWith(MockitoExtension.class)
@Tag("REQ-1")
class CustomerRegisterServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-18T01:10:00Z");
	private static final UUID ACTIVE_ID = UUID.fromString("a3f0c1e2-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final UUID INACTIVE_ID = UUID.fromString("b4e1d2f3-6c8e-4f3b-8d9c-2e3f4a5b6c7d");

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
	@DisplayName("같은 번호 고객이 없으면 메모와 함께 고객과 잔액 0원 계좌를 등록한다")
	void register_with_memo_saves_customer_and_account_when_no_same_phone() {
		// given
		given(customerRepository.findAllByPhone("01033814821")).willReturn(List.of());

		// when
		RegisteredCustomer result = service.register("박서준", "01033814821", "  단골, 아메리카노 선호 ", false);

		// then
		ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
		verify(customerRepository).add(customerCaptor.capture());
		verify(prepaidAccountRepository).add(any());
		assertThat(customerCaptor.getValue().getMemo()).isEqualTo("단골, 아메리카노 선호");
		assertThat(result.memo()).isEqualTo("단골, 아메리카노 선호");
		assertThat(result.balance()).isEqualByComparingTo("0");
		assertThat(result.createdAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("같은 매장에 동일 연락처가 이미 있으면 확인 표시 없이는 등록하지 않고 기존 고객을 알린다")
	void register_rejects_same_phone_without_confirm() {
		// given: 활성 먼저 → 최근 등록순으로 저장소가 돌려준다 (비활성 고객도 포함)
		List<SamePhoneCustomer> samePhone = List.of(
				new SamePhoneCustomer(ACTIVE_ID, "박서연", true),
				new SamePhoneCustomer(INACTIVE_ID, "박휴면", false));
		given(customerRepository.findAllByPhone("01033814821")).willReturn(samePhone);

		// when / then
		assertThatThrownBy(() -> service.register("박서준", "01033814821", null, false))
				.isInstanceOfSatisfying(DuplicatePhoneException.class,
						ex -> assertThat(ex.getExistingCustomers()).containsExactlyElementsOf(samePhone));
		verify(customerRepository, never()).add(any());
		verify(prepaidAccountRepository, never()).add(any());
	}

	@Test
	@DisplayName("확인 표시로 다시 요청하면 같은 번호 고객이 있어도 등록한다")
	void register_with_confirm_skips_same_phone_check() {
		// when
		RegisteredCustomer result = service.register("박서준", "01033814821", null, true);

		// then: 같은 번호 조회 없이 바로 등록
		verify(customerRepository, never()).findAllByPhone(anyString());
		verify(customerRepository).add(any());
		verify(prepaidAccountRepository).add(any());
		assertThat(result.memo()).isNull();
	}

	@Test
	@DisplayName("메모 없이 이름·연락처만 받는 등록은 같은 번호 확인을 하지 않는다")
	void legacy_register_skips_same_phone_check() {
		// when
		RegisteredCustomer result = service.register("박서준", "01033814821");

		// then
		verify(customerRepository, never()).findAllByPhone(anyString());
		verify(customerRepository).add(any());
		assertThat(result.memo()).isNull();
	}

	@Test
	@DisplayName("메모가 200자를 넘으면 같은 번호 확인 전에 거절하고 고객도 계좌도 저장하지 않는다")
	void register_rejects_long_memo_before_same_phone_check() {
		// given
		String memo = "메".repeat(201);

		// when / then
		assertThatThrownBy(() -> service.register("박서준", "01033814821", memo, false))
				.isInstanceOf(InvalidCustomerMemoException.class);
		verify(customerRepository, never()).findAllByPhone(anyString());
		verify(customerRepository, never()).add(any());
		verify(prepaidAccountRepository, never()).add(any());
	}

}
