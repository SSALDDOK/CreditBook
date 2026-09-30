package com.creditbook.prepaid.domain;

import java.math.BigDecimal;

/**
 * 잔액보다 큰 금액을 사용하려 할 때 (REQ-9). 거래는 생성되지 않는다.
 * 화면이 "N원이 부족합니다" 를 안내할 수 있도록 부족 금액을 담는다.
 */
public class InsufficientBalanceException extends PrepaidDomainException {

	private final BigDecimal balance;
	private final BigDecimal requestedAmount;
	private final BigDecimal shortage;

	InsufficientBalanceException(BigDecimal balance, BigDecimal requestedAmount, BigDecimal shortage) {
		super("잔액이 " + shortage.toPlainString() + "원 부족합니다.");
		this.balance = balance;
		this.requestedAmount = requestedAmount;
		this.shortage = shortage;
	}

	public BigDecimal getBalance() {
		return balance;
	}

	public BigDecimal getRequestedAmount() {
		return requestedAmount;
	}

	/** 부족 금액 = 요청 금액 - 잔액 (PrepaidAccount 가 계산해 넣는다). */
	public BigDecimal getShortage() {
		return shortage;
	}

}
