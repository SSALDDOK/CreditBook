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
	/** 요청 키(Idempotency-Key) 헤더가 없거나 형식(영문·숫자·하이픈 1–64자)이 틀림 (REQ-10). 거절된 값은 되돌리지 않는다. */
	INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "요청 키(Idempotency-Key)를 확인해 주세요."),

	// 400 — 고객 도메인 규칙
	INVALID_CUSTOMER_NAME(HttpStatus.BAD_REQUEST, "이름을 확인해 주세요."),
	INVALID_PHONE_NUMBER(HttpStatus.BAD_REQUEST, "연락처는 숫자 9–11자리여야 합니다."),

	// 400 — 선결제 도메인 규칙 (message 에 도메인이 정한 사유가 실린다)
	INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "금액을 확인해 주세요."),
	CHARGE_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "1회 충전 한도를 넘었습니다."),
	/** 잔액보다 큰 금액 사용 (REQ-9). 현재 잔액·부족액은 응답의 details 에 실린다. */
	INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "잔액이 부족합니다."),

	// 401 — 인증
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	/** 로그인 실패. 아이디 없음·비밀번호 틀림·비활성 직원을 구분하지 않는다. */
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),

	// 403 — 권한
	FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),

	// 404 — 대상 없음
	CUSTOMER_NOT_FOUND(HttpStatus.NOT_FOUND, "고객을 찾을 수 없습니다."),

	// 4xx — Spring MVC 가 판단하는 요청 오류
	NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),

	// 422 — 요청 키 재사용 (REQ-10)
	/** 이미 쓰인 요청 키로 다른 거래(고객·유형·금액·메모 중 하나라도 다름)를 요청. */
	IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "같은 요청 키로 다른 거래를 요청했습니다. 새로 시도해 주세요."),

	// 409 — 현재 상태와 충돌
	/** 잔액이 남은 고객 비활성화 (REQ-4). 현재 잔액은 응답의 details 에 실린다. */
	CUSTOMER_BALANCE_NOT_ZERO(HttpStatus.CONFLICT, "잔액이 0원인 고객만 비활성화할 수 있습니다."),

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
