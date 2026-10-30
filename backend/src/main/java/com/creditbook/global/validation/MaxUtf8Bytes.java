package com.creditbook.global.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * UTF-8 로 바꿨을 때 {@link #value()} 바이트 이하인지 본다. BCrypt 는 72바이트를 넘는 비밀번호를 해시하지 않으므로(예외 → 500)
 * 글자 수 제한과 함께 비밀번호에 건다 — 한글은 글자당 3바이트라 72자여도 넘을 수 있다. null 은 통과시킨다.
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

	String message() default "너무 깁니다.";

	int value();

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
