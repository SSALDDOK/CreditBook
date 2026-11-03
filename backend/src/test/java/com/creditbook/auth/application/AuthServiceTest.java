package com.creditbook.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.auth.domain.AccountLockedException;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.InvalidCredentialsException;
import com.creditbook.auth.domain.RevokedTokenRepository;
import com.creditbook.auth.domain.Role;
import com.creditbook.global.security.JwtTestSupport;
import com.creditbook.global.security.JwtTestSupport.MutableClock;
import com.creditbook.global.security.JwtTokenService;
import com.creditbook.global.security.JwtTokenService.VerifiedToken;
import com.creditbook.global.security.UnauthenticatedException;

/**
 * 로그인·로그아웃·세션 조회 유스케이스. 비밀번호 대조는 실제 BCrypt(테스트용 낮은 비용 계수), 토큰은 실제 발급기(무작위 키)를 쓴다.
 */
@Tag("REQ-15")
@Tag("REQ-22")
class AuthServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-07T00:30:00Z");
	private static final String PASSWORD = "correct-horse-9";

	private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
	private final RevokedTokenRepository revokedTokenRepository = mock(RevokedTokenRepository.class);
	private final PasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder(4));
	private final MutableClock clock = new MutableClock(NOW);
	private final JwtTokenService tokenService = JwtTestSupport.tokenService(JwtTestSupport.randomProperties(), clock);
	private static final int MAX_FAILURES = 5;
	private static final Duration LOCK = Duration.ofMinutes(5);
	private final LoginLockProperties loginLockProperties = new LoginLockProperties(MAX_FAILURES, LOCK);

	private AuthService authService;
	private Employee owner;

	@BeforeEach
	void setUp() {
		authService = new AuthService(employeeRepository, revokedTokenRepository, passwordEncoder, tokenService, clock,
				loginLockProperties);
		owner = Employee.create("owner", passwordEncoder.encode(PASSWORD), "김사장", Role.ADMIN,
				Instant.parse("2026-10-01T00:00:00Z"));
		given(employeeRepository.findByLoginIdForUpdate("owner")).willReturn(Optional.of(owner));
		clearInvocations(passwordEncoder);
	}

	@Test
	@DisplayName("아이디와 비밀번호가 맞으면 토큰을 발급하고 마지막 로그인 시각을 기록한다")
	void login_succeeds_with_correct_credentials() {
		// when
		LoginResult result = authService.login("owner", PASSWORD);

		// then
		assertThat(result.employee()).isEqualTo(new AuthenticatedEmployee(owner.getId(), "김사장", Role.ADMIN, true));
		assertThat(result.token().expiresAt()).isEqualTo(NOW.plus(JwtTestSupport.TTL));
		assertThat(tokenService.verify(result.token().value()))
				.get().extracting(VerifiedToken::employeeId).isEqualTo(owner.getId());
		assertThat(owner.getLastLoginAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("비밀번호가 틀리면 로그인에 실패하고 마지막 로그인 시각은 바뀌지 않는다")
	void login_fails_with_wrong_password() {
		// when / then
		assertThatThrownBy(() -> authService.login("owner", "wrong-password"))
				.isInstanceOf(InvalidCredentialsException.class);
		assertThat(owner.getLastLoginAt()).isNull();
	}

	@Test
	@DisplayName("없는 아이디면 로그인에 실패하고, 응답 시간 차이를 없애려고 더미 해시와 한 번 대조한다")
	void login_fails_with_unknown_login_id_after_dummy_compare() {
		// given
		given(employeeRepository.findByLoginIdForUpdate("nobody")).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> authService.login("nobody", PASSWORD))
				.isInstanceOf(InvalidCredentialsException.class);
		verify(passwordEncoder, times(1)).matches(anyString(), anyString());
	}

	@Test
	@DisplayName("비활성 직원은 비밀번호가 맞아도 로그인에 실패한다")
	void inactive_employee_cannot_login() {
		// given
		Employee inactive = mock(Employee.class);
		given(inactive.getId()).willReturn(UUID.randomUUID());
		given(inactive.getPasswordHash()).willReturn(owner.getPasswordHash());
		given(inactive.canLogin()).willReturn(false);
		given(employeeRepository.findByLoginIdForUpdate("retired")).willReturn(Optional.of(inactive));

		// when / then
		assertThatThrownBy(() -> authService.login("retired", PASSWORD))
				.isInstanceOf(InvalidCredentialsException.class);
		verify(inactive, never()).recordLogin(any());
	}

	@ParameterizedTest(name = "[{index}] {0}바이트")
	@ValueSource(ints = { 73, 100, 1000 })
	@DisplayName("비밀번호가 72바이트를 넘으면 서버 오류가 아니라 로그인 실패로 응답하고, 실제 해시와 대조하지 않는다")
	void password_longer_than_72_bytes_is_invalid_credentials(int bytes) {
		// given
		String tooLong = "a".repeat(bytes);

		// when / then
		assertThatThrownBy(() -> authService.login("owner", tooLong)).isInstanceOf(InvalidCredentialsException.class);
		verify(passwordEncoder, times(1)).matches(anyString(), anyString());
		verify(passwordEncoder, never()).matches(tooLong, owner.getPasswordHash());
	}

	@Test
	@DisplayName("한글 24자(72바이트) 비밀번호는 대조하고, 25자(75바이트)는 로그인 실패로 응답한다")
	void multibyte_password_boundary() {
		// given
		String password72 = "가".repeat(24);
		assertThat(password72.getBytes(StandardCharsets.UTF_8)).hasSize(72);
		Employee staff = Employee.create("staff1", passwordEncoder.encode(password72), "이직원", Role.STAFF, NOW);
		given(employeeRepository.findByLoginIdForUpdate("staff1")).willReturn(Optional.of(staff));

		// when
		LoginResult result = authService.login("staff1", password72);

		// then
		assertThat(result.employee().employeeId()).isEqualTo(staff.getId());
		assertThatThrownBy(() -> authService.login("staff1", "가".repeat(25)))
				.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	@DisplayName("아이디나 비밀번호가 null 이어도 예외 없이 로그인 실패로 응답한다")
	void null_inputs_are_invalid_credentials() {
		assertThatThrownBy(() -> authService.login(null, PASSWORD)).isInstanceOf(InvalidCredentialsException.class);
		assertThatThrownBy(() -> authService.login("owner", null)).isInstanceOf(InvalidCredentialsException.class);
	}

	private void failLogin(int times) {
		for (int i = 0; i < times; i++) {
			assertThatThrownBy(() -> authService.login("owner", "wrong-password"))
					.isInstanceOf(InvalidCredentialsException.class);
		}
	}

	@Test
	@DisplayName("4번째 실패까지는 401, 연속 5번째 실패 응답부터 계정이 잠겨 423 으로 거절하고 남은 시간은 5분이다")
	void fifth_failure_locks_account() {
		// given
		failLogin(MAX_FAILURES - 1);

		// when / then
		assertThatThrownBy(() -> authService.login("owner", "wrong-password"))
				.isInstanceOfSatisfying(AccountLockedException.class,
						ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(300));
		assertThat(owner.isLocked(NOW)).isTrue();
		assertThat(owner.getFailedLoginCount()).isEqualTo(MAX_FAILURES);
	}

	@Test
	@DisplayName("잠긴 동안에는 올바른 비밀번호도 423 으로 거절하고, 비밀번호를 대조하지 않으며 토큰을 발급하지 않는다")
	void locked_account_rejects_correct_password_without_compare() {
		// given
		failLogin(MAX_FAILURES - 1);
		assertThatThrownBy(() -> authService.login("owner", "wrong-password")).isInstanceOf(AccountLockedException.class);
		clock.set(NOW.plus(LOCK).minusSeconds(1));
		clearInvocations(passwordEncoder);

		// when / then
		assertThatThrownBy(() -> authService.login("owner", PASSWORD))
				.isInstanceOfSatisfying(AccountLockedException.class,
						ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(1));
		verify(passwordEncoder, never()).matches(anyString(), anyString());
		assertThat(owner.getLastLoginAt()).isNull();
		assertThat(owner.getFailedLoginCount()).isEqualTo(MAX_FAILURES);
	}

	@Test
	@DisplayName("잠금 5분이 지나면 올바른 비밀번호로 로그인되고 실패 횟수가 초기화된다")
	void login_succeeds_after_lock_expires() {
		// given
		failLogin(MAX_FAILURES - 1);
		assertThatThrownBy(() -> authService.login("owner", "wrong-password")).isInstanceOf(AccountLockedException.class);
		clock.set(NOW.plus(LOCK));

		// when
		LoginResult result = authService.login("owner", PASSWORD);

		// then
		assertThat(result.employee().employeeId()).isEqualTo(owner.getId());
		assertThat(owner.getFailedLoginCount()).isZero();
		assertThat(owner.getLockedUntil()).isNull();
	}

	@Test
	@DisplayName("성공하면 실패 횟수가 초기화돼, 그 뒤 4번 더 틀려도 잠기지 않는다")
	void success_resets_failure_count() {
		// given
		failLogin(MAX_FAILURES - 1);
		authService.login("owner", PASSWORD);

		// when
		failLogin(MAX_FAILURES - 1);

		// then
		assertThat(owner.isLocked(NOW)).isFalse();
		assertThat(owner.getFailedLoginCount()).isEqualTo(MAX_FAILURES - 1);
	}

	@Test
	@DisplayName("없는 아이디는 몇 번을 시도해도 항상 401 이고 저장소에 아무것도 쓰지 않는다")
	void unknown_login_id_is_never_locked() {
		// given
		given(employeeRepository.findByLoginIdForUpdate("nobody")).willReturn(Optional.empty());

		// when / then
		for (int i = 0; i < MAX_FAILURES + 2; i++) {
			assertThatThrownBy(() -> authService.login("nobody", PASSWORD))
					.isInstanceOf(InvalidCredentialsException.class);
		}
		verify(employeeRepository, times(MAX_FAILURES + 2)).findByLoginIdForUpdate("nobody");
		verify(employeeRepository, never()).add(any());
	}

	@Test
	@DisplayName("비활성 직원의 틀린 비밀번호도 실패로 센다 — 계정이 있으므로")
	void inactive_employee_wrong_password_counts_as_failure() {
		// given
		Employee inactive = mock(Employee.class);
		given(inactive.getId()).willReturn(UUID.randomUUID());
		given(inactive.getPasswordHash()).willReturn(owner.getPasswordHash());
		given(inactive.canLogin()).willReturn(false);
		given(employeeRepository.findByLoginIdForUpdate("retired")).willReturn(Optional.of(inactive));

		// when / then
		assertThatThrownBy(() -> authService.login("retired", "wrong-password"))
				.isInstanceOf(InvalidCredentialsException.class);
		verify(inactive).recordLoginFailure(eq(NOW), eq(MAX_FAILURES), eq(LOCK));
	}

	@Test
	@DisplayName("비활성 직원이 비밀번호를 맞히면 401 이고 실패로 세지 않는다")
	void inactive_employee_correct_password_is_not_counted() {
		// given
		Employee inactive = mock(Employee.class);
		given(inactive.getId()).willReturn(UUID.randomUUID());
		given(inactive.getPasswordHash()).willReturn(owner.getPasswordHash());
		given(inactive.canLogin()).willReturn(false);
		given(employeeRepository.findByLoginIdForUpdate("retired")).willReturn(Optional.of(inactive));

		// when / then
		assertThatThrownBy(() -> authService.login("retired", PASSWORD))
				.isInstanceOf(InvalidCredentialsException.class);
		verify(inactive, never()).recordLoginFailure(any(), anyInt(), any());
	}

	@Test
	@DisplayName("72바이트를 넘는 비밀번호도 있는 계정이면 실패로 센다")
	void too_long_password_counts_as_failure() {
		// when
		assertThatThrownBy(() -> authService.login("owner", "a".repeat(73)))
				.isInstanceOf(InvalidCredentialsException.class);

		// then
		assertThat(owner.getFailedLoginCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("로그인 실패·잠금 예외로 끝나도 트랜잭션을 롤백하지 않는다 — 실패 횟수·잠금이 커밋돼야 한다")
	void login_transaction_commits_on_failure_exceptions() throws NoSuchMethodException {
		// given
		Method login = AuthService.class.getMethod("login", String.class, String.class);

		// when
		Transactional transactional = login.getAnnotation(Transactional.class);

		// then
		assertThat(transactional).isNotNull();
		assertThat(transactional.noRollbackFor())
				.contains(InvalidCredentialsException.class, AccountLockedException.class);
	}

	@Test
	@DisplayName("유효한 토큰으로 로그아웃하면 그 토큰을 만료 시각과 함께 폐기 목록에 넣는다")
	void logout_revokes_valid_token() {
		// given
		String token = authService.login("owner", PASSWORD).token().value();
		VerifiedToken verified = tokenService.verify(token).orElseThrow();
		Instant logoutAt = NOW.plusSeconds(600);
		clock.set(logoutAt);

		// when
		authService.logout(token);

		// then
		verify(revokedTokenRepository).revoke(verified.jti(), owner.getId(), verified.expiresAt(), logoutAt);
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@ValueSource(strings = { "", "garbage", "a.b.c" })
	@DisplayName("토큰이 유효하지 않아도 로그아웃은 예외 없이 끝나고 아무것도 저장하지 않는다")
	void logout_with_invalid_token_does_nothing(String token) {
		// when
		authService.logout(token);

		// then
		verifyNoInteractions(revokedTokenRepository);
	}

	@Test
	@DisplayName("토큰 없이 로그아웃해도 예외 없이 끝난다")
	void logout_without_token_does_nothing() {
		// when
		authService.logout(null);

		// then
		verifyNoInteractions(revokedTokenRepository);
	}

	@Test
	@DisplayName("세션 조회는 현재 직원의 이름·역할·비밀번호 변경 필요 여부를 돌려준다")
	void session_returns_current_employee() {
		// given
		given(employeeRepository.findById(owner.getId())).willReturn(Optional.of(owner));

		// when
		AuthenticatedEmployee employee = authService.session(owner.getId());

		// then
		assertThat(employee).isEqualTo(new AuthenticatedEmployee(owner.getId(), "김사장", Role.ADMIN, true));
	}

	@Test
	@DisplayName("세션 조회 중 직원이 없으면 401 로 거절한다")
	void session_for_unknown_employee_is_unauthenticated() {
		// given
		UUID unknown = UUID.randomUUID();
		given(employeeRepository.findById(unknown)).willReturn(Optional.empty());

		// when / then
		assertThatThrownBy(() -> authService.session(unknown)).isInstanceOf(UnauthenticatedException.class);
	}

}
