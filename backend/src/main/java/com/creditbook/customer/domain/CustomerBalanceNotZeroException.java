package com.creditbook.customer.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * 잔액이 0원이 아닌 고객을 비활성화하려 할 때 (REQ-4). 오류 코드 CUSTOMER_BALANCE_NOT_ZERO 와 짝이다.
 * 화면이 남은 잔액을 안내할 수 있게 요청 시점의 잔액(원)을 담는다.
 */
public class CustomerBalanceNotZeroException extends CustomerDomainException {

	private final UUID customerId;
	private final BigDecimal balance;

	public CustomerBalanceNotZeroException(UUID customerId, BigDecimal balance) {
		super("잔액이 0원인 고객만 비활성화할 수 있습니다.");
		this.customerId = customerId;
		this.balance = Objects.requireNonNull(balance, "balance");
	}

	public UUID getCustomerId() {
		return customerId;
	}

	/** 요청 시점의 잔액 (원, scale 0). */
	public BigDecimal getBalance() {
		return balance;
	}

}
