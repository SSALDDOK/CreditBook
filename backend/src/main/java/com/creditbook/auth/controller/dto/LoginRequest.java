package com.creditbook.auth.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 로그인 요청. 비밀번호의 바이트 길이(BCrypt 72바이트) 검사는 서비스가 한다 — 형식 오류(400)가 아니라 로그인 실패(401)로 응답하기 위해서다.
 * {@link #toString()} 은 비밀번호를 가린다.
 */
public record LoginRequest(
		@NotBlank(message = "아이디를 입력해 주세요.")
		@Size(max = 50, message = "아이디는 50자 이하여야 합니다.")
		String loginId,
		@NotBlank(message = "비밀번호를 입력해 주세요.")
		String password) {

	@Override
	public String toString() {
		return "LoginRequest{loginId=[PROTECTED], password=[PROTECTED]}";
	}

}
