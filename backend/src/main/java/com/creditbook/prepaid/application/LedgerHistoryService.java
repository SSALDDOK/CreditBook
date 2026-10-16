package com.creditbook.prepaid.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.customer.domain.CustomerNotFoundException;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.LedgerHistoryEntry;

/**
 * 거래 이력 조회 — 고객의 거래를 최신순으로 한 페이지씩 돌려주고, 각 거래를 지금 취소할 수 있는지 함께 알린다.
 * 비활성 고객의 이력도 조회된다 (REQ-4). 읽기만 하므로 원장·잔액을 바꾸지 않는다.
 * <p>
 * 취소 가능 판단은 {@link LedgerHistoryEntry#canBeCancelledAt} (도메인)이 하고, 이 서비스는 서버 시각(Clock)과
 * 영업 시간대만 넘긴다 — 충전 취소의 '당일' 규칙과 같은 판단을 쓴다.
 */
@Service
public class LedgerHistoryService {

	private static final Logger log = LoggerFactory.getLogger(LedgerHistoryService.class);

	private final CustomerRepository customerRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final Clock clock;
	private final ZoneId businessZone;

	/**
	 * @param businessZone 영업 시간대 — 충전 건의 '당일' 을 이 시간대의 달력 날짜로 판단한다 (creditbook.business-zone)
	 */
	LedgerHistoryService(CustomerRepository customerRepository, LedgerEntryRepository ledgerEntryRepository,
			Clock clock, @Value("${creditbook.business-zone}") ZoneId businessZone) {
		this.customerRepository = customerRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.clock = clock;
		this.businessZone = Objects.requireNonNull(businessZone, "businessZone");
	}

	/**
	 * 고객의 거래 이력 한 페이지 (seq 내림차순, 최신이 0번). 계좌·거래가 없는 고객이면 빈 페이지다.
	 *
	 * @param customerId 고객 ID
	 * @param page 0부터 시작하는 페이지 번호 (형식 검증은 controller 가 한다)
	 * @param size 페이지 크기
	 * @throws CustomerNotFoundException 고객이 없을 때
	 */
	@Transactional(readOnly = true)
	public Page<LedgerHistoryItem> getHistory(UUID customerId, int page, int size) {
		Objects.requireNonNull(customerId, "customerId");
		if (customerRepository.findById(customerId).isEmpty()) {
			throw new CustomerNotFoundException(customerId);
		}
		Page<LedgerHistoryEntry> entries = ledgerEntryRepository.findHistoryByCustomerId(customerId,
				PageRequest.of(page, size));
		Instant now = clock.instant();
		// 고객 이름·연락처는 로그에 남기지 않는다 (운영 점검 가이드 §6)
		log.debug("ledger history: customerId={}, page={}, size={}, total={}", customerId, page, size,
				entries.getTotalElements());
		return entries.map(entry -> new LedgerHistoryItem(entry, entry.canBeCancelledAt(now, businessZone)));
	}

}
