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
import com.creditbook.store.domain.SetupAlreadyCompletedException;
import com.creditbook.store.domain.Store;
import com.creditbook.store.domain.StoreRepository;

/**
 * 매장 개설 (REQ-29, CB-44). 직원 계정이 하나도 없을 때만 매장 행과 첫 ADMIN 계정을 한 트랜잭션으로 만든다.
 * 사장 계정은 새 매장에 속한다(employees.store_id, REQ-36). 매장·직원 중 하나만 남는 상태가 없다.
 * <p>
 * 동시 요청: 매장 표의 단일 행 인덱스가 V5 에서 없어졌으므로, 두 요청이 모두 "직원 0명" 을 보고 지나가면 매장이 두 개 생길 수 있다
 * (같은 아이디면 ux_employees_login_id 가 늦은 쪽을 막는다). 이 경로는 초대 코드 가입으로 바뀔 예정이다.
 * <p>
 * 로그에는 새 ADMIN 직원 ID 만 남긴다 — 매장명·아이디·이름·비밀번호는 남기지 않는다. 개설 뒤 자동 로그인은 하지 않는다.
 */
@Service
public class StoreSetupService {

	private static final Logger log = LoggerFactory.getLogger(StoreSetupService.class);

	private final EmployeeRepository employeeRepository;
	private final StoreRepository storeRepository;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	public StoreSetupService(EmployeeRepository employeeRepository, StoreRepository storeRepository,
			PasswordEncoder passwordEncoder, Clock clock) {
		this.employeeRepository = employeeRepository;
		this.storeRepository = storeRepository;
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
	 * @throws SetupAlreadyCompletedException 직원 계정이 이미 있을 때
	 */
	@Transactional
	public StoreSetupResult setUp(String storeName, String ownerName, String loginId, String rawPassword) {
		if (employeeRepository.existsAny()) {
			throw new SetupAlreadyCompletedException();
		}
		Instant now = clock.instant();
		Store store = Store.open(storeName, now);
		storeRepository.add(store);

		Employee admin = Employee.createOwner(store.getId(), loginId, passwordEncoder.encode(rawPassword), ownerName, now);
		employeeRepository.add(admin);

		log.info("store setup completed: adminEmployeeId={}, storeId={}", admin.getId(), store.getId());
		return new StoreSetupResult(store.getName(), admin.getId(), admin.getLoginId(), admin.getName(), admin.getRole());
	}

}
