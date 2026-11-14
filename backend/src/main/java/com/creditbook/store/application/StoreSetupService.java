package com.creditbook.store.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.auth.domain.DuplicateLoginIdException;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.store.domain.InvalidInviteCodeException;
import com.creditbook.store.domain.Store;
import com.creditbook.store.domain.StoreInviteCode;
import com.creditbook.store.domain.StoreInviteCodeRepository;
import com.creditbook.store.domain.StoreRepository;

/**
 * 초대 코드 가입 (REQ-29·36). 가입 = 새 매장 + 그 매장의 사장(ADMIN). 한 트랜잭션에서 순서대로:
 * <ol>
 * <li>초대 코드 사용 횟수 차감 (조건부 UPDATE) — 0행이면 {@link InvalidInviteCodeException}</li>
 * <li>아이디 중복 확인 (모든 매장, 비활성 포함) — 있으면 {@link DuplicateLoginIdException}</li>
 * <li>매장 행 → 사장 계정 추가. 동시에 같은 아이디로 가입해 2를 함께 지나간 늦은 쪽은 ux_employees_login_id 위반이
 * {@link DuplicateLoginIdException} 이 된다</li>
 * </ol>
 * 2·3에서 실패하면 트랜잭션이 되돌아가 1의 차감도 취소된다 — 실패한 가입이 코드 한도를 쓰지 않는다.
 * <p>
 * 로그에는 매장·사장·초대 코드 id 만 남긴다 — 코드 원문·해시, 매장명·아이디·이름·비밀번호는 남기지 않는다. 가입 뒤 자동 로그인은 하지 않는다.
 */
@Service
public class StoreSetupService {

	private static final Logger log = LoggerFactory.getLogger(StoreSetupService.class);

	private final StoreInviteCodeRepository storeInviteCodeRepository;
	private final EmployeeRepository employeeRepository;
	private final StoreRepository storeRepository;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	public StoreSetupService(StoreInviteCodeRepository storeInviteCodeRepository, EmployeeRepository employeeRepository,
			StoreRepository storeRepository, PasswordEncoder passwordEncoder, Clock clock) {
		this.storeInviteCodeRepository = storeInviteCodeRepository;
		this.employeeRepository = employeeRepository;
		this.storeRepository = storeRepository;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	/**
	 * 초대 코드로 새 매장과 그 사장 계정을 만든다. 입력 형식(아이디 규칙·비밀번호 길이·확인 일치·코드 길이)은 요청 DTO 가 이미 검사했다.
	 *
	 * @param inviteCode 입력한 초대 코드 원문 — 공백·하이픈·대소문자는 {@link StoreInviteCode#normalize} 가 고친다
	 * @throws InvalidInviteCodeException 없는 코드·사용 중지된 코드·한도를 다 쓴 코드 (구분하지 않는다)
	 * @throws DuplicateLoginIdException 같은 아이디(모든 매장, 비활성 포함, 대소문자 구분)가 이미 있을 때
	 */
	@Transactional
	public StoreSetupResult setUp(String inviteCode, String storeName, String ownerName, String loginId,
			String rawPassword) {
		UUID inviteCodeId = storeInviteCodeRepository.claimUse(StoreInviteCode.hashOf(inviteCode))
				.orElseThrow(InvalidInviteCodeException::new);
		if (employeeRepository.existsByLoginId(loginId)) {
			throw new DuplicateLoginIdException();
		}
		Instant now = clock.instant();
		Store store = Store.open(storeName, inviteCodeId, now);
		storeRepository.add(store);

		Employee admin = Employee.createOwner(store.getId(), loginId, passwordEncoder.encode(rawPassword), ownerName, now);
		employeeRepository.add(admin);

		log.info("store signup completed: storeId={}, adminEmployeeId={}, inviteCodeId={}", store.getId(), admin.getId(),
				inviteCodeId);
		return new StoreSetupResult(store.getName(), admin.getId(), admin.getLoginId(), admin.getName(), admin.getRole());
	}

}
