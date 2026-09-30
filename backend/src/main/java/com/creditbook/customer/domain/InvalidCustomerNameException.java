package com.creditbook.customer.domain;

/**
 * 고객 이름으로 쓸 수 없는 값 — null, 공백뿐, 길이 초과. DB 의 ck_customers_name_not_blank 보다 먼저 막는다.
 */
public class InvalidCustomerNameException extends CustomerDomainException {

	public InvalidCustomerNameException(String message) {
		super(message);
	}

}
