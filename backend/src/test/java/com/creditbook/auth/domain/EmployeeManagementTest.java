package com.creditbook.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 사장의 직원 관리(비밀번호 초기화·비활성화)와 본인 비밀번호 변경의 도메인 규칙 (REQ-30). 스프링 없이 순수 객체로 검증한다.
 */
@Tag("REQ-30")
class EmployeeManagementTest {

	private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000005001");

	private static final Instant CREATED_AT = Instant.parse("2026-11-01T00:00:00Z");
	private static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");
	private static final String HASH = "$2a$10$initialinitialinitialinitialinitialinitialinitialini";
	private static final String NEW_HASH = "$2a$10$changedchangedchangedchangedchangedchangedchangedcha";
	private static final int MAX_FAILURES = 5;
	private static final Duration LOCK = Duration.ofMinutes(5);

	private static Employee owner() {
		return Employee.createOwner(STORE_ID, "kim.owner", HASH, "김사장", CREATED_AT);
	}

	private static Employee staff() {
		return Employee.createStaff(STORE_ID, "lee.staff", HASH, "이직원", CREATED_AT);
	}

	private static void lock(Employee employee) {
		for (int i = 0; i < MAX_FAILURES; i++) {
			employee.recordLoginFailure(NOW, MAX_FAILURES, LOCK);
		}
	}

	// ── 관리 가능 여부 ──

