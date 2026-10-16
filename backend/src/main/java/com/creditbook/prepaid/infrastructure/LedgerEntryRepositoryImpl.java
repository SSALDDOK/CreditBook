package com.creditbook.prepaid.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import com.creditbook.prepaid.domain.IdempotencyKey;
import com.creditbook.prepaid.domain.IdempotencyKeyConflictException;
import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryRepository;
import com.creditbook.prepaid.domain.LedgerHistoryEntry;
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

	/**
	 * 거래 이력 한 페이지 (최신순). 계좌 ID 는 스칼라 서브쿼리로 한 번 구하므로(고객 1명당 계좌 1개 — ux_prepaid_accounts_customer_id)
	 * ix_ledger_entries_account_seq(account_id, seq DESC) 를 정렬 없이 그대로 탄다. 계좌가 없으면 서브쿼리가 NULL 이라 0행이다.
	 * <ul>
	 * <li>직원 LEFT JOIN — 이름이 없다고 거래가 빠지면 안 된다 (고객 상세의 최근 거래와 같은 방식)</li>
	 * <li>반제 LEFT JOIN — 원본 1건당 반제는 최대 1건(ux_ledger_entries_reverses)이라 행이 늘지 않는다. 있으면 취소된 거래</li>
	 * </ul>
	 */
	static final String HISTORY_QUERY = """
			select new com.creditbook.prepaid.domain.LedgerHistoryEntry(
			    e.id, e.type, e.amount, e.signedAmount, e.balanceAfter, e.memo, e.performedAt, emp.name,
			    case when r.id is null then false else true end, e.reversesId)
			from LedgerEntry e
			left join Employee emp on emp.id = e.performedBy
			left join LedgerEntry r on r.reversesId = e.id
			where e.accountId = (select a.id from PrepaidAccount a where a.customerId = :customerId)
			order by e.seq desc
			""";

	/** 거래 이력 전체 건수. {@link #HISTORY_QUERY} 와 같은 조건이다. */
	static final String HISTORY_COUNT_QUERY = """
			select count(e) from LedgerEntry e
			where e.accountId = (select a.id from PrepaidAccount a where a.customerId = :customerId)
			""";

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

	/**
	 * 한 페이지를 쿼리 1번으로 읽고, 건수는 필요할 때만 1번 더 센다 — 첫 페이지가 다 차지 않았거나 마지막 페이지면
	 * 건수를 읽은 행으로 알 수 있으므로 세지 않는다 ({@link PageableExecutionUtils}).
	 */
	@Override
	public Page<LedgerHistoryEntry> findHistoryByCustomerId(UUID customerId, Pageable pageable) {
		// 오프셋이 int 를 넘는 페이지(page 가 아주 큰 값)는 행이 있을 수 없다 — 형 변환 오버플로로 음수 오프셋이 되지 않게 읽지 않는다
		List<LedgerHistoryEntry> content = pageable.getOffset() > Integer.MAX_VALUE
				? List.of()
				: em.createQuery(HISTORY_QUERY, LedgerHistoryEntry.class)
						.setParameter("customerId", customerId)
						.setFirstResult((int) pageable.getOffset())
						.setMaxResults(pageable.getPageSize())
						.getResultList();
		return PageableExecutionUtils.getPage(content, pageable, () -> em
				.createQuery(HISTORY_COUNT_QUERY, Long.class)
				.setParameter("customerId", customerId)
				.getSingleResult());
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
