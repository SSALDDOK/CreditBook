package com.creditbook.auth.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 직원 저장소. 구현은 infrastructure 에 있다. 직원은 삭제하지 않는다 (비활성화만) — 삭제 메서드를 두지 않는다.
 */
public interface EmployeeRepository {

	/** 새 직원을 추가한다 (INSERT). */
	void add(Employee employee);

	Optional<Employee> findById(UUID id);

	Optional<Employee> findByLoginId(String loginId);

	/**
	 * 토큰 인증에 필요한 직원 상태와 그 토큰의 폐기 여부를 한 번의 조회로 읽는다. 요청마다 불리므로 엔티티를 읽지 않는다.
	 *
	 * @param employeeId 토큰의 sub
	 * @param jti 토큰의 jti
	 * @return 직원이 없으면 빈 값
	 */
	Optional<EmployeeAuthStatus> findAuthStatus(UUID employeeId, UUID jti);

}
