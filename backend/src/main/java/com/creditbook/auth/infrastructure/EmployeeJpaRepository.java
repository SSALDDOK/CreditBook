package com.creditbook.auth.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.creditbook.auth.domain.Employee;

import jakarta.persistence.LockModeType;

interface EmployeeJpaRepository extends JpaRepository<Employee, UUID> {

	/** 로그인용 — 직원 행 잠금(PESSIMISTIC_WRITE)을 걸어 같은 계정의 동시 로그인이 실패 횟수를 덮어쓰지 않게 한다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from Employee e where e.loginId = :loginId")
	Optional<Employee> findByLoginIdForUpdate(@Param("loginId") String loginId);

	/**
	 * 토큰 인증용 1회 조회: 직원 활성 여부·역할과 그 토큰의 폐기 여부. 인증 필터에서 요청마다 부르므로 엔티티 대신 프로젝션을 읽는다.
	 * revoked_tokens.jti 는 기본 키라 EXISTS 는 인덱스 조회 한 번이다.
	 */
	@Query(value = """
			SELECT e.active AS active,
			       e.role AS role,
			       EXISTS (SELECT 1 FROM revoked_tokens r WHERE r.jti = :jti) AS revoked
			  FROM employees e
			 WHERE e.id = :employeeId
			""", nativeQuery = true)
	Optional<AuthStatusRow> findAuthStatus(@Param("employeeId") UUID employeeId, @Param("jti") UUID jti);

	interface AuthStatusRow {

		Boolean getActive();

		String getRole();

		Boolean getRevoked();

	}

}
