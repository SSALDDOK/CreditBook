package com.creditbook.global.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 비밀번호 확인이 비밀번호와 같은지 본다 (클래스 단위 제약). 다르면 위반을 {@code passwordConfirm} 항목에 붙인다 —
 * 응답의 fieldErrors[].field 가 "passwordConfirm" 이 된다. 둘 중 하나가 null 이면 각 항목의 {@code @NotNull} 에 맡기고 통과시킨다.
 */
@Documented
@Constraint(validatedBy = PasswordConfirmedValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordConfirmed {

	String message() default "비밀번호 확인이 일치하지 않습니다.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
