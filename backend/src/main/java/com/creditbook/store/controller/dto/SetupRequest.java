package com.creditbook.store.controller.dto;

import com.creditbook.auth.domain.EmployeeCredentialRules;
import com.creditbook.global.validation.MaxUtf8Bytes;
import com.creditbook.global.validation.PasswordConfirmation;
import com.creditbook.global.validation.PasswordConfirmed;
import com.creditbook.global.validation.StrippedLength;
import com.creditbook.store.domain.StoreProfile;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 매장 개설 요청 (REQ-29). 형식이 틀리면 400 INVALID_INPUT 의 fieldErrors 에 항목 이름이 실린다.
 * 받지 않는 항목(role 등)은 본문 읽기 단계에서 거절된다. {@link #toString()} 은 비밀번호를 가린다.
 */
@PasswordConfirmed
public record SetupRequest(
		@NotNull(message = "매장명을 입력해 주세요.")
		@StrippedLength(min = 1, max = StoreProfile.NAME_MAX_LENGTH, message = "매장명은 1–30자여야 합니다.")
		String storeName,

		@NotNull(message = "사장 이름을 입력해 주세요.")
		@StrippedLength(min = 1, max = EmployeeCredentialRules.NAME_MAX_LENGTH, message = "이름은 1–50자여야 합니다.")
		String ownerName,

		@NotNull(message = "아이디를 입력해 주세요.")
		@Pattern(regexp = EmployeeCredentialRules.LOGIN_ID_PATTERN, message = "아이디는 영문·숫자·마침표 4–20자여야 합니다.")
		String loginId,

		@NotNull(message = "비밀번호를 입력해 주세요.")
		@Size(min = EmployeeCredentialRules.PASSWORD_MIN_LENGTH, max = EmployeeCredentialRules.PASSWORD_MAX_LENGTH,
				message = "비밀번호는 8–72자여야 합니다.")
		@MaxUtf8Bytes(value = EmployeeCredentialRules.PASSWORD_MAX_BYTES,
				message = "비밀번호가 너무 깁니다. 한글 등은 글자당 여러 바이트를 차지합니다.")
		String password,

		@NotNull(message = "비밀번호 확인을 입력해 주세요.")
		String passwordConfirm) implements PasswordConfirmation {

	@Override
	public String toString() {
		return "SetupRequest{storeName=[PROTECTED], ownerName=[PROTECTED], loginId=[PROTECTED], password=[PROTECTED], "
				+ "passwordConfirm=[PROTECTED]}";
	}

}
