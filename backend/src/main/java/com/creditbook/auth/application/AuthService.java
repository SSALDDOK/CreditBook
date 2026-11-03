package com.creditbook.auth.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.auth.domain.AccountLockedException;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeCredentialRules;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.InvalidCredentialsException;
import com.creditbook.auth.domain.RevokedTokenRepository;
import com.creditbook.global.security.JwtTokenService;
import com.creditbook.global.security.JwtTokenService.IssuedToken;
import com.creditbook.global.security.UnauthenticatedException;

/**
 * 직원 로그인·로그아웃·세션 조회 (REQ-15·22, CB-42), 로그인 잠금 (REQ-15, CB-15).
 * <p>
 * 로그인 실패는 사유(아이디 없음·비밀번호 틀림·비활성·비밀번호 72바이트 초과)를 응답에서 구분하지 않는다. 다만 있는 계정이
 * 연속 실패로 잠기면 423 으로 따로 알린다 — 잠금을 알려야 직원이 기다릴 수 있다. 응답 시간으로도 구분되지 않도록
 * 직원을 못 찾았거나 비밀번호가 너무 길 때도 기동 시 만든 더미 해시와 한 번 대조한다(같은 비용 계수).
 * 로그에는 직원을 찾았을 때만 직원 ID 를 남기고, 입력한 아이디·비밀번호·해시·토큰 원문은 남기지 않는다.
 */
@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	/** BCrypt 는 72바이트까지만 쓴다. 넘으면 IllegalArgumentException(500)이 되므로 대조 전에 거른다. */
	static final int BCRYPT_MAX_PASSWORD_BYTES = EmployeeCredentialRules.PASSWORD_MAX_BYTES;

	private static final String DUMMY_PASSWORD = "dummy-password-for-timing";

	private final EmployeeRepository employeeRepository;
	private final RevokedTokenRepository revokedTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenService tokenService;
	private final Clock clock;
	private final LoginLockProperties loginLockProperties;
	private final String dummyHash;

	public AuthService(EmployeeRepository employeeRepository, RevokedTokenRepository revokedTokenRepository,
			PasswordEncoder passwordEncoder, JwtTokenService tokenService, Clock clock,
			LoginLockProperties loginLockProperties) {
		this.employeeRepository = employeeRepository;
		this.revokedTokenRepository = revokedTokenRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenService = tokenService;
		this.clock = clock;
		this.loginLockProperties = loginLockProperties;
		this.dummyHash = passwordEncoder.encode(DUMMY_PASSWORD + UUID.randomUUID());
	}

	/**
	 * 아이디·비밀번호를 확인하고 토큰을 발급한다. 성공하면 마지막 로그인 시각을 기록하고 연속 실패 횟수를 초기화한다.
	 * <p>
	 * 순서 (REQ-15): 직원 조회(행 잠금) → 잠겨 있으면 거절(비밀번호를 대조하지 않는다 — 맞아도 423) → 비밀번호 대조 →
	 * 틀리면 실패를 기록하고 기준 횟수째면 잠근다(그 응답부터 423) → 비활성이면 거절 → 성공.
	 * 없는 아이디는 아무것도 저장하지 않고 항상 같은 실패로 응답한다. 같은 IP 의 요청 수 제한은 이 앞의 필터가 맡는다.
	 * <p>
	 * 실패로 끝나도 실패 횟수·잠금은 커밋돼야 하므로 두 실패 예외에는 롤백하지 않는다.
	 *
	 * @throws InvalidCredentialsException 아이디가 없거나, 비밀번호가 틀리거나, 비활성 직원이거나, 비밀번호가 72바이트를 넘을 때
	 * @throws AccountLockedException 잠긴 계정이거나, 이번 실패로 잠겼을 때
	 */
	@Transactional(noRollbackFor = { InvalidCredentialsException.class, AccountLockedException.class })
	public LoginResult login(String loginId, String password) {
		boolean passwordTooLong = password == null
				|| password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES;
		Optional<Employee> found = loginId == null ? Optional.empty() : employeeRepository.findByLoginIdForUpdate(loginId);

		if (found.isEmpty()) {
			passwordEncoder.matches(DUMMY_PASSWORD, dummyHash);
			log.warn("login failed: reason=unknown login id");
			throw new InvalidCredentialsException();
		}

		Employee employee = found.get();
		Instant now = clock.instant();
		if (employee.isLocked(now)) {
			log.warn("login rejected: employeeId={} reason=account locked", employee.getId());
			throw new AccountLockedException(employee.lockRemaining(now));
		}

		if (passwordTooLong) {
			passwordEncoder.matches(DUMMY_PASSWORD, dummyHash);
			throw recordFailure(employee, now, "password too long");
		}
		if (!passwordEncoder.matches(password, employee.getPasswordHash())) {
			throw recordFailure(employee, now, "password mismatch");
		}
		if (!employee.canLogin()) {
			log.warn("login failed: employeeId={} reason=inactive employee", employee.getId());
			throw new InvalidCredentialsException();
		}

		employee.recordLogin(now);
		IssuedToken token = tokenService.issue(employee.getId());
		log.info("login succeeded: employeeId={} role={}", employee.getId(), employee.getRole());
		return new LoginResult(AuthenticatedEmployee.from(employee), token);
	}

	/** 실패를 기록하고 던질 예외를 돌려준다. 이번 실패로 잠겼으면 423, 아니면 401. 비활성 직원도 계정이 있으므로 센다. */
	private RuntimeException recordFailure(Employee employee, Instant now, String reason) {
		boolean locked = employee.recordLoginFailure(now, loginLockProperties.maxFailures(), loginLockProperties.duration());
		log.warn("login failed: employeeId={} reason={}", employee.getId(), reason);
		if (locked) {
			log.warn("account locked: employeeId={} duration={}", employee.getId(), loginLockProperties.duration());
			return new AccountLockedException(employee.lockRemaining(now));
		}
		return new InvalidCredentialsException();
	}

	/**
	 * 토큰을 폐기 목록에 넣는다. 토큰이 없거나 이미 유효하지 않으면 아무것도 하지 않는다 — 로그아웃은 항상 성공한다.
	 * 같은 토큰으로 두 번 불러도 된다.
	 */
	@Transactional
	public void logout(String tokenValue) {
		tokenService.verify(tokenValue).ifPresentOrElse(token -> {
			revokedTokenRepository.revoke(token.jti(), token.employeeId(), token.expiresAt(), clock.instant());
			log.info("logout: employeeId={}", token.employeeId());
		}, () -> log.debug("logout without a valid token"));
	}

	/**
	 * 현재 로그인한 직원의 정보.
	 *
	 * @throws UnauthenticatedException 직원이 없거나 비활성일 때 (인증 직후 비활성화된 경우)
	 */
	@Transactional(readOnly = true)
	public AuthenticatedEmployee session(UUID employeeId) {
		return employeeRepository.findById(employeeId)
				.filter(Employee::canLogin)
				.map(AuthenticatedEmployee::from)
				.orElseThrow(UnauthenticatedException::new);
	}

}
