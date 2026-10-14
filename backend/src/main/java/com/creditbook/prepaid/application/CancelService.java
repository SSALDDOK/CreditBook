package com.creditbook.prepaid.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerNotFoundException;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.prepaid.domain.AlreadyCancelledException;
import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.prepaid.domain.IdempotentRequest;
import com.creditbook.prepaid.domain.InvalidCancelTargetException;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryNotFoundException;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.LedgerEntryType;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;

/**
 * 취소 유스케이스 — 사용 취소 (REQ-34, REQ-10, REQ-33). 대상 검증(같은 계좌·유형 대응·반제 행 재반제 금지·이미 취소)과
 * 잔액 계산은 {@link PrepaidAccount#cancelUse}, 재활성화는 {@link Customer} 가 하고, 요청 키 처리와 트랜잭션 경계는
 * {@link IdempotentLedgerWriter} 가 잡는다.
 * <p>
 * 대상 거래와 기존 반제 거래 조회는 계좌 잔액 변경·반제 거래 추가와 같은 쓰기 트랜잭션 안에서 한다.
 * 사용 취소는 잔액을 늘리므로, 비활성 고객이면 같은 트랜잭션에서 다시 활성화한다 (충전과 같은 규칙).
 * 원 거래는 바꾸지 않고 USE_CANCEL 반제 거래를 추가만 한다 (append-only).
 */
@Service
public class CancelService {

	private static final Logger log = LoggerFactory.getLogger(CancelService.class);

	private final IdempotentLedgerWriter ledgerWriter;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final CustomerRepository customerRepository;
	private final Clock clock;

	CancelService(IdempotentLedgerWriter ledgerWriter, LedgerEntryRepository ledgerEntryRepository,
			CustomerRepository customerRepository, Clock clock) {
		this.ledgerWriter = ledgerWriter;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.customerRepository = customerRepository;
		this.clock = clock;
	}

	/**
	 * 사용 건을 전액 취소한다 (REQ-34). 기한은 없고 사유는 필수다. 시각은 서버 시각이다 (REQ-20).
	 * 비활성 고객이면 취소와 같은 트랜잭션에서 다시 활성화한다 (REQ-33).
	 * 같은 요청 키로 같은 요청(고객·대상 거래·사유)이 다시 오면 다시 취소하지 않고 처음 거래를 {@code replayed = true} 로 돌려준다 (REQ-10) —
	 * 이때는 재활성화도 다시 하지 않는다.
	 *
	 * @param customerId 취소할 사용 건의 고객
	 * @param useEntryId 취소할 사용 거래 ID
	 * @param reason 취소 사유 (필수)
	 * @param performedBy 처리 직원 ID — 인증 정보에서 꺼낸 값만 넘긴다
	 * @param idempotencyKey 요청 키
	 * @throws PrepaidAccountNotFoundException 고객의 계좌가 없을 때
	 * @throws LedgerEntryNotFoundException 거래 ID 에 해당하는 거래가 없을 때
	 * @throws InvalidCancelTargetException 다른 고객의 거래이거나 USE 가 아니거나 반제 거래일 때
	 * @throws AlreadyCancelledException 이미 취소된 사용 건일 때
	 * @throws com.creditbook.prepaid.domain.CancelReasonRequiredException 사유가 없거나 공백일 때
	 * @throws com.creditbook.prepaid.domain.IdempotencyKeyReusedException 요청 키가 다른 요청에 이미 쓰였을 때
	 */
	public UseCancelResult cancelUse(UUID customerId, UUID useEntryId, String reason, UUID performedBy,
			IdempotencyKey idempotencyKey) {
		Objects.requireNonNull(customerId, "customerId");
		Objects.requireNonNull(useEntryId, "useEntryId");
		Objects.requireNonNull(performedBy, "performedBy");
		Objects.requireNonNull(idempotencyKey, "idempotencyKey");
		IdempotentRequest request = IdempotentRequest.reversal(LedgerEntryType.USE_CANCEL, useEntryId, reason);
		// 재활성화 여부는 커밋된 뒤에만 로그로 남긴다 — 커밋 전에 남기면 롤백된 재활성화가 로그에 남는다
		AtomicBoolean reactivated = new AtomicBoolean(false);

		LedgerWriteOutcome outcome = ledgerWriter.write(customerId, idempotencyKey, request,
				account -> cancelUseAndReactivate(account, useEntryId, reason, performedBy, idempotencyKey,
						reactivated));

		if (!outcome.replayed()) {
			LedgerEntry entry = outcome.entry();
			// 고객 이름·연락처·취소 사유는 남기지 않는다 (운영 점검 가이드 §6)
			log.info("balance changed: accountId={}, type={}, amount={}, balanceAfter={}, performedBy={}, reversesId={}",
					entry.getAccountId(), entry.getType(), entry.getAmount().toPlainString(),
					entry.getBalanceAfter().toPlainString(), performedBy, entry.getReversesId());
			if (reactivated.get()) {
				log.info("customer reactivated by use cancel: customerId={}, accountId={}, performedBy={}",
						customerId, entry.getAccountId(), performedBy);
			}
		}
		return UseCancelResult.of(customerId, outcome);
	}

	/**
	 * 쓰기 트랜잭션 안에서 불린다. 취소가 도메인 규칙에 걸리면 고객 상태는 건드리지 않는다 (취소 먼저, 재활성화는 그 뒤).
	 */
	private LedgerEntry cancelUseAndReactivate(PrepaidAccount account, UUID useEntryId, String reason,
			UUID performedBy, IdempotencyKey idempotencyKey, AtomicBoolean reactivated) {
		reactivated.set(false); // 반제 충돌로 다시 판정할 때 앞 시도(롤백됨)의 값이 남지 않게
		Instant now = clock.instant();
		LedgerEntry entry = cancelUseOrWarn(account, useEntryId, reason, performedBy, now, idempotencyKey);
		Customer customer = customerRepository.findById(account.getCustomerId())
				.orElseThrow(() -> new CustomerNotFoundException(account.getCustomerId()));
		reactivated.set(customer.reactivate(now));
		return entry;
	}

	private LedgerEntry cancelUseOrWarn(PrepaidAccount account, UUID useEntryId, String reason, UUID performedBy,
			Instant now, IdempotencyKey idempotencyKey) {
		LedgerEntry target = ledgerEntryRepository.findById(useEntryId)
				.orElseThrow(() -> new LedgerEntryNotFoundException(useEntryId));
		Optional<LedgerEntry> existingCancel = ledgerEntryRepository.findByReversesId(useEntryId);
		try {
			return account.cancelUse(target, existingCancel, reason, performedBy, now, idempotencyKey);
		}
		catch (AlreadyCancelledException ex) {
			// 정상적인 거절이므로 WARN (운영 점검 가이드 §6). 고객 이름·연락처·사유는 남기지 않는다
			log.warn("use cancel rejected: already cancelled, accountId={}, targetId={}, cancelEntryId={}, performedBy={}",
					account.getId(), ex.getTargetId(), ex.getCancelEntryId(), performedBy);
			throw ex;
		}
		catch (InvalidCancelTargetException ex) {
			log.warn("use cancel rejected: invalid target, accountId={}, targetId={}, targetAccountId={}, targetType={}, performedBy={}",
					account.getId(), target.getId(), target.getAccountId(), target.getType(), performedBy);
			throw ex;
		}
	}

}
