package com.creditbook.prepaid.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.prepaid.domain.IdempotencyKeyConflictException;
import com.creditbook.prepaid.domain.IdempotencyKeyReusedException;
import com.creditbook.prepaid.domain.IdempotentRequest;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

/**
 * 요청 키(Idempotency-Key)로 거래를 한 번만 만든다 (REQ-10). 충전·사용(이후 사용 취소)이 같은 순서를 쓴다.
 * 순서와 트랜잭션만 맡고, "같은 요청인가" 판단은 {@link LedgerEntry#isSameRequest}, 잔액 계산은 {@link PrepaidAccount} 가 한다.
 * <ol>
 * <li>쓰기 트랜잭션: 키로 저장된 거래가 있으면 재응답(같은 요청) 또는 거절(다른 요청). 없으면 도메인 연산으로 거래를 만들어 저장</li>
 * <li>저장이 ux_ledger_entries_idem 에 걸리면(같은 키 동시 요청) 쓰기 트랜잭션은 롤백된다. 실패한 트랜잭션에서는 더 조회할 수
 * 없으므로 <b>새 읽기 전용 트랜잭션(REQUIRES_NEW)</b>에서 먼저 저장된 거래를 찾아 1과 같은 규칙으로 응답한다.
 * 그래도 없으면(드묾) 충돌 예외를 그대로 올려 409 로 응답한다</li>
 * </ol>
 * 트랜잭션을 {@code @Transactional} 대신 {@link TransactionTemplate} 으로 잡는 이유: 롤백이 끝난 뒤(트랜잭션 바깥)에서
 * 위반을 잡아 새 트랜잭션을 여는 흐름을 한 메서드 안에서 보이게 하려고. 세션 단위 잠금(advisory lock)은 쓰지 않는다 (Neon pooler).
 */
@Component
class IdempotentLedgerWriter {

	private static final Logger log = LoggerFactory.getLogger(IdempotentLedgerWriter.class);

	private final PrepaidAccountRepository prepaidAccountRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final TransactionTemplate writeTransaction;
	private final TransactionTemplate replayLookupTransaction;

	IdempotentLedgerWriter(PrepaidAccountRepository prepaidAccountRepository,
			LedgerEntryRepository ledgerEntryRepository, PlatformTransactionManager transactionManager) {
		this.prepaidAccountRepository = prepaidAccountRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.writeTransaction = new TransactionTemplate(transactionManager);
		this.replayLookupTransaction = new TransactionTemplate(transactionManager);
		this.replayLookupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.replayLookupTransaction.setReadOnly(true);
	}

	/**
	 * @param customerId 거래할 고객
	 * @param key 요청 키
	 * @param request 같은 요청인지 비교할 기준
	 * @param operation 계좌에 거래를 만드는 도메인 호출 (거래에 {@code key} 를 담아 만든다)
	 * @return 만들어진 거래 또는 먼저 저장된 거래(재응답)
	 * @throws PrepaidAccountNotFoundException 키가 처음이고 고객의 계좌가 없을 때
	 * @throws IdempotencyKeyReusedException 키가 이미 다른 요청에 쓰였을 때
	 * @throws IdempotencyKeyConflictException 같은 키 동시 요청에서 먼저 저장된 거래를 찾지 못했을 때
	 */
	LedgerWriteOutcome write(UUID customerId, IdempotencyKey key, IdempotentRequest request,
			Function<PrepaidAccount, LedgerEntry> operation) {
		Objects.requireNonNull(customerId, "customerId");
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(operation, "operation");
		try {
			return writeTransaction.execute(status -> replayOrWrite(customerId, key, request, operation));
		}
		catch (IdempotencyKeyConflictException conflict) {
			// 쓰기 트랜잭션은 이미 롤백됐다. 먼저 커밋된 같은 키의 거래를 새 트랜잭션에서 찾는다
			Optional<LedgerWriteOutcome> replayed = replayLookupTransaction.execute(status -> ledgerEntryRepository
					.findByIdempotencyKey(key)
					.map(stored -> replay(stored, customerId, key, request)));
			return replayed.orElseThrow(() -> {
				log.warn("idempotent write conflict: stored entry not found, keyPrefix={}", key.logPrefix());
				return conflict;
			});
		}
	}

	private LedgerWriteOutcome replayOrWrite(UUID customerId, IdempotencyKey key, IdempotentRequest request,
			Function<PrepaidAccount, LedgerEntry> operation) {
		Optional<LedgerEntry> stored = ledgerEntryRepository.findByIdempotencyKey(key);
		if (stored.isPresent()) {
			return replay(stored.get(), customerId, key, request);
		}
		PrepaidAccount account = prepaidAccountRepository.findByCustomerId(customerId)
				.orElseThrow(() -> new PrepaidAccountNotFoundException(customerId));
		LedgerEntry entry = operation.apply(account);
		ledgerEntryRepository.add(entry);
		return new LedgerWriteOutcome(entry, false);
	}

	private LedgerWriteOutcome replay(LedgerEntry stored, UUID customerId, IdempotencyKey key,
			IdempotentRequest request) {
		UUID requestAccountId = prepaidAccountRepository.findByCustomerId(customerId)
				.map(PrepaidAccount::getId)
				.orElse(null);
		if (!stored.isSameRequest(requestAccountId, request)) {
			// 정상적인 거절이므로 WARN. 고객 ID·금액·메모는 남기지 않는다 (키 재사용 시도의 내용이 아니라 사실만 남긴다)
			log.warn("idempotency key reused for a different request: storedAccountId={}, storedEntryId={}, keyPrefix={}",
					stored.getAccountId(), stored.getId(), key.logPrefix());
			throw new IdempotencyKeyReusedException(stored.getId());
		}
		log.info("idempotent replay: accountId={}, entryId={}, replayed=true, keyPrefix={}",
				stored.getAccountId(), stored.getId(), key.logPrefix());
		return new LedgerWriteOutcome(stored, true);
	}

}
