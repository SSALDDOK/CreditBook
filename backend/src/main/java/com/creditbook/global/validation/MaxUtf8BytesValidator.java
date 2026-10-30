package com.creditbook.global.validation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link MaxUtf8Bytes} 검사기. */
public class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, String> {

	private int maxBytes;

	@Override
	public void initialize(MaxUtf8Bytes constraint) {
		this.maxBytes = constraint.value();
	}

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		return value == null || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
	}

}
