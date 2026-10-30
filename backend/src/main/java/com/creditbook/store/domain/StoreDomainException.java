package com.creditbook.store.domain;

/**
 * 매장 도메인 규칙 위반의 공통 상위 타입. 전역 예외 처리기가 하위 타입별로 4xx 를 정한다.
 */
public abstract class StoreDomainException extends RuntimeException {

	protected StoreDomainException(String message) {
		super(message);
	}

}
