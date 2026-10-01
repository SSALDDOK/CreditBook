package com.creditbook.prepaid.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.prepaid.domain.ChargePolicy;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 충전 유스케이스 (REQ-5). 트랜잭션 경계만 잡고, 금액 검증·한도·잔액 계산은 {@link PrepaidAccount} 가 한다.
 * <p>
 * 계좌의 잔액 변경(dirty checking 으로 UPDATE, @Version 증가)과 CHARGE 거래 추가가 한 트랜잭션에서 함께 반영되거나
 * 함께 취소된다. 같은 계좌에 동시에 충전·사용하면 늦게 커밋하는 쪽이 낙관적 락 충돌로 실패한다.
 */
@Service
public class ChargeService {

	private static final Logger log = LoggerFactory.getLogger(ChargeService.class);

	private final PrepaidAccountRepository prepaidAccountRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final ChargePolicy chargePolicy;
	private final Clock clock;

	public ChargeService(PrepaidAccountRepository prepaidAccountRepository, LedgerEntryRepository ledgerEntryRepository,
			ChargePolicy chargePolicy, Clock clock) {
		this.prepaidAccountRepository = prepaidAccountRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.chargePolicy = chargePolicy;
		this.clock = clock;
	}

	/**
	 * 고객의 선결제 계좌에 충전한다 (REQ-5). 시각은 서버 시각이다 (REQ-20).
	 *
	 * @param customerId 충전할 고객
	 * @param amount 충전 금액 (형식·범위·1회 한도 검증은 도메인이 한다)
	 * @param memo 메모 (선택)
	 * @param performedBy 처리 직원 ID — 인증 정보에서 꺼낸 값만 넘긴다
	 * @throws PrepaidAccountNotFoundException 고객의 계좌가 없을 때
	 * @throws com.creditbook.prepaid.domain.InvalidAmountException 금액이 규칙에 맞지 않을 때 (1회 한도 초과 포함). 아무것도 저장하지 않는다
	 */
	@Transactional
	public ChargeResult charge(UUID customerId, BigDecimal amount, String memo, UUID performedBy) {
		Objects.requireNonNull(customerId, "customerId");
		Objects.requireNonNull(performedBy, "performedBy");
		PrepaidAccount account = prepaidAccountRepository.findByCustomerId(customerId)
				.orElseThrow(() -> new PrepaidAccountNotFoundException(customerId));

		LedgerEntry entry = account.charge(amount, chargePolicy, performedBy, clock.instant(), memo);
		ledgerEntryRepository.add(entry);

		// 고객 이름·연락처·메모는 남기지 않는다 (운영 점검 가이드 §6)
		log.info("balance changed: accountId={}, type={}, amount={}, balanceAfter={}, performedBy={}",
				account.getId(), entry.getType(), entry.getAmount().toPlainString(),
				entry.getBalanceAfter().toPlainString(), performedBy);
		return ChargeResult.of(customerId, entry);
	}

}
