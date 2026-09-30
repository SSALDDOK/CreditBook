package com.creditbook.customer.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.creditbook.customer.domain.Customer;

/**
 * 고객 조회용 Spring Data 리포지토리.
 * JpaRepository 대신 {@link Repository} 를 상속해 필요한 메서드만 연다 — save(merge)·delete 경로를 만들지 않는다.
 * 추가(INSERT)는 {@link CustomerRepositoryImpl} 이 EntityManager.persist 로 한다.
 */
interface CustomerJpaRepository extends Repository<Customer, UUID> {

	Optional<Customer> findById(UUID id);

}
