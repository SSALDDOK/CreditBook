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
import jakarta.persistence.Version;

/**
 * 고객 (customers). 이름·연락처 규칙은 여기서 검증하고, DB 의 CHECK 제약은 최후 방어선으로 남긴다.
 * <p>
 * 연락처는 정규형(숫자 9–11자리)만 받는다. 하이픈 같은 입력 형식을 숫자로 바꾸는 일은 입력을 받는 쪽(controller)이 한다.
 * 시각은 직접 구하지 않고 인자로 받는다 (서비스가 Clock 으로 넘긴다).
 * <p>
 * 고객은 한 매장에 속하고 매장은 바뀌지 않는다 (REQ-36). 연락처 중복 확인도 같은 매장 안에서만 한다.
 */
@Entity
@Table(name = "customers")
public class Customer {

	/** customers.name VARCHAR(20) */
	public static final int NAME_MAX_LENGTH = 20;

	/** customers.memo VARCHAR(200) */
	public static final int MEMO_MAX_LENGTH = 200;

	/** ck_customers_phone_digits 와 같은 규칙 */
	private static final Pattern PHONE_DIGITS = Pattern.compile("^[0-9]{9,11}$");

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	/** 이 고객이 속한 매장 (stores.id). 계좌·거래도 같은 매장이다 (복합 외래 키). */
	@Column(name = "store_id", nullable = false, updatable = false)
	private UUID storeId;

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

	/**
	 * 동시 수정 방지 (REQ-3, V6). 화면이 받은 값을 수정 요청에 돌려보내고 {@link #edit} 가 비교한다.
	 * 값은 JPA 가 고객 행을 UPDATE 할 때마다 올린다 — 수정뿐 아니라 비활성화·재활성화도 올린다.
	 */
	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Customer() {
		// JPA 전용
	}

	private Customer(UUID storeId, String name, String phone, String memo, Instant registeredAt) {
		this.id = UUID.randomUUID();
		this.storeId = Objects.requireNonNull(storeId, "storeId");
		this.name = requireValidName(name);
		this.phone = requireValidPhone(phone);
		this.memo = normalizeMemo(memo);
		this.active = true;
		this.createdAt = Objects.requireNonNull(registeredAt, "registeredAt");
		this.updatedAt = registeredAt;
	}

	/**
	 * 고객을 등록한다 (REQ-1). 이름은 앞뒤 공백을 지워 저장한다.
	 * 연락처는 필수다. DB(customers.phone NOT NULL, V2)와 이 도메인 검증이 함께 지킨다.
	 *
	 * @param storeId 고객을 등록하는 매장 (필수)
	 * @param phone 숫자 9–11자리
	 * @throws InvalidCustomerNameException 이름이 null·공백뿐이거나 20자를 넘을 때
	 * @throws InvalidPhoneNumberException 연락처가 null 이거나 숫자 9–11자리가 아닐 때
	 * @throws NullPointerException 매장이 없을 때
	 */
	public static Customer register(UUID storeId, String name, String phone, Instant registeredAt) {
		return register(storeId, name, phone, null, registeredAt);
	}

