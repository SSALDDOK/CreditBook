package com.creditbook.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerChangeLogRepository;
import com.creditbook.customer.domain.CustomerChanges;
import com.creditbook.customer.domain.CustomerConcurrentModificationException;
import com.creditbook.customer.domain.CustomerDetail;
import com.creditbook.customer.domain.CustomerNotFoundException;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.customer.domain.DuplicatePhoneException;
import com.creditbook.customer.domain.SamePhoneCustomer;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 고객 정보 수정 유스케이스 (REQ-3). 스프링 없이 Mockito 로 저장소만 대체한다.
 * 실제 UPDATE·버전 증가·변경 기록 INSERT 는 DB 가 있어야 하므로 통합 테스트에서 본다.
 */
@ExtendWith(MockitoExtension.class)
@Tag("REQ-3")
class CustomerEditServiceTest {

	private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000005001");
	private static final UUID OTHER_STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000005002");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	private static final UUID OTHER_CUSTOMER_ID = UUID.fromString("a3f0c1e2-5b7d-4e2a-9c8b-1d2e3f4a5b6c");

	private static final Instant REGISTERED_AT = Instant.parse("2026-11-01T01:00:00Z");
	private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");

	@Mock
	CustomerRepository customerRepository;

	@Mock
	PrepaidAccountRepository prepaidAccountRepository;

	@Mock
	CustomerChangeLogRepository customerChangeLogRepository;

	CustomerService service;

	Customer customer;

	@BeforeEach
	void setUp() {
		service = new CustomerService(customerRepository, prepaidAccountRepository, customerChangeLogRepository,
				Clock.fixed(NOW, ZoneOffset.UTC));
		customer = Customer.register(STORE_ID, "김단골", "01012345678", "아메리카노", REGISTERED_AT);
	}

	private void givenCustomerFound() {
		given(customerRepository.findByIdAndStoreId(customer.getId(), STORE_ID)).willReturn(Optional.of(customer));
	}

	private CustomerDetail givenDetailAfterEdit() {
		CustomerDetail detail = new CustomerDetail(customer.getId(), "김새이름", "01012345678", new BigDecimal("5000"),
				true, REGISTERED_AT, "아메리카노", 1L, List.of());
		given(customerRepository.findDetail(STORE_ID, customer.getId(), CustomerService.RECENT_ENTRY_LIMIT))
				.willReturn(Optional.of(detail));
		return detail;
	}

	private static CustomerEditCommand command(String name, String phone, String memo, boolean confirm) {
		return new CustomerEditCommand(0L, name, phone, memo, confirm);
	}

