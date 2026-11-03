package com.creditbook.auth.application;

import java.util.UUID;

/**
 * 비밀번호 초기화 결과 (REQ-30). 새 초기 비밀번호 원문은 이 응답으로 한 번만 나간다. {@link #toString()} 은 비밀번호를 가린다.
 */
public record PasswordResetResult(UUID employeeId, String initialPassword) {

	@Override
	public String toString() {
		return "PasswordResetResult{employeeId=" + employeeId + ", initialPassword=[PROTECTED]}";
	}

}
