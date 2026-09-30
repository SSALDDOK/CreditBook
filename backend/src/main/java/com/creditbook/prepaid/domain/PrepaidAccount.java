package com.creditbook.prepaid.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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
	 * @throws InsufficientBalanceException 금액이 잔액보다 클 때. 부족 금액을 담는다 (REQ-9, 거래는 만들어지지 않는다)
	 */
	public LedgerEntry use(BigDecimal amount, UUID performedBy, Instant performedAt, String memo) {
		BigDecimal value = requireValidAmount(amount);
		if (!canUse(value)) {
			throw new InsufficientBalanceException(this.balance, value, value.subtract(this.balance));
		}
		BigDecimal newBalance = this.balance.subtract(value);
		return apply(newBalance, LedgerEntry.original(id, LedgerEntryType.USE, value, newBalance,
				normalizeMemo(memo), performedBy, performedAt));
	}

	/**
	 * 사용 건을 취소한다 (REQ-34). 원 거래는 그대로 두고 USE_CANCEL 반제 거래를 추가해 사용 금액만큼 잔액을 되돌린다.
	 * 전액 취소만 있고 기한은 없다.
	 * <p>
	 * 이 계좌는 거래 이력을 들고 있지 않으므로 "이미 취소됐는가" 는 혼자 알 수 없다. 호출자(서비스)가
	 * {@code reverses_id = target.id} 인 거래를 조회해 {@code existingCancel} 로 넘기고, 판단은 여기서 한다.
	 * 조회와 저장 사이에 다른 요청이 끼어들면 DB 의 ux_ledger_entries_reverses 가 두 번째 반제를 막는다.
	 *
	 * @param target 취소할 사용 거래
	 * @param existingCancel target 을 이미 반제한 거래 (없으면 empty)
	 * @param reason 취소 사유 (필수, 앞뒤 공백 제거 후 memo 에 저장)
	 * @throws InvalidCancelTargetException target 이 다른 계좌의 거래이거나 USE 가 아닐 때 (반제 행 포함)
	 * @throws AlreadyCancelledException target 이 이미 취소됐을 때
	 * @throws CancelReasonRequiredException 사유가 null·공백일 때
	 * @throws IllegalArgumentException existingCancel 이 target 을 반제한 거래가 아닐 때 (호출자 버그)
	 */
	public LedgerEntry cancelUse(LedgerEntry target, Optional<LedgerEntry> existingCancel, String reason,
			UUID performedBy, Instant performedAt) {
		requireReversible(target, LedgerEntryType.USE_CANCEL, existingCancel);
		String normalizedReason = requireReason(reason);
		BigDecimal newBalance = requireWithinRange(this.balance.add(target.getAmount()));
		return apply(newBalance, LedgerEntry.reversal(id, LedgerEntryType.USE_CANCEL, target.getAmount(), newBalance,
				normalizedReason, target.getId(), performedBy, performedAt));
	}

	/** 이 금액을 지금 사용할 수 있는가 (잔액 이하인가). */
	public boolean canUse(BigDecimal amount) {
		return amount != null && this.balance.compareTo(amount) >= 0;
	}

	/** 반제 공통 규칙: 같은 계좌, 유형 대응(반제 행은 다시 반제 불가), 원본 1건당 반제 1건. */
	private void requireReversible(LedgerEntry target, LedgerEntryType reversalType,
			Optional<LedgerEntry> existingCancel) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(existingCancel, "existingCancel");
		if (!this.id.equals(target.getAccountId())) {
			throw new InvalidCancelTargetException("다른 계좌의 거래는 취소할 수 없습니다.");
		}
		if (target.isReversal()) {
			throw new InvalidCancelTargetException("취소 거래는 다시 취소할 수 없습니다.");
		}
		if (target.getType() != reversalType.reversedType()) {
			throw new InvalidCancelTargetException(
					reversalType + " 는 " + reversalType.reversedType() + " 거래만 취소할 수 있습니다.");
		}
		if (existingCancel.isPresent()) {
			LedgerEntry cancel = existingCancel.get();
			if (!target.getId().equals(cancel.getReversesId())) {
				throw new IllegalArgumentException("existingCancel 은 target 을 반제한 거래여야 한다");
			}
			throw new AlreadyCancelledException(target.getId(), cancel.getId());
		}
	}

	private static String requireReason(String reason) {
		if (reason == null || reason.isBlank()) {
			throw new CancelReasonRequiredException();
		}
		return normalizeMemo(reason);
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
