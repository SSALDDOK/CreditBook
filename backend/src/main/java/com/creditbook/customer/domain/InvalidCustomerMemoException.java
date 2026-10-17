package com.creditbook.customer.domain;

/**
 * 고객 메모로 쓸 수 없는 값 — 앞뒤 공백을 지운 뒤 200자 초과. DB 의 customers.memo VARCHAR(200) 보다 먼저 막는다.
 */
public class InvalidCustomerMemoException extends CustomerDomainException {

	public InvalidCustomerMemoException(String message) {
		super(message);
	}

}
