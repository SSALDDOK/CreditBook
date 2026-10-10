package com.creditbook.customer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.creditbook.prepaid.domain.PrepaidAccount;

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

	@Column(name = "phone", nullable = false, length = 20)
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
	 * 연락처는 필수다. DB(customers.phone NOT NULL, V2)와 이 도메인 검증이 함께 지킨다.
	 *
	 * @param phone 숫자 9–11자리
	 * @throws InvalidCustomerNameException 이름이 null·공백뿐이거나 20자를 넘을 때
	 * @throws InvalidPhoneNumberException 연락처가 null 이거나 숫자 9–11자리가 아닐 때
	 */
	public static Customer register(String name, String phone, Instant registeredAt) {
		return new Customer(name, phone, registeredAt);
	}

	/**
	 * 고객을 비활성화한다 (REQ-4). 잔액이 0원인 고객만 비활성화할 수 있다 — 잔액 판단은 계좌가 한다.
	 * 기본 목록에서 빠지지만 거래 이력은 남는다 (삭제하지 않는다).
	 * <p>
	 * 이미 비활성이면 아무것도 바꾸지 않는다 (멱등 — 상태·변경 시각 그대로).
	 * <p>
	 * 계좌 잔액이 이 판단 뒤에 바뀌지 않는다는 보장은 호출자(서비스)가 같은 트랜잭션에서 계좌 version 으로 잡는다.
	 *
	 * @param account 이 고객의 선결제 계좌
	 * @param deactivatedAt 비활성화 시각 (서버 시각)
	 * @throws CustomerBalanceNotZeroException 활성 고객인데 잔액이 0원이 아닐 때. 상태를 바꾸지 않는다
	 * @throws IllegalArgumentException 다른 고객의 계좌일 때 (호출자 버그)
	 */
	public void deactivate(PrepaidAccount account, Instant deactivatedAt) {
		Objects.requireNonNull(account, "account");
		Objects.requireNonNull(deactivatedAt, "deactivatedAt");
		if (!this.id.equals(account.getCustomerId())) {
			throw new IllegalArgumentException("account 는 이 고객의 계좌여야 한다");
		}
		if (!this.active) {
			return;
		}
		if (!account.hasZeroBalance()) {
			throw new CustomerBalanceNotZeroException(this.id, account.getBalance());
		}
		this.active = false;
		this.updatedAt = deactivatedAt;
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
		if (phone == null || phone.isEmpty()) {
			throw InvalidPhoneNumberException.missing();
		}
		if (!PHONE_DIGITS.matcher(phone).matches()) {
			throw InvalidPhoneNumberException.invalidFormat();
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
