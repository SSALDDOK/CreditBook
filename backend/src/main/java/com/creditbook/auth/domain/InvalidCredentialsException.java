package com.creditbook.auth.domain;

/**
 * 로그인 실패. 아이디 없음·비밀번호 틀림·비활성 직원을 구분하지 않는다 — 어느 아이디가 있는지 알려주지 않기 위해서다.
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("아이디 또는 비밀번호가 올바르지 않습니다.");
	}

}
