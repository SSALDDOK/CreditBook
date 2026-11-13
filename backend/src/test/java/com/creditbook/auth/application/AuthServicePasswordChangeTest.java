package com.creditbook.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.creditbook.auth.domain.AccountLockedException;
import com.creditbook.auth.domain.Employee;
import com.creditbook.auth.domain.EmployeeRepository;
import com.creditbook.auth.domain.InvalidCredentialsException;
import com.creditbook.auth.domain.InvalidCurrentPasswordException;
import com.creditbook.auth.domain.PasswordUnchangedException;
import com.creditbook.auth.domain.RevokedTokenRepository;
import com.creditbook.auth.domain.Role;
import com.creditbook.global.security.JwtTestSupport;
import com.creditbook.global.security.JwtTestSupport.MutableClock;
import com.creditbook.global.security.UnauthenticatedException;

/**
 * 로그인한 직원의 비밀번호 변경 (REQ-30). 현재 비밀번호 틀림은 로그인 실패와 같은 횟수로 센다 (REQ-15).
 * 비밀번호 대조는 실제 BCrypt(테스트용 낮은 비용 계수)를 쓴다.
 */
@Tag("REQ-30")
@ExtendWith(OutputCaptureExtension.class)
class AuthServicePasswordChangeTest {

	private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000005001");

	private static final Instant NOW = Instant.parse("2026-11-03T01:00:00Z");
	private static final String INITIAL = "Rv7m-Kq2x-9Tn4";
	private static final String NEW_PASSWORD = "my-new-pass-77";
	private static final int MAX_FAILURES = 5;
	private static final Duration LOCK = Duration.ofMinutes(5);