	@Test
	@DisplayName("다른 매장 고객이거나 없는 고객이면 404로 거절하고 아무것도 쓰지 않는다")
	void edit_rejects_customer_of_other_store() {
		// given: 이 매장 조건으로는 찾지 못한다
		given(customerRepository.findByIdAndStoreId(customer.getId(), OTHER_STORE_ID)).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> service.edit(OTHER_STORE_ID, customer.getId(), command("김새이름", null, null, false),
				EMPLOYEE_ID))
				.isInstanceOf(CustomerNotFoundException.class);
		verifyNoInteractions(customerChangeLogRepository);
		verify(customerRepository, never()).findDetail(any(), any(), anyInt());
	}

	@Test
	@DisplayName("이름을 바꾸면 변경 기록을 남긴 뒤 상세를 다시 읽어 돌려준다")
	void edit_records_change_and_returns_fresh_detail() {
		// given
		givenCustomerFound();
		CustomerDetail detail = givenDetailAfterEdit();

		// when
		CustomerDetail result = service.edit(STORE_ID, customer.getId(), command("김새이름", null, null, false),
				EMPLOYEE_ID);

		// then: 수정 → 기록 INSERT → 다시 읽기 순서 (바뀐 고객은 다시 읽을 때 DB 로 간다)
		assertThat(result).isSameAs(detail);
		assertThat(customer.getName()).isEqualTo("김새이름");
		InOrder order = inOrder(customerChangeLogRepository, customerRepository);
		order.verify(customerChangeLogRepository).add(STORE_ID, customer.getId(), EMPLOYEE_ID, NOW,
				new CustomerChanges(true, false, false));
		order.verify(customerRepository).findDetail(STORE_ID, customer.getId(), CustomerService.RECENT_ENTRY_LIMIT);
		// 연락처가 바뀌지 않았으니 같은 번호 확인을 하지 않는다
		verify(customerRepository, never()).findAllByStoreIdAndPhone(any(), anyString());
	}

	@Test
	@DisplayName("변경 기록의 변경 시각은 고객의 변경 시각과 같은 서버 시각이다")
	void edit_uses_same_instant_for_log_and_customer() {
		// given
		givenCustomerFound();
		givenDetailAfterEdit();

		// when
		service.edit(STORE_ID, customer.getId(), command(null, null, "", false), EMPLOYEE_ID);

		// then
		assertThat(customer.getUpdatedAt()).isEqualTo(NOW);
		verify(customerChangeLogRepository).add(STORE_ID, customer.getId(), EMPLOYEE_ID, NOW,
				new CustomerChanges(false, false, true));
	}

	@Test
	@DisplayName("바뀐 칸이 없으면 변경 기록을 남기지 않고 상세를 돌려준다")
	void edit_without_changes_writes_nothing() {
		// given
		givenCustomerFound();
		givenDetailAfterEdit();

		// when: 앞뒤 공백만 다른 이름, 하이픈을 지운 같은 번호, 같은 메모
		service.edit(STORE_ID, customer.getId(), command(" 김단골 ", "01012345678", "아메리카노", false), EMPLOYEE_ID);

		// then
		verifyNoInteractions(customerChangeLogRepository);
		verify(customerRepository, never()).findAllByStoreIdAndPhone(any(), anyString());
		assertThat(customer.getUpdatedAt()).isEqualTo(REGISTERED_AT);
	}

	@Test
	@DisplayName("버전이 낡았으면 409로 거절하고 같은 번호 확인·변경 기록·재조회를 하지 않는다")
	void edit_rejects_stale_version() {
		// given
		givenCustomerFound();

		// when / then
		assertThatThrownBy(() -> service.edit(STORE_ID, customer.getId(),
				new CustomerEditCommand(3L, null, "01098765432", null, false), EMPLOYEE_ID))
				.isInstanceOf(CustomerConcurrentModificationException.class);
		verify(customerRepository, never()).findAllByStoreIdAndPhone(any(), anyString());
		verifyNoInteractions(customerChangeLogRepository);
		verify(customerRepository, never()).findDetail(any(), any(), anyInt());
	}

	@Test
	@DisplayName("바뀔 연락처의 다른 고객이 같은 매장에 있고 확인 표시가 없으면 409로 보류하고 아무것도 저장하지 않는다")
	void edit_holds_changed_phone_used_by_other_customer() {
		// given
		givenCustomerFound();
		List<SamePhoneCustomer> samePhone = List.of(new SamePhoneCustomer(OTHER_CUSTOMER_ID, "박서연", false));
		given(customerRepository.findAllByStoreIdAndPhone(STORE_ID, "01098765432")).willReturn(samePhone);

		// when / then
		assertThatThrownBy(() -> service.edit(STORE_ID, customer.getId(),
				command("김새이름", "01098765432", null, false), EMPLOYEE_ID))
				.isInstanceOfSatisfying(DuplicatePhoneException.class,
						ex -> assertThat(ex.getExistingCustomers()).containsExactlyElementsOf(samePhone));
		// 요청 전체를 저장하지 않는다 — 이름도 그대로
		assertThat(customer.getName()).isEqualTo("김단골");
		assertThat(customer.getPhone()).isEqualTo("01012345678");
		verifyNoInteractions(customerChangeLogRepository);
		verify(customerRepository, never()).findDetail(any(), any(), anyInt());
	}

	@Test
	@DisplayName("같은 번호 확인에서 고치는 고객 자신은 빼고 본다")
	void edit_excludes_self_from_same_phone_check() {
		// given: 저장소가 자기 자신만 돌려주는 경우 (예: 동시에 같은 번호로 바뀐 직후)
		givenCustomerFound();
		givenDetailAfterEdit();
		given(customerRepository.findAllByStoreIdAndPhone(STORE_ID, "01098765432"))
				.willReturn(List.of(new SamePhoneCustomer(customer.getId(), "김단골", true)));

		// when
		service.edit(STORE_ID, customer.getId(), command(null, "01098765432", null, false), EMPLOYEE_ID);

		// then: 보류하지 않고 바꾼다
		assertThat(customer.getPhone()).isEqualTo("01098765432");
		verify(customerChangeLogRepository).add(STORE_ID, customer.getId(), EMPLOYEE_ID, NOW,
				new CustomerChanges(false, true, false));
	}

	@Test
	@DisplayName("확인 표시로 다시 요청하면 같은 번호 고객이 있어도 연락처를 바꾼다")
	void edit_with_confirm_skips_same_phone_check() {
		// given
		givenCustomerFound();
		givenDetailAfterEdit();

		// when
		service.edit(STORE_ID, customer.getId(), command(null, "01098765432", null, true), EMPLOYEE_ID);

		// then
		verify(customerRepository, never()).findAllByStoreIdAndPhone(any(), anyString());
		assertThat(customer.getPhone()).isEqualTo("01098765432");
		verify(customerChangeLogRepository).add(STORE_ID, customer.getId(), EMPLOYEE_ID, NOW,
				new CustomerChanges(false, true, false));
	}

	@Test
	@DisplayName("연락처를 지금과 같은 번호로 보내면 같은 번호 확인을 하지 않는다")
	void edit_same_phone_does_not_check_duplicates() {
		// given
		givenCustomerFound();
		givenDetailAfterEdit();

		// when
		service.edit(STORE_ID, customer.getId(), command("김새이름", "01012345678", null, false), EMPLOYEE_ID);

		// then
		verify(customerRepository, never()).findAllByStoreIdAndPhone(any(), anyString());
		verify(customerChangeLogRepository).add(STORE_ID, customer.getId(), EMPLOYEE_ID, NOW,
				new CustomerChanges(true, false, false));
	}

	@Test
	@DisplayName("잔액·계좌는 고객 정보 수정에서 건드리지 않는다")
	void edit_does_not_touch_accounts() {
		// given
		givenCustomerFound();
		givenDetailAfterEdit();

		// when
		service.edit(STORE_ID, customer.getId(), command("김새이름", null, null, false), EMPLOYEE_ID);

		// then
		verifyNoInteractions(prepaidAccountRepository);
	}

}
