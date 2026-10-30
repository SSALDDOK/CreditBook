package com.creditbook.store.application;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.Role;
import com.creditbook.store.domain.SetupAlreadyCompletedException;
import com.creditbook.store.domain.StoreProfile;
import com.creditbook.store.domain.StoreProfileRepository;

/**
 * 매장 개설 (REQ-29, CB-44). 직원 계정이 하나도 없을 때만 매장 행과 첫 ADMIN 계정을 한 트랜잭션으로 만든다.
 * <p>
 * 동시 요청: 두 요청이 모두 "직원 0명" 을 보고 지나가도 ux_store_profile_singleton 이 늦은 쪽의 매장 INSERT 를 막는다.
 * 매장 행을 직원보다 먼저 INSERT·flush 하므로, 늦은 요청은 직원을 만들기 전에 {@link SetupAlreadyCompletedException} 으로
 * 거절되고 트랜잭션 전체가 롤백된다 (매장·직원 중 하나만 남는 상태가 없다).
 * <p>
 * 로그에는 새 ADMIN 직원 ID 만 남긴다 — 매장명·아이디·이름·비밀번호는 남기지 않는다. 개설 뒤 자동 로그인은 하지 않는다.
 */
@Service
public class StoreSetupService {

	private static final Logger log = LoggerFactory.getLogger(StoreSetupService.class);

	private final EmployeeRepository employeeRepository;
	private final StoreProfileRepository storeProfileRepository;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	public StoreSetupService(EmployeeRepository employeeRepository, StoreProfileRepository storeProfileRepository,
			PasswordEncoder passwordEncoder, Clock clock) {
		this.employeeRepository = employeeRepository;
		this.storeProfileRepository = storeProfileRepository;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	/** 매장 개설이 가능한가 — 직원 계정(비활성 포함)이 하나도 없을 때만. */
	@Transactional(readOnly = true)
	public boolean isSetupAvailable() {
		return !employeeRepository.existsAny();
	}

	/**
	 * 매장과 첫 ADMIN 계정을 만든다. 입력 형식(아이디 규칙·비밀번호 길이·확인 일치)은 요청 DTO 가 이미 검사했다.
	 *
	 * @throws SetupAlreadyCompletedException 직원 계정이 이미 있거나, 다른 개설 요청이 매장을 먼저 만들었을 때
	 */
	@Transactional
	public StoreSetupResult setUp(String storeName, String ownerName, String loginId, String rawPassword) {
		if (employeeRepository.existsAny()) {
			throw new SetupAlreadyCompletedException();
		}
		Instant now = clock.instant();
		StoreProfile store = StoreProfile.open(storeName, now);
		storeProfileRepository.add(store);

		Employee admin = Employee.create(loginId, passwordEncoder.encode(rawPassword), ownerName, Role.ADMIN, now);
		employeeRepository.add(admin);

		log.info("store setup completed: adminEmployeeId={}", admin.getId());
		return new StoreSetupResult(store.getName(), admin.getId(), admin.getLoginId(), admin.getName(), admin.getRole());
	}

}
