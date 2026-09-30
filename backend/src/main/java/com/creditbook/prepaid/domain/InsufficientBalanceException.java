package com.creditbook.prepaid.domain;

import java.math.BigDecimal;

/**
 * 잔액보다 큰 금액을 사용하려 할 때. 거래는 생성되지 않는다.
 */
public class InsufficientBalanceException extends PrepaidDomainException {

	private final BigDecimal balance;
	private final BigDecimal requestedAmount;

	InsufficientBalanceException(BigDecimal balance, BigDecimal requestedAmount) {
		super("잔액이 부족합니다. 잔액 " + balance.toPlainString() + "원, 요청 " + requestedAmount.toPlainString() + "원");
		this.balance = balance;
		this.requestedAmount = requestedAmount;
	}

	public BigDecimal getBalance() {
		return balance;
	}

	public BigDecimal getRequestedAmount() {
		return requestedAmount;
	}

}
