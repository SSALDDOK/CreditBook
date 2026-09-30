package com.creditbook.prepaid.domain;

/**
 * 선결제 도메인 규칙 위반의 공통 상위 타입. 전역 예외 처리기가 이 타입으로 묶어 4xx 로 변환할 수 있다.
 */
public abstract class PrepaidDomainException extends RuntimeException {

	protected PrepaidDomainException(String message) {
		super(message);
	}

}
