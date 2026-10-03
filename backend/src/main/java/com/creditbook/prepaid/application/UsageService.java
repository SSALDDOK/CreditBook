package com.creditbook.prepaid.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.prepaid.domain.InsufficientBalanceException;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 사용 유스케이스 (REQ-8). 트랜잭션 경계만 잡고, 금액 검증·잔액 부족 판단·잔액 계산은 {@link PrepaidAccount} 가 한다.
 * <p>
 * 계좌의 잔액 변경(dirty checking 으로 UPDATE, @Version 증가)과 USE 거래 추가가 한 트랜잭션에서 함께 반영되거나
 * 함께 취소된다. 같은 계좌에 동시에 충전·사용하면 늦게 커밋하는 쪽이 낙관적 락 충돌로 실패한다.
 */
@Service
public class UsageService {

	private static final Logger log = LoggerFactory.getLogger(UsageService.class);

	private final PrepaidAccountRepository prepaidAccountRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final Clock clock;

	public UsageService(PrepaidAccountRepository prepaidAccountRepository, LedgerEntryRepository ledgerEntryRepository,
			Clock clock) {
		this.prepaidAccountRepository = prepaidAccountRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.clock = clock;
	}

	/**
	 * 고객의 선결제 잔액에서 사용 금액을 뺀다 (REQ-8). 시각은 서버 시각이다 (REQ-20).
	 *
	 * @param customerId 사용할 고객
	 * @param amount 사용 금액 (형식·범위·잔액 이하 여부는 도메인이 판단한다)
	 * @param memo 메모 (선택)
	 * @param performedBy 처리 직원 ID — 인증 정보에서 꺼낸 값만 넘긴다
	 * @throws PrepaidAccountNotFoundException 고객의 계좌가 없을 때
	 * @throws com.creditbook.prepaid.domain.InvalidAmountException 금액이 규칙에 맞지 않을 때. 아무것도 저장하지 않는다
	 * @throws InsufficientBalanceException 금액이 잔액보다 클 때 (REQ-9). 아무것도 저장하지 않는다
	 */
	@Transactional
	public UsageResult use(UUID customerId, BigDecimal amount, String memo, UUID performedBy) {
		Objects.requireNonNull(customerId, "customerId");
		Objects.requireNonNull(performedBy, "performedBy");
		PrepaidAccount account = prepaidAccountRepository.findByCustomerId(customerId)
				.orElseThrow(() -> new PrepaidAccountNotFoundException(customerId));

		LedgerEntry entry;
		try {
			entry = account.use(amount, performedBy, clock.instant(), memo);
		}
		catch (InsufficientBalanceException ex) {
			// 잔액 부족은 정상적인 거절이므로 WARN (운영 점검 가이드 §6). 고객 이름·연락처·메모는 남기지 않는다
			log.warn("use rejected: insufficient balance, accountId={}, amount={}, balance={}, shortage={}, performedBy={}",
					account.getId(), ex.getRequestedAmount().toPlainString(), ex.getBalance().toPlainString(),
					ex.getShortage().toPlainString(), performedBy);
			throw ex;
		}
		ledgerEntryRepository.add(entry);

		// 고객 이름·연락처·메모는 남기지 않는다 (운영 점검 가이드 §6)
		log.info("balance changed: accountId={}, type={}, amount={}, balanceAfter={}, performedBy={}",
				account.getId(), entry.getType(), entry.getAmount().toPlainString(),
				entry.getBalanceAfter().toPlainString(), performedBy);
		return UsageResult.of(customerId, entry);
	}

}