	@Test
	@DisplayName("사장은 다른 직원(STAFF) 계정을 관리할 수 있다")
	void owner_can_manage_staff() {
		// given
		Employee owner = owner();
		Employee staff = staff();

		// when / then
		assertThatCode(() -> staff.requireManageableBy(owner.getId())).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("본인 계정은 비활성화하거나 비밀번호를 초기화할 수 없다")
	void own_account_is_protected() {
		// given
		Employee staff = staff();

		// when / then
		assertThatThrownBy(() -> staff.requireManageableBy(staff.getId()))
				.isInstanceOf(AdminAccountProtectedException.class)
				.hasMessage("사장님 계정은 비활성화하거나 비밀번호를 초기화할 수 없습니다.");
	}

	@Test
	@DisplayName("사장(ADMIN) 계정은 누가 요청해도 비활성화하거나 비밀번호를 초기화할 수 없다")
	void admin_account_is_protected() {
		// given
		Employee owner = owner();

		// when / then
		assertThatThrownBy(() -> owner.requireManageableBy(owner.getId()))
				.isInstanceOf(AdminAccountProtectedException.class);
		assertThatThrownBy(() -> owner.requireManageableBy(UUID.randomUUID()))
				.isInstanceOf(AdminAccountProtectedException.class);
	}

	@Test
	@DisplayName("요청한 직원 ID 가 없으면 관리 가능 여부를 판단하지 않는다")
	void null_actor_is_rejected() {
		assertThatThrownBy(() -> staff().requireManageableBy(null)).isInstanceOf(NullPointerException.class);
	}

	// ── 비밀번호 초기화 ──

	@Test
	@DisplayName("비밀번호를 초기화하면 새 해시로 바뀌고 다음 로그인 뒤 다시 바꿔야 한다")
	void reset_password_replaces_hash_and_requires_change() {
		// given — 첫 비밀번호를 이미 바꾼 직원
		Employee staff = staff();
		staff.changePassword(HASH, CREATED_AT);
		assertThat(staff.mustChangePassword()).isFalse();

		// when
		staff.resetPassword(NEW_HASH, NOW);

		// then
		assertThat(staff.getPasswordHash()).isEqualTo(NEW_HASH);
		assertThat(staff.mustChangePassword()).isTrue();
		assertThat(staff.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	@Tag("REQ-15")
	@DisplayName("비밀번호를 초기화하면 연속 실패 횟수가 0이 되고 잠금이 풀린다")
	void reset_password_clears_failures_and_lock() {
		// given
		Employee staff = staff();
		lock(staff);
		assertThat(staff.isLocked(NOW)).isTrue();

		// when
		staff.resetPassword(NEW_HASH, NOW);

		// then
		assertThat(staff.getFailedLoginCount()).isZero();
		assertThat(staff.getLockedUntil()).isNull();
		assertThat(staff.isLocked(NOW)).isFalse();
	}

	@Test
	@DisplayName("비활성 직원도 비밀번호를 초기화할 수 있고, 초기화해도 비활성 상태는 그대로다")
	void inactive_staff_can_be_reset_and_stays_inactive() {
		// given
		Employee staff = staff();
		staff.deactivate(CREATED_AT);

		// when
		staff.resetPassword(NEW_HASH, NOW);

		// then
		assertThat(staff.getPasswordHash()).isEqualTo(NEW_HASH);
		assertThat(staff.isActive()).isFalse();
		assertThat(staff.canLogin()).isFalse();
	}

	@Test
	@DisplayName("사장 계정의 비밀번호는 초기화할 수 없고 아무것도 바뀌지 않는다")
	void owner_password_cannot_be_reset() {
		// given
		Employee owner = owner();

		// when / then
		assertThatThrownBy(() -> owner.resetPassword(NEW_HASH, NOW)).isInstanceOf(AdminAccountProtectedException.class);
		assertThat(owner.getPasswordHash()).isEqualTo(HASH);
		assertThat(owner.mustChangePassword()).isFalse();
		assertThat(owner.getUpdatedAt()).isEqualTo(CREATED_AT);
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@NullAndEmptySource
	@ValueSource(strings = { "  " })
	@DisplayName("새 비밀번호 해시가 비어 있으면 초기화·변경하지 않는다")
	void blank_new_hash_is_rejected(String blank) {
		// given
		Employee staff = staff();

		// when / then
		assertThatThrownBy(() -> staff.resetPassword(blank, NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> staff.changePassword(blank, NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThat(staff.getPasswordHash()).isEqualTo(HASH);
	}

	@Test
	@DisplayName("변경 시각이 없으면 초기화·변경·비활성화하지 않는다")
	void null_time_is_rejected() {
		// given
		Employee staff = staff();

		// when / then
		assertThatThrownBy(() -> staff.resetPassword(NEW_HASH, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> staff.changePassword(NEW_HASH, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> staff.deactivate(null)).isInstanceOf(NullPointerException.class);
		assertThat(staff.getPasswordHash()).isEqualTo(HASH);
		assertThat(staff.isActive()).isTrue();
	}

	// ── 비밀번호 변경 ──

	@Test
	@DisplayName("비밀번호를 바꾸면 새 해시로 바뀌고 비밀번호 변경 필요 표시가 내려간다")
	void change_password_clears_must_change_flag() {
		// given
		Employee staff = staff();
		assertThat(staff.mustChangePassword()).isTrue();

		// when
		staff.changePassword(NEW_HASH, NOW);

		// then
		assertThat(staff.getPasswordHash()).isEqualTo(NEW_HASH);
		assertThat(staff.mustChangePassword()).isFalse();
		assertThat(staff.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	@Tag("REQ-15")
	@DisplayName("비밀번호를 바꾸면 연속 실패 횟수가 0이 되고 잠금이 풀린다")
	void change_password_clears_failures_and_lock() {
		// given — 4번 틀린 사장 (잠기기 직전)
		Employee owner = owner();
		for (int i = 0; i < MAX_FAILURES - 1; i++) {
			owner.recordLoginFailure(NOW, MAX_FAILURES, LOCK);
		}
		assertThat(owner.getFailedLoginCount()).isEqualTo(MAX_FAILURES - 1);

		// when
		owner.changePassword(NEW_HASH, NOW);

		// then
		assertThat(owner.getFailedLoginCount()).isZero();
		assertThat(owner.getLockedUntil()).isNull();
		assertThat(owner.mustChangePassword()).isFalse();
	}

	@Test
	@DisplayName("비밀번호를 바꿔도 마지막 로그인 시각과 역할·활성 여부는 그대로다")
	void change_password_keeps_profile() {
		// given
		Employee staff = staff();
		Instant loggedInAt = Instant.parse("2026-11-02T00:00:00Z");
		staff.recordLogin(loggedInAt);

		// when
		staff.changePassword(NEW_HASH, NOW);

		// then
		assertThat(staff.getLastLoginAt()).isEqualTo(loggedInAt);
		assertThat(staff.getRole()).isEqualTo(Role.STAFF);
		assertThat(staff.isActive()).isTrue();
	}

	// ── 비활성화 ──

	@Test
	@DisplayName("직원을 비활성화하면 로그인할 수 없고 프로필 변경 시각이 바뀐다")
	void deactivate_staff() {
		// given
		Employee staff = staff();

		// when
		boolean changed = staff.deactivate(NOW);

		// then
		assertThat(changed).isTrue();
		assertThat(staff.isActive()).isFalse();
		assertThat(staff.canLogin()).isFalse();
		assertThat(staff.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("이미 비활성인 직원을 다시 비활성화하면 아무것도 바뀌지 않는다")
	void deactivate_inactive_staff_changes_nothing() {
		// given
		Employee staff = staff();
		staff.deactivate(CREATED_AT);

		// when
		boolean changed = staff.deactivate(NOW);

		// then
		assertThat(changed).isFalse();
		assertThat(staff.isActive()).isFalse();
		assertThat(staff.getUpdatedAt()).isEqualTo(CREATED_AT);
	}

	@Test
	@DisplayName("사장 계정은 비활성화할 수 없고 활성 상태가 그대로다")
	void owner_cannot_be_deactivated() {
		// given
		Employee owner = owner();

		// when / then
		assertThatThrownBy(() -> owner.deactivate(NOW)).isInstanceOf(AdminAccountProtectedException.class);
		assertThat(owner.isActive()).isTrue();
		assertThat(owner.getUpdatedAt()).isEqualTo(CREATED_AT);
	}

	// ── 예외 문구 (오류 코드 문구와 같다) ──

	@Test
	@DisplayName("직원 관리·비밀번호 변경 예외 문구는 정해진 안내 문구다")
	void exception_messages() {
		UUID id = UUID.randomUUID();
		assertThat(new DuplicateLoginIdException()).hasMessage("이미 사용 중인 아이디입니다.");
		IllegalStateException cause = new IllegalStateException("ux_employees_login_id");
		assertThat(new DuplicateLoginIdException(cause)).hasMessage("이미 사용 중인 아이디입니다.").hasCause(cause);
		assertThat(new EmployeeNotFoundException(id)).hasMessage("직원을 찾을 수 없습니다.")
				.extracting(EmployeeNotFoundException::getEmployeeId).isEqualTo(id);
		assertThat(new InvalidCurrentPasswordException()).hasMessage("현재 비밀번호가 올바르지 않습니다.");
		assertThat(new PasswordUnchangedException()).hasMessage("현재 비밀번호와 다른 비밀번호를 입력해 주세요.");
	}

	@Test
	@DisplayName("비밀번호를 바꿔야 하는 계정의 인증 상태도 활성·미폐기면 인증할 수 있다 (막는 것은 권한 단계)")
	void must_change_password_status_still_authenticates() {
		assertThat(new EmployeeAuthStatus(STORE_ID, true, Role.STAFF, false, true).canAuthenticate()).isTrue();
		assertThat(new EmployeeAuthStatus(STORE_ID, true, Role.STAFF, false, true).mustChangePassword()).isTrue();
	}

}
