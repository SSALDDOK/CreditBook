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
import java.time.ZoneId;
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
import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.prepaid.domain.InvalidCancelTargetException;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 비활성 고객의 사용 건을 취소하면 자동 재활성화 (REQ-33, REQ-34). 스프링 없이 Mockito 로 저장소만 대체한다.
 * 실제 커밋·롤백(재활성화와 취소가 함께 반영·취소되는지)과 기본 목록에 다시 보이는지는 PostgreSQL 통합 테스트(TC-41)에서 본다.
 */
@ExtendWith(MockitoExtension.class)
@Tag("REQ-33")
@Tag("REQ-34")
class UseCancelReactivationServiceTest {

	private static final Instant REGISTERED_AT = Instant.parse("2026-10-01T01:00:00Z");
	private static final Instant USED_AT = Instant.parse("2026-10-10T02:00:00Z");
	private static final Instant DEACTIVATED_AT = Instant.parse("2026-10-11T03:00:00Z");
	private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");
	private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
	private static final IdempotencyKey KEY = IdempotencyKey.of("5c9e1a7b-cancel-0033");
	private static final String REASON = "주문 착오";
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

	LedgerEntry charge;

	LedgerEntry use;

	CancelService service;

	/** 10,000원 충전 → 10,000원 사용으로 잔액 0원인 고객 (비활성화할 수 있는 상태). */
	@BeforeEach
	void setUp() {
		customer = Customer.register("김단골", "01012345678", REGISTERED_AT);
		account = PrepaidAccount.open(customer.getId(), REGISTERED_AT);
		charge = account.charge(BigDecimal.valueOf(10_000), SETUP_POLICY, EMPLOYEE_ID, USED_AT, null,
				IdempotencyKey.of("setup-charge"));
		use = account.use(BigDecimal.valueOf(10_000), EMPLOYEE_ID, USED_AT, "음료", IdempotencyKey.of("setup-use"));
		service = new CancelService(
				new IdempotentLedgerWriter(prepaidAccountRepository, ledgerEntryRepository, transactionManager),
				ledgerEntryRepository, customerRepository, Clock.fixed(NOW, ZoneOffset.UTC), ZoneId.of("Asia/Seoul"));
	}

	private void givenStored(LedgerEntry target) {
		given(prepaidAccountRepository.findByCustomerId(customer.getId())).willReturn(Optional.of(account));
		given(ledgerEntryRepository.findById(target.getId())).willReturn(Optional.of(target));
	}

	@Test
	@DisplayName("비활성 고객의 사용 건을 취소하면 잔액이 복구되고 고객이 같은 처리에서 다시 활성화된다")
	void cancel_use_reactivates_inactive_customer() {
		// given
		customer.deactivate(account, DEACTIVATED_AT);
		givenStored(use);
		given(customerRepository.findById(customer.getId())).willReturn(Optional.of(customer));

		// when
		UseCancelResult result = service.cancelUse(customer.getId(), use.getId(), REASON, EMPLOYEE_ID, KEY);

		// then
		ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).add(entryCaptor.capture());
		assertThat(entryCaptor.getValue().getType()).isEqualTo(LedgerEntryType.USE_CANCEL);
		assertThat(result.replayed()).isFalse();
		assertThat(result.balanceAfter()).isEqualByComparingTo("10000");
		assertThat(account.getBalance()).isEqualByComparingTo("10000");
		assertThat(customer.isActive()).isTrue();
		// 재활성화 시점 = 사용 취소 거래의 처리 시각 (REQ-33 — 별도 이력 없이 그 거래로 갈음)
		assertThat(customer.getUpdatedAt()).isEqualTo(NOW).isEqualTo(result.performedAt());
	}

	@Test
	@DisplayName("활성 고객의 사용 건을 취소하면 고객 상태와 변경 시각은 바뀌지 않는다")
	void cancel_use_keeps_active_customer_unchanged() {
		// given
		givenStored(use);
		given(customerRepository.findById(customer.getId())).willReturn(Optional.of(customer));

		// when
		service.cancelUse(customer.getId(), use.getId(), REASON, EMPLOYEE_ID, KEY);

		// then
		assertThat(customer.isActive()).isTrue();
		assertThat(customer.getUpdatedAt()).isEqualTo(REGISTERED_AT);
		verify(ledgerEntryRepository).add(any());
	}

	@Test
	@DisplayName("비활성 고객의 취소가 대상 검증에 걸리면 거래를 저장하지 않고 고객도 비활성 그대로다")
	void rejected_cancel_does_not_reactivate() {
		// given — 충전 거래는 사용 취소 대상이 아니다
		customer.deactivate(account, DEACTIVATED_AT);
		givenStored(charge);

		// when / then
		assertThatThrownBy(() -> service.cancelUse(customer.getId(), charge.getId(), REASON, EMPLOYEE_ID, KEY))
				.isInstanceOf(InvalidCancelTargetException.class);
		verify(ledgerEntryRepository, never()).add(any());
		verify(customerRepository, never()).findById(any());
		assertThat(customer.isActive()).isFalse();
		assertThat(account.getBalance()).isEqualByComparingTo("0");
	}

	@Test
	@Tag("REQ-10")
	@DisplayName("같은 요청 키로 다시 온 사용 취소는 저장된 거래로 재응답하고 고객을 다시 활성화하지 않는다")
	void replayed_cancel_does_not_reactivate_again() {
		// given: 처음 취소(재활성화 포함)가 커밋된 뒤 잔액을 모두 쓰고 다시 비활성화된 고객
		customer.deactivate(account, DEACTIVATED_AT);
		givenStored(use);
		given(customerRepository.findById(customer.getId())).willReturn(Optional.of(customer));
		UseCancelResult first = service.cancelUse(customer.getId(), use.getId(), REASON, EMPLOYEE_ID, KEY);
		ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).add(entryCaptor.capture());
		account.use(BigDecimal.valueOf(10_000), EMPLOYEE_ID, NOW, null, IdempotencyKey.of("setup-use-again"));
		customer.deactivate(account, NOW);
		given(ledgerEntryRepository.findByIdempotencyKey(KEY)).willReturn(Optional.of(entryCaptor.getValue()));

		// when
		UseCancelResult again = service.cancelUse(customer.getId(), use.getId(), REASON, EMPLOYEE_ID, KEY);

		// then
		assertThat(again.replayed()).isTrue();
		assertThat(again.entryId()).isEqualTo(first.entryId());
		assertThat(customer.isActive()).isFalse();
		assertThat(account.getBalance()).isEqualByComparingTo("0");
		verify(ledgerEntryRepository).add(any());
		verify(customerRepository).findById(customer.getId());
	}

}
