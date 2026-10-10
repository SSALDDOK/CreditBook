package com.creditbook.prepaid.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 원장 거래 한 건 (ledger_entries). 한 번 만들어지면 바뀌지 않는다 — 정정은 반제 행을 새로 추가한다.
 * <p>
 * 생성자는 공개하지 않는다. 잔액과 balance_after 가 어긋나지 않도록 {@link PrepaidAccount} 만 거래를 만든다.
 * 모든 컬럼이 {@code updatable = false} 이고 setter 가 없어 Hibernate 도 UPDATE 를 만들지 않는다 (DB 트리거가 최후 방어선).
 * {@code @Immutable} 은 쓰지 않는다 — Hibernate 7.2 에서 {@code @Generated} 컬럼을 INSERT 후 다시 읽을 때
 * UnsupportedLockAttemptException 이 난다.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

	/** ledger_entries.memo VARCHAR(200) */
	public static final int MEMO_MAX_LENGTH = 200;

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	/** BIGSERIAL — DB 가 채운다. INSERT 후 Hibernate 가 다시 읽어 온다. */
	@Generated
	@Column(name = "seq", insertable = false, updatable = false)
	private Long seq;

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, updatable = false, length = 20)
	private LedgerEntryType type;

	@Column(name = "amount", nullable = false, updatable = false, precision = 12, scale = 0)
	private BigDecimal amount;

	/**
	 * GENERATED ALWAYS AS ... STORED — DB 가 계산한다. INSERT 에 넣지 않고, 저장 후 DB 값으로 다시 읽어 온다.
	 * 저장 전(단위 테스트 등)에도 같은 규칙으로 계산해 둔다.
	 */
	@Generated
	@Column(name = "signed_amount", insertable = false, updatable = false, precision = 12, scale = 0)
	private BigDecimal signedAmount;

	@Column(name = "balance_after", nullable = false, updatable = false, precision = 12, scale = 0)
	private BigDecimal balanceAfter;

	@Column(name = "memo", updatable = false, length = MEMO_MAX_LENGTH)
	private String memo;

	@Column(name = "reverses_id", updatable = false)
	private UUID reversesId;

	@Column(name = "performed_by", nullable = false, updatable = false)
	private UUID performedBy;

	@Column(name = "performed_at", nullable = false, updatable = false)
	private Instant performedAt;

	@Column(name = "idempotency_key", updatable = false, length = 64)
	private String idempotencyKey;

	protected LedgerEntry() {
		// JPA 전용
	}

	private LedgerEntry(UUID accountId, LedgerEntryType type, BigDecimal amount, BigDecimal balanceAfter,
			String memo, UUID reversesId, UUID performedBy, Instant performedAt, IdempotencyKey idempotencyKey) {
		this.id = UUID.randomUUID();
		this.accountId = Objects.requireNonNull(accountId, "accountId");
		this.type = Objects.requireNonNull(type, "type");
		this.amount = Objects.requireNonNull(amount, "amount");
		this.signedAmount = type.isDebit() ? amount.negate() : amount;
		this.balanceAfter = Objects.requireNonNull(balanceAfter, "balanceAfter");
		this.memo = memo;
		this.reversesId = reversesId;
		this.performedBy = Objects.requireNonNull(performedBy, "performedBy");
		this.performedAt = Objects.requireNonNull(performedAt, "performedAt");
		this.idempotencyKey = idempotencyKey == null ? null : idempotencyKey.value();
		// ck_ledger_entries_reverses 와 같은 규칙: 반제 유형만 reverses_id 를 가진다
		if (type.isReversal() != (reversesId != null)) {
			throw new IllegalArgumentException("반제 유형만 reverses_id 를 가진다: " + type);
		}
	}

	/**
	 * 원 거래(CHARGE·USE)를 만든다. {@link PrepaidAccount} 에서만 호출한다.
	 *
	 * @param idempotencyKey 요청 키 (없으면 null — idempotency_key 를 NULL 로 저장)
	 */
	static LedgerEntry original(UUID accountId, LedgerEntryType type, BigDecimal amount, BigDecimal balanceAfter,
			String memo, UUID performedBy, Instant performedAt, IdempotencyKey idempotencyKey) {
		return new LedgerEntry(accountId, type, amount, balanceAfter, memo, null, performedBy, performedAt,
				idempotencyKey);
	}

	/** 반제 거래(CHARGE_CANCEL·USE_CANCEL)를 만든다. {@link PrepaidAccount} 에서만 호출한다. */
	static LedgerEntry reversal(UUID accountId, LedgerEntryType type, BigDecimal amount, BigDecimal balanceAfter,
			String reason, UUID reversesId, UUID performedBy, Instant performedAt) {
		return new LedgerEntry(accountId, type, amount, balanceAfter, reason,
				Objects.requireNonNull(reversesId, "reversesId"), performedBy, performedAt, null);
	}

	/**
	 * 메모·사유를 저장 형태로 맞춘다 — 앞뒤 공백 제거, null·공백뿐이면 null. 길이 검사는 하지 않는다
	 * (저장할 때는 {@link PrepaidAccount} 가 길이를 검사하고, 요청 비교 때는 다르기만 하면 되므로).
	 */
	static String normalizeMemo(String memo) {
		if (memo == null || memo.isBlank()) {
			return null;
		}
		return memo.strip();
	}

	public boolean isReversal() {
		return type.isReversal();
	}

	/**
	 * 같은 요청 키로 다시 온 요청이 이 거래를 만든 요청과 같은가 (REQ-10).
	 * 계좌(= 고객, 고객 1명당 계좌 1개)·유형·금액·메모가 모두 같아야 같다. 반제 요청은 금액 대신 대상 거래를 비교한다.
	 * 금액은 값으로 비교한다 (10000 과 10000.0 은 같다).
	 *
	 * @param accountId 이번 요청 고객의 계좌 ID. 계좌가 없는 고객이면 null — 이 거래의 계좌와 같을 수 없으므로 다른 요청이다
	 * @param request 이번 요청의 비교 기준
	 */
	public boolean isSameRequest(UUID accountId, IdempotentRequest request) {
		Objects.requireNonNull(request, "request");
		return this.accountId.equals(accountId)
				&& this.type == request.type()
				&& (type.isReversal() || isSameAmount(request.amount()))
				&& Objects.equals(this.reversesId, request.reversesId())
				&& Objects.equals(this.memo, request.memo());
	}

	private boolean isSameAmount(BigDecimal requested) {
		return requested != null && this.amount.compareTo(requested) == 0;
	}

	public UUID getId() {
		return id;
	}

	public Long getSeq() {
		return seq;
	}

	public UUID getAccountId() {
		return accountId;
	}

	public LedgerEntryType getType() {
		return type;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public BigDecimal getSignedAmount() {
		return signedAmount;
	}

	public BigDecimal getBalanceAfter() {
		return balanceAfter;
	}

	public String getMemo() {
		return memo;
	}

	public UUID getReversesId() {
		return reversesId;
	}

	public UUID getPerformedBy() {
		return performedBy;
	}

	public Instant getPerformedAt() {
		return performedAt;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

}
