package com.creditbook.auth.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;

import com.creditbook.auth.domain.DuplicateLoginIdException;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeAuthStatus;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.Role;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

/**
 * {@link EmployeeRepository} 의 JPA 구현. id 를 도메인이 직접 할당하므로 추가는 persist 로 한다 (save 는 merge 로 동작).
 */
@Repository
class EmployeeRepositoryImpl implements EmployeeRepository {

	/** V1__init.sql 의 로그인 아이디 유니크 제약. */
	static final String LOGIN_ID_UNIQUE = "ux_employees_login_id";

	@PersistenceContext
	private EntityManager em;

	private final EmployeeJpaRepository jpaRepository;

	EmployeeRepositoryImpl(EmployeeJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	/**
	 * persist 후 바로 flush 한다 — INSERT 와 제약 위반(ux_employees_login_id 등)이 커밋 시점이 아니라 호출 안에서 드러나게 한다.
	 * 매장 개설·직원 추가는 이 뒤에 성공 로그를 남기므로, 로그가 실제로 DB 가 받아들인 INSERT 뒤에 찍힌다.
	 * 같은 아이디로 동시에 추가해 사전 확인을 함께 지나간 늦은 쪽은 여기서 {@link DuplicateLoginIdException}(409)이 된다 (500 이 아니라).
	 * 다른 제약 위반은 바꾸지 않고 그대로 다시 던진다.
	 */
	@Override
	public void add(Employee employee) {
		em.persist(employee);
		try {
			em.flush();
		}
		catch (PersistenceException ex) {
			if (LOGIN_ID_UNIQUE.equals(violatedConstraint(ex))) {
				throw new DuplicateLoginIdException(ex);
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

	@Override
	public Optional<Employee> findById(UUID id) {
		return jpaRepository.findById(id);
	}

	@Override
	public Optional<Employee> findByIdForUpdate(UUID id) {
		return jpaRepository.findByIdForUpdate(id);
	}

	@Override
	public Optional<Employee> findByIdAndStoreIdForUpdate(UUID id, UUID storeId) {
		return jpaRepository.findByIdAndStoreIdForUpdate(id, storeId);
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
	public boolean existsByLoginId(String loginId) {
		return jpaRepository.existsByLoginId(loginId);
	}

	@Override
	public List<Employee> findAllForManagement(UUID storeId) {
		return jpaRepository.findAllForManagement(storeId);
	}

	@Override
	public Optional<Employee> findByLoginIdForUpdate(String loginId) {
		return jpaRepository.findByLoginIdForUpdate(loginId);
	}

	/**
	 * 비밀번호 변경 필요 여부는 값을 못 읽으면(null) 참으로 본다 — 별칭이 어긋나도 차단이 조용히 꺼지지 않게 한다.
	 * 매장은 못 읽으면 null 그대로 넘긴다 — 인증 변환기가 401 로 거절한다 (매장 조건 없이 요청이 처리되지 않게).
	 */
	@Override
	public Optional<EmployeeAuthStatus> findAuthStatus(UUID employeeId, UUID jti) {
		return jpaRepository.findAuthStatus(employeeId, jti)
				.map(row -> new EmployeeAuthStatus(
						row.getStoreId(),
						Boolean.TRUE.equals(row.getActive()),
						Role.valueOf(row.getRole()),
						Boolean.TRUE.equals(row.getRevoked()),
						!Boolean.FALSE.equals(row.getMustChangePassword())));
	}

}
