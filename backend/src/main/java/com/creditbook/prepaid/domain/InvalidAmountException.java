package com.creditbook.prepaid.domain;

/**
 * 거래 금액으로 쓸 수 없는 값 — null, 0 이하, 소수점, NUMERIC(12,0) 범위 초과.
 */
public class InvalidAmountException extends PrepaidDomainException {

	public InvalidAmountException(String message) {
		super(message);
	}

}
