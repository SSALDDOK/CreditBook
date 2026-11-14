package com.creditbook.auth.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.creditbook.auth.domain.Employee;

import jakarta.persistence.LockModeType;

/**
 * 직원 조회용 Spring Data 리포지토리.
 * JpaRepository 대신 {@link Repository} 를 상속해 필요한 메서드만 연다 — 매장 조건 없는 findAll·save(merge)·delete 경로가
 * 새지 않게 한다 (REQ-36). 추가(INSERT)는 {@link EmployeeRepositoryImpl} 이 EntityManager.persist 로 한다.
 */
interface EmployeeJpaRepository extends Repository<Employee, UUID> {

	/** 본인 조회 전용 (세션). */
	Optional<Employee> findById(UUID id);

	/** 로그인용 — 직원 행 잠금(PESSIMISTIC_WRITE)을 걸어 같은 계정의 동시 로그인이 실패 횟수를 덮어쓰지 않게 한다. 아이디는 전역 유일. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from Employee e where e.loginId = :loginId")
	Optional<Employee> findByLoginIdForUpdate(@Param("loginId") String loginId);

	/** 본인 비밀번호 변경용 — 직원 행 잠금(PESSIMISTIC_WRITE). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from Employee e where e.id = :id")
	Optional<Employee> findByIdForUpdate(@Param("id") UUID id);

	/** 관리용(비밀번호 초기화·비활성화) — 같은 매장 직원만, 직원 행 잠금(PESSIMISTIC_WRITE). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from Employee e where e.id = :id and e.storeId = :storeId")
	Optional<Employee> findByIdAndStoreIdForUpdate(@Param("id") UUID id, @Param("storeId") UUID storeId);

	boolean existsByLoginId(String loginId);

	/**
	 * 직원 관리 목록: 그 매장만. ADMIN 먼저 → 활성 먼저 → 만든 순서. 만든 시각이 같으면 id 로 순서를 고정한다.
	 * store_id 가 앞인 ux_employees_store_id_id 가 매장 조건을 받친다.
	 */
	@Query("""
			select e from Employee e
			 where e.storeId = :storeId
			 order by case when e.role = com.creditbook.auth.domain.Role.ADMIN then 0 else 1 end,
			          e.active desc,
			          e.createdAt asc,
			          e.id asc
			""")
	List<Employee> findAllForManagement(@Param("storeId") UUID storeId);

	/**
	 * 토큰 인증용 1회 조회: 직원의 매장·활성 여부·역할·비밀번호 변경 필요 여부와 그 토큰의 폐기 여부. 인증 필터에서 요청마다 부르므로
	 * 엔티티 대신 프로젝션을 읽는다. revoked_tokens.jti 는 기본 키라 EXISTS 는 인덱스 조회 한 번이다.
	 * 별칭은 따옴표로 감싸 대소문자를 지킨다 — PostgreSQL 은 따옴표 없는 별칭을 소문자로 바꾼다.
	 */
	@Query(value = """
			SELECT e.store_id AS "storeId",
			       e.active AS active,
			       e.role AS role,
			       e.must_change_password AS "mustChangePassword",
			       EXISTS (SELECT 1 FROM revoked_tokens r WHERE r.jti = :jti) AS revoked
			  FROM employees e
			 WHERE e.id = :employeeId
			""", nativeQuery = true)
	Optional<AuthStatusRow> findAuthStatus(@Param("employeeId") UUID employeeId, @Param("jti") UUID jti);

	interface AuthStatusRow {

		UUID getStoreId();

		Boolean getActive();

		String getRole();

		Boolean getMustChangePassword();

		Boolean getRevoked();

	}

}
