package com.creditbook.customer.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * {@link CustomerRepository} 의 JPA 구현.
 * <p>
 * Customer 는 id(UUID)를 도메인이 직접 할당하고 @Version 이 없어서, Spring Data 의 save() 는 새 엔티티로 보지 않고
 * merge 한다 (불필요한 SELECT 1회, 넘긴 객체가 아닌 사본이 관리 상태가 됨). 그래서 추가는 persist 로 한다.
 */
@Repository
class CustomerRepositoryImpl implements CustomerRepository {

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

}
