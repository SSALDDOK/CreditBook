package com.creditbook.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 직원 (employees). 로그인 주체이자 거래의 처리 직원(ledger_entries.performed_by)이다.
 * <p>
 * 직원은 삭제하지 않고 {@code active} 로 비활성화한다 — 비활성 직원은 로그인할 수 없고, 이미 발급된 토큰도 다음 요청부터 거절된다.
 * {@code passwordHash} 는 BCrypt 해시다. 응답 DTO·로그·{@link #toString()} 에 내보내지 않는다.
 */
@Entity
@Table(name = "employees")
public class Employee {

	/** employees.login_id VARCHAR(50) */
	public static final int LOGIN_ID_MAX_LENGTH = 50;

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "login_id", nullable = false, length = LOGIN_ID_MAX_LENGTH)
	private String loginId;

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Column(name = "name", nullable = false, length = EmployeeCredentialRules.NAME_MAX_LENGTH)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 10)
	private Role role;

	@Column(name = "active", nullable = false)
	private boolean active;

	@Column(name = "must_change_password", nullable = false)
	private boolean mustChangePassword;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	/** 연속 로그인 실패 횟수 (REQ-15). 성공하거나 잠금이 끝난 뒤 다시 틀리면 처음부터 센다. */
	@Column(name = "failed_login_count", nullable = false)
	private int failedLoginCount;

	/** 잠금이 끝나는 시각. null 이면 잠기지 않았다. 이 시각부터는 풀린 것으로 본다. */
	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** 프로필(이름·역할·활성 여부·비밀번호) 변경 시각. 로그인은 프로필 변경이 아니므로 바꾸지 않는다. */
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Employee() {
		// JPA 전용
	}

	private Employee(String loginId, String passwordHash, String name, Role role, boolean mustChangePassword,
			Instant createdAt) {
		this.id = UUID.randomUUID();
		this.loginId = requireText(loginId, "loginId");
		this.passwordHash = requireText(passwordHash, "passwordHash");
		this.name = requireValidName(name);
		this.role = Objects.requireNonNull(role, "role");
		this.active = true;
		this.mustChangePassword = mustChangePassword;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		this.updatedAt = createdAt;
	}

	/**
	 * 매장 개설로 사장(ADMIN) 계정을 만든다 (REQ-29·30). 사장이 직접 정한 비밀번호이므로 바꾸라고 하지 않는다
	 * ({@code mustChangePassword = false}). ADMIN 은 이 경로로만 생긴다 — 앱에 권한 변경 기능이 없다.
	 * 비밀번호는 이미 해시된 값을 받는다 (해시는 애플리케이션 계층의 PasswordEncoder 가 한다). 이름은 앞뒤 공백을 빼고 저장한다.
	 *
	 * @throws IllegalArgumentException 아이디·해시·이름이 비어 있거나, 이름이 {@value EmployeeCredentialRules#NAME_MAX_LENGTH}자를 넘을 때
	 */
	public static Employee createOwner(String loginId, String passwordHash, String name, Instant createdAt) {
		return new Employee(loginId, passwordHash, name, Role.ADMIN, false, createdAt);
	}

	/**
	 * 사장이 직원(STAFF) 계정을 추가한다 (REQ-30 — 직원 추가는 STAFF 로만). 초기 비밀번호는 사장이 건네주는 값이므로
	 * 첫 로그인 뒤 바꾸게 한다 ({@code mustChangePassword = true}).
	 *
	 * @throws IllegalArgumentException 아이디·해시·이름이 비어 있거나, 이름이 {@value EmployeeCredentialRules#NAME_MAX_LENGTH}자를 넘을 때
	 */
	public static Employee createStaff(String loginId, String passwordHash, String name, Instant createdAt) {
		return new Employee(loginId, passwordHash, name, Role.STAFF, true, createdAt);
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}

	/** 앞뒤 공백을 뺀 이름. 글자 수는 코드 포인트로 센다 (PostgreSQL VARCHAR 의 글자 수와 같다). */
	private static String requireValidName(String name) {
		String stripped = requireText(name, "name").strip();
		if (stripped.codePointCount(0, stripped.length()) > EmployeeCredentialRules.NAME_MAX_LENGTH) {
			throw new IllegalArgumentException("name must be at most " + EmployeeCredentialRules.NAME_MAX_LENGTH + " characters");
		}
		return stripped;
	}

	/** 로그인할 수 있는 직원인가. 비활성 직원은 비밀번호가 맞아도 로그인할 수 없다. */
	public boolean canLogin() {
		return active;
	}

	/**
	 * 로그인 성공을 기록하고 연속 실패 횟수·잠금을 초기화한다. {@code updatedAt} 은 프로필 변경 시각이라 건드리지 않는다.
	 */
	public void recordLogin(Instant loggedInAt) {
		this.lastLoginAt = Objects.requireNonNull(loggedInAt, "loggedInAt");
		this.failedLoginCount = 0;
		this.lockedUntil = null;
	}

	/**
	 * 지금 잠겨 있는가 (REQ-15). 잠금 끝 시각과 같거나 지났으면 풀린 것으로 본다.
	 * 잠긴 동안은 비밀번호가 맞아도 로그인할 수 없다.
	 */
	public boolean isLocked(Instant now) {
		Objects.requireNonNull(now, "now");
		return lockedUntil != null && now.isBefore(lockedUntil);
	}

	/** 잠금이 풀릴 때까지 남은 시간. 잠겨 있지 않으면 0. */
	public Duration lockRemaining(Instant now) {
		return isLocked(now) ? Duration.between(now, lockedUntil) : Duration.ZERO;
	}

	/**
	 * 로그인 실패(비밀번호 틀림)를 기록한다 (REQ-15). 연속 실패가 {@code maxFailures} 번이 되면 {@code lockDuration} 동안 잠근다.
	 * 잠금 시간은 늘리지 않는다. 잠금이 끝난 뒤의 실패는 횟수를 1부터 다시 센다.
	 * {@code updatedAt} 은 프로필 변경 시각이라 건드리지 않는다.
	 *
	 * @return 이 실패로 계정이 잠겼으면 true
	 * @throws IllegalStateException 이미 잠긴 계정 — 잠긴 동안은 비밀번호를 대조하지 않으므로 실패도 기록하지 않는다
	 * @throws IllegalArgumentException 기준 횟수가 1 미만이거나 잠금 시간이 0 이하일 때
	 */
	public boolean recordLoginFailure(Instant now, int maxFailures, Duration lockDuration) {
		Objects.requireNonNull(lockDuration, "lockDuration");
		if (maxFailures < 1) {
			throw new IllegalArgumentException("maxFailures must be at least 1");
		}
		if (lockDuration.isNegative() || lockDuration.isZero()) {
			throw new IllegalArgumentException("lockDuration must be positive");
		}
		if (isLocked(now)) {
			throw new IllegalStateException("account is locked");
		}
		if (lockedUntil != null) {
			// 잠금이 끝났다 — 횟수를 처음부터 센다
			this.failedLoginCount = 0;
			this.lockedUntil = null;
		}
		this.failedLoginCount++;
		if (failedLoginCount >= maxFailures) {
			this.lockedUntil = now.plus(lockDuration);
			return true;
		}
		return false;
	}

	/**
	 * 사장이 이 계정을 관리(비밀번호 초기화·비활성화)할 수 있는지 확인한다 (REQ-30). 본인 계정과 사장(ADMIN) 계정은 관리 대상이 아니다 —
	 * 사장 계정이 잠기거나 사라지면 매장을 운영할 사람이 없어진다. 사장 교체·분실 복구는 운영자 절차로만 한다.
	 *
	 * @param actorId 요청한 직원(사장) ID
	 * @throws AdminAccountProtectedException 대상이 본인이거나 ADMIN 일 때
	 */
	public void requireManageableBy(UUID actorId) {
		Objects.requireNonNull(actorId, "actorId");
		if (id.equals(actorId) || role == Role.ADMIN) {
			throw new AdminAccountProtectedException();
		}
	}

	/**
	 * 사장이 비밀번호를 초기화한다 (REQ-30). 새 초기 비밀번호(해시)로 바꾸고, 다음 로그인 뒤 다시 바꾸게 하며, 연속 실패 횟수·잠금을 푼다.
	 * 비활성 직원도 초기화할 수 있다 (로그인은 여전히 막힌다).
	 *
	 * @throws AdminAccountProtectedException ADMIN 계정 — 사장 비밀번호는 앱에서 초기화하지 않는다
	 * @throws IllegalArgumentException 해시가 비어 있을 때
	 */
	public void resetPassword(String newPasswordHash, Instant now) {
		if (role == Role.ADMIN) {
			throw new AdminAccountProtectedException();
		}
		replacePassword(newPasswordHash, now);
		this.mustChangePassword = true;
	}

	/**
	 * 본인이 비밀번호를 바꾼다 (REQ-30). 현재 비밀번호 대조와 '현재와 다른가' 검사는 원문이 필요하므로 애플리케이션 계층이 먼저 한다.
	 * 바꾸면 비밀번호 변경 필요 표시를 내리고, 연속 실패 횟수·잠금을 푼다.
	 *
	 * @throws IllegalArgumentException 해시가 비어 있을 때
	 */
	public void changePassword(String newPasswordHash, Instant now) {
		replacePassword(newPasswordHash, now);
		this.mustChangePassword = false;
	}

	private void replacePassword(String newPasswordHash, Instant now) {
		Objects.requireNonNull(now, "now");
		this.passwordHash = requireText(newPasswordHash, "passwordHash");
		this.failedLoginCount = 0;
		this.lockedUntil = null;
		this.updatedAt = now;
	}

	/**
	 * 직원을 비활성화한다 (REQ-30). 비활성 직원은 로그인할 수 없고, 이미 받은 토큰도 다음 요청부터 거절된다. 거래 이력의 처리 직원은 그대로 남는다.
	 * 이미 비활성이면 아무것도 바꾸지 않는다. 다시 활성화하는 기능은 없다.
	 *
	 * @return 이번에 비활성화했으면 true, 이미 비활성이었으면 false
	 * @throws AdminAccountProtectedException ADMIN 계정 — 사장 계정은 비활성화하지 않는다
	 */
	public boolean deactivate(Instant now) {
		Objects.requireNonNull(now, "now");
		if (role == Role.ADMIN) {
			throw new AdminAccountProtectedException();
		}
		if (!active) {
			return false;
		}
		this.active = false;
		this.updatedAt = now;
		return true;
	}

	public UUID getId() {
		return id;
	}

	public String getLoginId() {
		return loginId;
	}

	/** BCrypt 해시. 비밀번호 대조에만 쓴다 — 응답·로그에 내보내지 않는다. */
	public String getPasswordHash() {
		return passwordHash;
	}

	public String getName() {
		return name;
	}

	public Role getRole() {
		return role;
	}

	public boolean isActive() {
		return active;
	}

	public boolean mustChangePassword() {
		return mustChangePassword;
	}

	public Instant getLastLoginAt() {
		return lastLoginAt;
	}

	public int getFailedLoginCount() {
		return failedLoginCount;
	}

	/** 잠금 끝 시각. 지난 시각일 수 있다 — 잠김 여부는 {@link #isLocked(Instant)} 로 본다. */
	public Instant getLockedUntil() {
		return lockedUntil;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	/** 비밀번호 해시를 빼고 식별에 필요한 값만 남긴다. */
	@Override
	public String toString() {
		return "Employee{id=" + id + ", role=" + role + ", active=" + active + "}";
	}

}
