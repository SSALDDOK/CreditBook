package com.creditbook.customer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 고객 (customers). 이름·연락처 규칙은 여기서 검증하고, DB 의 CHECK 제약은 최후 방어선으로 남긴다.
 * <p>
 * 연락처는 정규형(숫자 9–11자리)만 받는다. 하이픈 같은 입력 형식을 숫자로 바꾸는 일은 입력을 받는 쪽(controller)이 한다.
 * 시각은 직접 구하지 않고 인자로 받는다 (서비스가 Clock 으로 넘긴다).
 */
@Entity
@Table(name = "customers")
public class Customer {

	/** customers.name VARCHAR(20) */
	public static final int NAME_MAX_LENGTH = 20;

	/** ck_customers_phone_digits 와 같은 규칙 */
	private static final Pattern PHONE_DIGITS = Pattern.compile("^[0-9]{9,11}$");

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
	private String name;

	@Column(name = "phone", length = 20)
	private String phone;

	@Column(name = "memo", length = 200)
	private String memo;

	@Column(name = "active", nullable = false)
	private boolean active;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Customer() {
		// JPA 전용
	}

	private Customer(String name, String phone, Instant registeredAt) {
		this.id = UUID.randomUUID();
		this.name = requireValidName(name);
		this.phone = requireValidPhone(phone);
		this.active = true;
		this.createdAt = Objects.requireNonNull(registeredAt, "registeredAt");
		this.updatedAt = registeredAt;
	}

	/**
	 * 고객을 등록한다 (REQ-1). 이름은 앞뒤 공백을 지워 저장한다.
	 * 연락처는 null 을 허용한다 (customers.phone 이 NULL 가능 — 연락처를 주지 않는 고객).
	 *
	 * @param phone 숫자 9–11자리 또는 null
	 * @throws InvalidCustomerNameException 이름이 null·공백뿐이거나 20자를 넘을 때
	 * @throws InvalidPhoneNumberException 연락처가 숫자 9–11자리가 아닐 때
	 */
	public static Customer register(String name, String phone, Instant registeredAt) {
		return new Customer(name, phone, registeredAt);
	}

	private static String requireValidName(String name) {
		if (name == null || name.isBlank()) {
			throw new InvalidCustomerNameException("이름을 입력해 주세요.");
		}
		String stripped = name.strip();
		// PostgreSQL VARCHAR(n) 은 문자(코드 포인트) 수로 센다
		if (stripped.codePointCount(0, stripped.length()) > NAME_MAX_LENGTH) {
			throw new InvalidCustomerNameException("이름은 " + NAME_MAX_LENGTH + "자 이하여야 합니다.");
		}
		return stripped;
	}

	private static String requireValidPhone(String phone) {
		if (phone == null) {
			return null;
		}
		if (!PHONE_DIGITS.matcher(phone).matches()) {
			throw new InvalidPhoneNumberException();
		}
		return phone;
	}

	public boolean isActive() {
		return active;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getPhone() {
		return phone;
	}

	public String getMemo() {
		return memo;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
