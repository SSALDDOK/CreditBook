package com.creditbook.customer.domain;

/**
 * 고객 도메인 규칙 위반의 공통 상위 타입. 전역 예외 처리기가 하위 타입별로 4xx 를 정한다.
 */
public abstract class CustomerDomainException extends RuntimeException {

	protected CustomerDomainException(String message) {
		super(message);
	}

}
