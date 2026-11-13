package com.creditbook.auth.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 직원 저장소. 구현은 infrastructure 에 있다. 직원은 삭제하지 않는다 (비활성화만) — 삭제 메서드를 두지 않는다.
 * <p>
 * 다른 직원을 다루는 조회(직원 관리)는 모두 매장 조건을 받는다 (REQ-36) — 다른 매장 직원은 없는 것과 같다.
 * 매장 조건이 없는 조회는 본인 조회(토큰의 직원 ID)·로그인(아이디 전역 유일)·매장 개설(계정 유무)에만 쓴다.
 */
public interface EmployeeRepository {

	/**
	 * 새 직원을 추가하고 바로 DB 에 보낸다 (INSERT + flush).
	 *
	 * @throws DuplicateLoginIdException 같은 로그인 아이디가 이미 있을 때 (동시에 같은 아이디로 추가한 경우 포함, ux_employees_login_id)
	 */
	void add(Employee employee);

	/** 본인 조회 (세션 조회). id 는 인증 정보에서 꺼낸 값만 넘긴다 — 다른 직원 조회에 쓰지 않는다. */
	Optional<Employee> findById(UUID id);

	/**
	 * 본인 비밀번호 변경용 조회. 그 직원 행을 트랜잭션이 끝날 때까지 잠근다 — 비밀번호 초기화·비활성화·비밀번호 변경이 같은 행을
	 * 동시에 바꿔 서로 덮어쓰지 않게 한다 (로그인 실패 횟수 포함). 트랜잭션 안에서만 부른다.
	 * id 는 인증 정보에서 꺼낸 값만 넘긴다 — 다른 직원 조회는 {@link #findByIdAndStoreIdForUpdate} 를 쓴다.
	 */
	Optional<Employee> findByIdForUpdate(UUID id);

	/**
	 * 관리용 조회 (비밀번호 초기화·비활성화). 같은 매장 직원만 찾고 그 행을 트랜잭션이 끝날 때까지 잠근다.
	 * 다른 매장 직원이면 빈 값이다 (REQ-36 — 없는 직원과 같게 404). 트랜잭션 안에서만 부른다.
	 */
	Optional<Employee> findByIdAndStoreIdForUpdate(UUID id, UUID storeId);

	/** 직원 계정이 하나라도 있는가 (비활성 직원 포함). 매장 개설은 계정이 하나도 없을 때만 열린다 (REQ-29). */
	boolean existsAny();

	/** 같은 로그인 아이디(대소문자 구분, 정확히 일치)의 직원이 있는가. 비활성 직원도 센다 (REQ-30). */
	boolean existsByLoginId(String loginId);

	/**
	 * 직원 관리 목록 (REQ-30). 그 매장의 비활성 포함 전체. 정렬: ADMIN 먼저 → 활성 먼저 → 만든 순서.
	 * 한 매장의 직원은 많지 않아 페이지를 나누지 않는다.
	 */
	List<Employee> findAllForManagement(UUID storeId);

	/**
	 * 로그인용 조회. 그 직원 행을 트랜잭션이 끝날 때까지 잠근다(SELECT ... FOR UPDATE 계열) — 같은 계정에 동시에 들어온 로그인이
	 * 연속 실패 횟수를 서로 덮어쓰지 않게 차례로 처리한다 (REQ-15). 트랜잭션 안에서만 부른다.
	 */
	Optional<Employee> findByLoginIdForUpdate(String loginId);

	/**
	 * 토큰 인증에 필요한 직원 상태(매장 포함)와 그 토큰의 폐기 여부를 한 번의 조회로 읽는다. 요청마다 불리므로 엔티티를 읽지 않는다.
	 *
	 * @param employeeId 토큰의 sub
	 * @param jti 토큰의 jti
	 * @return 직원이 없으면 빈 값
	 */
	Optional<EmployeeAuthStatus> findAuthStatus(UUID employeeId, UUID jti);

}
