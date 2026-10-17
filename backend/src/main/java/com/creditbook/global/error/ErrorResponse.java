package com.creditbook.global.error;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

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

	/**
	 * 잔액이 남은 고객 비활성화(CUSTOMER_BALANCE_NOT_ZERO)의 부가 정보. 화면이 남은 잔액을 안내할 수 있게 한다.
	 *
	 * @param balance 요청 시점의 현재 잔액 (원)
	 */
	public record CustomerBalanceNotZeroDetails(BigDecimal balance) {
	}

	/**
	 * 이미 취소된 거래(ALREADY_CANCELLED)의 부가 정보. 화면이 먼저 만들어진 취소 거래를 안내할 수 있게 한다.
	 *
	 * @param cancelEntryId 먼저 만들어진 취소(반제) 거래의 ID
	 */
	public record AlreadyCancelledDetails(UUID cancelEntryId) {
	}

	/**
	 * 이미 사용돼 취소할 수 없는 충전(CHARGE_ALREADY_USED)의 부가 정보. 화면이 현재 잔액과 충전액을 안내할 수 있게 한다.
	 *
	 * @param balance 요청 시점의 현재 잔액 (원)
	 * @param chargeAmount 취소하려던 충전 금액 (원)
	 */
	public record ChargeAlreadyUsedDetails(BigDecimal balance, BigDecimal chargeAmount) {
	}

	/**
	 * 같은 번호 고객이 있음(DUPLICATE_PHONE)의 부가 정보. 화면이 기존 고객 이름을 보여 주고 등록 진행 여부를 묻게 한다.
	 * 연락처(원문·마스킹 모두)는 담지 않는다.
	 *
	 * @param existingCustomers 같은 번호 고객 전부 (활성 먼저 → 최근 등록순)
	 */
	public record DuplicatePhoneDetails(List<ExistingCustomer> existingCustomers) {

		public DuplicatePhoneDetails {
			existingCustomers = List.copyOf(existingCustomers);
		}

		/**
		 * @param id 고객 ID
		 * @param name 고객 이름 — 직원이 같은 사람인지 판단하는 기준
		 * @param active 활성 여부 (비활성 고객도 중복으로 본다)
		 */
		public record ExistingCustomer(UUID id, String name, boolean active) {
		}

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
