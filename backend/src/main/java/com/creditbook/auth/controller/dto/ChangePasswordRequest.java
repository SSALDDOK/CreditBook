package com.creditbook.auth.controller.dto;

import com.creditbook.auth.domain.EmployeeCredentialRules;
import com.creditbook.global.validation.MaxUtf8Bytes;
import com.creditbook.global.validation.PasswordConfirmation;
import com.creditbook.global.validation.PasswordConfirmed;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 변경 요청 (REQ-30). 새 비밀번호 규칙은 매장 개설과 같다(8–72자, 72바이트, 확인 일치).
 * 현재 비밀번호에도 길이·바이트 상한을 둔다 — BCrypt 가 72바이트를 넘는 값을 대조하지 못해 500 이 되지 않게 400 으로 막는다.
 * 확인 불일치는 fieldErrors 의 {@code newPasswordConfirm} 항목으로 나간다. {@link #toString()} 은 비밀번호를 가린다.
 */
@PasswordConfirmed(field = "newPasswordConfirm")
public record ChangePasswordRequest(
		@NotEmpty(message = "현재 비밀번호를 입력해 주세요.")
		@Size(max = EmployeeCredentialRules.PASSWORD_MAX_LENGTH, message = "현재 비밀번호는 72자 이하여야 합니다.")
		@MaxUtf8Bytes(value = EmployeeCredentialRules.PASSWORD_MAX_BYTES,
				message = "비밀번호가 너무 깁니다. 한글 등은 글자당 여러 바이트를 차지합니다.")
		String currentPassword,

		@NotNull(message = "새 비밀번호를 입력해 주세요.")
		@Size(min = EmployeeCredentialRules.PASSWORD_MIN_LENGTH, max = EmployeeCredentialRules.PASSWORD_MAX_LENGTH,
				message = "비밀번호는 8–72자여야 합니다.")
		@MaxUtf8Bytes(value = EmployeeCredentialRules.PASSWORD_MAX_BYTES,
				message = "비밀번호가 너무 깁니다. 한글 등은 글자당 여러 바이트를 차지합니다.")
		String newPassword,

		@NotNull(message = "새 비밀번호 확인을 입력해 주세요.")
		String newPasswordConfirm) implements PasswordConfirmation {

	/** 확인 일치 검사용 — 새 비밀번호. */
	@Override
	public String password() {
		return newPassword;
	}

	/** 확인 일치 검사용 — 새 비밀번호 확인. */
	@Override
	public String passwordConfirm() {
		return newPasswordConfirm;
	}

	@Override
	public String toString() {
		return "ChangePasswordRequest{currentPassword=[PROTECTED], newPassword=[PROTECTED], newPasswordConfirm=[PROTECTED]}";
	}

}
