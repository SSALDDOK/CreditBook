package com.creditbook.global.error;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.creditbook.auth.domain.InvalidCredentialsException;
import com.creditbook.customer.domain.InvalidCustomerNameException;
import com.creditbook.customer.domain.InvalidPhoneNumberException;
import com.creditbook.global.security.UnauthenticatedException;
import com.creditbook.prepaid.domain.ChargeLimitExceededException;
import com.creditbook.prepaid.domain.InsufficientBalanceException;
import com.creditbook.prepaid.domain.InvalidAmountException;
import com.creditbook.prepaid.domain.PrepaidAccountNotFoundException;

import jakarta.persistence.OptimisticLockException;

/**
 * 예외를 {@link ErrorResponse} 로 바꾼다. 응답에는 스택트레이스를 넣지 않고, 예상하지 못한 예외만 ERROR 로 스택트레이스를 로그에 남긴다.
 * <p>
 * 새 도메인 예외는 {@link ErrorCode} 에 코드를 추가하고 여기에 핸들러를 추가한다 (규칙 위반 400, 상태 충돌 409).
 * 로그에는 입력값을 남기지 않는다 — 고객 이름·연락처가 섞일 수 있다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** Bean Validation 실패 (@Valid @RequestBody). */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
				.toList();
		log.debug("request validation failed: fields={}", fieldErrors.stream().map(ErrorResponse.FieldError::field).toList());
		return respond(ErrorCode.INVALID_INPUT, ErrorResponse.of(ErrorCode.INVALID_INPUT, fieldErrors));
	}

	/** 쿼리 파라미터 등 메서드 인자 검증 실패 (@RequestParam 의 @Min·@Max·@Size). 거절된 값은 응답·로그에 넣지 않는다. */
	@ExceptionHandler(HandlerMethodValidationException.class)
	ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex) {
		List<ErrorResponse.FieldError> fieldErrors = ex.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(error -> new ErrorResponse.FieldError(result.getMethodParameter().getParameterName(),
								error.getDefaultMessage())))
				.toList();
		log.debug("parameter validation failed: fields={}", fieldErrors.stream().map(ErrorResponse.FieldError::field).toList());
		return respond(ErrorCode.INVALID_INPUT, ErrorResponse.of(ErrorCode.INVALID_INPUT, fieldErrors));
	}

	/** 쿼리 파라미터 타입 불일치 (예: page=abc). 처리하지 않으면 아래의 Exception 처리기가 500 으로 응답한다. */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		log.debug("parameter type mismatch: field={}", ex.getName());
		return respond(ErrorCode.INVALID_INPUT, ErrorResponse.of(ErrorCode.INVALID_INPUT,
				List.of(new ErrorResponse.FieldError(ex.getName(), "형식이 올바르지 않습니다."))));
	}

	/** JSON 문법 오류, 타입 불일치 등 본문을 읽지 못한 경우. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
		log.debug("request body not readable");
		return respond(ErrorCode.MALFORMED_REQUEST, ErrorResponse.of(ErrorCode.MALFORMED_REQUEST));
	}

	@ExceptionHandler(InvalidCustomerNameException.class)
	ResponseEntity<ErrorResponse> handleInvalidCustomerName(InvalidCustomerNameException ex) {
		return respond(ErrorCode.INVALID_CUSTOMER_NAME, ErrorResponse.of(ErrorCode.INVALID_CUSTOMER_NAME, ex.getMessage()));
	}

	@ExceptionHandler(InvalidPhoneNumberException.class)
	ResponseEntity<ErrorResponse> handleInvalidPhoneNumber(InvalidPhoneNumberException ex) {
		return respond(ErrorCode.INVALID_PHONE_NUMBER, ErrorResponse.of(ErrorCode.INVALID_PHONE_NUMBER, ex.getMessage()));
	}

	/** 1회 충전 한도 초과. {@link InvalidAmountException} 의 하위 타입이라 코드를 따로 주려고 별도 처리기를 둔다. */
	@ExceptionHandler(ChargeLimitExceededException.class)
	ResponseEntity<ErrorResponse> handleChargeLimitExceeded(ChargeLimitExceededException ex) {
		return respond(ErrorCode.CHARGE_LIMIT_EXCEEDED, ErrorResponse.of(ErrorCode.CHARGE_LIMIT_EXCEEDED, ex.getMessage()));
	}

	/** 거래 금액 규칙 위반 (0 이하, 소수점, 범위 초과 등). 사유는 도메인 문구를 그대로 싣는다. */
	@ExceptionHandler(InvalidAmountException.class)
	ResponseEntity<ErrorResponse> handleInvalidAmount(InvalidAmountException ex) {
		return respond(ErrorCode.INVALID_AMOUNT, ErrorResponse.of(ErrorCode.INVALID_AMOUNT, ex.getMessage()));
	}

	/**
	 * 잔액보다 큰 금액 사용 (REQ-9). 사유 문구와 함께 현재 잔액·부족액을 details 에 싣는다.
	 * 정상적인 거절이라 WARN 로그는 서비스가 계좌 ID 와 함께 남기고 여기서는 다시 남기지 않는다.
	 */
	@ExceptionHandler(InsufficientBalanceException.class)
	ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException ex) {
		ErrorResponse.InsufficientBalanceDetails details =
				new ErrorResponse.InsufficientBalanceDetails(ex.getBalance(), ex.getShortage());
		return respond(ErrorCode.INSUFFICIENT_BALANCE,
				ErrorResponse.of(ErrorCode.INSUFFICIENT_BALANCE, ex.getMessage(), details));
	}

	@ExceptionHandler(PrepaidAccountNotFoundException.class)
	ResponseEntity<ErrorResponse> handleAccountNotFound(PrepaidAccountNotFoundException ex) {
		return respond(ErrorCode.CUSTOMER_NOT_FOUND, ErrorResponse.of(ErrorCode.CUSTOMER_NOT_FOUND));
	}

	/** 현재 직원을 알 수 없는 요청. 정상적인 거절이므로 WARN. */
	@ExceptionHandler(UnauthenticatedException.class)
	ResponseEntity<ErrorResponse> handleUnauthenticated(UnauthenticatedException ex) {
		log.warn("request rejected: unauthenticated");
		return respond(ErrorCode.UNAUTHENTICATED, ErrorResponse.of(ErrorCode.UNAUTHENTICATED));
	}

	/** 로그인 실패. 사유는 응답에서 구분하지 않는다. WARN 로그는 서비스가 직원 ID 와 함께 남기므로 여기서는 다시 남기지 않는다. */
	@ExceptionHandler(InvalidCredentialsException.class)
	ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
		return respond(ErrorCode.INVALID_CREDENTIALS, ErrorResponse.of(ErrorCode.INVALID_CREDENTIALS));
	}

	/**
	 * 컨트롤러·서비스 안에서 난 권한 거부 (메서드 보안 등). 처리하지 않으면 아래의 Exception 처리기가 500 으로 응답한다.
	 * 필터 단계의 거부는 RestAccessDeniedHandler 가 같은 형식으로 응답한다. 정상적인 거절이므로 WARN.
	 */
	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
		log.warn("request rejected: access denied");
		return respond(ErrorCode.FORBIDDEN, ErrorResponse.of(ErrorCode.FORBIDDEN));
	}

	/** 같은 계좌를 동시에 바꿔 @Version 검사에 걸린 경우. 정상 동작이므로 WARN. */
	@ExceptionHandler({ ObjectOptimisticLockingFailureException.class, OptimisticLockException.class })
	ResponseEntity<ErrorResponse> handleOptimisticLock(RuntimeException ex) {
		log.warn("concurrent modification rejected: {}", ex.getClass().getSimpleName());
		return respond(ErrorCode.CONCURRENT_MODIFICATION, ErrorResponse.of(ErrorCode.CONCURRENT_MODIFICATION));
	}

	/**
	 * 나머지 전부. Spring MVC 의 표준 예외(404·405·415 등)는 {@link org.springframework.web.ErrorResponse} 를 구현하므로
	 * 그 상태 코드를 살리고, 그 밖의 예외는 500 으로 응답하고 스택트레이스는 로그에만 남긴다.
	 */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		if (ex instanceof org.springframework.web.ErrorResponse springError
				&& springError.getStatusCode().is4xxClientError()) {
			log.debug("request rejected by framework: {}", ex.getClass().getSimpleName());
			ErrorCode code = switch (springError.getStatusCode().value()) {
				case 404 -> ErrorCode.NOT_FOUND;
				case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
				case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
				default -> ErrorCode.INVALID_INPUT;
			};
			// 코드 표에 없는 4xx(406 등)도 상태 코드는 프레임워크가 정한 값을 그대로 쓴다
			return ResponseEntity.status(springError.getStatusCode()).body(ErrorResponse.of(code));
		}
		log.error("unexpected error", ex);
		return respond(ErrorCode.INTERNAL_ERROR, ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
	}

	private static ResponseEntity<ErrorResponse> respond(ErrorCode code, ErrorResponse body) {
		return ResponseEntity.status(code.status()).body(body);
	}

}
