package com.creditbook.customer.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerDetail;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.customer.domain.CustomerSearchKeyword;
import com.creditbook.customer.domain.CustomerSummary;
import com.creditbook.prepaid.domain.LedgerEntryType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;

/**
 * {@link CustomerRepository} 의 JPA 구현.
 * <p>
 * Customer 는 id(UUID)를 도메인이 직접 할당하고 @Version 이 없어서, Spring Data 의 save() 는 새 엔티티로 보지 않고
 * merge 한다 (불필요한 SELECT 1회, 넘긴 객체가 아닌 사본이 관리 상태가 됨). 그래서 추가는 persist 로 한다.
 */
@Repository
class CustomerRepositoryImpl implements CustomerRepository {

	/** LIKE 이스케이프 문자. JPQL 의 {@code escape '!'} 와 같아야 한다. */
	static final char LIKE_ESCAPE = '!';

	/**
	 * 고객 상세 (REQ-11) — 고객·잔액·최근 거래를 한 문장으로 읽는다. 한 문장은 한 스냅샷을 보므로 balance 와
	 * 최근 거래의 balance_after 가 서로 다른 시점의 값이 되지 않는다 (두 쿼리로 나누면 그 사이 커밋된 거래가 끼어든다).
	 * <ul>
	 * <li>LATERAL — 계좌별 seq 내림차순 상위 N 건. ix_ledger_entries_account_seq(account_id, seq DESC) 를 탄다</li>
	 * <li>LEFT JOIN LATERAL ... ON TRUE — 거래가 없어도 고객 행 1개가 나온다 (거래 칼럼은 NULL)</li>
	 * <li>반제 LEFT JOIN — 원본 1건당 반제는 최대 1건(ux_ledger_entries_reverses)이라 행이 늘지 않는다</li>
	 * <li>직원 LEFT JOIN — performed_by 는 NOT NULL FK 지만, 이름이 없다고 거래가 빠지면 안 되므로 LEFT</li>
	 * </ul>
	 * ledger_entries 는 읽기만 한다. 활성 여부 조건은 없다 (비활성 고객도 조회됨).
	 */
	static final String FIND_DETAIL_SQL = """
			SELECT c.id            AS customer_id,
			       c.name          AS customer_name,
			       c.phone         AS phone,
			       a.balance       AS balance,
			       c.active        AS active,
			       c.created_at    AS created_at,
			       e.id            AS entry_id,
			       e.type          AS entry_type,
			       e.amount        AS amount,
			       e.signed_amount AS signed_amount,
			       e.balance_after AS balance_after,
			       e.memo          AS memo,
			       e.performed_at  AS performed_at,
			       emp.name        AS performed_by_name,
			       (r.id IS NOT NULL) AS cancelled
			FROM customers c
			JOIN prepaid_accounts a ON a.customer_id = c.id
			LEFT JOIN LATERAL (
			    SELECT le.id, le.seq, le.type, le.amount, le.signed_amount, le.balance_after,
			           le.memo, le.performed_at, le.performed_by
			    FROM ledger_entries le
			    WHERE le.account_id = a.id
			    ORDER BY le.seq DESC
			    LIMIT :limit
			) e ON TRUE
			LEFT JOIN employees emp ON emp.id = e.performed_by
			LEFT JOIN ledger_entries r ON r.reverses_id = e.id
			WHERE c.id = :customerId
			ORDER BY e.seq DESC
			""";

	@PersistenceContext
	private EntityManager em;

	private final CustomerJpaRepository jpaRepository;

