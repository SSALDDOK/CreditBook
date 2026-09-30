package com.creditbook.prepaid.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.creditbook.prepaid.domain.PrepaidAccount;
import com.creditbook.prepaid.domain.PrepaidAccountRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * {@link PrepaidAccountRepository} 의 JPA 구현.
 * <p>
 * id(UUID)를 도메인이 직접 할당하므로 추가는 persist 로 명시한다. 고객·계좌·거래 저장 방식을 하나로 맞춰
 * "새 엔티티인지" 를 Spring Data 의 추측(@Version null 여부 등)에 맡기지 않는다.
 * 잔액 변경은 여기서 하지 않는다 — PrepaidAccount 의 메서드로 바뀐 상태가 커밋 때 dirty checking 으로 반영된다.
 */
@Repository
class PrepaidAccountRepositoryImpl implements PrepaidAccountRepository {

	@PersistenceContext
	private EntityManager em;

	private final PrepaidAccountJpaRepository jpaRepository;

	PrepaidAccountRepositoryImpl(PrepaidAccountJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public void add(PrepaidAccount account) {
		em.persist(account);
	}

	@Override
	public Optional<PrepaidAccount> findByCustomerId(UUID customerId) {
		return jpaRepository.findByCustomerId(customerId);
	}

}
