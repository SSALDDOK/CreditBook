package com.creditbook.prepaid.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 취소하면 잔액이 음수가 되는 충전 건을 취소하려 할 때 (REQ-7) — 충전한 금액 일부가 이미 사용됐다.
 * 사용 건을 먼저 취소해야 한다. 거래는 만들어지지 않는다. 오류 코드 CHARGE_ALREADY_USED 와 짝이다.
 */
public class ChargeAlreadyUsedException extends PrepaidDomainException {

	private final UUID targetId;
	private final BigDecimal balance;
	private final BigDecimal chargeAmount;

	ChargeAlreadyUsedException(UUID targetId, BigDecimal balance, BigDecimal chargeAmount) {
		super("잔액이 부족해 취소할 수 없어요. 먼저 사용 건을 취소해 주세요.");
		this.targetId = targetId;
		this.balance = balance;
		this.chargeAmount = chargeAmount;
	}

	/** 취소하려던 충전 거래의 ID. */
	public UUID getTargetId() {
		return targetId;
	}

	/** 요청 시점의 현재 잔액 (원). */
	public BigDecimal getBalance() {
		return balance;
	}

	/** 취소하려던 충전 금액 (원). */
	public BigDecimal getChargeAmount() {
		return chargeAmount;
	}

}
