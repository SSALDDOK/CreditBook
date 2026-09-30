package com.creditbook.global.error;

import java.util.List;

/**
 * API 오류 응답 본문. 스택트레이스·예외 클래스명·입력값(연락처 등 개인정보일 수 있음)은 담지 않는다.
 *
 * @param code 클라이언트가 분기하는 오류 코드 ({@link ErrorCode} 이름)
 * @param message 사용자에게 보여줄 문구
 * @param fieldErrors 입력 검증 실패 시 필드별 사유 (없으면 빈 목록)
 */
public record ErrorResponse(String code, String message, List<FieldError> fieldErrors) {

	public record FieldError(String field, String message) {
	}

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.name(), errorCode.defaultMessage(), List.of());
	}

	public static ErrorResponse of(ErrorCode errorCode, String message) {
		return new ErrorResponse(errorCode.name(), message, List.of());
	}

	public static ErrorResponse of(ErrorCode errorCode, List<FieldError> fieldErrors) {
		return new ErrorResponse(errorCode.name(), errorCode.defaultMessage(), List.copyOf(fieldErrors));
	}

}
