package com.creditbook.prepaid.infrastructure;

import org.springframework.stereotype.Repository;

import com.creditbook.prepaid.domain.LedgerEntry;
import com.creditbook.prepaid.domain.LedgerEntryRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * {@link LedgerEntryRepository} 의 JPA 구현.
 * <p>
 * Spring Data 의 JpaRepository 를 쓰지 않는다 — save(merge)·delete 경로가 열리면 원장 UPDATE·DELETE 가 코드로 가능해진다.
 * 추가는 persist 로만 한다 (id 를 도메인이 할당하므로 save 는 merge 로 동작한다).
 */
@Repository
class LedgerEntryRepositoryImpl implements LedgerEntryRepository {

	@PersistenceContext
	private EntityManager em;

	@Override
	public void add(LedgerEntry entry) {
		em.persist(entry);
	}

}
