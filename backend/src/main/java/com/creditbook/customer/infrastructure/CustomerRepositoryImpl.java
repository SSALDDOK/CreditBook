package com.creditbook.customer.infrastructure;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerRepository;
import com.creditbook.customer.domain.CustomerSearchKeyword;
import com.creditbook.customer.domain.CustomerSummary;

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

	/** LIKE 이스케이프 문자. JPQL 의 {@code escape '!'} 와 같아야 한다. */
	static final char LIKE_ESCAPE = '!';

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
	public Page<CustomerSummary> searchActive(CustomerSearchKeyword keyword, Pageable pageable) {
		// 정렬은 쿼리에 고정돼 있다 — 호출자가 넘긴 정렬이 ORDER BY 에 덧붙지 않도록 떼어 낸다
		Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
		return switch (keyword.type()) {
			case NONE -> jpaRepository.findActiveSummaries(unsorted);
			case NAME -> jpaRepository.findActiveSummariesByNameLike(
					"%" + escapeLike(keyword.value().toLowerCase(Locale.ROOT)) + "%", unsorted);
			case PHONE_SUFFIX -> jpaRepository.findActiveSummariesByPhoneLike(
					"%" + escapeLike(keyword.value()), unsorted);
		};
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
