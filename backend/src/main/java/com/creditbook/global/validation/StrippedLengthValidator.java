package com.creditbook.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link StrippedLength} 검사기. */
public class StrippedLengthValidator implements ConstraintValidator<StrippedLength, String> {

	private int min;
	private int max;

	@Override
	public void initialize(StrippedLength constraint) {
		this.min = constraint.min();
		this.max = constraint.max();
	}

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		if (value == null) {
			return true;
		}
		String stripped = value.strip();
		int length = stripped.codePointCount(0, stripped.length());
		return length >= min && length <= max;
	}

}
