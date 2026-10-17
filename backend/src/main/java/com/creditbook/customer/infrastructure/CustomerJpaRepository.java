package com.creditbook.customer.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.creditbook.customer.domain.Customer;
import com.creditbook.customer.domain.CustomerSummary;
import com.creditbook.customer.domain.SamePhoneCustomer;

/**
 * 고객 조회용 Spring Data 리포지토리.
 * JpaRepository 대신 {@link Repository} 를 상속해 필요한 메서드만 연다 — save(merge)·delete 경로를 만들지 않는다.
 * 추가(INSERT)는 {@link CustomerRepositoryImpl} 이 EntityManager.persist 로 한다.
 * <p>
 * 목록 검색(REQ-2)은 고객과 계좌를 조인해 {@link CustomerSummary} 로 바로 읽는다 — 고객마다 계좌를 따로 읽는 N+1 이 없다.
 * 검색 방식별로 쿼리를 나눈 이유: 조건을 {@code (:type = ... OR ...)} 식으로 한 쿼리에 섞으면 어떤 조건이 쓰이는지 읽기 어렵고
 * 플래너가 인덱스를 고르기도 어렵다. 정렬은 최근 등록순으로 고정하고 id 로 동순위를 끊어 페이지 경계가 흔들리지 않게 한다.
 * 같은 이유로 활성 고객만(기본, {@code FROM_ACTIVE} — ix_customers_active_created(active, created_at DESC) 를 쓸 수 있다)과
 * 비활성 포함(REQ-33 includeInactive, {@code FROM_ALL} — active 조건 없음)도 쿼리를 나눈다.
 * <p>
 * 계좌는 고객 등록과 같은 트랜잭션에서 반드시 함께 생기므로 내부 조인을 쓴다.
 * LIKE 의 이스케이프 문자는 {@code !} — 패턴을 만드는 쪽({@link CustomerRepositoryImpl})이 {@code ! % _} 앞에 붙인다.
 */
interface CustomerJpaRepository extends Repository<Customer, UUID> {

	String SUMMARY = "select new com.creditbook.customer.domain.CustomerSummary(c.id, c.name, c.phone, a.balance, c.createdAt, c.active) ";
	String FROM_ALL = "from Customer c join PrepaidAccount a on a.customerId = c.id ";
	String FROM_ACTIVE = FROM_ALL + "where c.active = true ";
	String NAME_LIKE = "lower(c.name) like :pattern escape '!' ";
	String PHONE_LIKE = "c.phone like :pattern ";
	String ORDER = "order by c.createdAt desc, c.id desc";
	String COUNT = "select count(c) ";

	Optional<Customer> findById(UUID id);

	/**
	 * 같은 연락처 고객 전부 (REQ-1 중복 연락처 확인). 같음 비교라 ix_customers_phone 을 탄다. 활성 여부 조건은 없다 (비활성도 중복).
	 * boolean 내림차순은 true 가 먼저라 활성 고객이 앞에 온다.
	 */
	@Query("select new com.creditbook.customer.domain.SamePhoneCustomer(c.id, c.name, c.active) "
			+ "from Customer c where c.phone = :phone "
			+ "order by c.active desc, c.createdAt desc, c.id desc")
	List<SamePhoneCustomer> findAllByPhone(@Param("phone") String phone);

	// 활성 고객만 (기본)

	@Query(value = SUMMARY + FROM_ACTIVE + ORDER,
			countQuery = COUNT + FROM_ACTIVE)
	Page<CustomerSummary> findActiveSummaries(Pageable pageable);

	@Query(value = SUMMARY + FROM_ACTIVE + "and " + NAME_LIKE + ORDER,
			countQuery = COUNT + FROM_ACTIVE + "and " + NAME_LIKE)
	Page<CustomerSummary> findActiveSummariesByNameLike(@Param("pattern") String pattern, Pageable pageable);

	@Query(value = SUMMARY + FROM_ACTIVE + "and " + PHONE_LIKE + ORDER,
			countQuery = COUNT + FROM_ACTIVE + "and " + PHONE_LIKE)
	Page<CustomerSummary> findActiveSummariesByPhoneLike(@Param("pattern") String pattern, Pageable pageable);

	// 비활성 포함 (REQ-33 includeInactive=true)

	@Query(value = SUMMARY + FROM_ALL + ORDER,
			countQuery = COUNT + FROM_ALL)
	Page<CustomerSummary> findAllSummaries(Pageable pageable);

	@Query(value = SUMMARY + FROM_ALL + "where " + NAME_LIKE + ORDER,
			countQuery = COUNT + FROM_ALL + "where " + NAME_LIKE)
	Page<CustomerSummary> findAllSummariesByNameLike(@Param("pattern") String pattern, Pageable pageable);

	@Query(value = SUMMARY + FROM_ALL + "where " + PHONE_LIKE + ORDER,
			countQuery = COUNT + FROM_ALL + "where " + PHONE_LIKE)
	Page<CustomerSummary> findAllSummariesByPhoneLike(@Param("pattern") String pattern, Pageable pageable);

}
