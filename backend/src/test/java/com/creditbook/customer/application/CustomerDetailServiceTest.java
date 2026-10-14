package com.creditbook.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.creditbook.customer.domain.CustomerDetail;
import com.creditbook.customer.domain.CustomerNotFoundException;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 고객 상세 유스케이스 (REQ-11). 스프링 없이 Mockito 로 저장소만 대체한다.
 */
@ExtendWith(MockitoExtension.class)
@Tag("REQ-11")
class CustomerDetailServiceTest {

	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final Instant NOW = Instant.parse("2026-10-15T01:00:00Z");

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
	@DisplayName("고객 상세는 최근 거래를 최대 5건까지 요청하고 저장소가 읽은 값을 그대로 돌려준다")
	void get_detail_requests_five_recent_entries() {
		// given
		CustomerDetail detail = new CustomerDetail(CUSTOMER_ID, "김단골", "01000005678", new BigDecimal("5500"), true,
				NOW, List.of());
		given(customerRepository.findDetail(CUSTOMER_ID, 5)).willReturn(Optional.of(detail));

		// when
		CustomerDetail result = service.getDetail(CUSTOMER_ID);

		// then
		verify(customerRepository).findDetail(CUSTOMER_ID, CustomerService.RECENT_ENTRY_LIMIT);
		assertThat(CustomerService.RECENT_ENTRY_LIMIT).isEqualTo(5);
		assertThat(result).isSameAs(detail);
		assertThat(result.balance()).isEqualByComparingTo("5500");
	}

	@Test
	@DisplayName("없는 고객이면 CustomerNotFoundException 으로 거절한다")
	void get_detail_fails_when_customer_not_found() {
		// given
		given(customerRepository.findDetail(CUSTOMER_ID, 5)).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> service.getDetail(CUSTOMER_ID))
				.isInstanceOf(CustomerNotFoundException.class);
	}

}
