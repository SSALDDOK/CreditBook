package com.creditbook.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.customer.domain.CustomerSearchKeyword;
import com.creditbook.customer.domain.CustomerSummary;
import com.creditbook.customer.domain.InvalidPhoneNumberException;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 고객 등록·검색 유스케이스. 스프링 없이 Mockito 로 저장소만 대체한다.
 */
@ExtendWith(MockitoExtension.class)
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
	@Tag("REQ-1")
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
	@Tag("REQ-1")
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
	@Tag("REQ-1")
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

	@ParameterizedTest(name = "[{index}] \"{0}\" → {1} \"{2}\"")
	@CsvSource({
			"김단,      NAME,         김단",
			"5678,      PHONE_SUFFIX, 5678",
			"010-0000-5678, PHONE_SUFFIX, 01000005678" })
	@Tag("REQ-2")
	@DisplayName("Given 고객이 30명 등록된 상태에서 When 이름 일부, 전화번호 뒷자리를 검색하면 Then 해당 고객만 잔액과 함께 목록에 표시된다")
	void search_returns_matching_customers_with_balance(String query, CustomerSearchKeyword.Type expectedType,
			String expectedValue) {
		// given: 저장소는 검색어에 맞는 고객만 잔액과 함께 돌려준다 (30명 중 조건 걸러내기는 쿼리가 한다 — S3 통합 테스트)
		CustomerSummary matched = new CustomerSummary(UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c"), "김단골",
				"01000005678", new BigDecimal("15000"), NOW);
		given(customerRepository.searchActive(any(), any()))
				.willReturn(new PageImpl<>(List.of(matched), PageRequest.of(0, 20), 1));

		// when
		Page<CustomerSummary> result = service.search(query, 0, 20);

		// then
		ArgumentCaptor<CustomerSearchKeyword> keywordCaptor = ArgumentCaptor.forClass(CustomerSearchKeyword.class);
		verify(customerRepository).searchActive(keywordCaptor.capture(), any());
		assertThat(keywordCaptor.getValue().type()).isEqualTo(expectedType);
		assertThat(keywordCaptor.getValue().value()).isEqualTo(expectedValue);

		assertThat(result.getContent()).containsExactly(matched);
		assertThat(result.getContent().get(0).balance()).isEqualByComparingTo("15000");
	}

	@Test
	@Tag("REQ-2")
	@DisplayName("검색어가 없으면 전체 목록을 요청한 페이지 번호와 크기로 조회한다")
	void search_without_query_requests_all_with_page() {
		// given
		given(customerRepository.searchActive(any(), any())).willReturn(Page.empty());

		// when
		service.search(null, 3, 50);

		// then
		ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
		verify(customerRepository).searchActive(eq(CustomerSearchKeyword.parse(null)),
				pageCaptor.capture());
		assertThat(pageCaptor.getValue().getPageNumber()).isEqualTo(3);
		assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(50);
	}

}