	private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
	private final PasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder(4));
	private final MutableClock clock = new MutableClock(NOW);

	private AuthService authService;
	private Employee staff;

	@BeforeEach
	void setUp() {
		authService = new AuthService(employeeRepository, mock(RevokedTokenRepository.class), passwordEncoder,
				JwtTestSupport.tokenService(JwtTestSupport.randomProperties(), clock), clock,
				new LoginLockProperties(MAX_FAILURES, LOCK));
		// 사장이 추가해 초기 비밀번호를 받은 직원 (비밀번호 변경 필요)
		staff = Employee.createStaff(STORE_ID, "lee.staff", passwordEncoder.encode(INITIAL), "이직원",
				Instant.parse("2026-11-01T00:00:00Z"));
		given(employeeRepository.findByIdForUpdate(staff.getId())).willReturn(Optional.of(staff));
		clearInvocations(passwordEncoder);
	}

	private void failChange(int times) {
		for (int i = 0; i < times; i++) {
			assertThatThrownBy(() -> authService.changePassword(staff.getId(), "wrong-current", NEW_PASSWORD))
					.isInstanceOf(InvalidCurrentPasswordException.class);
		}
	}

	@Test
	@DisplayName("현재 비밀번호가 맞으면 새 비밀번호로 바뀌고 비밀번호 변경 필요 표시가 내려간다")
	void change_password_succeeds(CapturedOutput output) {
		// when
		AuthenticatedEmployee result = authService.changePassword(staff.getId(), INITIAL, NEW_PASSWORD);

		// then
		assertThat(result).isEqualTo(new AuthenticatedEmployee(staff.getId(), "이직원", Role.STAFF, false));
		assertThat(staff.mustChangePassword()).isFalse();
		assertThat(passwordEncoder.matches(NEW_PASSWORD, staff.getPasswordHash())).isTrue();
		assertThat(passwordEncoder.matches(INITIAL, staff.getPasswordHash())).isFalse();
		assertThat(staff.getUpdatedAt()).isEqualTo(NOW);
		assertThat(output.getOut()).contains("password changed: employeeId=" + staff.getId())
				.doesNotContain(INITIAL).doesNotContain(NEW_PASSWORD).doesNotContain("lee.staff");
	}

	@Test
	@DisplayName("비밀번호를 바꿔야 하지 않는 계정도 스스로 비밀번호를 바꿀 수 있다")
	void ready_employee_can_change_password_again() {
		// given
		authService.changePassword(staff.getId(), INITIAL, NEW_PASSWORD);

		// when
		authService.changePassword(staff.getId(), NEW_PASSWORD, "another-pass-88");

		// then
		assertThat(staff.mustChangePassword()).isFalse();
		assertThat(passwordEncoder.matches("another-pass-88", staff.getPasswordHash())).isTrue();
	}

	@Test
	@DisplayName("현재 비밀번호가 틀리면 400 으로 거절하고 로그인 실패와 같은 횟수로 센다")
	void wrong_current_password_counts_as_login_failure(CapturedOutput output) {
		// when
		failChange(1);

		// then
		assertThat(staff.getFailedLoginCount()).isEqualTo(1);
		assertThat(passwordEncoder.matches(INITIAL, staff.getPasswordHash())).isTrue();
		assertThat(staff.mustChangePassword()).isTrue();
		assertThat(output.getOut()).contains("password change failed: employeeId=" + staff.getId())
				.doesNotContain("wrong-current");
	}

	@Test
	@Tag("REQ-15")
	@DisplayName("현재 비밀번호를 연속 5번째 틀린 응답부터 계정이 잠겨 423 으로 거절하고 남은 시간은 5분이다")
	void fifth_wrong_current_password_locks_account() {
		// given
		failChange(MAX_FAILURES - 1);

		// when / then
		assertThatThrownBy(() -> authService.changePassword(staff.getId(), "wrong-current", NEW_PASSWORD))
				.isInstanceOfSatisfying(AccountLockedException.class,
						ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(300));
		assertThat(staff.isLocked(NOW)).isTrue();
	}

	@Test
	@Tag("REQ-15")
	@DisplayName("로그인 실패와 비밀번호 변경 실패는 같은 횟수로 합쳐 센다")
	void login_and_change_failures_share_counter() {
		// given — 로그인 4번 실패
		given(employeeRepository.findByLoginIdForUpdate("lee.staff")).willReturn(Optional.of(staff));
		for (int i = 0; i < MAX_FAILURES - 1; i++) {
			assertThatThrownBy(() -> authService.login("lee.staff", "wrong-login"))
					.isInstanceOf(InvalidCredentialsException.class);
		}

		// when / then — 비밀번호 변경 1번 실패로 5번째
		assertThatThrownBy(() -> authService.changePassword(staff.getId(), "wrong-current", NEW_PASSWORD))
				.isInstanceOf(AccountLockedException.class);
	}

	@Test
	@Tag("REQ-15")
	@DisplayName("잠긴 동안에는 올바른 현재 비밀번호도 423 으로 거절하고 비밀번호를 대조하지 않는다")
	void locked_account_rejects_change_without_compare() {
		// given
		failChange(MAX_FAILURES - 1);
		assertThatThrownBy(() -> authService.changePassword(staff.getId(), "wrong-current", NEW_PASSWORD))
				.isInstanceOf(AccountLockedException.class);
		clock.set(NOW.plus(LOCK).minusSeconds(1));
		clearInvocations(passwordEncoder);

		// when / then
		assertThatThrownBy(() -> authService.changePassword(staff.getId(), INITIAL, NEW_PASSWORD))
				.isInstanceOfSatisfying(AccountLockedException.class,
						ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(1));
		verify(passwordEncoder, never()).matches(anyString(), anyString());
		assertThat(staff.mustChangePassword()).isTrue();
		assertThat(staff.getFailedLoginCount()).isEqualTo(MAX_FAILURES);
	}

	@Test
	@Tag("REQ-15")
	@DisplayName("비밀번호를 바꾸면 그전까지의 실패 횟수가 초기화된다")
	void success_resets_failure_count() {
		// given
		failChange(MAX_FAILURES - 1);

		// when
		authService.changePassword(staff.getId(), INITIAL, NEW_PASSWORD);

		// then
		assertThat(staff.getFailedLoginCount()).isZero();
		assertThat(staff.getLockedUntil()).isNull();
	}

	@Test
	@DisplayName("새 비밀번호가 현재 비밀번호와 같으면 400 으로 거절하고 아무것도 바꾸지 않는다")
	void same_password_is_rejected() {
		// when / then
		assertThatThrownBy(() -> authService.changePassword(staff.getId(), INITIAL, INITIAL))
				.isInstanceOf(PasswordUnchangedException.class)
				.hasMessage("현재 비밀번호와 다른 비밀번호를 입력해 주세요.");
		assertThat(staff.mustChangePassword()).isTrue();
		assertThat(staff.getFailedLoginCount()).isZero();
		assertThat(passwordEncoder.matches(INITIAL, staff.getPasswordHash())).isTrue();
	}

	@Test
	@DisplayName("현재 비밀번호가 틀렸으면 새 비밀번호가 같아도 같음 대신 현재 비밀번호 틀림으로 응답한다")
	void wrong_current_password_is_checked_before_unchanged() {
		assertThatThrownBy(() -> authService.changePassword(staff.getId(), "wrong-current", "wrong-current"))
				.isInstanceOf(InvalidCurrentPasswordException.class);
	}

	@Test
	@DisplayName("직원이 없거나 비활성이면 401 로 거절한다")
	void unknown_or_inactive_employee_is_unauthenticated() {
		// given
		UUID unknown = UUID.randomUUID();
		given(employeeRepository.findByIdForUpdate(unknown)).willReturn(Optional.empty());
		Employee inactive = Employee.createStaff(STORE_ID, "old.staff", passwordEncoder.encode(INITIAL), "퇴사자", NOW);
		inactive.deactivate(NOW);
		given(employeeRepository.findByIdForUpdate(inactive.getId())).willReturn(Optional.of(inactive));

		// when / then
		assertThatThrownBy(() -> authService.changePassword(unknown, INITIAL, NEW_PASSWORD))
				.isInstanceOf(UnauthenticatedException.class);
		assertThatThrownBy(() -> authService.changePassword(inactive.getId(), INITIAL, NEW_PASSWORD))
				.isInstanceOf(UnauthenticatedException.class);
		assertThatThrownBy(() -> authService.changePassword(null, INITIAL, NEW_PASSWORD))
				.isInstanceOf(UnauthenticatedException.class);
		assertThat(passwordEncoder.matches(INITIAL, inactive.getPasswordHash())).isTrue();
	}

	@Test
	@DisplayName("비밀번호 변경 실패·잠금 예외로 끝나도 트랜잭션을 롤백하지 않는다 — 실패 횟수·잠금이 커밋돼야 한다")
	void change_password_transaction_commits_on_failure_exceptions() throws NoSuchMethodException {
		// given
		Method changePassword = AuthService.class.getMethod("changePassword", UUID.class, String.class, String.class);

		// when
		Transactional transactional = changePassword.getAnnotation(Transactional.class);

		// then
		assertThat(transactional).isNotNull();
		assertThat(transactional.noRollbackFor())
				.contains(InvalidCurrentPasswordException.class, AccountLockedException.class);
	}

}
