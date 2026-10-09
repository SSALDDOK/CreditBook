package com.creditbook.auth.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.creditbook.auth.domain.Employee;

interface EmployeeJpaRepository extends JpaRepository<Employee, UUID> {

	Optional<Employee> findByLoginId(String loginId);

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
