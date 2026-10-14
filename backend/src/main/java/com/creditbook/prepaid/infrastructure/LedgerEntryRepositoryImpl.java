package com.creditbook.prepaid.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;

import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.prepaid.domain.IdempotencyKeyConflictException;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.ReversalConflictException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

/**
 * {@link LedgerEntryRepository} 의 JPA 구현.
 * <p>
 * Spring Data 의 JpaRepository 를 쓰지 않는다 — save(merge)·delete 경로가 열리면 원장 UPDATE·DELETE 가 코드로 가능해진다.
 * 추가는 persist 로만 한다 (id 를 도메인이 할당하므로 save 는 merge 로 동작한다).
 */
@Repository
class LedgerEntryRepositoryImpl implements LedgerEntryRepository {

	/** V1__init.sql 의 부분 유니크 인덱스 — idempotency_key 가 NOT NULL 일 때 전역 유일. */
	static final String IDEMPOTENCY_KEY_INDEX = "ux_ledger_entries_idem";

	/** V1__init.sql 의 부분 유니크 인덱스 — 원본 1건당 반제 1건. */
	static final String REVERSES_INDEX = "ux_ledger_entries_reverses";

	@PersistenceContext
	private EntityManager em;

	/**
	 * persist 후 바로 flush 한다 — 제약 위반이 커밋 시점이 아니라 여기서 드러나야 같은 키·같은 원본 동시 요청을 구분할 수 있다.
	 * (Hibernate 는 flush 때 INSERT 를 UPDATE 보다 먼저 보내므로, 같은 트랜잭션의 계좌 잔액 UPDATE 보다 거래 INSERT 가 먼저 판정된다.)
	 * ux_ledger_entries_idem 위반은 {@link IdempotencyKeyConflictException}, ux_ledger_entries_reverses 위반은
	 * {@link ReversalConflictException} 으로 바꾸고, 다른 예외는 그대로 다시 던진다.
	 */
	@Override
	public void add(LedgerEntry entry) {
		em.persist(entry);
		try {
			em.flush();
		}
		catch (PersistenceException ex) {
			String constraint = violatedConstraint(ex);
			if (IDEMPOTENCY_KEY_INDEX.equals(constraint)) {
				throw new IdempotencyKeyConflictException(ex);
			}
			if (REVERSES_INDEX.equals(constraint)) {
				throw new ReversalConflictException(ex);
			}
			throw ex;
		}
	}

	@Override
	public Optional<LedgerEntry> findByIdempotencyKey(IdempotencyKey key) {
		return em.createQuery("select e from LedgerEntry e where e.idempotencyKey = :key", LedgerEntry.class)
				.setParameter("key", key.value())
				.getResultStream()
				.findFirst();
	}

	@Override
	public Optional<LedgerEntry> findById(UUID id) {
		return Optional.ofNullable(em.find(LedgerEntry.class, id));
	}

	@Override
	public Optional<LedgerEntry> findByReversesId(UUID originalId) {
		return em.createQuery("select e from LedgerEntry e where e.reversesId = :originalId", LedgerEntry.class)
				.setParameter("originalId", originalId)
				.getResultStream()
				.findFirst();
	}

	/** 원인 사슬에서 Hibernate 가 판별한 제약 이름. 제약 위반이 아니면 null. */
	static String violatedConstraint(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return violation.getConstraintName();
			}
		}
		return null;
	}

}