	CustomerRepositoryImpl(CustomerJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public void add(Customer customer) {
		em.persist(customer);
	}

	@Override
	public Optional<Customer> findById(UUID id) {
		return jpaRepository.findById(id);
	}

	@Override
	public Page<CustomerSummary> search(CustomerSearchKeyword keyword, boolean includeInactive, Pageable pageable) {
		// 정렬은 쿼리에 고정돼 있다 — 호출자가 넘긴 정렬이 ORDER BY 에 덧붙지 않도록 떼어 낸다
		Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
		return switch (keyword.type()) {
			case NONE -> includeInactive
					? jpaRepository.findAllSummaries(unsorted)
					: jpaRepository.findActiveSummaries(unsorted);
			case NAME -> {
				String pattern = "%" + escapeLike(keyword.value().toLowerCase(Locale.ROOT)) + "%";
				yield includeInactive
						? jpaRepository.findAllSummariesByNameLike(pattern, unsorted)
						: jpaRepository.findActiveSummariesByNameLike(pattern, unsorted);
			}
			case PHONE_SUFFIX -> {
				String pattern = "%" + escapeLike(keyword.value());
				yield includeInactive
						? jpaRepository.findAllSummariesByPhoneLike(pattern, unsorted)
						: jpaRepository.findActiveSummariesByPhoneLike(pattern, unsorted);
			}
		};
	}

	@Override
	public Optional<CustomerDetail> findDetail(UUID id, int recentEntryLimit) {
		if (recentEntryLimit < 1) {
			throw new IllegalArgumentException("recentEntryLimit must be >= 1");
		}
		@SuppressWarnings("unchecked")
		List<Tuple> rows = em.createNativeQuery(FIND_DETAIL_SQL, Tuple.class)
				.setParameter("customerId", id)
				.setParameter("limit", recentEntryLimit)
				.getResultList();
		if (rows.isEmpty()) {
			return Optional.empty();
		}

		List<CustomerDetail.RecentEntry> entries = new ArrayList<>(rows.size());
		for (Tuple row : rows) {
			Object entryId = row.get("entry_id");
			if (entryId == null) {
				continue; // 거래가 없는 고객 — LEFT JOIN LATERAL 이 고객 행 1개만 돌려준다
			}
			entries.add(new CustomerDetail.RecentEntry(
					toUuid(entryId),
					LedgerEntryType.valueOf((String) row.get("entry_type")),
					(BigDecimal) row.get("amount"),
					(BigDecimal) row.get("signed_amount"),
					(BigDecimal) row.get("balance_after"),
					(String) row.get("memo"),
					toInstant(row.get("performed_at")),
					(String) row.get("performed_by_name"),
					Boolean.TRUE.equals(row.get("cancelled"))));
		}

		Tuple first = rows.get(0);
		return Optional.of(new CustomerDetail(
				toUuid(first.get("customer_id")),
				(String) first.get("customer_name"),
				(String) first.get("phone"),
				(BigDecimal) first.get("balance"),
				Boolean.TRUE.equals(first.get("active")),
				toInstant(first.get("created_at")),
				entries));
	}

	/** 네이티브 쿼리의 uuid 칼럼 값을 UUID 로 바꾼다 (드라이버·Hibernate 설정에 따라 UUID 또는 문자열). */
	static UUID toUuid(Object value) {
		if (value instanceof UUID uuid) {
			return uuid;
		}
		return UUID.fromString(value.toString());
	}

	/** 네이티브 쿼리의 timestamptz 칼럼 값을 Instant 로 바꾼다 (Hibernate 설정에 따라 반환 타입이 다르다). */
	static Instant toInstant(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Instant instant) {
			return instant;
		}
		if (value instanceof OffsetDateTime offsetDateTime) {
			return offsetDateTime.toInstant();
		}
		if (value instanceof ZonedDateTime zonedDateTime) {
			return zonedDateTime.toInstant();
		}
		if (value instanceof Timestamp timestamp) {
			return timestamp.toInstant();
		}
		throw new IllegalStateException("unexpected timestamp type: " + value.getClass().getName());
	}

	/** 검색어의 {@code % _ !} 를 글자 그대로 찾도록 이스케이프한다 ("50%" 가 "50으로 시작하는 모든 것" 이 되지 않게). */
	static String escapeLike(String value) {
		StringBuilder escaped = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == LIKE_ESCAPE || c == '%' || c == '_') {
				escaped.append(LIKE_ESCAPE);
			}
			escaped.append(c);
		}
		return escaped.toString();
	}

}
