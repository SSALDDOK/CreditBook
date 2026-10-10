package com.creditbook.customer.domain;

import java.util.UUID;

/**
 * 고객을 찾을 수 없을 때. 오류 코드 CUSTOMER_NOT_FOUND 와 짝이다.
 */
public class CustomerNotFoundException extends CustomerDomainException {

	private final UUID customerId;

	public CustomerNotFoundException(UUID customerId) {
		super("고객을 찾을 수 없습니다.");
		this.customerId = customerId;
	}

	public UUID getCustomerId() {
		return customerId;
	}

}
