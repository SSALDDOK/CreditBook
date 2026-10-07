package com.creditbook.auth.domain;

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

	@Column(name = "name", nullable = false, length = 50)
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
		this.name = requireText(name, "name");
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
	 * @throws IllegalArgumentException 아이디·해시·이름이 비어 있을 때
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

	/** 로그인할 수 있는 직원인가. 비활성 직원은 비밀번호가 맞아도 로그인할 수 없다. */
	public boolean canLogin() {
		return active;
	}

	/**
	 * 로그인 성공을 기록한다. {@code updatedAt} 은 프로필 변경 시각이라 건드리지 않는다.
	 */
	public void recordLogin(Instant loggedInAt) {
		this.lastLoginAt = Objects.requireNonNull(loggedInAt, "loggedInAt");
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
