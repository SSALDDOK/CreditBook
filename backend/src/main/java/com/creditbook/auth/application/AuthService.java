package com.creditbook.auth.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.InvalidCredentialsException;
import com.creditbook.auth.domain.RevokedTokenRepository;
import com.creditbook.global.security.JwtTokenService;
import com.creditbook.global.security.JwtTokenService.IssuedToken;
import com.creditbook.global.security.UnauthenticatedException;

/**
 * 직원 로그인·로그아웃·세션 조회 (REQ-15·22, CB-42).
 * <p>
 * 로그인 실패는 사유(아이디 없음·비밀번호 틀림·비활성·비밀번호 72바이트 초과)를 응답에서 구분하지 않는다. 응답 시간으로도 구분되지 않도록
 * 직원을 못 찾았거나 비밀번호가 너무 길 때도 기동 시 만든 더미 해시와 한 번 대조한다(같은 비용 계수).
 * 로그에는 직원을 찾았을 때만 직원 ID 를 남기고, 입력한 아이디·비밀번호·해시·토큰 원문은 남기지 않는다.
 */
@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	/** BCrypt 는 72바이트까지만 쓴다. 넘으면 IllegalArgumentException(500)이 되므로 대조 전에 거른다. */
	static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

	private static final String DUMMY_PASSWORD = "dummy-password-for-timing";

	private final EmployeeRepository employeeRepository;
	private final RevokedTokenRepository revokedTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenService tokenService;
	private final Clock clock;
	private final String dummyHash;

	public AuthService(EmployeeRepository employeeRepository, RevokedTokenRepository revokedTokenRepository,
			PasswordEncoder passwordEncoder, JwtTokenService tokenService, Clock clock) {
		this.employeeRepository = employeeRepository;
		this.revokedTokenRepository = revokedTokenRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenService = tokenService;
		this.clock = clock;
		this.dummyHash = passwordEncoder.encode(DUMMY_PASSWORD + UUID.randomUUID());
	}

	/**
	 * 아이디·비밀번호를 확인하고 토큰을 발급한다. 성공하면 마지막 로그인 시각을 기록한다.
	 *
	 * @throws InvalidCredentialsException 아이디가 없거나, 비밀번호가 틀리거나, 비활성 직원이거나, 비밀번호가 72바이트를 넘을 때
	 */
	@Transactional
	public LoginResult login(String loginId, String password) {
		boolean passwordTooLong = password == null
				|| password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES;
		Optional<Employee> found = loginId == null ? Optional.empty() : employeeRepository.findByLoginId(loginId);

		if (found.isEmpty() || passwordTooLong) {
			passwordEncoder.matches(DUMMY_PASSWORD, dummyHash);
			if (found.isPresent()) {
				log.warn("login failed: employeeId={} reason=password too long", found.get().getId());
			}
			else {
				log.warn("login failed: reason=unknown login id");
			}
			throw new InvalidCredentialsException();
		}

		Employee employee = found.get();
		if (!passwordEncoder.matches(password, employee.getPasswordHash())) {
			log.warn("login failed: employeeId={} reason=password mismatch", employee.getId());
			throw new InvalidCredentialsException();
		}
		if (!employee.canLogin()) {
			log.warn("login failed: employeeId={} reason=inactive employee", employee.getId());
			throw new InvalidCredentialsException();
		}

		employee.recordLogin(clock.instant());
		IssuedToken token = tokenService.issue(employee.getId());
		log.info("login succeeded: employeeId={} role={}", employee.getId(), employee.getRole());
		return new LoginResult(AuthenticatedEmployee.from(employee), token);
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
