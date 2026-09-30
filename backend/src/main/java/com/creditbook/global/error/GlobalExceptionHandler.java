package com.creditbook.global.error;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.creditbook.customer.domain.InvalidCustomerNameException;
import com.creditbook.customer.domain.InvalidPhoneNumberException;

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
