package com.creditbook.customer.domain;

/**
 * 연락처로 쓸 수 없는 값 — 없음(null·빈 값) 또는 숫자 9–11자리가 아님. DB 의 ck_customers_phone_digits 보다 먼저 막는다.
 * (V1 의 customers.phone 은 NULL 을 허용하므로 "필수" 규칙은 지금 이 예외만 지킨다)
 */
public class InvalidPhoneNumberException extends CustomerDomainException {

	private InvalidPhoneNumberException(String message) {
		super(message);
	}

	/** 연락처를 입력하지 않았을 때 (null·빈 값). */
	public static InvalidPhoneNumberException missing() {
		return new InvalidPhoneNumberException("연락처를 입력해 주세요.");
	}

	/** 연락처가 숫자 9–11자리가 아닐 때. */
	public static InvalidPhoneNumberException invalidFormat() {
		return new InvalidPhoneNumberException("연락처는 숫자 9–11자리여야 합니다.");
	}

}
