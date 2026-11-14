package com.creditbook.store.infrastructure;

import org.springframework.stereotype.Repository;

import com.creditbook.store.domain.Store;
import com.creditbook.store.domain.StoreRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * {@link StoreRepository} 의 JPA 구현. id 를 도메인이 직접 할당하므로 추가는 persist 로 한다 (save 는 merge 로 동작).
 */
@Repository
class StoreRepositoryImpl implements StoreRepository {

	@PersistenceContext
	private EntityManager em;

	/** persist 후 바로 flush 한다 — 같은 트랜잭션에서 이어 만드는 사장 계정의 store_id 외래 키가 이 행을 바로 본다. */
	@Override
	public void add(Store store) {
		em.persist(store);
		em.flush();
	}

}