	/**
	 * 메모와 함께 고객을 등록한다 (REQ-1). 메모는 선택이다 — 앞뒤 공백을 지우고, 비었거나 공백뿐이면 메모 없음(null)으로 저장한다.
	 *
	 * @param storeId 고객을 등록하는 매장 (필수)
	 * @param phone 숫자 9–11자리
	 * @param memo 메모 (null 가능). 공백을 지운 뒤 200자 이하
	 * @throws InvalidCustomerNameException 이름이 null·공백뿐이거나 20자를 넘을 때
	 * @throws InvalidPhoneNumberException 연락처가 null 이거나 숫자 9–11자리가 아닐 때
	 * @throws InvalidCustomerMemoException 메모가 200자를 넘을 때
	 * @throws NullPointerException 매장이 없을 때
	 */
	public static Customer register(UUID storeId, String name, String phone, String memo, Instant registeredAt) {
		return new Customer(storeId, name, phone, memo, registeredAt);
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

	/**
	 * 비활성 고객을 다시 활성화한다 (REQ-33). 비활성 고객에게 충전하면 충전과 같은 트랜잭션에서 호출된다.
	 * 이미 활성이면 아무것도 바꾸지 않는다 (멱등 — 상태·변경 시각 그대로).
	 *
	 * @param reactivatedAt 재활성화 시각 (서버 시각)
	 * @return 이번 호출로 비활성에서 활성으로 바뀌었으면 true, 이미 활성이었으면 false
	 */
	public boolean reactivate(Instant reactivatedAt) {
		Objects.requireNonNull(reactivatedAt, "reactivatedAt");
		if (this.active) {
			return false;
		}
		this.active = true;
		this.updatedAt = reactivatedAt;
		return true;
	}

	/**
	 * 이 고객이 선결제 잔액을 사용(USE)할 수 있는지 확인한다 (REQ-33). 비활성 고객은 사용할 수 없다 — 충전하면 다시 활성화된다.
	 * 잔액은 보지 않는다 (잔액 부족 판단은 계좌가 한다).
	 *
	 * @throws CustomerInactiveException 비활성 고객일 때
	 */
	public void ensureCanUse() {
		if (!this.active) {
			throw new CustomerInactiveException(this.id);
		}
	}

	/**
	 * 고객 정보(이름·연락처·메모)를 고친다 (REQ-3). 넘긴 칸만 고치고 null 인 칸은 그대로 둔다.
	 * <ol>
	 * <li>버전을 가장 먼저 비교한다 — 다르면 값이 같아도 거절한다</li>
	 * <li>넘긴 값을 정규화·검증한다 (이름 앞뒤 공백 지움, 메모가 비었거나 공백뿐이면 지우기)</li>
	 * <li>정규화한 값으로 지금 값과 비교해 실제로 바뀐 칸을 계산한다</li>
	 * <li>연락처가 실제로 바뀔 때만 {@code phoneChangeCheck} 를 부른다 (같은 번호 고객 확인)</li>
	 * <li>바뀐 칸이 있을 때만 상태와 변경 시각을 바꾼다 — 바뀐 칸이 없으면 아무것도 건드리지 않는다</li>
	 * </ol>
	 * 활성 여부는 바꾸지 않는다 — 비활성 고객도 고칠 수 있고, 고쳐도 비활성으로 남는다.
	 *
	 * @param expectedVersion 화면이 고객 정보를 읽을 때 받은 버전
	 * @param name 새 이름 (null 이면 그대로)
	 * @param phone 새 연락처, 숫자 정규형 (null 이면 그대로)
	 * @param memo 새 메모 (null 이면 그대로, 비었거나 공백뿐이면 지우기)
	 * @param editedAt 변경 시각 (서버 시각)
	 * @param phoneChangeCheck 연락처가 바뀔 때 상태를 바꾸기 전에 부를 확인
	 * @return 실제로 바뀐 칸. 없으면 {@link CustomerChanges#NONE}
	 * @throws CustomerConcurrentModificationException 버전이 다를 때. 아무것도 바꾸지 않는다
	 * @throws InvalidCustomerNameException 이름이 공백뿐이거나 20자를 넘을 때
	 * @throws InvalidPhoneNumberException 연락처가 숫자 9–11자리가 아닐 때
	 * @throws InvalidCustomerMemoException 메모가 200자를 넘을 때
	 */
	public CustomerChanges edit(long expectedVersion, String name, String phone, String memo, Instant editedAt,
			PhoneChangeCheck phoneChangeCheck) {
		Objects.requireNonNull(editedAt, "editedAt");
		Objects.requireNonNull(phoneChangeCheck, "phoneChangeCheck");
		if (expectedVersion != this.version) {
			throw new CustomerConcurrentModificationException(this.id, expectedVersion, this.version);
		}

		String newName = name == null ? this.name : requireValidName(name);
		String newPhone = phone == null ? this.phone : requireValidPhone(phone);
		String newMemo = memo == null ? this.memo : normalizeMemo(memo);

		CustomerChanges changes = new CustomerChanges(
				!newName.equals(this.name),
				!newPhone.equals(this.phone),
				!Objects.equals(newMemo, this.memo));
		if (!changes.hasAny()) {
			return CustomerChanges.NONE;
		}
		if (changes.phoneChanged()) {
			phoneChangeCheck.check(newPhone);
		}

		this.name = newName;
		this.phone = newPhone;
		this.memo = newMemo;
		this.updatedAt = editedAt;
		return changes;
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

	private static String normalizeMemo(String memo) {
		if (memo == null || memo.isBlank()) {
			return null;
		}
		String stripped = memo.strip();
		// 이름과 같은 방식 — PostgreSQL VARCHAR(n) 은 문자(코드 포인트) 수로 센다
		if (stripped.codePointCount(0, stripped.length()) > MEMO_MAX_LENGTH) {
			throw new InvalidCustomerMemoException("메모는 " + MEMO_MAX_LENGTH + "자 이하여야 합니다.");
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

	public UUID getStoreId() {
		return storeId;
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

	public long getVersion() {
		return version;
	}

}
