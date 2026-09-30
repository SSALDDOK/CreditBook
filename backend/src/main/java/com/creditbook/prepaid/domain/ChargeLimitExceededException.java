package com.creditbook.prepaid.domain;

import java.math.BigDecimal;

/**
 * 1회 충전 한도를 넘는 금액을 충전하려 할 때 (REQ-6). 금액 검증 실패의 한 종류다.
 */
public class ChargeLimitExceededException extends InvalidAmountException {

	private final BigDecimal maxAmount;

	ChargeLimitExceededException(BigDecimal maxAmount) {
		super("1회 충전 한도(" + maxAmount.toPlainString() + "원)를 넘었습니다.");
		this.maxAmount = maxAmount;
	}

	public BigDecimal getMaxAmount() {
		return maxAmount;
	}

}
