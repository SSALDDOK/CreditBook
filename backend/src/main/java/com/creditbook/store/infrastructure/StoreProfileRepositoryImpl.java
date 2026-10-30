package com.creditbook.store.infrastructure;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;

import com.creditbook.store.domain.SetupAlreadyCompletedException;
import com.creditbook.store.domain.StoreProfile;
import com.creditbook.store.domain.StoreProfileRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

/**
 * {@link StoreProfileRepository} 의 JPA 구현. id 를 도메인이 직접 할당하므로 추가는 persist 로 한다 (save 는 merge 로 동작).
 */
@Repository
class StoreProfileRepositoryImpl implements StoreProfileRepository {

	/** V1__init.sql 의 유니크 인덱스 — store_profile 은 한 행뿐. */
	static final String SINGLETON_INDEX = "ux_store_profile_singleton";

	@PersistenceContext
	private EntityManager em;

	/**
	 * persist 후 바로 flush 한다 — 두 개설 요청이 동시에 "직원 0명" 을 보고 지나가도, 늦은 쪽의 INSERT 가 여기서
	 * ux_store_profile_singleton 에 걸려 {@link SetupAlreadyCompletedException} 이 된다 (500 이 아니라 403).
	 * 다른 제약 위반(ck_store_profile_name 등)은 바꾸지 않고 그대로 다시 던진다.
	 */
	@Override
	public void add(StoreProfile storeProfile) {
		em.persist(storeProfile);
		try {
			em.flush();
		}
		catch (PersistenceException ex) {
			if (SINGLETON_INDEX.equals(violatedConstraint(ex))) {
				throw new SetupAlreadyCompletedException(ex);
			}
			throw ex;
		}
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
