package com.creditbook.global.error;

import org.springframework.http.HttpStatus;

/**
 * API 오류 코드. 응답의 {@code code} 필드로 나가며 클라이언트는 이 값으로 분기한다 (메시지 문구로 분기하지 않는다).
 * 도메인 예외가 늘면 여기에 코드를 추가하고 {@link GlobalExceptionHandler} 에 매핑을 추가한다.
 */
public enum ErrorCode {

	// 400 — 요청 형식
	INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다."),

	// 400 — 고객 도메인 규칙
	INVALID_CUSTOMER_NAME(HttpStatus.BAD_REQUEST, "이름을 확인해 주세요."),
	INVALID_PHONE_NUMBER(HttpStatus.BAD_REQUEST, "연락처는 숫자 9–11자리여야 합니다."),

	// 4xx — Spring MVC 가 판단하는 요청 오류
	NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),

	// 409 — 동시성
	CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "다른 요청이 먼저 처리되었습니다. 다시 시도해 주세요."),

	// 500
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");

	private final HttpStatus status;
	private final String defaultMessage;

	ErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus status() {
		return status;
	}

	public String defaultMessage() {
		return defaultMessage;
	}

}
