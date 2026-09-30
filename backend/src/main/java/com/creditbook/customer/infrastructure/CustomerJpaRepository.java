package com.creditbook.customer.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerSummary;

/**
 * 고객 조회용 Spring Data 리포지토리.
 * JpaRepository 대신 {@link Repository} 를 상속해 필요한 메서드만 연다 — save(merge)·delete 경로를 만들지 않는다.
 * 추가(INSERT)는 {@link CustomerRepositoryImpl} 이 EntityManager.persist 로 한다.
 * <p>
 * 목록 검색(REQ-2)은 고객과 계좌를 조인해 {@link CustomerSummary} 로 바로 읽는다 — 고객마다 계좌를 따로 읽는 N+1 이 없다.
 * 검색 방식별로 쿼리를 나눈 이유: 조건을 {@code (:type = ... OR ...)} 식으로 한 쿼리에 섞으면 어떤 조건이 쓰이는지 읽기 어렵고
 * 플래너가 인덱스를 고르기도 어렵다. 정렬은 최근 등록순으로 고정하고 id 로 동순위를 끊어 페이지 경계가 흔들리지 않게 한다.
 * <p>
 * 계좌는 고객 등록과 같은 트랜잭션에서 반드시 함께 생기므로 내부 조인을 쓴다.
 * LIKE 의 이스케이프 문자는 {@code !} — 패턴을 만드는 쪽({@link CustomerRepositoryImpl})이 {@code ! % _} 앞에 붙인다.
 */
interface CustomerJpaRepository extends Repository<Customer, UUID> {

	String SUMMARY = "select new com.creditbook.customer.domain.CustomerSummary(c.id, c.name, c.phone, a.balance, c.createdAt) ";
	String FROM_ACTIVE = "from Customer c join PrepaidAccount a on a.customerId = c.id where c.active = true ";
	String ORDER = "order by c.createdAt desc, c.id desc";
	String COUNT = "select count(c) ";

	Optional<Customer> findById(UUID id);

	@Query(value = SUMMARY + FROM_ACTIVE + ORDER,
			countQuery = COUNT + FROM_ACTIVE)
	Page<CustomerSummary> findActiveSummaries(Pageable pageable);

	@Query(value = SUMMARY + FROM_ACTIVE + "and lower(c.name) like :pattern escape '!' " + ORDER,
			countQuery = COUNT + FROM_ACTIVE + "and lower(c.name) like :pattern escape '!'")
	Page<CustomerSummary> findActiveSummariesByNameLike(@Param("pattern") String pattern, Pageable pageable);

	@Query(value = SUMMARY + FROM_ACTIVE + "and c.phone like :pattern " + ORDER,
			countQuery = COUNT + FROM_ACTIVE + "and c.phone like :pattern")
	Page<CustomerSummary> findActiveSummariesByPhoneLike(@Param("pattern") String pattern, Pageable pageable);

}
