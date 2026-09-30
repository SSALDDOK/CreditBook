package com.creditbook.prepaid.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 선결제 계좌 (prepaid_accounts). 잔액을 더하고 빼는 모든 계산과 검사는 이 클래스 안에만 있다.
 * <p>
 * 잔액을 바꾸는 메서드는 모두 {@link LedgerEntry} 를 만들어 돌려준다. 그 거래의 balance_after 는
 * 바뀐 뒤의 계좌 잔액과 같다 — 호출자(서비스)는 계좌와 돌려받은 거래를 같은 트랜잭션에서 저장만 한다.
 * <p>
 * 시각은 직접 구하지 않고 인자로 받는다 (서버 시각은 서비스가 Clock 으로 넘긴다 — REQ-20, 테스트 재현성).
 */
@Entity
@Table(name = "prepaid_accounts")
public class PrepaidAccount {

	/** NUMERIC(12,0) 이 담을 수 있는 최댓값. 금액·잔액이 이를 넘으면 DB 가 거절하기 전에 도메인이 거절한다. */
	static final BigDecimal NUMERIC_12_MAX = new BigDecimal("999999999999");

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "customer_id", nullable = false, updatable = false)
	private UUID customerId;

	@Column(name = "balance", nullable = false, precision = 12, scale = 0)
	private BigDecimal balance;

	/** 낙관적 락. 같은 계좌를 동시에 바꾸면 늦게 커밋하는 쪽이 실패한다. */
	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected PrepaidAccount() {
		// JPA 전용
	}

	private PrepaidAccount(UUID customerId, Instant openedAt) {
		this.id = UUID.randomUUID();
		this.customerId = Objects.requireNonNull(customerId, "customerId");
		this.balance = BigDecimal.ZERO;
		this.updatedAt = Objects.requireNonNull(openedAt, "openedAt");
	}

	/** 고객의 계좌를 잔액 0원으로 연다. */
	public static PrepaidAccount open(UUID customerId, Instant openedAt) {
		return new PrepaidAccount(customerId, openedAt);
	}

	/**
	 * 충전한다 (REQ-5). 잔액이 금액만큼 늘고 CHARGE 거래가 만들어진다.
	 * 한도는 1회 충전 금액에만 적용된다 — 여러 번 충전해 잔액이 한도를 넘는 것은 허용한다 (REQ-6).
	 *
	 * @throws ChargeLimitExceededException 금액이 1회 충전 한도를 넘을 때
	 * @throws InvalidAmountException 금액이 null·0 이하·소수점이거나 충전 후 잔액이 NUMERIC(12,0) 범위를 넘을 때
	 */
	public LedgerEntry charge(BigDecimal amount, ChargePolicy policy, UUID performedBy, Instant performedAt,
			String memo) {
		Objects.requireNonNull(policy, "policy");
		BigDecimal value = requireValidAmount(amount);
		if (!policy.isWithinLimit(value)) {
			throw new ChargeLimitExceededException(policy.maxAmount());
		}
		BigDecimal newBalance = requireWithinRange(this.balance.add(value));
		return apply(newBalance, LedgerEntry.original(id, LedgerEntryType.CHARGE, value, newBalance,
				normalizeMemo(memo), performedBy, performedAt));
	}

	/**
	 * 사용한다 (REQ-8). 잔액이 금액만큼 줄고 USE 거래가 만들어진다. 잔액과 같은 금액까지 쓸 수 있다.
	 *
	 * @throws InvalidAmountException 금액이 null·0 이하·소수점일 때
	 * @throws InsufficientBalanceException 금액이 잔액보다 클 때 (거래는 만들어지지 않는다)
	 */
	public LedgerEntry use(BigDecimal amount, UUID performedBy, Instant performedAt, String memo) {
		BigDecimal value = requireValidAmount(amount);
		if (!canUse(value)) {
			throw new InsufficientBalanceException(balance, value);
		}
		BigDecimal newBalance = this.balance.subtract(value);
		return apply(newBalance, LedgerEntry.original(id, LedgerEntryType.USE, value, newBalance,
				normalizeMemo(memo), performedBy, performedAt));
	}

	/** 이 금액을 지금 사용할 수 있는가 (잔액 이하인가). */
	public boolean canUse(BigDecimal amount) {
		return amount != null && this.balance.compareTo(amount) >= 0;
	}

	private LedgerEntry apply(BigDecimal newBalance, LedgerEntry entry) {
		this.balance = newBalance;
		this.updatedAt = entry.getPerformedAt();
		return entry;
	}

	/** 거래 금액은 1원 이상의 원 단위 정수여야 하고 NUMERIC(12,0) 에 들어가야 한다. scale 0 으로 맞춰 돌려준다. */
	private static BigDecimal requireValidAmount(BigDecimal amount) {
		if (amount == null) {
			throw new InvalidAmountException("금액을 입력해 주세요.");
		}
		if (amount.signum() <= 0) {
			throw new InvalidAmountException("금액은 1원 이상이어야 합니다.");
		}
		if (amount.stripTrailingZeros().scale() > 0) {
			throw new InvalidAmountException("금액은 원 단위 정수여야 합니다.");
		}
		BigDecimal value = amount.setScale(0, RoundingMode.UNNECESSARY);
		if (value.compareTo(NUMERIC_12_MAX) > 0) {
			throw new InvalidAmountException("금액이 허용 범위를 넘습니다.");
		}
		return value;
	}

	private static BigDecimal requireWithinRange(BigDecimal newBalance) {
		if (newBalance.compareTo(NUMERIC_12_MAX) > 0) {
			throw new InvalidAmountException("거래 후 잔액이 허용 범위를 넘습니다.");
		}
		return newBalance;
	}

	private static String normalizeMemo(String memo) {
		if (memo == null || memo.isBlank()) {
			return null;
		}
		String trimmed = memo.strip();
		if (trimmed.length() > LedgerEntry.MEMO_MAX_LENGTH) {
			throw new IllegalArgumentException("메모는 " + LedgerEntry.MEMO_MAX_LENGTH + "자 이하여야 합니다.");
		}
		return trimmed;
	}

	public UUID getId() {
		return id;
	}

	public UUID getCustomerId() {
		return customerId;
	}

	public BigDecimal getBalance() {
		return balance;
	}

	public Long getVersion() {
		return version;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
