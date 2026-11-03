package com.creditbook.auth.application;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.auth.domain.AdminAccountProtectedException;
import com.creditbook.auth.domain.DuplicateLoginIdException;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeNotFoundException;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.InitialPasswordGenerator;

/**
 * 사장의 직원 계정 관리 (REQ-30, CB-45): 목록·직원 추가·비밀번호 초기화·비활성화. 사장(ADMIN)만 부른다 — 권한은 보안 구성이 경로로 확인한다.
 * <p>
 * 직원 추가는 STAFF 로만 한다(권한 변경 기능 없음). 사장 계정과 본인 계정은 비활성화·초기화하지 않는다 — 확인 순서는 대상 없음(404) →
 * 보호 계정(409) → 처리. 초기 비밀번호는 무작위로 만들어 결과로 한 번만 돌려주고 DB 에는 해시만 둔다.
 * <p>
 * 로그에는 대상·처리자 직원 ID 만 남긴다 — 아이디·이름·비밀번호는 남기지 않는다. 정상 처리는 INFO, 거절은 WARN.
 */
@Service
public class EmployeeManagementService {

	private static final Logger log = LoggerFactory.getLogger(EmployeeManagementService.class);

	private final EmployeeRepository employeeRepository;
	private final PasswordEncoder passwordEncoder;
	private final InitialPasswordGenerator initialPasswordGenerator;
	private final Clock clock;

	public EmployeeManagementService(EmployeeRepository employeeRepository, PasswordEncoder passwordEncoder,
			InitialPasswordGenerator initialPasswordGenerator, Clock clock) {
		this.employeeRepository = employeeRepository;
		this.passwordEncoder = passwordEncoder;
		this.initialPasswordGenerator = initialPasswordGenerator;
		this.clock = clock;
	}

	/** 직원 전체(비활성 포함). ADMIN 먼저 → 활성 먼저 → 만든 순서. */
	@Transactional(readOnly = true)
	public List<EmployeeSummary> list() {
		return employeeRepository.findAllForManagement().stream()
				.map(EmployeeSummary::from)
				.toList();
	}

	/**
	 * 직원(STAFF) 계정을 추가한다. 입력 형식(이름 길이·아이디 규칙)은 요청 DTO 가 이미 검사했다.
	 *
	 * @param performedBy 요청한 사장 ID (로그용)
	 * @throws DuplicateLoginIdException 같은 아이디(비활성 직원 포함, 대소문자 구분)가 이미 있을 때
	 */
	@Transactional
	public StaffAddedResult addStaff(String name, String loginId, UUID performedBy) {
		Objects.requireNonNull(performedBy, "performedBy");
		if (employeeRepository.existsByLoginId(loginId)) {
			log.warn("staff add rejected: reason=duplicate login id, performedBy={}", performedBy);
			throw new DuplicateLoginIdException();
		}
		String initialPassword = initialPasswordGenerator.generate();
		Employee staff = Employee.createStaff(loginId, passwordEncoder.encode(initialPassword), name, clock.instant());
		try {
			employeeRepository.add(staff);
		}
		catch (DuplicateLoginIdException ex) {
			// 같은 아이디 동시 추가 — 사전 확인을 함께 지나간 늦은 쪽
			log.warn("staff add rejected: reason=duplicate login id (concurrent), performedBy={}", performedBy);
			throw ex;
		}
		log.info("staff added: employeeId={}, performedBy={}", staff.getId(), performedBy);
		return new StaffAddedResult(staff.getId(), staff.getName(), staff.getLoginId(), staff.getRole(), initialPassword);
	}

	/**
	 * 직원 비밀번호를 새 초기 비밀번호로 바꾸고, 다음 로그인 뒤 다시 바꾸게 한다. 연속 실패 횟수·잠금도 푼다.
	 * 비활성 직원도 초기화한다(로그인은 여전히 막힌다).
	 *
	 * @throws EmployeeNotFoundException 직원이 없을 때
	 * @throws AdminAccountProtectedException 대상이 본인이거나 사장 계정일 때
	 */
	@Transactional
	public PasswordResetResult resetPassword(UUID employeeId, UUID performedBy) {
		Employee target = findManageable(employeeId, performedBy, "password reset");
		String initialPassword = initialPasswordGenerator.generate();
		target.resetPassword(passwordEncoder.encode(initialPassword), clock.instant());
		log.info("employee password reset: employeeId={}, performedBy={}", employeeId, performedBy);
		return new PasswordResetResult(target.getId(), initialPassword);
	}

	/**
	 * 직원을 비활성화한다. 이미 비활성이면 아무것도 바꾸지 않는다. 비활성화된 직원의 토큰은 다음 요청부터 거절된다.
	 *
	 * @throws EmployeeNotFoundException 직원이 없을 때
	 * @throws AdminAccountProtectedException 대상이 본인이거나 사장 계정일 때
	 */
	@Transactional
	public void deactivate(UUID employeeId, UUID performedBy) {
		Employee target = findManageable(employeeId, performedBy, "deactivation");
		if (target.deactivate(clock.instant())) {
			log.info("employee deactivated: employeeId={}, performedBy={}", employeeId, performedBy);
		}
		else {
			log.info("employee already inactive: employeeId={}, performedBy={}", employeeId, performedBy);
		}
	}

	/** 대상 행을 잠그고 읽은 뒤(404) 관리할 수 있는 계정인지(409) 확인한다. */
	private Employee findManageable(UUID employeeId, UUID performedBy, String action) {
		Objects.requireNonNull(employeeId, "employeeId");
		Objects.requireNonNull(performedBy, "performedBy");
		Employee target = employeeRepository.findByIdForUpdate(employeeId)
				.orElseThrow(() -> {
					log.warn("employee {} rejected: reason=not found, employeeId={}, performedBy={}", action, employeeId,
							performedBy);
					return new EmployeeNotFoundException(employeeId);
				});
		try {
			target.requireManageableBy(performedBy);
		}
		catch (AdminAccountProtectedException ex) {
			log.warn("employee {} rejected: reason=protected account, employeeId={}, performedBy={}", action, employeeId,
					performedBy);
			throw ex;
		}
		return target;
	}

}
