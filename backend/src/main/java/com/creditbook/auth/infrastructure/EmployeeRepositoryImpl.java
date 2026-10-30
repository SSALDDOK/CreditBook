package com.creditbook.auth.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeAuthStatus;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.Role;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * {@link EmployeeRepository} 의 JPA 구현. id 를 도메인이 직접 할당하므로 추가는 persist 로 한다 (save 는 merge 로 동작).
 */
@Repository
class EmployeeRepositoryImpl implements EmployeeRepository {

	@PersistenceContext
	private EntityManager em;

	private final EmployeeJpaRepository jpaRepository;

	EmployeeRepositoryImpl(EmployeeJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	/**
	 * persist 후 바로 flush 한다 — INSERT 와 제약 위반(ux_employees_login_id 등)이 커밋 시점이 아니라 호출 안에서 드러나게 한다.
	 * 매장 개설은 이 뒤에 성공 로그를 남기므로, 로그가 실제로 DB 가 받아들인 INSERT 뒤에 찍힌다.
	 */
	@Override
	public void add(Employee employee) {
		em.persist(employee);
		em.flush();
	}

	@Override
	public Optional<Employee> findById(UUID id) {
		return jpaRepository.findById(id);
	}

	/**
	 * 한 행만 읽는다 (LIMIT 1). JPQL 이라 같은 트랜잭션에서 아직 보내지 않은 직원 INSERT 도 먼저 flush 돼 판정에 들어간다.
	 */
	@Override
	public boolean existsAny() {
		return !em.createQuery("select e.id from Employee e", UUID.class)
				.setMaxResults(1)
				.getResultList()
				.isEmpty();
	}

	@Override
	public Optional<Employee> findByLoginId(String loginId) {
		return jpaRepository.findByLoginId(loginId);
	}

	@Override
	public Optional<EmployeeAuthStatus> findAuthStatus(UUID employeeId, UUID jti) {
		return jpaRepository.findAuthStatus(employeeId, jti)
				.map(row -> new EmployeeAuthStatus(
						Boolean.TRUE.equals(row.getActive()),
						Role.valueOf(row.getRole()),
						Boolean.TRUE.equals(row.getRevoked())));
	}

}
