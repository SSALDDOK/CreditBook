package com.creditbook.prepaid.domain;

/**
 * 요청 키(Idempotency-Key)가 없거나 형식(영문·숫자·하이픈 1–64자)에 맞지 않을 때.
 * 거절된 값은 메시지에 담지 않는다 — 응답·로그로 입력값을 되돌리지 않는다.
 */
public class InvalidIdempotencyKeyException extends PrepaidDomainException {

	public InvalidIdempotencyKeyException() {
		super("요청 키(Idempotency-Key)를 확인해 주세요.");
	}

}
