package com.creditbook.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
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

	private AuthService authService;
	private Employee owner;

	@BeforeEach
	void setUp() {
		authService = new AuthService(employeeRepository, revokedTokenRepository, passwordEncoder, tokenService, clock);
		owner = Employee.create("owner", passwordEncoder.encode(PASSWORD), "김사장", Role.ADMIN,
				Instant.parse("2026-10-01T00:00:00Z"));
		given(employeeRepository.findByLoginId("owner")).willReturn(Optional.of(owner));
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
		given(employeeRepository.findByLoginId("nobody")).willReturn(Optional.empty());

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
		given(employeeRepository.findByLoginId("retired")).willReturn(Optional.of(inactive));

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
		given(employeeRepository.findByLoginId("staff1")).willReturn(Optional.of(staff));

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
