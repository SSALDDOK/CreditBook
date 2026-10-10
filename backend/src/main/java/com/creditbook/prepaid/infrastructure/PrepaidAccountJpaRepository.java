package com.creditbook.prepaid.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.creditbook.prepaid.domain.PrepaidAccount;

import jakarta.persistence.LockModeType;

/**
 * 선결제 계좌 조회용 Spring Data 리포지토리.
 * JpaRepository 대신 {@link Repository} 를 상속해 필요한 메서드만 연다 — save(merge)·delete·잔액 UPDATE 쿼리 경로를 만들지 않는다.
 * 추가(INSERT)는 {@link PrepaidAccountRepositoryImpl} 이 EntityManager.persist 로 한다.
 */
interface PrepaidAccountJpaRepository extends Repository<PrepaidAccount, UUID> {

	Optional<PrepaidAccount> findByCustomerId(UUID customerId);

	/**
	 * 조회한 계좌의 version 을 커밋 때 강제로 올린다 (UPDATE ... SET version = v+1 WHERE id = ? AND version = v).
	 * 그사이 다른 트랜잭션이 계좌를 바꿨으면 0행이 갱신돼 Optimistic Lock 예외가 난다.
	 */
	@Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
	@Query("select a from PrepaidAccount a where a.customerId = :customerId")
	Optional<PrepaidAccount> findForVersionIncrementByCustomerId(@Param("customerId") UUID customerId);

}
