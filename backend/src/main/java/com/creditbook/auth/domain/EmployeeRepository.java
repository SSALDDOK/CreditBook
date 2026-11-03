package com.creditbook.auth.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 직원 저장소. 구현은 infrastructure 에 있다. 직원은 삭제하지 않는다 (비활성화만) — 삭제 메서드를 두지 않는다.
 */
public interface EmployeeRepository {

	/** 새 직원을 추가하고 바로 DB 에 보낸다 (INSERT + flush). */
	void add(Employee employee);

	Optional<Employee> findById(UUID id);

	/** 직원 계정이 하나라도 있는가 (비활성 직원 포함). 매장 개설은 계정이 하나도 없을 때만 열린다 (REQ-29). */
	boolean existsAny();

	/**
	 * 로그인용 조회. 그 직원 행을 트랜잭션이 끝날 때까지 잠근다(SELECT ... FOR UPDATE 계열) — 같은 계정에 동시에 들어온 로그인이
	 * 연속 실패 횟수를 서로 덮어쓰지 않게 차례로 처리한다 (REQ-15). 트랜잭션 안에서만 부른다.
	 */
	Optional<Employee> findByLoginIdForUpdate(String loginId);

	/**
	 * 토큰 인증에 필요한 직원 상태와 그 토큰의 폐기 여부를 한 번의 조회로 읽는다. 요청마다 불리므로 엔티티를 읽지 않는다.
	 *
	 * @param employeeId 토큰의 sub
	 * @param jti 토큰의 jti
	 * @return 직원이 없으면 빈 값
	 */
	Optional<EmployeeAuthStatus> findAuthStatus(UUID employeeId, UUID jti);

}
