package com.creditbook.customer.domain;

import java.util.UUID;

/**
 * 비활성 고객에게 사용(USE)을 요청했을 때 (REQ-33). 오류 코드 CUSTOMER_INACTIVE 와 짝이다.
 * 비활성 고객은 충전하면 다시 활성화되므로 안내 문구에 그 방법을 담는다.
 */
public class CustomerInactiveException extends CustomerDomainException {

	private final UUID customerId;

	public CustomerInactiveException(UUID customerId) {
		super("비활성 고객입니다. 충전하면 다시 활성화됩니다.");
		this.customerId = customerId;
	}

	public UUID getCustomerId() {
		return customerId;
	}

}
