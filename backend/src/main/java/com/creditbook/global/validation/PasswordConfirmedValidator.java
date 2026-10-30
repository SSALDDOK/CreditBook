package com.creditbook.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link PasswordConfirmed} 검사기. 위반을 객체 전체가 아니라 passwordConfirm 항목에 붙인다. */
public class PasswordConfirmedValidator implements ConstraintValidator<PasswordConfirmed, PasswordConfirmation> {

	static final String FIELD = "passwordConfirm";

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
				.addPropertyNode(FIELD)
				.addConstraintViolation();
		return false;
	}

}
