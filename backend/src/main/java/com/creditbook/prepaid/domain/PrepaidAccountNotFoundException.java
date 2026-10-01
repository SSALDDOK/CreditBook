package com.creditbook.prepaid.domain;

import java.util.UUID;

/**
 * 고객의 선결제 계좌를 찾을 수 없을 때. 계좌는 고객 등록과 함께 만들어지므로 사실상 고객이 없다는 뜻이다.
 */
public class PrepaidAccountNotFoundException extends PrepaidDomainException {

	private final UUID customerId;

	public PrepaidAccountNotFoundException(UUID customerId) {
		super("고객을 찾을 수 없습니다.");
		this.customerId = customerId;
	}

	public UUID getCustomerId() {
		return customerId;
	}

}
