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

	private Employee(String loginId, String passwordHash, String name, Role role, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.loginId = requireText(loginId, "loginId");
		this.passwordHash = requireText(passwordHash, "passwordHash");
		this.name = requireValidName(name);
		this.role = Objects.requireNonNull(role, "role");
		this.active = true;
		// 처음 받은 비밀번호는 바꾸게 한다 (employees.must_change_password 기본값과 같음)
		this.mustChangePassword = true;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		this.updatedAt = createdAt;
	}

	/**
	 * 새 직원을 만든다. 비밀번호는 이미 해시된 값을 받는다 (해시는 애플리케이션 계층의 PasswordEncoder 가 한다).
	 *
	 * 이름은 앞뒤 공백을 빼고 저장한다.
	 *
	 * @throws IllegalArgumentException 아이디·해시·이름이 비어 있거나, 이름이 {@value EmployeeCredentialRules#NAME_MAX_LENGTH}자를 넘을 때
	 */
	public static Employee create(String loginId, String passwordHash, String name, Role role, Instant createdAt) {
		return new Employee(loginId, passwordHash, name, role, createdAt);
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
