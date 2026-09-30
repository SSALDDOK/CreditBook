package com.creditbook.customer.domain;

/**
 * 연락처로 쓸 수 없는 값 — 숫자 9–11자리가 아님. DB 의 ck_customers_phone_digits 보다 먼저 막는다.
 */
public class InvalidPhoneNumberException extends CustomerDomainException {

	public InvalidPhoneNumberException() {
		super("연락처는 숫자 9–11자리여야 합니다.");
	}

}
