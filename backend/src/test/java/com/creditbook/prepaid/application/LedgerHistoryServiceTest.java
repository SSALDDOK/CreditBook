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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerNotFoundException;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.LedgerHistoryEntry;
import com.creditbook.prepaid.domain.PrepaidAccount;

/**
 * 거래 이력 조회 유스케이스. 스프링 없이 Mockito 로 저장소만 대체하고, 시계는 고정한다.
 * 취소 가능 판단 자체의 경계값은 도메인 테스트(LedgerEntryCancellabilityTest)에 있다 — 여기서는 서버 시각·영업 시간대를 넘기는지 본다.
 */
@ExtendWith(MockitoExtension.class)
class LedgerHistoryServiceTest {

	private static final UUID CUSTOMER_ID = UUID.fromString("0f9a3c1e-5b7d-4e2a-9c8b-1d2e3f4a5b6c");
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	/** KST 2026-10-16 00:30 — UTC 로는 아직 10-15 */
	private static final Instant NOW = Instant.parse("2026-10-15T15:30:00Z");

	@Mock
	CustomerRepository customerRepository;

	@Mock
	LedgerEntryRepository ledgerEntryRepository;

	LedgerHistoryService service;

	@BeforeEach
	void setUp() {
		service = new LedgerHistoryService(customerRepository, ledgerEntryRepository, Clock.fixed(NOW, ZoneOffset.UTC),
				SEOUL);
	}

	private static LedgerHistoryEntry entry(LedgerEntryType type, Instant performedAt, boolean cancelled,
			UUID reversesId) {
		BigDecimal amount = new BigDecimal("4500");
		return new LedgerHistoryEntry(UUID.randomUUID(), type, amount, type.isDebit() ? amount.negate() : amount,
				new BigDecimal("45500"), "음료", performedAt, "김직원", cancelled, reversesId);
	}

	@Test
	@Tag("REQ-7")
	@Tag("REQ-34")
	@DisplayName("거래 이력의 취소 가능 여부는 서버 시각과 영업 시간대(KST) 날짜로 판단한다")
	void history_marks_cancellable_with_server_clock_and_business_zone() {
		// given: KST 오늘 00:10 충전(UTC 로는 같은 10-15), KST 어제 23:50 충전(UTC 로도 10-15), 오래된 사용, 취소 행
		LedgerHistoryEntry todayCharge = entry(LedgerEntryType.CHARGE, Instant.parse("2026-10-15T15:10:00Z"), false,
				null);
		LedgerHistoryEntry yesterdayCharge = entry(LedgerEntryType.CHARGE, Instant.parse("2026-10-15T14:50:00Z"),
				false, null);
		LedgerHistoryEntry oldUse = entry(LedgerEntryType.USE, Instant.parse("2026-01-01T03:00:00Z"), false, null);
		LedgerHistoryEntry useCancel = entry(LedgerEntryType.USE_CANCEL, NOW, false, oldUse.id());
		given(customerRepository.findById(CUSTOMER_ID)).willReturn(Optional.of(customer()));
		given(ledgerEntryRepository.findHistoryByCustomerId(CUSTOMER_ID, PageRequest.of(0, 20)))
				.willReturn(new PageImpl<>(List.of(useCancel, todayCharge, yesterdayCharge, oldUse),
						PageRequest.of(0, 20), 4));

		// when
		Page<LedgerHistoryItem> result = service.getHistory(CUSTOMER_ID, 0, 20);

		// then: 순서·페이지 정보는 저장소 그대로, 취소 가능만 붙는다
		assertThat(result.getContent()).extracting(LedgerHistoryItem::entry)
				.containsExactly(useCancel, todayCharge, yesterdayCharge, oldUse);
		assertThat(result.getContent()).extracting(LedgerHistoryItem::cancellable)
				.containsExactly(false, true, false, true);
		assertThat(result.getTotalElements()).isEqualTo(4);
	}

	@Test
	@DisplayName("요청한 페이지 번호와 크기로 저장소를 조회한다")
	void page_and_size_are_passed_to_repository() {
		// given
		given(customerRepository.findById(CUSTOMER_ID)).willReturn(Optional.of(customer()));
		given(ledgerEntryRepository.findHistoryByCustomerId(CUSTOMER_ID, PageRequest.of(2, 10)))
				.willReturn(Page.empty(PageRequest.of(2, 10)));

		// when
		Page<LedgerHistoryItem> result = service.getHistory(CUSTOMER_ID, 2, 10);

		// then
		verify(ledgerEntryRepository).findHistoryByCustomerId(CUSTOMER_ID, PageRequest.of(2, 10));
		assertThat(result.getContent()).isEmpty();
	}

	@Test
	@DisplayName("계좌·거래가 없는 고객은 빈 이력을 돌려준다")
	void customer_without_entries_gets_empty_history() {
		// given
		given(customerRepository.findById(CUSTOMER_ID)).willReturn(Optional.of(customer()));
		given(ledgerEntryRepository.findHistoryByCustomerId(CUSTOMER_ID, PageRequest.of(0, 20)))
				.willReturn(Page.empty(PageRequest.of(0, 20)));

		// when
		Page<LedgerHistoryItem> result = service.getHistory(CUSTOMER_ID, 0, 20);

		// then
		assertThat(result.getContent()).isEmpty();
		assertThat(result.getTotalElements()).isZero();
	}

	@Test
	@Tag("REQ-4")
	@DisplayName("비활성 고객도 거래 이력이 조회된다")
	void inactive_customer_history_is_returned() {
		// given
		Customer inactive = customer();
		inactive.deactivate(PrepaidAccount.open(inactive.getId(), NOW), NOW);
		LedgerHistoryEntry use = entry(LedgerEntryType.USE, Instant.parse("2026-10-01T03:00:00Z"), false, null);
		given(customerRepository.findById(CUSTOMER_ID)).willReturn(Optional.of(inactive));
		given(ledgerEntryRepository.findHistoryByCustomerId(CUSTOMER_ID, PageRequest.of(0, 20)))
				.willReturn(new PageImpl<>(List.of(use), PageRequest.of(0, 20), 1));

		// when
		Page<LedgerHistoryItem> result = service.getHistory(CUSTOMER_ID, 0, 20);

		// then
		assertThat(result.getContent()).extracting(LedgerHistoryItem::entry).containsExactly(use);
	}

	@Test
	@DisplayName("없는 고객이면 CustomerNotFoundException 으로 거절하고 거래를 조회하지 않는다")
	void unknown_customer_is_rejected() {
		// given
		given(customerRepository.findById(CUSTOMER_ID)).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> service.getHistory(CUSTOMER_ID, 0, 20))
				.isInstanceOf(CustomerNotFoundException.class);
		verify(ledgerEntryRepository, never()).findHistoryByCustomerId(any(), any());
	}

	private static Customer customer() {
		return Customer.register("김단골", "01000005678", NOW);
	}

}
