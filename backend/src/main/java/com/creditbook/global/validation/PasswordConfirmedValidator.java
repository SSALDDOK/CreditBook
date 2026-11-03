package com.creditbook.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link PasswordConfirmed} 검사기. 위반을 객체 전체가 아니라 확인 항목({@link PasswordConfirmed#field()})에 붙인다. */
public class PasswordConfirmedValidator implements ConstraintValidator<PasswordConfirmed, PasswordConfirmation> {

	private String field = "passwordConfirm";

	@Override
	public void initialize(PasswordConfirmed annotation) {
		this.field = annotation.field();
	}

	@Override
	public boolean isValid(PasswordConfirmation value, ConstraintValidatorContext context) {
		if (value == null || value.password() == null || value.passwordConfirm() == null) {
			return true;
		}
		if (value.password().equals(value.passwordConfirm())) {
			return true;
		}
		context.disableDefaultConstraintViolation();
		context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
				.addPropertyNode(field)
				.addConstraintViolation();
		return false;
	}

}
