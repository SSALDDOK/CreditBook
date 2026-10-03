package com.creditbook.global.error;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * API 오류 응답 본문. 스택트레이스·예외 클래스명·입력값(연락처 등 개인정보일 수 있음)은 담지 않는다.
 *
 * @param code 클라이언트가 분기하는 오류 코드 ({@link ErrorCode} 이름)
 * @param message 사용자에게 보여줄 문구
 * @param fieldErrors 입력 검증 실패 시 필드별 사유 (없으면 빈 목록)
 * @param details 오류 코드별 부가 정보 (예: {@link InsufficientBalanceDetails}). 없는 오류에는 필드 자체가 나가지 않는다
 */
public record ErrorResponse(
		String code,
		String message,
		List<FieldError> fieldErrors,
		@JsonInclude(JsonInclude.Include.NON_NULL) Object details) {

	public record FieldError(String field, String message) {
	}

	/**
	 * 잔액 부족(INSUFFICIENT_BALANCE)의 부가 정보. 화면이 "잔액이 N원 부족합니다" 와 현재 잔액을 안내할 수 있게 한다.
	 *
	 * @param balance 요청 시점의 현재 잔액 (원)
	 * @param shortage 부족액 = 요청 금액 - 현재 잔액 (원)
	 */
	public record InsufficientBalanceDetails(BigDecimal balance, BigDecimal shortage) {
	}

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.name(), errorCode.defaultMessage(), List.of(), null);
	}

	public static ErrorResponse of(ErrorCode errorCode, String message) {
		return new ErrorResponse(errorCode.name(), message, List.of(), null);
	}

	public static ErrorResponse of(ErrorCode errorCode, List<FieldError> fieldErrors) {
		return new ErrorResponse(errorCode.name(), errorCode.defaultMessage(), List.copyOf(fieldErrors), null);
	}

	public static ErrorResponse of(ErrorCode errorCode, String message, Object details) {
		return new ErrorResponse(errorCode.name(), message, List.of(), details);
	}

}
