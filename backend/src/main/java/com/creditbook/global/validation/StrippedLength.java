package com.creditbook.global.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 앞뒤 공백을 뺀 글자 수(코드 포인트)가 {@link #min()}–{@link #max()} 인지 본다. 공백뿐이면 0자로 센다.
 * {@code @Size} 는 공백을 빼지 않고 UTF-16 단위로 세므로, "앞뒤 공백 제거 후 n자" 규칙에는 이것을 쓴다.
 * null 은 통과시킨다 — 필수 여부는 {@code @NotNull} 로 따로 건다.
 */
@Documented
@Constraint(validatedBy = StrippedLengthValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface StrippedLength {

	String message() default "길이를 확인해 주세요.";

	int min() default 1;

	int max() default Integer.MAX_VALUE;

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
