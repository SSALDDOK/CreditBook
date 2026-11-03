package com.creditbook.auth.controller.dto;

import com.creditbook.auth.domain.EmployeeCredentialRules;
import com.creditbook.global.validation.StrippedLength;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 직원 추가 요청 (REQ-30). 이름·아이디만 받는다 — 권한은 STAFF 로 고정이고 비밀번호는 서버가 만든다.
 * 규칙은 매장 개설과 같다 ({@link EmployeeCredentialRules}). 받지 않는 항목(role·password 등)은 본문 읽기 단계에서 400 으로 거절된다.
 * {@link #toString()} 은 이름·아이디를 가린다.
 */
public record AddEmployeeRequest(
		@NotNull(message = "이름을 입력해 주세요.")
		@StrippedLength(min = 1, max = EmployeeCredentialRules.NAME_MAX_LENGTH, message = "이름은 1–50자여야 합니다.")
		String name,

		@NotNull(message = "아이디를 입력해 주세요.")
		@Pattern(regexp = EmployeeCredentialRules.LOGIN_ID_PATTERN, message = "아이디는 영문·숫자·마침표 4–20자여야 합니다.")
		String loginId) {

	@Override
	public String toString() {
		return "AddEmployeeRequest{name=[PROTECTED], loginId=[PROTECTED]}";
	}

}
