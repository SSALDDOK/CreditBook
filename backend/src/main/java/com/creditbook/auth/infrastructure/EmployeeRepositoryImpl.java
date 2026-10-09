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

	@Override
	public void add(Employee employee) {
		em.persist(employee);
	}

	@Override
	public Optional<Employee> findById(UUID id) {
		return jpaRepository.findById(id);
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
