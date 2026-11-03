package com.creditbook.auth.controller.dto;

import java.util.UUID;

import com.creditbook.auth.application.PasswordResetResult;

/**
 * 비밀번호 초기화 결과 (REQ-30). 새 초기 비밀번호는 이 응답에서 한 번만 보여 준다. {@link #toString()} 은 비밀번호를 가린다.
 */
public record ResetPasswordResponse(UUID employeeId, String initialPassword) {

	public static ResetPasswordResponse from(PasswordResetResult result) {
		return new ResetPasswordResponse(result.employeeId(), result.initialPassword());
	}

	@Override
	public String toString() {
		return "ResetPasswordResponse{employeeId=" + employeeId + ", initialPassword=[PROTECTED]}";
	}

}
